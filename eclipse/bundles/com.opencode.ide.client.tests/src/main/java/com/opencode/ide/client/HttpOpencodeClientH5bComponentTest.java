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
 * content, provider auth, the event SSE stream): real {@code HttpOpencodeClient}
 * over real HTTP against a local stub server, verifying paths, methods, bodies
 * and parsing. No Eclipse, no opencode.
 *
 * <p>Migrated to <b>opencode v2</b>: {@code GET /file/status} became
 * {@code GET /api/vcs/status}, and the two v1 event endpoints ({@code /event}
 * per project and {@code /global/event} with its {@code payload} envelope)
 * collapsed into a single {@code GET /api/event} stream whose frames carry the
 * payload under {@code data} and the owning worktree under
 * {@code location.directory}.</p>
 */
public class HttpOpencodeClientH5bComponentTest {

    /**
     * One v2 {@code /api/event} frame: {@code {id, created, type, location, data}}.
     * There is no {@code payload} envelope any more - {@code location.directory}
     * is what scopes the event, because one stream now serves every directory.
     */
    private static final String EVENT_JSON = "{\"id\":\"evt_1\",\"created\":1789885567144,"
            + "\"type\":\"session.created\",\"location\":{\"directory\":\"C:\\\\repo\"},"
            + "\"data\":{\"sessionID\":\"ses_g1\",\"slug\":\"shiny-tiger\",\"projectID\":\"prj_1\","
            + "\"title\":\"Refactor the parser\"}}";

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
            if ("/api/event".equals(path)) {
                byte[] sse = ("data: " + EVENT_JSON + "\n\n").getBytes(StandardCharsets.UTF_8);
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
        lastPath.set(null);
        lastQuery.set(null);
    }

    private static String respond(String path) {
        return switch (path) {
            // v2: the changed-file list lives under /vcs (v1 served it at /file/status)
            case "/api/vcs/status" -> """
                    {"data":[{"path":"src/new.cpp","added":12,"removed":0,"status":"added"},
                             {"path":"src/old.cpp","added":1,"removed":5,"status":"modified"}]}
                    """;
            // v2 serves raw file bytes at /fs/read/<path> (no JSON envelope);
            // the stub sees the DECODED path (getPath() unescapes %20)
            case "/api/fs/read/src/a b/main.cpp" -> "int main() { return 0; }";
            // {"<providerID>": [{"type":"oauth"|"api","label":…, prompts?}, …]} - a MAP of method lists
            case "/api/provider/auth" -> """
                    {"anthropic":[{"type":"oauth","label":"Anthropic Console","prompts":[]}],
                     "github":[{"type":"oauth","label":"GitHub"},{"type":"api","label":"Personal access token"}]}
                    """;
            // v2 OAuth: the provider's integration lists its methods, the first
            // OAuth method starts via /api/integration/:id/connect/oauth
            case "/api/integration" -> """
                    {"location":{},"data":[{"id":"anthropic","name":"Anthropic",
                      "methods":[{"type":"key"},{"id":"browser","type":"oauth","label":"Anthropic Console"}],
                      "connections":[]}]}
                    """;
            case "/api/integration/anthropic/connect/oauth" ->
                "{\"location\":{},\"data\":{\"attemptID\":\"att_1\",\"url\":\"https://auth.anthropic.com/oauth\",\"instructions\":\"open the url\",\"mode\":\"auto\",\"time\":{\"created\":1,\"expires\":2}}}";
            default -> "{}";
        };
    }

    @AfterClass
    public static void stopStub() {
        server.stop(0);
    }

    @Test
    public void fileStatusParsesEntriesFromTheVcsEndpoint() throws Exception {
        List<FileStatus> status = client.getFileStatus();
        assertEquals(2, status.size());
        assertEquals("src/new.cpp", status.get(0).path());
        assertEquals("added", status.get(0).status());
        assertEquals(Integer.valueOf(12), status.get(0).added());
        assertEquals(Integer.valueOf(0), status.get(0).removed());
        assertEquals("modified", status.get(1).status());
        assertEquals("GET", lastMethod.get());
        assertEquals("v2 moved the changed-file list to /vcs/status", "/api/vcs/status", lastPath.get());
        assertNull("the endpoint takes no path query", lastQuery.get());
    }

