package com.opencode.ide.fleet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.opencode.ide.client.ClientLog;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tools.McpDispatcher;
import com.opencode.ide.tools.ToolProvider;

/**
 * The fleet engine as a detached per-repo daemon (V-006): a loopback TCP
 * server speaking the same line-based MCP JSON-RPC subset as
 * {@link FleetStdioMain}, reusing the {@link McpDispatcher} one request per
 * line. The daemon - not any client session - owns the {@link FleetControl}
 * behind the providers, so a reconnecting client sees running jobs, and the
 * auto-dispatch loop outlives every attach. Pure Java, no Eclipse/OSGi.
 *
 * <p>Discovery + singleton: the identity lives in the pidfile
 * {@code <repoRoot>/.git/opencode-fleet/daemon.json} (the fleet's existing
 * cross-engine coordination directory, never committed):
 * {@code {"port":<int>,"pid":<long>,"token":"<hex>","startedAt":"<ISO instant>"}}
 * where {@code startedAt} is the daemon PROCESS's start instant - the
 * pid-reuse anchor, identical semantics to {@code DispatchGuard}'s OWNER
 * markers. {@link #probe} reports a daemon live only when the recorded pid
 * is alive (checked against that start instant) AND the port answers a
 * cheap TCP connect; {@link #start} refuses while a daemon is live and
 * overwrites a stale pidfile. The bind-check-write sequence runs under
 * {@code admission.lock} so two starters cannot both win.</p>
 *
 * <p>Auth contract (pinned for the stdio proxy): the FIRST line a client
 * sends must be
 * {@code {"jsonrpc":"2.0","id":1,"method":"daemon/hello","params":{"token":"<token>"}}}.
 * A wrong or missing token yields a JSON-RPC error result (code -32000)
 * and the connection is closed before anything is dispatched. After a
 * successful hello every subsequent line is dispatched verbatim through
 * {@link McpDispatcher#handle(String)} (all {@code fleet_*}/{@code task_*}
 * tools work; notifications produce no output, as on stdio), except
 * {@code daemon/shutdown} (params {@code {"token":...}}) from an authed
 * connection: the daemon acknowledges, then stops accepting, briefly drains
 * open connections, closes the injected engine (the
 * {@link FleetControl#close} drain-then-kill path when one is owned) and
 * removes the pidfile. A daemon may also run engine-less for pure tool
 * serving - the engine is a constructor-injected {@link AutoCloseable}
 * seam, like the rest of the fleet bundle's fakes-friendly wiring.</p>
 */
public final class FleetDaemon implements AutoCloseable {

    /**
     * Bounded drain of open connections on stop before they are force-closed
     * (the fleet's canonical knob table - env FLEET_DAEMON_DRAIN_MS).
     */
    private static final Duration CONNECTION_DRAIN = FleetTuning.DAEMON_DRAIN_WAIT;

    /** Cheap liveness connect timeout in {@link #probe}. */
    private static final int PROBE_CONNECT_TIMEOUT_MILLIS = 500;

    /** JSON-RPC server-error code for handshake/auth refusals (outside the -326xx protocol range). */
    static final int UNAUTHORIZED = -32000;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path repoRoot;
    private final McpDispatcher dispatcher;
    private final AutoCloseable engine;
    private final String token;
    private final ExecutorService pool = Executors.newCachedThreadPool(daemons());
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ServerSocket server;
    private DaemonInfo info;
    private boolean started;
    private boolean stopping;

    /**
     * Engine-less daemon over the given dispatcher (pure tool serving).
     *
     * @param repoRoot the repository root whose {@code .git/opencode-fleet}
     *                 hosts the pidfile
     * @param dispatcher the per-line JSON-RPC dispatcher, composed exactly
     *                 as {@link FleetStdioMain} composes its stdio loop
     */
    public FleetDaemon(Path repoRoot, McpDispatcher dispatcher) {
        this(repoRoot, dispatcher, null);
    }

    /**
     * @param engine optional owned engine released on
     *                 {@code daemon/shutdown}/{@link #stop()} after the
     *                 connection drain - e.g. {@code FleetToolProvider::close},
     *                 which runs {@link FleetControl#close()}
     */
    public FleetDaemon(Path repoRoot, McpDispatcher dispatcher, AutoCloseable engine) {
        this(repoRoot, dispatcher, engine, null);
    }

