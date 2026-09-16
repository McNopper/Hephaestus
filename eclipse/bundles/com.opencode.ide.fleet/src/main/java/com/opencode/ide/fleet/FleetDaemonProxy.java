package com.opencode.ide.fleet;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client half of the fleet daemon wire protocol (V-006): connects to the
 * daemon's loopback socket, performs the {@code daemon/hello} token
 * handshake, then pumps JSON-RPC lines verbatim — client stdin to the
 * socket, socket to client stdout — one daemon thread per direction. The
 * proxy never interprets a request and never synthesizes
 * {@code daemon/shutdown}: the client detaching (stdin EOF) only
 * half-closes the connection so the runs it dispatched keep running in the
 * daemon (detach semantics — a leaving client must not kill runs), while
 * the daemon dying while the client is still attached surfaces as
 * {@link End#DAEMON_CLOSED} for the caller to exit non-zero on (a crashed
 * MCP server, which clients already tolerate).
 *
 * <p>Constructor-injected seams — the {@link Opener} for the connection and
 * the client streams — keep the class testable against a fake daemon over a
 * real loopback socket, no processes involved. The handshake error line is
 * surfaced through the thrown message; the token itself never appears in
 * one.</p>
 *
 * <p>The hello request is serialized as real JSON (gson), so a token
 * containing quotes or other JSON metacharacters — anything
 * {@code FLEET_DAEMON_PASSWORD} allows — travels escaped rather than
 * corrupting the frame.</p>
 */
public final class FleetDaemonProxy {

    /** How a proxy session ended: which side closed first. */
    public enum End {
        /** The MCP client closed stdin (normal detach); the daemon keeps running. */
        CLIENT_CLOSED,
        /** The daemon closed the connection while the client was still attached. */
        DAEMON_CLOSED
    }

    /** Opens the daemon connection; the seam tests point at a fake listener. */
    @FunctionalInterface
    public interface Opener {

        Socket open() throws IOException;
    }

    /**
     * After a client detach the reader keeps draining this long for the
     * daemon's final response lines before the socket is force-closed;
     * bounded so a daemon that never answers an EOF cannot hang the exit.
     */
    static final long DETACH_DRAIN_MS = 5_000;

    /**
     * Read bound for the daemon's hello reply: a wedged listener must not
     * hang the attach (and with it an {@code always}-mode server start)
     * forever; cleared again before the pumps take over the socket.
     */
    static final long HELLO_TIMEOUT_MS = 10_000;

    private static final Gson GSON = new Gson();

    private final Opener opener;
    private final String token;
    private final InputStream clientIn;
    private final PrintStream clientOut;

    /** Production wiring: a plain socket to {@code host:port}. */
    public FleetDaemonProxy(String host, int port, String token, InputStream clientIn, PrintStream clientOut) {
        this(() -> new Socket(host, port), token, clientIn, clientOut);
    }