    /** REGRESSION: vcs/status resolves per location in v2 (unscoped = the user's home repo). */
    @Test
    public void fileStatusScopesWithLocationBracketSyntax() throws Exception {
        client.getFileStatus("C:\\Development\\GitHub\\Hephaestus");
        String q = lastQuery.get();
        assertTrue("vcs/status scope must use bracket syntax, got: " + q,
                q != null && q.startsWith("location%5Bdirectory%5D="));
    }

    @Test
    public void fileStatusEmptyOn404() throws Exception {
        statusOverride.set(404);
        assertTrue(client.getFileStatus().isEmpty());
    }

    @Test
    public void fileStatusToleratesMissingFields() throws Exception {
        bodyOverride.set("{\"data\":[{\"path\":\"src/x.cpp\"},{}]}");
        List<FileStatus> status = client.getFileStatus();
        assertEquals(2, status.size());
        assertEquals("src/x.cpp", status.get(0).path());
        assertNull(status.get(0).status());
        assertNull(status.get(0).added());
        assertNull(status.get(1).path());
    }

    @Test
    public void fileContentReturnsRawBytesAndEncodesPath() throws Exception {
        String content = client.getFileContent("src/a b/main.cpp");
        assertEquals("int main() { return 0; }", content);
        assertEquals("v2 reads raw bytes at /fs/read/<path>", "/api/fs/read/src/a b/main.cpp", lastPath.get());
        assertNull("no query string on the v2 route", lastQuery.get());
    }

    @Test
    public void fileContentNullOn404() throws Exception {
        statusOverride.set(404);
        assertNull(client.getFileContent("missing.cpp"));
    }

    @Test
    public void fileContentPassesTextThroughUnparsed() throws Exception {
        // raw bytes are returned as-is - there is no JSON envelope to reject
        bodyOverride.set("<<not-json>>");
        assertEquals("<<not-json>>", client.getFileContent("src/a.cpp"));
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
        assertEquals("/api/provider/auth", lastPath.get());
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
    public void startProviderOauthUsesTheIntegrationFlowAndSucceeds() throws Exception {
        assertTrue(client.startProviderOauth("anthropic"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/integration/anthropic/connect/oauth", lastPath.get());
        assertTrue(lastBody.get().contains("\"methodID\":\"browser\""));
    }

    @Test
    public void startProviderOauthFalseOnErrorOrMissingUrl() throws Exception {
        statusOverride.set(400);
        assertFalse(client.startProviderOauth("anthropic"));

        statusOverride.set(200);
        bodyOverride.set(""); // 200 with no body - the attempt could not be read
        assertFalse(client.startProviderOauth("anthropic"));

        bodyOverride.set("{}"); // 200 without an authorization url
        assertFalse(client.startProviderOauth("anthropic"));

        bodyOverride.set("<<garbage>>");
        assertFalse(client.startProviderOauth("anthropic"));
    }

    /**
     * v1 had two event endpoints and wrapped the global one in a
     * {@code payload} envelope; v2 serves a single {@code /api/event} stream for
     * every directory, so the frame's {@code data} IS the payload and
     * {@code location.directory} is what tells the IDE which worktree an event
     * belongs to.
     */
    @Test
    public void eventStreamDeliversOneEventCarryingItsDirectory() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<OpencodeEvent> event = new AtomicReference<>();
        OpencodeEventStream stream = client.getGlobalEvents(e -> {
            event.set(e);
            received.countDown();
        }, null);
        try {
            stream.start();
            assertTrue("expected one event within 5s", received.await(5, TimeUnit.SECONDS));
            assertEquals("v2 serves one stream for all directories", "/api/event", lastPath.get());
            assertEquals("session.created", event.get().type());
            assertEquals("the frame's `data` is the payload", "ses_g1", event.get().string("sessionID"));
            assertEquals("location.directory scopes the event", "C:\\repo", event.get().directory());
        } finally {
            stream.stop();
        }
    }
}
