package com.opencode.ide.client;

import com.opencode.ide.client.ClientTuning;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.opencode.ide.client.model.HealthStatus;

/**
 * Starts and owns a child {@code opencode serve} process (spawn mode).
 *
 * <p>Resolves the {@code opencode} binary (explicit path, else PATH search with
 * Windows {@code .cmd}/{@code .exe} handling), allocates a free port (unless a
 * fixed one is configured), spawns the server, waits for {@code /global/health}
 * to go healthy, and tears the whole process tree down on {@link #stop()}.
 * On a successful start it captures the health snapshot
 * ({@link #getLastHealth()}) and warns when the server's version drifted
 * from the {@link ServerVersionPin} (H-002 — a warning, never a failed
 * start).</p>
 */
public final class OpencodeServerLauncher {

    private static final Gson GSON = new Gson();

    private final String configuredBinary;
    private final String hostname;
    private final int port;
    private final Path workingDirectory;
    private final String password;

    private Process process;
    private URI baseUrl;
    private Thread outputDrainer;
    private volatile HealthStatus lastHealth;

    public OpencodeServerLauncher(String configuredBinary, String hostname, int port,
            Path workingDirectory, String password) {
        this.configuredBinary = configuredBinary;
        this.hostname = hostname;
        this.port = port;
        this.workingDirectory = workingDirectory;
        this.password = password;
    }

    /**
     * Starts the server and blocks until it is healthy (or {@code timeout} elapses).
     *
     * @return the base URL of the running server
     */
    public synchronized URI start(Duration timeout) throws OpencodeException {
        if (process != null && process.isAlive()) {
            return baseUrl;
        }

        if (port > 0) {
            requirePortFree(hostname, port);
        }

        Path binary = BinaryResolver.resolveBinary(configuredBinary);
        if (binary == null) {
            throw new OpencodeConnectionException(
                    "opencode binary not found. Configure it in Preferences → OpenCode "
                            + "(spawn mode), or put 'opencode' on PATH.");
        }

        int actualPort = port > 0 ? port : findFreePort();

        List<String> command = new ArrayList<>();
        if (isWindowsScript(binary)) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(binary.toString());
        command.add("serve");
        command.add("--hostname");
        command.add(hostname);
        command.add("--port");
        command.add(Integer.toString(actualPort));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            builder.directory(workingDirectory.toFile());
        }
        if (password != null && !password.isEmpty()) {
            Map<String, String> env = builder.environment();
            env.put("OPENCODE_SERVER_PASSWORD", password);
        }
        builder.redirectErrorStream(true);

        try {
            process = builder.start();
        } catch (IOException e) {
            throw new OpencodeConnectionException("Failed to start opencode server", e);
        }

        baseUrl = URI.create("http://" + hostname + ":" + actualPort);
        drainOutput(process.getInputStream());

        try {
            waitForHealth(baseUrl, timeout);
        } catch (OpencodeException e) {
            stop();
            throw e;
        }
        warnOnVersionMismatch();
        return baseUrl;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized URI getBaseUrl() {
        return baseUrl;
    }

    /** @return the OS pid of the spawned server process while it is running, else {@code null}. */
    public synchronized Long getProcessId() {
        return (process != null && process.isAlive()) ? process.pid() : null;
    }

    /**
     * The health snapshot the readiness poll captured (H-002): the server
     * version {@link ServerVersionPin} compares against. {@code null} before
     * the first successful poll or when the body could not be parsed.
     */
    public HealthStatus getLastHealth() {
        return lastHealth;
    }

