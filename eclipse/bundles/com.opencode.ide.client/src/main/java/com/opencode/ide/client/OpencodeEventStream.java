package com.opencode.ide.client;

import com.opencode.ide.client.ClientTuning;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.opencode.ide.client.internal.Auth;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Subscribes to an opencode server SSE stream ({@code /event} for one project,
 * {@code /global/event} via {@link #global} for all projects), parses the
 * {@code data:} frames into {@link OpencodeEvent}s, and pushes them to a sink.
 * Reconnects with exponential back-off on close/error. Runs on a daemon thread.
 *
 * <p>The wire format is SSE: lines starting with {@code data:} carry JSON;
 * events are separated by blank lines. Global frames wrap the event in a
 * {@code payload} envelope; {@link Sse#parseEvent(String)} unwraps it.</p>
 *
 * <p>Lifecycle matters here: the response body of a long-lived SSE request keeps
 * a connection (and the client's selector thread) alive, so {@link #stop()}
 * closes both the body stream and the {@link HttpClient}. A stream is created
 * per connection rebuild, so leaking one per reconnect would accumulate threads
 * and sockets for the whole Eclipse session.</p>
 */
public final class OpencodeEventStream {

    private static final String PATH = "/event";
    private static final String GLOBAL_PATH = "/global/event";
    /** A connection must last this long before the back-off is considered cleared. */
    private static final long STABLE_CONNECTION_MILLIS = 10_000L;

    private final HttpClient http;
    private final String path;
    private final URI eventUri;
    private final String authHeader;
    private final Consumer<OpencodeEvent> sink;
    private final Consumer<Boolean> connectionListener;

    private volatile boolean running;
    private volatile Stream<String> body;
    private volatile boolean connected;
    private Thread loop;
    private int backoffSeconds = 1;

    public OpencodeEventStream(ConnectionConfig config, Consumer<OpencodeEvent> sink) {
        this(config, sink, null);
    }

    /**
     * @param connectionListener notified with {@code true} when the stream is live
     *                           and {@code false} when it drops (may be {@code null});
     *                           used by the UI to show liveness and to resynchronize
     *                           after an outage, since events during the gap are lost.
     */
    public OpencodeEventStream(ConnectionConfig config, Consumer<OpencodeEvent> sink,
            Consumer<Boolean> connectionListener) {
        this(PATH, config, sink, connectionListener);
    }

    private OpencodeEventStream(String path, ConnectionConfig config, Consumer<OpencodeEvent> sink,
            Consumer<Boolean> connectionListener) {
        this.path = path;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // match HttpOpencodeClient: server dislikes h2c upgrades
                .connectTimeout(ClientTuning.SSE_STABLE_CONNECTION)
                .build();
        this.eventUri = config.baseUrl().resolve(path);
        this.authHeader = Auth.basicHeader(config.username(), config.password());
        this.sink = sink;
        this.connectionListener = connectionListener;
    }

    /**
     * Stream for {@code GET /global/event} (opencode v1.18.30): events across
     * all projects, same lifecycle and callbacks as the per-project stream.
     */
    public static OpencodeEventStream global(ConnectionConfig config, Consumer<OpencodeEvent> sink,
            Consumer<Boolean> connectionListener) {
        return new OpencodeEventStream(GLOBAL_PATH, config, sink, connectionListener);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = new Thread(this::runLoop, "opencode-sse");
        loop.setDaemon(true);
        loop.start();
    }

    /** Stops the loop and releases the connection, selector thread and executor. */
    public synchronized void stop() {
        running = false;
        Stream<String> open = body;
        if (open != null) {
            try {
                open.close(); // unblocks the reader thread parked on the SSE body
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (loop != null) {
            loop.interrupt();
            loop = null;
        }
        try {
            http.close(); // Java 21: shuts down the selector thread + executor
        } catch (Exception ignored) {
            // best effort
        }
        setConnected(false);
    }

    /** @return {@code true} while the SSE stream is actually connected. */
    public boolean isConnected() {
        return connected;
    }

    private void runLoop() {
        while (running) {
            long connectedAt = 0L;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(eventUri)
                        .header("Accept", "text/event-stream")
                        .GET();
                if (authHeader != null) {
                    builder.header("Authorization", authHeader);
                }
                HttpResponse<Stream<String>> response =
                        http.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
                int status = response.statusCode();
                try (Stream<String> stream = response.body()) {
                    if (status == 200) {
                        body = stream;
                        connectedAt = System.currentTimeMillis();
                        setConnected(true);
                        drain(stream.iterator());
                    } else {
                        // the body must still be closed, or the connection leaks per retry
                        ClientLog.warning("opencode " + path + " returned HTTP " + status);
                    }
                } finally {
                    body = null;
                    setConnected(false);
                }
            } catch (Exception e) {
                if (running && !Thread.currentThread().isInterrupted()) {
                    ClientLog.warning("opencode " + path + " error: " + e.getMessage());
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (running) {
                sleep(backoffSeconds);
                // only clear the back-off when the connection actually held for a while,
                // otherwise an immediately-closing endpoint becomes a 1 req/s hot loop
                boolean stable = connectedAt > 0
                        && System.currentTimeMillis() - connectedAt >= STABLE_CONNECTION_MILLIS;
                backoffSeconds = stable ? 1 : Math.min(Math.max(backoffSeconds, 1) * 2, 30);
            }
        }
    }

    private void setConnected(boolean value) {
        if (connected == value) {
            return;
        }
        connected = value;
        if (connectionListener != null) {
            try {
                connectionListener.accept(value);
            } catch (Exception e) {
                ClientLog.warning("opencode " + path + " listener failed: " + e.getMessage());
            }
        }
    }

    private void drain(Iterator<String> lines) {
        Sse.parseFrames(lines, json -> {
            if (!running) {
                return;
            }
            OpencodeEvent event = Sse.parseEvent(json);
            if (event != null) {
                sink.accept(event);
            } else {
                ClientLog.warning("opencode " + path + ": skipped malformed frame (" + truncate(json, 160) + ")");
            }
        });
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