    /**
     * Full seam, token included.
     *
     * @param token the auth token published in the pidfile; blank generates
     *                 a fresh 64-hex value via
     *                 {@link FleetControl#resolvePassword} (the same
     *                 generated-password path as the spawned server). The
     *                 static factories resolve {@code FLEET_DAEMON_PASSWORD}
     *                 here; an explicit value is the injection point tests
     *                 and launchers use.
     */
    public FleetDaemon(Path repoRoot, McpDispatcher dispatcher, AutoCloseable engine, String token) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
        this.engine = engine;
        this.token = FleetControl.resolvePassword(token);
    }

    /** Engine-less convenience start: dispatches the given providers verbatim. */
    public static FleetDaemon start(Path repoRoot, ToolProvider... providers) {
        return start(repoRoot, List.of(providers), null);
    }

    /**
     * The composition {@link FleetStdioMain} uses, as a daemon: one
     * dispatcher over the providers, plus an optional owned engine. The
     * token comes from {@code FLEET_DAEMON_PASSWORD} when set, else is
     * generated.
     */
    public static FleetDaemon start(Path repoRoot, List<ToolProvider> providers, AutoCloseable engine) {
        return start(repoRoot, providers, engine, System.getenv("FLEET_DAEMON_PASSWORD"));
    }

    /** Explicit-token start: the injection seam behind the env-resolving overload. */
    public static FleetDaemon start(Path repoRoot, List<ToolProvider> providers, AutoCloseable engine, String token) {
        FleetDaemon daemon = new FleetDaemon(repoRoot, new McpDispatcher(providers), engine, token);
        daemon.start();
        return daemon;
    }

    /**
     * Binds the loopback port and publishes the pidfile; refuses with the
     * live port in the message while {@link #probe} reports a daemon live
     * for this repo. Singleton check and pidfile write share
     * {@code admission.lock} so racing starters serialize; a stale pidfile
     * (dead pid, or pid reuse with a different start instant) is
     * overwritten.
     */
    public void start() {
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("fleet daemon already started");
            }
        }
        DispatchGuard.exclusive(repoRoot, () -> {
            doStart();
            return null;
        });
    }

    private void doStart() {
        Optional<DaemonInfo> live = probe(repoRoot);
        if (live.isPresent()) {
            throw new IllegalStateException("fleet daemon already listening on 127.0.0.1:"
                    + live.get().port() + " (pid " + live.get().pid()
                    + ", pidfile " + pidfileAt(repoRoot) + ")");
        }
        ServerSocket bound;
        try {
            bound = new ServerSocket();
            bound.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind the fleet daemon to loopback: " + e.getMessage(), e);
        }
        DaemonInfo published = new DaemonInfo(bound.getLocalPort(), ProcessHandle.current().pid(),
                token, ProcessHandle.current().info().startInstant().orElseGet(Instant::now));
        try {
            writePidfile(pidfileAt(repoRoot), published);
        } catch (IOException e) {
            try {
                bound.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("cannot write the daemon pidfile " + pidfileAt(repoRoot), e);
        }
        synchronized (this) {
            this.server = bound;
            this.info = published;
            this.started = true;
        }
        Thread acceptor = new Thread(() -> acceptLoop(bound), "fleet-daemon-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        ClientLog.info("fleet daemon listening on 127.0.0.1:" + published.port()
                + " (pidfile " + pidfileAt(repoRoot) + ")");
    }

    /**
     * Reports the live daemon for this repo, if any: reads the pidfile,
     * requires the recorded pid alive (with the start-instant pid-reuse
     * check) and the port answering a cheap TCP connect. Missing or
     * unparseable pidfiles read as "no daemon" (stale residue); the token
     * never leaves the returned {@link DaemonInfo}.
     */
    public static Optional<DaemonInfo> probe(Path repoRoot) {
        Path pidfile = pidfileAt(repoRoot);
        String json;
        try {
            json = Files.readString(pidfile);
        } catch (IOException e) {
            return Optional.empty();
        }
        try {
            JsonObject recorded = JsonParser.parseString(json).getAsJsonObject();
            DaemonInfo info = new DaemonInfo(recorded.get("port").getAsInt(),
                    recorded.get("pid").getAsLong(),
                    recorded.get("token").getAsString(),
                    Instant.parse(recorded.get("startedAt").getAsString()));
            if (!pidAlive(info.pid(), info.startedAt())) {
                return Optional.empty();
            }
            return portAnswers(info.port()) ? Optional.of(info) : Optional.empty();
        } catch (RuntimeException e) {
            ClientLog.warning("cannot parse daemon pidfile " + pidfile + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The bound loopback port; valid after {@link #start}. */
    public int port() {
        DaemonInfo snapshot;
        synchronized (this) {
            snapshot = info;
        }
        if (snapshot == null) {
            throw new IllegalStateException("fleet daemon is not started");
        }
        return snapshot.port();
    }

    /** Blocks until the daemon has stopped (daemon/shutdown or {@link #stop()}). */
    public void awaitStopped() throws InterruptedException {
        stopped.await();
    }

    /**
     * Graceful stop, idempotent: stop accepting, drain open connections
     * briefly (then force-close them), close the owned engine, remove the
     * pidfile. Safe to call from a JVM shutdown hook and concurrently with
     * a {@code daemon/shutdown} in flight. The monitor guards only the
     * idempotence flag - the drain itself runs unheld so an in-flight
     * handshake (which briefly takes the monitor) cannot stall it.
     */
    public void stop() {
        ServerSocket closing;
        synchronized (this) {
            if (stopping) {
                return;
            }
            stopping = true;
            closing = server;
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (IOException e) {
                ClientLog.warning("fleet daemon listener close failed: " + e.getMessage());
            }
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(CONNECTION_DRAIN.toMillis(), TimeUnit.MILLISECONDS)) {
                forceCloseConnections();
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            forceCloseConnections();
            pool.shutdownNow();
        }
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception e) {
                ClientLog.warning("fleet daemon engine close failed: " + e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(pidfileAt(repoRoot));
        } catch (IOException e) {
            ClientLog.warning("cannot remove the daemon pidfile " + pidfileAt(repoRoot) + ": " + e.getMessage());
        }
        stopped.countDown();
        if (closing != null) {
            ClientLog.info("fleet daemon stopped (was listening on 127.0.0.1:" + closing.getLocalPort() + ")");
        }
    }

    /** {@link AutoCloseable} alias so try-with-resources owns the daemon. */
    @Override
    public void close() {
        stop();
    }

    private void acceptLoop(ServerSocket bound) {
        while (!bound.isClosed()) {
            try {
                Socket socket = bound.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException e) {
                if (!bound.isClosed()) {
                    ClientLog.warning("fleet daemon accept failed, stopping listener: " + e.getMessage());
                }
                return;
            } catch (RuntimeException e) {
                // pool rejected the task (stop() raced the accept): drop the socket and wind down
                ClientLog.warning("fleet daemon cannot serve connection: " + e.getMessage());
                try {
                    bound.close();
                } catch (IOException ignored) {
                    // already going away
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        connections.add(socket);
        try (socket) {
            socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintStream out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            String hello = in.readLine();
            if (hello == null || !handshake(hello, out)) {
                return; // rejected handshake: the error line is written, closing the socket
            }
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.contains("daemon/shutdown")) {
                    Shutdown shutdown = shutdown(line, out);
                    if (shutdown == Shutdown.ACCEPTED) {
                        return; // acknowledged; the stop thread drains and closes
                    }
                    if (shutdown == Shutdown.REFUSED) {
                        continue; // only the shutdown was refused; keep serving
                    }
                }
                String response = dispatcher.handle(line);
                if (response != null) {
                    out.println(response);
                }
            }
        } catch (IOException e) {
            // client detach mid-line: normal (tools stay pull-based, the daemon keeps everything)
        } finally {
            connections.remove(socket);
        }
    }

    /**
     * The pinned handshake: the first line must be a {@code daemon/hello}
     * request carrying the pidfile token. Writes the JSON-RPC result
     * ({@code {"ok":true,"port":...,"pid":...}}) on success, or a -32000
     * error on any other first line, and answers whether dispatching may
     * begin. The comparison is constant-time so a scan cannot time-side-channel it.
     */
    private boolean handshake(String line, PrintStream out) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(line);
        } catch (JsonSyntaxException e) {
            out.println(error(null, -32700, "parse error"));
            return false;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            out.println(error(null, -32600, "invalid request: expected a single JSON-RPC 2.0 object"));
            return false;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        JsonElement method = request.get("method");
        if (method == null || !method.isJsonPrimitive()
                || !"daemon/hello".equals(method.getAsString())) {
            out.println(error(id, UNAUTHORIZED,
                    "unauthorized: the first request must be daemon/hello"));
            return false;
        }
        JsonElement paramsElement = request.get("params");
        JsonObject params = paramsElement != null && paramsElement.isJsonObject()
                ? paramsElement.getAsJsonObject()
                : null;
        JsonElement sent = params == null ? null : params.get("token");
        if (sent == null || !sent.isJsonPrimitive()
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        sent.getAsString().getBytes(StandardCharsets.UTF_8))) {
            // pidfile hygiene: the token value never appears in the refusal
            out.println(error(id, UNAUTHORIZED, "unauthorized: invalid daemon token"));
            return false;
        }
        DaemonInfo snapshot;
        synchronized (this) {
            snapshot = info;
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("port", snapshot.port());
        result.addProperty("pid", snapshot.pid());
        out.println(result(id, result));
        return true;
    }

    /** Outcome of intercepting a {@code daemon/shutdown} line from an authed connection. */
    private enum Shutdown {
        /** Not a shutdown request (or unparseable): dispatch the line verbatim. */
        NOT_SHUTDOWN,
        /** Wrong token: the shutdown alone is refused; the connection keeps serving. */
        REFUSED,
        /** Right token: acknowledged; the graceful stop is running on its own thread. */
        ACCEPTED
    }

    /**
     * Handles an authed {@code daemon/shutdown} line: with the right token
     * the request is acknowledged and the graceful stop runs on its own
     * thread (the drain must not wait on the requesting connection's own
     * handler); with a wrong token only the shutdown is refused and the
     * connection keeps serving.
     */
    private Shutdown shutdown(String line, PrintStream out) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(line);
        } catch (JsonSyntaxException e) {
            return Shutdown.NOT_SHUTDOWN;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return Shutdown.NOT_SHUTDOWN;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement method = request.get("method");
        if (method == null || !method.isJsonPrimitive()
                || !"daemon/shutdown".equals(method.getAsString())) {
            return Shutdown.NOT_SHUTDOWN;
        }
        JsonElement id = request.get("id");
        JsonElement paramsElement = request.get("params");
        JsonObject params = paramsElement != null && paramsElement.isJsonObject()
                ? paramsElement.getAsJsonObject()
                : null;
        JsonElement sent = params == null ? null : params.get("token");
        if (sent == null || !sent.isJsonPrimitive()
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        sent.getAsString().getBytes(StandardCharsets.UTF_8))) {
            out.println(error(id, UNAUTHORIZED, "unauthorized: daemon/shutdown requires the daemon token"));
            return Shutdown.REFUSED;
        }
        out.println(result(id, okResult()));
        out.flush();
        Thread stopper = new Thread(this::stop, "fleet-daemon-shutdown");
        // non-daemon: a stop in progress must survive the last client detaching
        stopper.setDaemon(false);
        stopper.start();
        return Shutdown.ACCEPTED;
    }

    private static JsonObject okResult() {
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return ok;
    }

    private static String result(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("result", result);
        return GSON.toJson(response);
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("error", error);
        return GSON.toJson(response);
    }

    private void forceCloseConnections() {
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort: unblocks readLine so the pool can terminate
            }
        }
    }

    private static Path pidfileAt(Path repoRoot) {
        return FleetGit.fleetRoot(repoRoot).resolve("daemon.json");
    }

    /** Atomic pidfile publish (temp file + move), creating the fleet root. */
    private static void writePidfile(Path pidfile, DaemonInfo info) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("port", info.port());
        json.addProperty("pid", info.pid());
        json.addProperty("token", info.token());
        json.addProperty("startedAt", info.startedAt().toString());
        Files.createDirectories(pidfile.getParent());
        Path tmp = pidfile.resolveSibling(pidfile.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, pidfile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, pidfile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pid liveness with the pid-reuse check: a live pid with a different start instant is residue. */
    private static boolean pidAlive(long pid, Instant startedAt) {
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) {
            return false;
        }
        Optional<Instant> start = process.get().info().startInstant();
        return start.isEmpty() || startedAt == null || start.get().equals(startedAt);
    }

    private static boolean portAnswers(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    PROBE_CONNECT_TIMEOUT_MILLIS);
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    private static ThreadFactory daemons() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "fleet-daemon-conn-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
