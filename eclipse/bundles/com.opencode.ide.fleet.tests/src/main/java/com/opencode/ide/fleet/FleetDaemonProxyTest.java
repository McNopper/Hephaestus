package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@link FleetDaemonProxy} against {@link FakeDaemon}, an in-test stand-in
 * speaking the pinned wire contract (authed {@code daemon/hello} first line,
 * one JSON-RPC result line per request, close-on-refusal, clean close on
 * client EOF). The real {@code FleetDaemon} is deliberately never
 * instantiated — this slice owns the client side only.
 */
public class FleetDaemonProxyTest {

    private static final String TOKEN = "0123456789abcdef".repeat(4);

    @Test(timeout = 20000)
    public void handshakeSucceedsAndPumpsPassthroughVerbatim() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}";
            String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
            InputStream clientIn = new ByteArrayInputStream(
                    (initialize + "\n" + toolsList + "\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), TOKEN, clientIn, clientOut);
            assertEquals(FleetDaemonProxy.End.CLIENT_CLOSED, proxy.awaitCompletion());
            String stdout = sink.toString(StandardCharsets.UTF_8);
            // the handshake exchange is internal: its result never reaches the client
            assertFalse(stdout.contains("hello"));
            // both requests round-tripped with their ids intact
            assertTrue(stdout.contains("\"id\":1,"));
            assertTrue(stdout.contains("\"id\":2,"));
            assertTrue(stdout.contains("initialize"));
            assertTrue(stdout.contains("tools/list"));
            assertEquals(List.of(initialize, toolsList), daemon.passthrough);
            assertEquals(1, daemon.finishedSessions);
        }
    }

    @Test(timeout = 20000)
    public void clientDetachLeavesTheDaemonListening() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            String toolsList = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            InputStream clientIn = new ByteArrayInputStream((toolsList + "\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), TOKEN, clientIn, clientOut);
            assertEquals(FleetDaemonProxy.End.CLIENT_CLOSED, proxy.awaitCompletion());
            // detach semantics: the client leaving ends only its session
            assertFalse(daemon.shutdownSeen);
            assertEquals(1, daemon.finishedSessions);
            assertTrue(daemon.listener.isBound());
            // ...and the daemon still accepts and handshakes the next client
            try (Socket next = new Socket("127.0.0.1", daemon.port())) {
                String reply = helloHandshake(next, TOKEN);
                assertNotNull(reply);
                assertTrue(reply.contains("\"result\""));
            }
        }
    }

    @Test(timeout = 20000)
    public void wrongTokenSurfacesTheErrorLineAndDoesNotBlock() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            try {
                FleetDaemonProxy.attach("127.0.0.1", daemon.port(), "f".repeat(64),
                        new ByteArrayInputStream(new byte[0]), clientOut);
                fail("attach must fail on a refused handshake");
            } catch (IOException e) {
                // the daemon's error line is surfaced; the token is not
                assertTrue(e.getMessage().contains("unauthorized"));
                assertFalse(e.getMessage().contains(TOKEN));
            }
            assertTrue(daemon.refused);
            assertEquals(List.of(), daemon.passthrough);
            // the failed attach left nothing running: the daemon serves the right token next
            try (Socket next = new Socket("127.0.0.1", daemon.port())) {
                String reply = helloHandshake(next, TOKEN);
                assertNotNull(reply);
                assertTrue(reply.contains("\"result\""));
            }
        }
    }

    @Test(timeout = 20000)
    public void daemonDeathMidSessionEndsAsDaemonClosed() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            daemon.dieAfterResponding = true;
            PipedInputStream clientIn = new PipedInputStream();
            PipedOutputStream writer = new PipedOutputStream(clientIn);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), TOKEN, clientIn, clientOut);
            String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"fleet_jobs\"}}";
            writer.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
            awaitTrue(() -> sink.toString(StandardCharsets.UTF_8).contains("fleet_jobs"));
            // wait for the proxy to have SEEN the daemon-side EOF, so the
            // client leaving afterwards cannot mask who closed first
            awaitTrue(() -> proxy.currentEnd() == FleetDaemonProxy.End.DAEMON_CLOSED);
            writer.close();
            assertEquals(FleetDaemonProxy.End.DAEMON_CLOSED, proxy.awaitCompletion());
            assertFalse(daemon.shutdownSeen);
            assertEquals(0, daemon.finishedSessions); // not a clean detach
        }
    }

    @Test(timeout = 20000)
    public void daemonDeathReturnsEvenWhileTheClientStdinStaysOpen() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            daemon.dieAfterResponding = true;
            PipedOutputStream writer = new PipedOutputStream();
            PipedInputStream clientIn = new PipedInputStream(writer); // never closed inside the test
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), TOKEN, clientIn, clientOut);
            String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";
            writer.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
            // stdin stays open: the completion must come from the daemon side
            // alone, without waiting for the client to ever close (a client
            // that missed the stdout EOF cannot hang the non-zero exit)
            assertEquals(FleetDaemonProxy.End.DAEMON_CLOSED, proxy.awaitCompletion());
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("\"id\":3"));
            writer.close(); // hygiene: release the up pump's blocked read
        }
    }

    @Test(timeout = 20000)
    public void tokensWithJsonMetacharactersHandshakeAndPump() throws Exception {
        // a token FLEET_DAEMON_PASSWORD could legally contain: quote,
        // backslash and a raw newline would corrupt a string-concatenated
        // hello frame but must survive a real-JSON one
        String awkward = "pa\"ss\\wo\nrd";
        try (FakeDaemon daemon = new FakeDaemon(awkward)) {
            String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            InputStream clientIn = new ByteArrayInputStream((ping + "\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), awkward, clientIn, clientOut);
            assertEquals(FleetDaemonProxy.End.CLIENT_CLOSED, proxy.awaitCompletion());
            assertEquals(List.of(ping), daemon.passthrough);
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("\"id\":1"));
        }
    }

    @Test(timeout = 20000)
    public void shutdownRequestIsPumpedLikeAnyOtherLine() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(TOKEN)) {
            String shutdown = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"daemon/shutdown\","
                    + "\"params\":{\"token\":\"" + TOKEN + "\"}}";
            InputStream clientIn = new ByteArrayInputStream((shutdown + "\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream clientOut = new PrintStream(sink, true, StandardCharsets.UTF_8);
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), TOKEN, clientIn, clientOut);
            // the proxy must not intercept daemon/shutdown: it is just a line
            proxy.awaitCompletion();
            assertTrue(daemon.shutdownSeen);
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains("bye"));
        }
    }

    /** Sends the pinned hello line on the socket and returns the daemon's reply line. */
    private static String helloHandshake(Socket socket, String token) throws IOException {
        Writer toDaemon = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader fromDaemon = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        toDaemon.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"daemon/hello\","
                + "\"params\":{\"token\":\"" + token + "\"}}\n");
        toDaemon.flush();
        return fromDaemon.readLine();
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("condition not met within 10s");
    }

    /** A scripted stand-in for the daemon following the pinned wire contract. */
    private static final class FakeDaemon implements AutoCloseable {

        final ServerSocket listener = new ServerSocket();
        final String token;
        final List<String> passthrough = new CopyOnWriteArrayList<>();
        volatile boolean refused;
        volatile boolean shutdownSeen;
        volatile boolean dieAfterResponding;
        volatile int finishedSessions;

        FakeDaemon(String token) throws IOException {
            this.token = token;
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread.ofPlatform().daemon(true).name("fake-daemon-accept").start(this::acceptLoop);
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!listener.isClosed()) {
                try (Socket socket = listener.accept()) {
                    serve(socket);
                } catch (IOException e) {
                    return; // listener closed: the test is done
                }
            }
        }

        private void serve(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintStream out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            JsonObject hello = parse(in.readLine());
            if (hello == null || !"daemon/hello".equals(methodOf(hello)) || !token.equals(tokenOf(hello))) {
                refused = true;
                out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                        + "{\"code\":-32000,\"message\":\"unauthorized\"}}");
                out.flush();
                return; // contract: the daemon closes after the error result
            }
            out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"hello\":true}}");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject request = parse(line);
                if (request != null && "daemon/shutdown".equals(methodOf(request))) {
                    shutdownSeen = true;
                    out.println(reply(request, "{\"bye\":true}"));
                    out.flush();
                    return;
                }
                passthrough.add(line);
                out.println(reply(request, "{\"echo\":" + GSON.toJson(line) + "}"));
                out.flush();
                if (dieAfterResponding) {
                    return; // simulated daemon death mid-session
                }
            }
            finishedSessions++; // clean client detach
        }

        private static String reply(JsonObject request, String resultJson) {
            String id = request != null && request.has("id") ? request.get("id").toString() : "null";
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
        }

        private static JsonObject parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            try {
                var element = JsonParser.parseString(line);
                return element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static String methodOf(JsonObject request) {
            return request.has("method") && request.get("method").isJsonPrimitive()
                    ? request.get("method").getAsString() : null;
        }

        private static String tokenOf(JsonObject request) {
            if (request == null || !request.has("params") || !request.get("params").isJsonObject()) {
                return null;
            }
            JsonObject params = request.getAsJsonObject("params");
            return params.has("token") && params.get("token").isJsonPrimitive()
                    ? params.get("token").getAsString() : null;
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }

        private static final Gson GSON = new Gson();
    }
}