    /** Kills the spawned process and its descendants. */
    public synchronized void stop() {
        if (process != null) {
            try {
                process.descendants().forEach(h -> {
                    try { h.destroyForcibly(); } catch (Exception ignored) { /* ignore */ }
                });
                process.destroyForcibly();
                if (!process.waitFor(ClientTuning.DESTROY_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    // already forced; nothing more to do
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                process = null;
            }
        }
    }

    // ---- helpers ----

    private static boolean isWindowsScript(Path binary) {
        String name = binary.getFileName().toString().toLowerCase();
        return name.endsWith(".cmd") || name.endsWith(".bat");
    }

    private static int findFreePort() throws OpencodeConnectionException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(0));
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new OpencodeConnectionException("Could not allocate a free port for opencode serve", e);
        }
    }

    /**
     * A configured (fixed) port must be OURS. A stranger already listening there
     * (typically a stale orphaned {@code opencode serve} from a crashed session)
     * would answer our authenticated probes with 401 forever - fail fast with a
     * message that names the cause instead.
     */
    private static void requirePortFree(String host, int port) throws OpencodeConnectionException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(host, port), 1);
        } catch (IOException e) {
            throw new OpencodeConnectionException("Port " + port + " on " + host
                    + " is already in use - probably a stale 'opencode serve' from a previous"
                    + " session. Stop that process (or clear the configured port) and reconnect.", e);
        }
    }

    private void waitForHealth(URI base, Duration timeout) throws OpencodeException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(ClientTuning.PROBE_CONNECT_TIMEOUT)
                .build();
        // the readiness probes must authenticate exactly like the real client:
        // with a password set (always, in fleet spawn mode - possibly generated),
        // unauthenticated probes would 401 until the timeout and abort the start
        String auth = com.opencode.ide.client.internal.Auth.basicHeader("opencode", password);
        HttpRequest healthReq = probe(base, "/global/health", auth, ClientTuning.HEALTH_PROBE_TIMEOUT);
        // opencode reports /global/health = healthy before the data endpoints are
        // populated, so also require a 200 from /agent before considering the server ready.
        HttpRequest agentReq = probe(base, "/agent", auth, ClientTuning.AGENT_PROBE_TIMEOUT);

        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        Integer lastStatus = null;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new OpencodeConnectionException(
                        "opencode serve exited before becoming ready");
            }
            try {
                HttpResponse<String> health = client.send(healthReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (health.statusCode() == 200 && health.body().contains("\"healthy\":true")) {
                    HttpResponse<String> agent = client.send(agentReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (agent.statusCode() == 200) {
                        lastHealth = parseHealth(health.body()); // captured for getLastHealth() + the version pin
                        return; // health + data layer both ready
                    }
                    lastStatus = agent.statusCode();
                } else {
                    lastStatus = health.statusCode();
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpencodeConnectionException("Interrupted while waiting for opencode readiness", e);
            }
            try {
                Thread.sleep(ClientTuning.PROBE_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpencodeConnectionException("Interrupted while waiting for opencode readiness", e);
            }
        }
        throw new OpencodeConnectionException(
                "opencode server did not become ready within " + timeout.toSeconds() + "s"
                        + (lastStatus != null ? " (last HTTP status: " + lastStatus
                                + (lastStatus == 401 ? " - check the server password" : "") + ")" : "")
                        + (last == null ? "" : " (last error: " + last.getMessage() + ")"));
    }

    /** A GET probe for the readiness poll, authenticated when a password is set. */
    private static HttpRequest probe(URI base, String path, String auth, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(base.resolve(path))
                .timeout(timeout)
                .GET();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return builder.build();
    }

    /** Deserializes the polled health body; never throws — an unparseable body reads as no snapshot ({@code null}). */
    private static HealthStatus parseHealth(String body) {
        if (body == null) {
            return null;
        }
        try {
            return GSON.fromJson(body, HealthStatus.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** H-002: warns through the client log when the started server left the pinned version; never fails the start. */
    private void warnOnVersionMismatch() {
        String warning = ServerVersionPin.evaluate(lastHealth).warning();
        if (warning != null) {
            ClientLog.warning(warning);
        }
    }

    /** Spawns a daemon thread that keeps the child's stdout drained. */
    private void drainOutput(InputStream in) {
        outputDrainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    ClientLog.warning("[opencode serve] " + line);
                }
            } catch (IOException ignored) {
                // process closed stream - expected on stop
            }
        }, "opencode-serve-output");
        outputDrainer.setDaemon(true);
        outputDrainer.start();
    }
}
