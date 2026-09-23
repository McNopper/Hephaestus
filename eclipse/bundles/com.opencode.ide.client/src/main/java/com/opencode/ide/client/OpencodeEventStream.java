package com.opencode.ide.client;
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
 * Subscribes to an opencode server SSE stream ({@code /api/event}), parses the
 * {@code data:} frames into {@link OpencodeEvent}s, and pushes them to a sink.
 * Reconnects with exponential back-off on close/error. Runs on a daemon thread.
 *
 * <p>v2 serves a single stream for every directory (the v1 split between a
 * per-project {@code /event} and a global {@code /global/event} is gone), so
 * {@link #global()} and {@link #forProject()} open the same endpoint; scoping
 * to a project or worktree happens downstream via
 * {@link OpencodeEvent#directory()}.</p>
 *
 * <p>The wire format is SSE: lines starting with {@code data:} carry one JSON
 * frame each ({@code {id, created, type, location, data}});
 * {@link Sse#parseEvent(String)} turns them into events.</p>
 *
 * <p>Lifecycle matters here: the response body of a long-lived SSE request keeps
 * a connection (and the client's selector thread) alive, so {@link #stop()}
 * closes both the body stream and the {@link HttpClient}. A stream is created
 * per connection rebuild, so leaking one per reconnect would accumulate threads
 * and sockets for the whole Eclipse session.</p>
 */
public final class OpencodeEventStream {

    private static final String PATH = "/api/event";
    private static final String GLOBAL_PATH = "/api/event";

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
    private Duration backoff = ClientTuning.SSE_BACKOFF_BASE;

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
                .connectTimeout(ClientTuning.SSE_CONNECT_TIMEOUT)
                .build();
        this.eventUri = config.baseUrl().resolve(path);
        this.authHeader = Auth.basicHeader(config.username(), config.password());
        this.sink = sink;
        this.connectionListener = connectionListener;
    }

    /**
     * Stream over events across all projects — in v2 simply the same
     * {@code /api/event} endpoint, since the server no longer splits streams
     * per project. Kept as a named factory so callers state their intent.
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
                sleep(backoff);
                // only clear the back-off when the connection actually held for a while,
                // otherwise an immediately-closing endpoint becomes a 1 req/s hot loop
                boolean stable = connectedAt > 0
                        && System.currentTimeMillis() - connectedAt >= ClientTuning.SSE_STABLE_CONNECTION.toMillis();
                backoff = stable ? ClientTuning.SSE_BACKOFF_BASE : nextBackoff(backoff);
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
                // 160 is a fixed diagnostic tier between SNIPPET_MIN and SNIPPET_MAX -
                // log verbosity, deliberately not an operational knob
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

    /**
     * Doubles the reconnect back-off, clamped to
     * [{@link ClientTuning#SSE_BACKOFF_BASE}, {@link ClientTuning#SSE_BACKOFF_MAX}]
     * (the floor mirrors the old {@code Math.max(backoffSeconds, 1)} guard).
     */
    private static Duration nextBackoff(Duration current) {
        Duration floor = ClientTuning.SSE_BACKOFF_BASE;
        Duration ceiling = ClientTuning.SSE_BACKOFF_MAX;
        if (current.compareTo(floor) < 0) {
            current = floor;
        }
        if (current.compareTo(ceiling) >= 0) {
            return ceiling;
        }
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(ceiling) > 0 ? ceiling : doubled;
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
