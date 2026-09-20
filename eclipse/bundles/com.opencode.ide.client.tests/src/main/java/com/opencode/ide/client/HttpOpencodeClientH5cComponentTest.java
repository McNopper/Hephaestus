package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.OauthStart;
import com.opencode.ide.client.model.ShellResult;

/**
 * Component test for the H5 leftovers of the client surface (session shell,
 * OAuth): real {@code HttpOpencodeClient} over real HTTP against a local stub
 * server, verifying paths, methods, bodies and parsing. Stub bodies follow the
 * v2 shapes verified against a live opencode 2.0.10 server: the shell command
 * is posted without a result body and its lifecycle is polled off
 * {@code GET /api/session/:id/message} as a {@code Session.Message.Shell};
 * v1's OAuth authorize endpoint is gone (moved to the integration flow).
 * No Eclipse, no opencode.
 */
public class HttpOpencodeClientH5cComponentTest {

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final Map<String, String> bodiesByPath = new ConcurrentHashMap<>();
    /** Settable body/status the stub serves on GET /api/session/ses_1/message. */
    private static final AtomicReference<String> messageBody = new AtomicReference<>();
    private static final AtomicInteger messageStatus = new AtomicInteger(200);
    /**
     * /message GETs per test: the FIRST answers empty (the client's anchor
     * fetch), later ones answer {@link #messageBody} (the shell's lifecycle).
     */
    private static final AtomicInteger messageGets = new AtomicInteger();

    @BeforeClass
    public static void startStub() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            bodiesByPath.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body;
            int status = 200;
            if ("/api/session/ses_1/message".equals(path)) {
                // the anchor fetch (first GET) sees no shells yet; the poll then
                // watches this run's shell message
                body = messageGets.getAndIncrement() == 0 ? "{\"data\":[]}" : messageBody.get();
                status = messageStatus.get();
            } else if ("/api/integration".equals(path)) {
                body = """
                        {"location":{},"data":[
                          {"id":"anthropic","name":"Anthropic",
                           "methods":[{"type":"key"},{"id":"browser","type":"oauth","label":"Login with Anthropic"}],
                           "connections":[]}
                        ]}
                        """;
            } else if ("/api/integration/anthropic/connect/oauth".equals(path)) {
                body = """
                        {"location":{},"data":{"attemptID":"att_1",
                          "url":"https://auth.anthropic.com/oauth","instructions":"open the url",
                          "mode":"auto","time":{"created":1,"expires":2}}}
                        """;
            } else {
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        client = new com.opencode.ide.client.internal.HttpOpencodeClient(
                new ConnectionConfig(URI.create("http://127.0.0.1:" + port), null, null));
    }

    @Before
    public void resetStub() {
        bodiesByPath.clear();
        messageStatus.set(200);
        messageGets.set(0);
        messageBody.set("""
                {"data":[
                  {"id":"msg_sh1","time":{"created":3},"type":"shell","shellID":"sh_1",
                   "command":"git status --short","status":"exited","exit":0,
                   "output":{"output":"M src/a.cpp","cursor":9,"size":9,"truncated":false}}
                ]}
                """);
    }

    @AfterClass
    public static void stopStub() {
        server.stop(0);
    }

    // ---------- session shell (v2: async, polled off the message list) ----------

    @Test
    public void shellSetsAgentPostsCommandAndParsesTheShellMessage() throws Exception {
        ShellResult result = client.runShell("ses_1", "build", "git status --short");

        assertEquals("msg_sh1", result.messageId());
        assertEquals("git status --short", result.command());
        assertEquals("exited", result.status());
        assertEquals("M src/a.cpp", result.output());

        assertEquals("{\"agent\":\"build\"}", bodiesByPath.get("/api/session/ses_1/agent"));
        assertTrue("the v2 shell body carries only the command, got: "
                + bodiesByPath.get("/api/session/ses_1/shell"),
                bodiesByPath.get("/api/session/ses_1/shell").contains("\"command\":\"git status --short\""));
    }

    @Test
    public void shellSkipsTheAgentPostWhenNoAgentIsGiven() throws Exception {
        client.runShell("ses_1", null, "true");
        assertNull(bodiesByPath.get("/api/session/ses_1/agent"));
    }

    @Test
    public void shellToleratesMissingFields() throws Exception {
        messageBody.set("{\"data\":[{\"id\":\"msg_1\",\"type\":\"shell\",\"status\":\"timeout\"}]}");
        ShellResult result = client.runShell("ses_1", null, "true");
        assertNotNull(result);
        assertEquals("msg_1", result.messageId());
        assertEquals("timeout", result.status());
        assertNull(result.command());
        assertNull(result.output());
    }

    @Test
    public void shellFailsFastWhenTheMessageEndpointIsDead() {
        messageStatus.set(404);
        OpencodeException e = assertThrows(OpencodeException.class,
                () -> client.runShell("ses_1", null, "true"));
        assertTrue(e.getMessage().contains("HTTP 404"));
    }

    // ---------- OAuth (v2 integration flow) ----------

    @Test
    public void beginOauthFindsTheOauthMethodAndStartsAnAttempt() throws Exception {
        OauthStart started = client.beginProviderOauth("anthropic");
        assertEquals("https://auth.anthropic.com/oauth", started.url());
        assertEquals("auto", started.method());
        assertEquals("open the url", started.instructions());
        assertEquals("{\"methodID\":\"browser\"}", bodiesByPath.get("/api/integration/anthropic/connect/oauth"));
    }

    @Test
    public void beginOauthReportsNothingStartedForAProviderWithoutOauth() throws Exception {
        // the stub's integration list only knows anthropic: any other provider
        // resolves to no OAuth method -> the "nothing started" callers handle
        OauthStart started = client.beginProviderOauth("no-such-provider");
        assertNull(started.url());
        assertNull(started.method());
        assertNull(started.instructions());
        assertNull("no connect attempt may be posted", bodiesByPath.get("/api/integration/no-such-provider/connect/oauth"));
    }

    @Test
    public void startProviderOauthDelegatesToTheIntegrationFlow() throws Exception {
        assertTrue(client.startProviderOauth("anthropic"));
        assertFalse(client.startProviderOauth("no-such-provider"));
    }
}