    /** Full-seam constructor. */
    public FleetDaemonProxy(Opener opener, String token, InputStream clientIn, PrintStream clientOut) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.token = Objects.requireNonNull(token, "token");
        this.clientIn = Objects.requireNonNull(clientIn, "clientIn");
        this.clientOut = Objects.requireNonNull(clientOut, "clientOut");
    }

    /** Convenience factory: connects, handshakes, returns the running session. */
    public static Attached attach(String host, int port, String token, InputStream clientIn, PrintStream clientOut)
            throws IOException {
        return new FleetDaemonProxy(host, port, token, clientIn, clientOut).attach();
    }

    /**
     * Connects and performs the handshake: the first line on the connection
     * is the authed {@code daemon/hello}; a JSON-RPC error reply (or EOF, or
     * an unparseable reply) fails the attach with the daemon's line surfaced
     * in the message. On success the two pump threads run and the returned
     * handle is the session; the handshake reply itself is internal — the
     * client's protocol starts with its own first request.
     */
    public Attached attach() throws IOException {
        Socket socket = opener.open();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) HELLO_TIMEOUT_MS);
            Writer toDaemon = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader fromDaemon = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            toDaemon.write(helloLine() + "\n");
            toDaemon.flush();
            String reply = fromDaemon.readLine();
            if (reply == null) {
                throw new IOException("fleet daemon closed the connection during the handshake");
            }
            if (isErrorReply(reply)) {
                throw new IOException("fleet daemon refused the handshake: " + reply);
            }
            socket.setSoTimeout(0); // the pumps own the socket from here
            Attached session = new Attached();
            // wire before start: a pump thread can outlive attach() before it returns
            Thread up = Thread.ofPlatform().daemon(true).name("fleet-proxy-up")
                    .unstarted(() -> pumpUp(session, socket, toDaemon));
            Thread down = Thread.ofPlatform().daemon(true).name("fleet-proxy-down")
                    .unstarted(() -> pumpDown(session, socket, fromDaemon));
            session.wire(up, down);
            up.start();
            down.start();
            return session;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * The pinned first line,
     * {@code {"jsonrpc":"2.0","id":1,"method":"daemon/hello","params":{"token":"…"}}},
     * serialized through gson so a token with JSON metacharacters survives
     * the round trip (the daemon compares the parsed value).
     */
    private String helloLine() {
        JsonObject params = new JsonObject();
        params.addProperty("token", token);
        JsonObject hello = new JsonObject();
        hello.addProperty("jsonrpc", "2.0");
        hello.addProperty("id", 1);
        hello.addProperty("method", "daemon/hello");
        hello.add("params", params);
        return GSON.toJson(hello);
    }

    /** Client stdin → socket. EOF here is the client leaving: half-close, drain, done. */
    private void pumpUp(Attached session, Socket socket, Writer toDaemon) {
        try {
            BufferedReader client = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
            String line;
            while ((line = client.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // same tolerance as the owned-engine loop
                }
                toDaemon.write(line + "\n");
                toDaemon.flush();
            }
        } catch (IOException e) {
            // a lost stdin is a detach, not a failure
        } finally {
            // record BEFORE the FIN so the detach outranks the daemon's EOF answer
            session.end(End.CLIENT_CLOSED);
            try {
                socket.shutdownOutput(); // FIN: the daemon sees its client leave
            } catch (IOException ignored) {
                // socket already dead - nothing to signal
            }
            session.awaitDown(DETACH_DRAIN_MS); // deliver the daemon's last responses...
            closeQuietly(socket); // ...then stop the reader, bounded
            session.signalEnded();
        }
    }

    /** Socket → client stdout. EOF here is the daemon's side ending. */
    private void pumpDown(Attached session, Socket socket, BufferedReader fromDaemon) {
        try {
            String line;
            while ((line = fromDaemon.readLine()) != null) {
                clientOut.println(line);
                clientOut.flush();
            }
        } catch (IOException e) {
            // EOF, reset or already closed all mean the same to the client:
            // the daemon side of the session has ended
        } finally {
            boolean daemonEndedSession = session.end(End.DAEMON_CLOSED);
            clientOut.flush();
            if (daemonEndedSession) {
                // the daemon is gone: EOF the client immediately (it sees a
                // crashed MCP server and closes stdin, ending the up pump)
                clientOut.close();
                closeQuietly(socket);
            }
            session.signalEnded();
        }
    }

    /** A JSON-RPC error object — or anything that is not a result — counts as refusal. */
    private static boolean isErrorReply(String line) {
        try {
            var parsed = JsonParser.parseString(line);
            return !parsed.isJsonObject() || parsed.getAsJsonObject().has("error");
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void closeQuietly(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    /** A running proxy session; the handle callers await detachment on. */
    public static final class Attached {

        /** The recorded end; guarded by {@code this} (monitor edges carry it to awaiters). */
        private End end;
        /** Releases {@link #awaitCompletion()} when either pump has finished. */
        private final CountDownLatch anyEnded = new CountDownLatch(1);
        private volatile Thread up;
        private volatile Thread down;

        void wire(Thread up, Thread down) {
            this.up = up;
            this.down = down;
        }

        /** The end recorded so far, or {@code null} while both sides still run. */
        public synchronized End currentEnd() {
            return end;
        }

        /**
         * Waits for the FIRST pump to finish and returns that side's
         * outcome. In the detach case the up pump finishes only after its
         * bounded drain of the daemon's final responses; in the daemon-death
         * case this returns without waiting for the client's stdin — the
         * stdin reader is a daemon thread, so the caller's exit (the
         * non-zero one {@code FleetStdioMain} owes opencode) ends it.
         */
        public End awaitCompletion() throws InterruptedException {
            anyEnded.await();
            synchronized (this) {
                return end != null ? end : End.CLIENT_CLOSED;
            }
        }

        /**
         * Records {@code outcome} unless the other side already ended the
         * session (first side to end names the outcome).
         *
         * @return whether this call's outcome was the one recorded
         */
        synchronized boolean end(End outcome) {
            if (this.end == null) {
                this.end = outcome;
                return true;
            }
            return false;
        }

        /** Releases {@link #awaitCompletion()}; called as each pump's last act. */
        void signalEnded() {
            anyEnded.countDown();
        }

        boolean awaitDown(long millis) {
            Thread reader = down;
            if (reader == null) {
                return true;
            }
            try {
                reader.join(millis);
                return !reader.isAlive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
