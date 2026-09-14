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
 * OAuth authorize answer): real {@code HttpOpencodeClient} over real HTTP
 * against a local stub server, verifying paths, methods, bodies and parsing.
 * Stub bodies follow the shapes of the opencode v1.18.30 server source
 * ({@code routes/instance/httpapi/groups/session.ts} shell endpoint over
 * {@code SessionPrompt.ShellInput}/{@code SessionV1.WithParts}, and
 * {@code groups/provider.ts} oauth authorize). No Eclipse, no opencode.
 */
public class HttpOpencodeClientH5cComponentTest {

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    /** Settable body/status the stub serves instead of the built-in happy path. */
    private static final AtomicReference<String> bodyOverride = new AtomicReference<>();
    private static final AtomicInteger statusOverride = new AtomicInteger(200);

    @BeforeClass
    public static void startStub() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(path);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = (bodyOverride.get() != null ? bodyOverride.get() : respond(path))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusOverride.get(), bytes.length);
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
        bodyOverride.set(null);
        statusOverride.set(200);
    }

    private static String respond(String path) {
        return switch (path) {
            // {info, parts} (SessionV1.WithParts): the assistant message plus the
            // shell tool part carrying command/status/output in its state
            case "/session/ses_1/shell" -> """
                    {"info":{"id":"msg_sh1","sessionID":"ses_1","role":"assistant","agent":"build",
                      "time":{"created":3}},
                     "parts":[{"type":"text","text":"The following tool was executed by the user"},
                      {"type":"tool","tool":"shell",
                       "state":{"status":"completed","input":{"command":"git status --short"},
                        "metadata":{"output":"M src/a.cpp"},"output":"M src/a.cpp"}}]}
                    """;
            // {url, method, instructions} when a flow started
            case "/provider/anthropic/oauth/authorize" ->
                "{\"url\":\"https://auth.anthropic.com/oauth\",\"method\":\"auto\",\"instructions\":\"open the url\"}";
            default -> "{}";
        };
    }

    @AfterClass
    public static void stopStub() {
        server.stop(0);
    }

    @Test
    public void shellPostsAgentAndCommandAndParsesResult() throws Exception {
        ShellResult result = client.runShell("ses_1", "build", "git status --short");
        assertEquals("msg_sh1", result.messageId());
        assertEquals("build", result.agent());
        assertEquals("git status --short", result.command());
        assertEquals("completed", result.status());
        assertEquals("M src/a.cpp", result.output());
        assertEquals("POST", lastMethod.get());
        assertEquals("/session/ses_1/shell", lastPath.get());
        assertTrue(lastBody.get().contains("\"agent\":\"build\""));
        assertTrue(lastBody.get().contains("\"command\":\"git status --short\""));
    }

    @Test
    public void shellToleratesMissingFields() throws Exception {
        bodyOverride.set("{\"info\":{},\"parts\":[{\"type\":\"tool\",\"state\":{}}]}");
        ShellResult result = client.runShell("ses_1", "build", "true");
        assertNotNull(result);
        assertNull(result.messageId());
        assertNull(result.agent());
        assertNull(result.command());
        assertNull(result.status());
        assertNull(result.output());
    }

    @Test
    public void shellThrowsOn404() {
        statusOverride.set(404);
        OpencodeException e = assertThrows(OpencodeException.class,
                () -> client.runShell("ses_1", "build", "true"));
        assertTrue(e.getMessage().contains("HTTP 404"));
    }

    @Test
    public void shellThrowsOnMalformedBody() {
        bodyOverride.set("<<not-json>>");
        assertThrows(OpencodeException.class, () -> client.runShell("ses_1", "build", "true"));
    }

    @Test
    public void beginOauthParsesUrlMethodAndInstructions() throws Exception {
        OauthStart started = client.beginProviderOauth("anthropic");
        assertEquals("https://auth.anthropic.com/oauth", started.url());
        assertEquals("auto", started.method());
        assertEquals("open the url", started.instructions());
        assertEquals("POST", lastMethod.get());
        assertEquals("/provider/anthropic/oauth/authorize", lastPath.get());
        assertEquals("{\"method\":0}", lastBody.get());
    }

    @Test
    public void beginOauthNullUrlOn404EmptyAndMalformed() throws Exception {
        statusOverride.set(404);
        assertNull(client.beginProviderOauth("anthropic").url());

        statusOverride.set(200);
        bodyOverride.set(""); // 200 with no body - method 0 was not an OAuth flow
        assertNull(client.beginProviderOauth("anthropic").url());

        bodyOverride.set("<<garbage>>");
        assertNull(client.beginProviderOauth("anthropic").url());
    }

    @Test
    public void startProviderOauthDelegatesToBegin() throws Exception {
        assertTrue(client.startProviderOauth("anthropic"));

        statusOverride.set(400);
        assertFalse(client.startProviderOauth("anthropic"));

        statusOverride.set(200);
        bodyOverride.set("{}"); // 200 without an authorization url
        assertFalse(client.startProviderOauth("anthropic"));
    }
}
