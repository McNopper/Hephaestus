package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.ProviderAuth;

/**
 * Component test for the H5 remainder of the client surface (file status and
 * content, provider auth, global event SSE): real {@code HttpOpencodeClient}
 * over real HTTP against a local stub server, verifying paths, methods,
 * bodies and parsing. Stub bodies follow the shapes of the opencode v1.18.30
 * server source ({@code routes/instance/httpapi/groups/file|provider|global.ts}).
 * No Eclipse, no opencode.
 */
public class HttpOpencodeClientH5bComponentTest {

    /** One {@code /global/event} frame: {directory, project?, payload:{id,type,properties}}. */
    private static final String GLOBAL_EVENT_JSON = "{\"directory\":\"C:/repo\",\"project\":\"p1\","
            + "\"payload\":{\"id\":\"evt_1\",\"type\":\"session.created\",\"properties\":{\"info\":{\"id\":\"ses_g1\"}}}}";

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
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
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("/global/event".equals(path)) {
                byte[] sse = ("data: " + GLOBAL_EVENT_JSON + "\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, sse.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(sse);
                }
                return;
            }
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
            // [{path, added, removed, status}] - the git status of ALL changed files (no path query)
            case "/file/status" -> """
                    [{"path":"src/new.cpp","added":12,"removed":0,"status":"added"},
                     {"path":"src/old.cpp","added":1,"removed":5,"status":"modified"}]
                    """;
            // {type, content, diff?, …} envelope; content is base64 when type=binary
            case "/file/content" -> "{\"type\":\"text\",\"content\":\"int main() { return 0; }\"}";
            // {"<providerID>": [{"type":"oauth"|"api","label":…, prompts?}, …]} - a MAP of method lists
            case "/provider/auth" -> """
                    {"anthropic":[{"type":"oauth","label":"Anthropic Console","prompts":[]}],
                     "github":[{"type":"oauth","label":"GitHub"},{"type":"api","label":"Personal access token"}]}
                    """;
            // {url, method, instructions} when a flow started; empty body when method 0 is not OAuth
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
    public void fileStatusParsesEntries() throws Exception {
        List<FileStatus> status = client.getFileStatus();
        assertEquals(2, status.size());
        assertEquals("src/new.cpp", status.get(0).path());
        assertEquals("added", status.get(0).status());
        assertEquals(Integer.valueOf(12), status.get(0).added());
        assertEquals(Integer.valueOf(0), status.get(0).removed());
        assertEquals("modified", status.get(1).status());
        assertEquals("GET", lastMethod.get());
        assertEquals("/file/status", lastPath.get());
        assertNull("the endpoint takes no path query", lastQuery.get());
    }

    @Test
    public void fileStatusEmptyOn404() throws Exception {
        statusOverride.set(404);
        assertTrue(client.getFileStatus().isEmpty());
    }

    @Test
    public void fileStatusToleratesMissingFields() throws Exception {
        bodyOverride.set("[{\"path\":\"src/x.cpp\"},{}]");
        List<FileStatus> status = client.getFileStatus();
        assertEquals(2, status.size());
        assertEquals("src/x.cpp", status.get(0).path());
        assertNull(status.get(0).status());
        assertNull(status.get(0).added());
        assertNull(status.get(1).path());
    }

    @Test
    public void fileContentReturnsEnvelopeContentAndEncodesPath() throws Exception {
        String content = client.getFileContent("src/a b/main.cpp");
        assertEquals("int main() { return 0; }", content);
        assertEquals("/file/content", lastPath.get());
        assertTrue("path must survive encoding: " + lastQuery.get(),
                lastQuery.get().startsWith("path=src%2Fa%20b%2Fmain.cpp"));
    }

    @Test
    public void fileContentNullOn404() throws Exception {
        statusOverride.set(404);
        assertNull(client.getFileContent("missing.cpp"));
    }

    @Test
    public void fileContentToleratesMalformedBody() throws Exception {
        bodyOverride.set("<<not-json>>");
        assertNull(client.getFileContent("src/a.cpp"));
    }

    @Test
    public void providerAuthsFlattenTheMethodMap() throws Exception {
        List<ProviderAuth> auths = client.getProviderAuths();
        assertEquals(3, auths.size());
        assertEquals("anthropic", auths.get(0).provider());
        assertEquals("oauth", auths.get(0).type());
        assertEquals("Anthropic Console", auths.get(0).label());
        assertEquals("github", auths.get(1).provider());
        assertEquals("oauth", auths.get(1).type());
        assertEquals("api", auths.get(2).type());
        assertEquals("GET", lastMethod.get());
        assertEquals("/provider/auth", lastPath.get());
    }

    @Test
    public void providerAuthsLenientOn404MalformedAndWrongShape() throws Exception {
        statusOverride.set(404);
        assertTrue(client.getProviderAuths().isEmpty());

        statusOverride.set(200);
        bodyOverride.set("[{\"type\":\"oauth\"}]"); // array, not the live map shape
        assertTrue(client.getProviderAuths().isEmpty());

        bodyOverride.set("<<garbage>>");
        assertTrue(client.getProviderAuths().isEmpty());
    }

    @Test
    public void startProviderOauthPostsMethodZeroAndSucceeds() throws Exception {
        assertTrue(client.startProviderOauth("anthropic"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/provider/anthropic/oauth/authorize", lastPath.get());
        assertTrue(lastBody.get().contains("\"method\":0"));
    }

    @Test
    public void startProviderOauthFalseOnErrorOrMissingUrl() throws Exception {
        statusOverride.set(400);
        assertFalse(client.startProviderOauth("anthropic"));

        statusOverride.set(200);
        bodyOverride.set(""); // 200 with no body - method 0 was not an OAuth flow
        assertFalse(client.startProviderOauth("anthropic"));

        bodyOverride.set("{}"); // 200 without an authorization url
        assertFalse(client.startProviderOauth("anthropic"));

        bodyOverride.set("<<garbage>>");
        assertFalse(client.startProviderOauth("anthropic"));
    }

    @Test
    public void globalEventsStreamDeliversOneUnwrappedEvent() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<OpencodeEvent> event = new AtomicReference<>();
        OpencodeEventStream stream = client.getGlobalEvents(e -> {
            event.set(e);
            received.countDown();
        }, null);
        try {
            stream.start();
            assertTrue("expected one global event within 5s", received.await(5, TimeUnit.SECONDS));
            assertEquals("/global/event", lastPath.get());
            assertEquals("session.created", event.get().type());
            assertEquals("ses_g1", event.get().at("info.id"));
        } finally {
            stream.stop();
        }
    }
}
