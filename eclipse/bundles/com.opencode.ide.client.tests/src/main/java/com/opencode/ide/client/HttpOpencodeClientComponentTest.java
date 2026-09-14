package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionTodo;
import com.opencode.ide.client.model.SkillInfo;

/**
 * Component test: exercises the real {@code HttpOpencodeClient} over real HTTP
 * against a local stub server (JDK-embedded), verifying request paths/methods/
 * bodies and response parsing for the chat surface. No Eclipse, no opencode.
 */
public class HttpOpencodeClientComponentTest {

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    /** Settable body served by the stub for {@code GET /session/:id/todo}. */
    private static final AtomicReference<String> todoBody = new AtomicReference<>("[]");

    @Test
    public void deleteSessionUsesDeleteAndPropagatesFailure() throws Exception {
        client.deleteSession("ses_delete");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/session/ses_delete", lastPath.get());
        org.junit.Assert.assertThrows(OpencodeException.class, () -> client.deleteSession("ses_error"));
    }

    @Test
    public void abortDoesNotTreatAuthorizationFailureAsSuccess() {
        org.junit.Assert.assertThrows(OpencodeException.class, () -> client.abortSession("ses_denied"));
    }

    @BeforeClass
    public static void startStub() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/session", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            String path = exchange.getRequestURI().getPath();
            if (path.contains("error")) {
                byte[] errBytes = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, errBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(errBytes);
                }
                return;
            }
            if (path.endsWith("/abort")) {
                // "…idle" ids answer 404 (already idle); everything else 200
                int status = path.contains("denied") ? 403 : path.contains("idle") ? 404 : 200;
                byte[] bytes = (status == 404
                        ? "{\"error\":\"session is not active\"}" : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            String response;
            if ("POST".equals(exchange.getRequestMethod()) && "/session".equals(path)) {
                response = "{\"id\":\"ses_new\",\"title\":\"Eclipse Chat\",\"time\":{\"created\":1,\"updated\":1}}";
            } else if (path.endsWith("/todo")) {
                response = todoBody.get();
            } else if (path.endsWith("/message")) {
                if ("POST".equals(exchange.getRequestMethod())) {
                    // real assistant shape: FLAT providerID/modelID (no nested "model")
                    response = """
                            {"info":{"id":"msg_a1","sessionID":"ses_new","role":"assistant",
                              "time":{"created":2},"agent":"build","mode":"build","finish":"stop",
                              "providerID":"opencode","modelID":"glm-5.2"},
                             "parts":[{"type":"text","text":"The answer is $4$."}]}
                            """;
                } else {
                    response = """
                            [
                             {"info":{"id":"msg_u1","sessionID":"ses_new","role":"user","time":{"created":1}},
                              "parts":[{"type":"text","text":"hi"}]},
                             {"info":{"id":"msg_a1","sessionID":"ses_new","role":"assistant","time":{"created":2}},
                              "parts":[{"type":"text","text":"hello **markdown**"}]}
                            ]
                            """;
                }
            } else {
                response = "[]";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/mcp", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // live v1.18 GET /mcp shape: a MAP keyed by server name
            byte[] bytes = ("GET".equals(exchange.getRequestMethod())
                    ? "{\"tasks\":{\"status\":\"connected\"},\"graphics\":{\"status\":\"error\"}}"
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/skill", exchange -> {
            byte[] bytes = "[{\"name\":\"cpp-tools\",\"description\":\"C++ execution utility\",\"location\":\"<built-in>\",\"content\":\"…\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/config", exchange -> {
            byte[] bytes = "{\"model\":\"zai-coding-plan/glm-4.6\",\"small_model\":\"zai-coding-plan/glm-4.5-air\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/global/health", exchange -> {
            byte[] bytes = "<<not-json-garbage>>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/agent", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, -1);
        });

        server.start();
        int port = server.getAddress().getPort();
        client = new com.opencode.ide.client.internal.HttpOpencodeClient(
                new ConnectionConfig(URI.create("http://127.0.0.1:" + port), "opencode", "secret"));
    }

    @AfterClass
    public static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void createSessionPostsTitleAndParsesResponse() throws Exception {
        Session session = client.createSession("Eclipse Chat");
        assertEquals("POST", lastMethod.get());
        assertEquals("/session", lastPath.get());
        assertTrue("body should contain the title", lastBody.get().contains("\"Eclipse Chat\""));
        assertNotNull(session);
        assertEquals("ses_new", session.id());
        assertEquals("Eclipse Chat", session.title());
    }

    @Test
    public void basicAuthHeaderIsSent() throws Exception {
        client.createSession(null);
        String auth = lastAuth.get();
        assertNotNull("Authorization header expected when a password is set", auth);
        assertTrue(auth.startsWith("Basic "));
    }

    @Test
    public void createSessionWithDirectoryScopesQuery() throws Exception {
        Session session = client.createSession(null, java.nio.file.Path.of("C:/work/.git/opencode-fleet/t1"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/session", lastPath.get());
        assertNotNull("directory query parameter expected", lastQuery.get());
        assertTrue("query should carry the encoded directory, got: " + lastQuery.get(),
                lastQuery.get().startsWith("directory="));
        assertTrue("path separators must survive encoding: " + lastQuery.get(),
                lastQuery.get().contains("%2F") || lastQuery.get().contains("%5C"));
        assertNotNull(session);
    }

    @Test
    public void registerMcpPostsRemoteConfig() throws Exception {
        client.registerMcp("eclipse-build", McpServerConfig.enabled("http://127.0.0.1:12345/mcp"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/mcp", lastPath.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"name\":\"eclipse-build\""));
        assertTrue(body.contains("\"type\":\"remote\""));
        assertTrue(body.contains("\"url\":\"http://127.0.0.1:12345/mcp\""));
        assertTrue("oauth must be explicitly off", body.contains("\"oauth\":false"));
    }

    @Test
    public void getMessagesParsesHistory() throws Exception {
        List<ChatEntry> entries = client.getMessages("ses_new");
        assertEquals("GET", lastMethod.get());
        assertEquals("/session/ses_new/message", lastPath.get());
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).isUser());
        assertEquals("hi", entries.get(0).text());
        assertEquals("hello **markdown**", entries.get(1).text());
    }

    @Test
    public void getSessionTodosParsesEntries() throws Exception {
        todoBody.set("""
                [
                 {"id":"todo_1","content":"Write the plan","status":"in_progress","priority":"high"},
                 {"id":"todo_2","content":"Run the build","status":"completed","priority":"low"}
                ]
                """);

        List<SessionTodo> todos = client.getSessionTodos("ses_new");

        assertEquals("GET", lastMethod.get());
        assertEquals("/session/ses_new/todo", lastPath.get());
        assertEquals(2, todos.size());
        assertEquals("todo_1", todos.get(0).id());
        assertEquals("Write the plan", todos.get(0).content());
        assertEquals("in_progress", todos.get(0).status());
        assertEquals("high", todos.get(0).priority());
        assertEquals("completed", todos.get(1).status());
    }

    @Test
    public void getSessionTodosHandlesAnEmptyList() throws Exception {
        todoBody.set("[]");

        assertTrue(client.getSessionTodos("ses_new").isEmpty());
    }

    @Test
    public void getMcpServersParsesTheLiveMapShape() throws Exception {
        // regression: live v1.18 returns {"<name>": {"status": "…"}} — NOT an array;
        // array parsing threw "malformed response body" and killed the Server view
        List<McpServerInfo> servers = client.getMcpServers();

        assertEquals(2, servers.size());
        assertEquals("tasks", servers.get(0).id());
        assertEquals("connected", servers.get(0).status());
        assertEquals("graphics", servers.get(1).id());
        assertEquals("error", servers.get(1).status());
    }

    @Test
    public void getSkillsParsesTheListShape() throws Exception {
        List<SkillInfo> skills = client.getSkills();

        assertEquals(1, skills.size());
        assertEquals("cpp-tools", skills.get(0).name());
        assertTrue(skills.get(0).description().startsWith("C++ execution"));
    }

    @Test
    public void getSessionTodosToleratesEntriesWithMissingFieldsAndNulls() throws Exception {
        todoBody.set("""
                [
                 {},
                 {"content":"Only content","status":"done"},
                 null
                ]
                """);

        List<SessionTodo> todos = client.getSessionTodos("ses_new");

        assertEquals(3, todos.size());
        assertNull("missing fields map to null components", todos.get(0).id());
        assertNull(todos.get(0).content());
        assertNull(todos.get(0).status());
        assertNull(todos.get(0).priority());
        assertEquals("Only content", todos.get(1).content());
        assertEquals("done", todos.get(1).status());
        assertNull("null array entries parse to null elements (callers skip)", todos.get(2));
    }

    @Test
    public void sendMessagePostsBuiltBodyAndParsesReply() throws Exception {
        ChatEntry reply = client.sendMessage(new ChatRequest("ses_new", "build", "opencode", "glm-5.2", "high", "SYSTEM-PROMPT", "What is 2+2?"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/session/ses_new/message", lastPath.get());
        String body = lastBody.get();
        assertTrue(body.contains("\"agent\":\"build\""));
        assertTrue(body.contains("\"providerID\":\"opencode\""));
        assertTrue(body.contains("\"modelID\":\"glm-5.2\""));
        assertTrue("variant must be sent", body.contains("\"variant\":\"high\""));
        assertTrue("system prompt must be sent", body.contains("SYSTEM-PROMPT"));
        assertTrue(body.contains("What is 2+2?"));
        assertNotNull(reply);
        assertEquals("assistant", reply.info().role());
        assertEquals("The answer is $4$.", reply.text());
        assertEquals("opencode/glm-5.2", reply.info().modelLabel());
    }

    @Test
    public void abortSessionPostsToTheAbortEndpoint() throws Exception {
        client.abortSession("ses_new");

        assertEquals("POST", lastMethod.get());
        assertEquals("/session/ses_new/abort", lastPath.get());
    }

    @Test
    public void abortSessionTolerates4xxAlreadyIdle() throws Exception {
        // the stub answers 404 "session is not active" for ids containing "idle";
        // abort racing a finished session must not surface that as an error
        client.abortSession("ses_idle");

        assertEquals("POST", lastMethod.get());
        assertEquals("/session/ses_idle/abort", lastPath.get());
    }

    @Test
    public void abortSessionMaps5xxToOpencodeException() {
        try {
            client.abortSession("error");
            fail("expected OpencodeException");
        } catch (OpencodeException expected) {
            assertTrue("message should name the endpoint: " + expected.getMessage(),
                    expected.getMessage().contains("/session/error/abort"));
            assertTrue("message should name the HTTP status: " + expected.getMessage(),
                    expected.getMessage().contains("500"));
        }
    }

    @Test
    public void getConfigParsesDefaultModel() throws Exception {
        // stubbed below via the /config context
        com.opencode.ide.client.model.ConfigInfo config = client.getConfig();
        assertEquals("zai-coding-plan/glm-4.6", config.model());
        String[] parts = config.defaultModelParts();
        assertEquals("zai-coding-plan", parts[0]);
        assertEquals("glm-4.6", parts[1]);
    }

    @Test
    public void non2xxRaisesOpencodeException() throws Exception {
        // route the client at the /error endpoint via a bespoke client instance
        OpencodeClient failing = new com.opencode.ide.client.internal.HttpOpencodeClient(
                new ConnectionConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, null));
        try {
            failing.getMessages("error");
            fail("expected OpencodeException");
        } catch (OpencodeException expected) {
            assertTrue(expected.getMessage().contains("500"));
        }
    }

    @Test
    public void garbage200BodyRaisesOpencodeExceptionWithEndpointStatusAndSnippet() {
        try {
            client.getHealth();
            fail("expected OpencodeException for a 200 with a non-JSON body");
        } catch (OpencodeException expected) {
            assertTrue("message should name the endpoint: " + expected.getMessage(),
                    expected.getMessage().contains("/global/health"));
            assertTrue("message should name the HTTP status: " + expected.getMessage(),
                    expected.getMessage().contains("200"));
            assertTrue("message should carry a body snippet: " + expected.getMessage(),
                    expected.getMessage().contains("not-json-garbage"));
        }
    }

    @Test
    public void empty200BodyRaisesOpencodeException() {
        try {
            client.getAgents();
            fail("expected OpencodeException for a 200 with an empty body");
        } catch (OpencodeException expected) {
            assertTrue("message should name the endpoint: " + expected.getMessage(),
                    expected.getMessage().contains("/agent"));
            assertTrue(expected.getMessage().contains("200"));
        }
    }
}
