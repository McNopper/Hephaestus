package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tools.McpTool;
import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.ToolProvider;

/**
 * Tests for the V-006 daemon core ({@link FleetDaemon}): the
 * {@code daemon/hello} auth gate over a plain TCP socket, verbatim
 * line dispatch through {@link com.opencode.ide.tools.McpDispatcher},
 * {@code daemon/shutdown} (listener stop, engine drain, pidfile
 * removal), the documented pidfile shape, the per-repo singleton check
 * and stale-pidfile replacement. Every test gets its own temp repo root
 * (hence its own pidfile and ephemeral port), so the class is
 * parallel-safe; sockets carry read timeouts so a broken daemon fails
 * the test instead of hanging it.
 */
public class FleetDaemonTest {

    /** One echo tool: tools/call answers with the arguments object verbatim. */
    private static final class EchoProvider implements ToolProvider {
        @Override
        public String language() {
            return "test";
        }

        @Override
        public List<McpTool> tools() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", new JsonObject());
            schema.add("required", new JsonArray());
            return List.of(new McpTool("echo_test", "echoes its arguments", schema));
        }

        @Override
        public McpToolResult call(String toolName, JsonObject arguments) {
            return McpToolResult.json(arguments);
        }
    }

    /** Minimal line-based test client over a plain {@link Socket}. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;

        Client(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(10_000);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void send(String line) throws IOException {
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        String read() throws IOException {
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private Path base;
    private final List<FleetDaemon> daemons = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        base = Files.createTempDirectory("opencode-fleet-daemon").toAbsolutePath().normalize();
    }

    @After
    public void tearDown() {
        for (FleetDaemon daemon : daemons) {
            daemon.stop(); // idempotent; also covers tests that failed mid-flight
        }
        if (base != null && Files.isDirectory(base)) {
            deleteRecursively(base);
        }
    }

    @Test(timeout = 20000)
    public void wrongOrMissingTokenHelloIsRejectedAndTheConnectionCloses() throws Exception {
        FleetDaemon daemon = startDaemon(new EchoProvider());
        String token = tokenOf(pidfile());
        try (Client client = new Client(daemon.port())) {
            client.send(hello("definitely-not-" + token));
            String refusal = client.read();
            assertNotNull(refusal);
            JsonObject response = JsonParser.parseString(refusal).getAsJsonObject();
            assertEquals(-32000, response.getAsJsonObject("error").get("code").getAsInt());
            assertFalse("the refusal must not leak the token", refusal.contains(token));
            assertNull("the daemon closes the connection after the refusal", client.read());
        }
        try (Client client = new Client(daemon.port())) {
            client.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"daemon/hello\"}");
            JsonObject response = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(-32000, response.getAsJsonObject("error").get("code").getAsInt());
            assertNull(client.read());
        }
    }

    @Test(timeout = 20000)
    public void firstLineMustBeHelloBeforeAnythingIsDispatched() throws Exception {
        FleetDaemon daemon = startDaemon(new EchoProvider());
        try (Client client = new Client(daemon.port())) {
            client.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            JsonObject response = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(-32000, response.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(response.toString().contains("daemon/hello"));
            assertNull("no dispatch happens before the handshake", client.read());
        }
    }

    @Test(timeout = 20000)
    public void helloThenToolsListAndCallRoundTripOverPlainSocket() throws Exception {
        FleetDaemon daemon = startDaemon(new EchoProvider());
        String token = tokenOf(pidfile());
        try (Client client = new Client(daemon.port())) {
            client.send(hello(token));
            JsonObject hello = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(1, hello.get("id").getAsInt());
            JsonObject result = hello.getAsJsonObject("result");
            assertTrue(result.get("ok").getAsBoolean());
            assertEquals(daemon.port(), result.get("port").getAsInt());

            client.send(request(2, "tools/list", null));
            JsonObject listed = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(2, listed.get("id").getAsInt());
            assertTrue(listed.toString().contains("echo_test"));

            JsonObject args = new JsonObject();
            args.addProperty("greeting", "hi");
            JsonObject params = new JsonObject();
            params.addProperty("name", "echo_test");
            params.add("arguments", args);
            client.send(request(3, "tools/call", params));
            JsonObject called = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(3, called.get("id").getAsInt());
            assertFalse(called.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(called.toString().contains("hi"));

            // notifications produce no output (stdio semantics), the next
            // response is for the request after them
            client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            client.send(request(7, "ping", null));
            JsonObject pong = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(7, pong.get("id").getAsInt());
        }
    }

    @Test(timeout = 20000)
    public void daemonShutdownStopsTheListenerDropsThePidfileAndClosesTheEngine() throws Exception {
        AtomicBoolean engineClosed = new AtomicBoolean();
        FleetDaemon daemon = track(FleetDaemon.start(
                repo(), List.of(new EchoProvider()), (AutoCloseable) () -> engineClosed.set(true)));
        String token = tokenOf(pidfile());
        try (Client client = new Client(daemon.port())) {
            client.send(hello(token));
            assertTrue(JsonParser.parseString(client.read()).getAsJsonObject()
                    .getAsJsonObject("result").get("ok").getAsBoolean());

            // a wrong-token shutdown is refused and the connection keeps serving
            JsonObject badParams = new JsonObject();
            badParams.addProperty("token", "nope");
            client.send(request(2, "daemon/shutdown", badParams));
            JsonObject refused = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(-32000, refused.getAsJsonObject("error").get("code").getAsInt());
            client.send(request(3, "ping", null));
            assertEquals(3, JsonParser.parseString(client.read()).getAsJsonObject().get("id").getAsInt());

            JsonObject params = new JsonObject();
            params.addProperty("token", token);
            client.send(request(4, "daemon/shutdown", params));
            JsonObject acknowledged = JsonParser.parseString(client.read()).getAsJsonObject();
            assertEquals(4, acknowledged.get("id").getAsInt());
            assertTrue(acknowledged.getAsJsonObject("result").get("ok").getAsBoolean());
        }
        awaitStoppedWithin(10, daemon);
        assertTrue("the owned engine was drained", engineClosed.get());
        assertFalse("the pidfile is removed", Files.exists(pidfile()));
        assertFalse(FleetDaemon.probe(repo()).isPresent());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), daemon.port()), 500);
            fail("the listener must be closed after daemon/shutdown");
        } catch (IOException expected) {
            // port no longer answers
        }
        // a successor may start immediately on the same repo
        FleetDaemon successor = startDaemon(new EchoProvider());
        assertTrue(FleetDaemon.probe(repo()).isPresent());
        assertEquals(successor.port(), FleetDaemon.probe(repo()).orElseThrow().port());
    }

    @Test(timeout = 20000)
    public void pidfileHasTheDocumentedShape() throws Exception {
        String token = "0123456789abcdef".repeat(4);
        FleetDaemon daemon = track(FleetDaemon.start(repo(), List.of(new EchoProvider()), null, token));
        JsonObject recorded = JsonParser.parseString(Files.readString(pidfile())).getAsJsonObject();
        assertEquals("exactly the pinned fields", Set.of("port", "pid", "token", "startedAt"),
                recorded.keySet());
        assertEquals(daemon.port(), recorded.get("port").getAsInt());
        assertEquals(ProcessHandle.current().pid(), recorded.get("pid").getAsLong());
        assertEquals(token, recorded.get("token").getAsString());
        Instant startedAt = Instant.parse(recorded.get("startedAt").getAsString());
        java.util.Optional<Instant> processStart = ProcessHandle.current().info().startInstant();
        assertTrue("startedAt is the process-start pid-reuse anchor",
                processStart.isEmpty() || processStart.get().equals(startedAt));
        DaemonInfo probed = FleetDaemon.probe(repo()).orElseThrow();
        assertEquals(daemon.port(), probed.port());
        assertEquals(token, probed.token());
        assertEquals(ProcessHandle.current().pid(), probed.pid());
    }

    @Test(timeout = 20000)
    public void generatedTokenIsHexAndTheDaemonInfoToStringHidesIt() throws Exception {
        org.junit.Assume.assumeTrue("FLEET_DAEMON_PASSWORD pinned in this environment",
                System.getenv("FLEET_DAEMON_PASSWORD") == null);
        FleetDaemon daemon = startDaemon(new EchoProvider());
        String token = tokenOf(pidfile());
        assertTrue(token + " is not 48+ lowercase hex", token.matches("[0-9a-f]{48,}"));
        assertFalse("toString never carries the token",
                FleetDaemon.probe(repo()).orElseThrow().toString().contains(token));
    }

    @Test(timeout = 20000)
    public void secondStartWhileLiveRefusesNamingTheLivePort() throws Exception {
        FleetDaemon daemon = startDaemon(new EchoProvider());
        try {
            FleetDaemon.start(repo(), List.of(new EchoProvider()), null);
            fail("a second daemon for the same repo must be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(daemon.port())));
            assertFalse("the refusal never carries the token", e.getMessage().contains(tokenOf(pidfile())));
        }
        assertEquals("the live daemon keeps its pidfile",
                daemon.port(), FleetDaemon.probe(repo()).orElseThrow().port());
    }

    @Test(timeout = 20000)
    public void stalePidfilesAreNotLiveAndAreReplacedByStart() throws Exception {
        // pid alive but startedAt differs (pid reuse): residue
        writePidfile(ProcessHandle.current().pid(), Instant.parse("2000-01-01T00:00:00Z"));
        assertFalse(FleetDaemon.probe(repo()).isPresent());
        // pid dead: residue
        writePidfile(deadProcessPid(), Instant.now());
        assertFalse(FleetDaemon.probe(repo()).isPresent());
        // garbage: residue
        Files.writeString(pidfile(), "not json at all");
        assertFalse(FleetDaemon.probe(repo()).isPresent());

        FleetDaemon daemon = startDaemon(new EchoProvider());
        DaemonInfo live = FleetDaemon.probe(repo()).orElseThrow();
        assertEquals(daemon.port(), live.port());
        assertEquals(ProcessHandle.current().pid(), live.pid());
        assertEquals(tokenOf(pidfile()), live.token());
    }

    // ---- helpers -------------------------------------------------------

    private Path repo() {
        return base.resolve("repo");
    }

    private Path pidfile() {
        return FleetGit.fleetRoot(repo()).resolve("daemon.json");
    }

    private FleetDaemon startDaemon(ToolProvider... providers) {
        return track(FleetDaemon.start(repo(), providers));
    }

    private FleetDaemon track(FleetDaemon daemon) {
        daemons.add(daemon);
        return daemon;
    }

    private static String tokenOf(Path pidfile) throws IOException {
        return JsonParser.parseString(Files.readString(pidfile)).getAsJsonObject()
                .get("token").getAsString();
    }

    private void writePidfile(long pid, Instant startedAt) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("port", 9);
        json.addProperty("pid", pid);
        json.addProperty("token", "residue");
        json.addProperty("startedAt", startedAt.toString());
        Files.createDirectories(pidfile().getParent());
        Files.writeString(pidfile(), json.toString());
    }

    /** A pid that was alive and is guaranteed dead now (spawn + waitFor). */
    private static long deadProcessPid() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        Process process = new ProcessBuilder(executable, "-version").start();
        process.getErrorStream().readAllBytes();
        process.getInputStream().readAllBytes();
        process.waitFor();
        return process.pid();
    }

    private static void awaitStoppedWithin(int seconds, FleetDaemon daemon) throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                daemon.awaitStopped();
                return null;
            }).get(seconds, TimeUnit.SECONDS);
        }
    }

    private static String hello(String token) {
        JsonObject params = new JsonObject();
        params.addProperty("token", token);
        return request(1, "daemon/hello", params);
    }

    private static String request(int id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        return request.toString();
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
