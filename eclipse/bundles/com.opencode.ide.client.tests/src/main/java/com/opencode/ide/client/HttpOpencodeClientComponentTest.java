package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SkillInfo;

/**
 * Component test: exercises the real {@code HttpOpencodeClient} over real HTTP
 * against a local stub server (JDK-embedded), verifying request paths/methods/
 * bodies and response parsing for the chat surface. No Eclipse, no opencode.
 *
 * <p>Everything here speaks <b>opencode v2</b>: every request path is prefixed
 * {@code /api}, list endpoints answer a {@code {"data":[...]}} envelope, and the
 * chat send is the asynchronous {@code POST /prompt} + poll dance rather than
 * v1's single blocking {@code POST /message}. The stub therefore registers its
 * contexts under {@code /api/...} - a v1-shaped stub would 404 every call.</p>
 */
public class HttpOpencodeClientComponentTest {

    /** One recorded stub hit, so the multi-request send path can be asserted in order. */
    private record Recorded(String method, String path, String body) {

        boolean is(String expectedMethod, String expectedPath) {
            return expectedMethod.equals(method) && expectedPath.equals(path);
        }
    }

    /** v2 {@code GET /api/info}: no health flag - reaching it at all is the health signal. */
    private static final String INFO_BODY = """
            {"version":"2.0.10","pid":4711,"urls":["http://127.0.0.1:4096"],
             "paths":{"config":"C:\\\\Users\\\\dev\\\\.config\\\\opencode"}}
            """;

    /** v2 message list: {@code {"data":[...]}}, NEWEST FIRST, with a completed assistant turn. */
    private static final String COMPLETED_TURN = """
            {"data":[
              {"id":"msg_a1","sessionID":"ses_new","type":"assistant",
               "time":{"created":2,"completed":9},
               "agent":"build","model":{"id":"glm-5.2","providerID":"opencode","variant":"high"},
               "content":[{"type":"text","text":"The answer is $4$."}],
               "cost":0.0065,
               "tokens":{"input":10,"output":3,"reasoning":0,"cache":{"read":0,"write":0}},
               "finish":"stop"},
              {"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1},
               "text":"What is 2+2?"}
            ]}
            """;

    /** The same turn mid-stream: the assistant message exists but has no {@code time.completed}. */
    private static final String STREAMING_TURN = """
            {"data":[
              {"id":"msg_a1","sessionID":"ses_new","type":"assistant","time":{"created":2},
               "agent":"build","model":{"id":"glm-5.2","providerID":"opencode","variant":"high"},
               "content":[{"type":"text","text":"The answer"}]},
              {"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1},
               "text":"What is 2+2?"}
            ]}
            """;

    /** v2's {@code POST /prompt} ack: the QUEUED user message (this turn's anchor). */
    private static final String PROMPT_ACK = """
            {"data":{"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1}}}
            """;

    /**
     * A RESUMED session's first poll: the new turn's assistant message is still
     * empty, while the history carries a COMPLETED assistant and an {@code idle}
     * marker from a previous turn. Answering from those is the bug this guards.
     */
    private static final String RESUMED_STREAMING = """
            {"data":[
              {"id":"msg_a2","sessionID":"ses_new","type":"assistant","time":{"created":11},
               "agent":"build","model":{"id":"glm-5.2","providerID":"opencode"},"content":[]},
              {"id":"msg_u2","sessionID":"ses_new","type":"user","time":{"created":10},"text":"again"},
              {"id":"msg_idle1","sessionID":"ses_new","type":"idle","time":{"created":5},
               "outcome":"succeeded"},
              {"id":"msg_a1","sessionID":"ses_new","type":"assistant",
               "time":{"created":2,"completed":3},"agent":"build",
               "model":{"id":"glm-5.2","providerID":"opencode"},
               "content":[{"type":"text","text":"stale answer"}],"finish":"stop"},
              {"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1},"text":"first"}
            ]}
            """;

    /** The resumed session's new turn, now completed. */
    private static final String RESUMED_COMPLETED = """
            {"data":[
              {"id":"msg_a2","sessionID":"ses_new","type":"assistant",
               "time":{"created":11,"completed":12},"agent":"build",
               "model":{"id":"glm-5.2","providerID":"opencode"},
               "content":[{"type":"text","text":"fresh answer"}],"finish":"stop"},
              {"id":"msg_u2","sessionID":"ses_new","type":"user","time":{"created":10},"text":"again"},
              {"id":"msg_idle1","sessionID":"ses_new","type":"idle","time":{"created":5},
               "outcome":"succeeded"},
              {"id":"msg_a1","sessionID":"ses_new","type":"assistant",
               "time":{"created":2,"completed":3},"agent":"build",
               "model":{"id":"glm-5.2","providerID":"opencode"},
               "content":[{"type":"text","text":"stale answer"}],"finish":"stop"},
              {"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1},"text":"first"}
            ]}
            """;

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    /** Every stub hit since the last reset, in order. */
    private static final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    /** The raw query string of the latest hit per path (for location-scoping assertions). */
    private static final java.util.Map<String, String> queriesByPath =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Settable body served by the stub for {@code GET /api/info}. */
    private static final AtomicReference<String> infoBody = new AtomicReference<>(INFO_BODY);
    /** Settable body served by the stub for {@code POST /api/session/:id/prompt}. */
    private static final AtomicReference<String> promptAck = new AtomicReference<>(PROMPT_ACK);
    /**
     * Bodies served by successive {@code GET /api/session/:id/message} calls -
     * the last entry repeats forever. This is what lets a test drive the v2
     * reply poll through "still streaming" into "completed".
     */
    private static final List<String> messageBodies = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger messageGets = new AtomicInteger();

    @BeforeClass
    public static void startStub() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/api/session", exchange -> {
            String path = recordExchange(exchange);
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));

            if (path.contains("error")) {
                respond(exchange, 500, "{\"error\":\"boom\"}");
                return;
            }
            if (path.endsWith("/interrupt")) {
                // "…idle" ids answer 404 (already idle); "denied" 403; everything else 200
                int status = path.contains("denied") ? 403 : path.contains("idle") ? 404 : 200;
                respond(exchange, status,
                        status == 404 ? "{\"error\":\"session is not active\"}" : "{}");
                return;
            }
            String response;
            if ("POST".equals(exchange.getRequestMethod()) && "/api/session".equals(path)) {
                // v2 session.create answers the resource ENVELOPE {data:{...}} -
                // the unwrap the client must do (a bare object once hid the id)
                response = """
                        {"data":{"id":"ses_new","projectID":"prj_1","title":"Eclipse Chat","agent":"build",
                          "model":{"id":"glm-5.2","providerID":"opencode","variant":"high"},
                          "time":{"created":1,"updated":1,"idle":0},
                          "location":{"directory":"C:\\\\repo"}}}
                        """;
            } else if (path.endsWith("/active")) {
                // v2 lists RUNNING sessions only; an absent session is idle
                response = "{\"data\":{\"ses_busy\":{\"type\":\"running\"}}}";
            } else if (path.endsWith("/message")) {
                response = nextMessagesBody();
            } else if (path.endsWith("/prompt")) {
                // v2 acks the QUEUED user message - the client anchors its
                // reply poll to this timestamp
                response = promptAck.get();
            } else {
                // /prompt, /agent, /model, /synthetic: v2 acks, the reply is polled
                response = "{}";
            }
            respond(exchange, 200, response);
        });

        // v2 GET /api/mcp: {"location":…, "data":[{"name","status"}]} (v1 was a bare map)
        server.createContext("/api/mcp", exchange -> {
            recordExchange(exchange);
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"location":{"directory":"C:\\\\repo"},
                     "data":[{"name":"tasks","status":"connected"},
                             {"name":"graphics","status":"error"}]}
                    """);
        });

        // v2 registration moved to PUT /api/experimental/mcp/:server
        server.createContext("/api/experimental/mcp", exchange -> {
            recordExchange(exchange);
            respond(exchange, 200, "{}");
        });

        server.createContext("/api/skill", exchange -> {
            recordExchange(exchange);
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"data":[{"name":"cpp-tools","description":"C++ execution utility",
                              "location":"<built-in>","content":"…"}]}
                    """);
        });

        // v2 GET /api/config: an ARRAY of config sources, each {type, path, info}
        server.createContext("/api/config", exchange -> {
            recordExchange(exchange);
            respond(exchange, 200, """
                    [{"type":"document","path":"C:\\\\repo\\\\opencode.json",
                      "info":{"model":{"providerID":"zai-coding-plan","model":"glm-4.6"}}}]
                    """);
        });

        server.createContext("/api/info", exchange -> {
            recordExchange(exchange);
            respond(exchange, 200, infoBody.get());
        });

        // v2 GET /api/model: models are flat with a providerID (v1 nested them per provider)
        server.createContext("/api/model", exchange -> {
            recordExchange(exchange);
            respond(exchange, 200, """
                    {"data":[
                      {"id":"jev-1.13","modelID":"jev-1.13","providerID":"opencode","name":"Jev 1.13",
                       "capabilities":{"tools":false,"input":["text"],"output":["text"]},
                       "variants":[],"time":{"released":1789000000000},
                       "cost":[{"input":0.042,"output":0,"cache":{"read":0,"write":0}}],
                       "status":"active","enabled":true,"limit":{"context":64000,"output":0}},
                      {"id":"llama3","modelID":"llama3","providerID":"ollama","name":"Llama 3",
                       "capabilities":{"tools":true,"input":["text"],"output":["text"]},
                       "variants":[],"cost":[],"status":"active","enabled":true,
                       "limit":{"context":8192,"output":4096}}
                    ]}
                    """);
        });

        server.createContext("/api/provider", exchange -> {
            recordExchange(exchange);
            respond(exchange, 200, """
                    {"data":[{"id":"opencode","name":"OpenCode"},{"id":"ollama","name":"Ollama"}]}
                    """);
        });

        // deliberately answers 200 with NO body - the empty-body error path
        server.createContext("/api/agent", exchange -> {
            recordExchange(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        server.start();
        int port = server.getAddress().getPort();
        client = new com.opencode.ide.client.internal.HttpOpencodeClient(
                new ConnectionConfig(URI.create("http://127.0.0.1:" + port), "opencode", "secret"));
    }

    @Before
    public void resetStub() {
        requests.clear();
        queriesByPath.clear();
        infoBody.set(INFO_BODY);
        promptAck.set(PROMPT_ACK);
        serveMessages(COMPLETED_TURN);
    }

    @AfterClass
    public static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------- stub plumbing ----------

    /** Records the exchange (method/path/body) and returns its path. */
    private static String recordExchange(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(path);
        lastBody.set(body);
        // ConcurrentHashMap rejects nulls: a request WITHOUT a query string
        // must clear the path's entry, not put(null)
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            queriesByPath.remove(path);
        } else {
            queriesByPath.put(path, query);
        }
        requests.add(new Recorded(exchange.getRequestMethod(), path, body));
        return path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Queues the bodies successive message GETs answer with; the last one repeats. */
    private static void serveMessages(String... bodies) {
        synchronized (messageBodies) {
            messageBodies.clear();
            Collections.addAll(messageBodies, bodies);
        }
        messageGets.set(0);
    }

    private static String nextMessagesBody() {
        int index = messageGets.getAndIncrement();
        synchronized (messageBodies) {
            if (messageBodies.isEmpty()) {
                return "{\"data\":[]}";
            }
            return messageBodies.get(Math.min(index, messageBodies.size() - 1));
        }
    }

    private static List<Recorded> recorded() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private static Recorded first(String method, String path) {
        for (Recorded request : recorded()) {
            if (request.is(method, path)) {
                return request;
            }
        }
        return null;
    }

    private static int count(String method, String path) {
        int n = 0;
        for (Recorded request : recorded()) {
            if (request.is(method, path)) {
                n++;
            }
        }
        return n;
    }

    // ---------- health ----------

    @Test
    public void healthIsProbedOnApiInfoAndCarriesTheVersion() throws Exception {
        HealthStatus health = client.getHealth();

        assertEquals("GET", lastMethod.get());
        assertEquals("v2 replaced /global/health with /api/info", "/api/info", lastPath.get());
        assertTrue("reaching /api/info at all IS the health signal", health.healthy());
        assertEquals("2.0.10", health.version());
    }

    @Test
    public void garbage200BodyRaisesOpencodeExceptionWithEndpointStatusAndSnippet() {
        infoBody.set("<<not-json-garbage>>");
        try {
            client.getHealth();
            fail("expected OpencodeException for a 200 with a non-JSON body");
        } catch (OpencodeException expected) {
            assertTrue("message should name the endpoint: " + expected.getMessage(),
                    expected.getMessage().contains("/api/info"));
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

    // ---------- sessions ----------

    @Test
    public void createSessionPostsTitleAndParsesResponse() throws Exception {
        Session session = client.createSession("Eclipse Chat");
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session", lastPath.get());
        assertTrue("body should contain the title", lastBody.get().contains("\"Eclipse Chat\""));
        assertNotNull(session);
        assertEquals("ses_new", session.id());
        assertEquals("Eclipse Chat", session.title());
        // v2 shape: the model triple is an object, the directory lives in location
        assertEquals("glm-5.2", session.modelId());
        assertEquals("opencode", session.providerId());
        assertEquals("C:\\repo", session.directory());
        assertFalse("a fresh session has no idle stamp", session.isIdle());
    }

    @Test
    public void basicAuthHeaderIsSent() throws Exception {
        client.createSession(null);
        String auth = lastAuth.get();
        assertNotNull("Authorization header expected when a password is set", auth);
        assertTrue(auth.startsWith("Basic "));
    }

    @Test
    public void createSessionWithDirectoryScopesViaLocationBody() throws Exception {
        Session session = client.createSession(null, java.nio.file.Path.of("C:/work/.git/opencode-fleet/t1"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session", lastPath.get());
        // v2 takes the directory as a location object in the BODY, not a query
        String body = lastBody.get();
        assertTrue("body should carry the location object, got: " + body, body.contains("\"location\""));
        assertTrue("body should carry the scoped path, got: " + body,
                body.contains("opencode-fleet") && body.contains("t1"));
        assertNotNull(session);
    }

    @Test
    public void deleteSessionUsesDeleteAndPropagatesFailure() throws Exception {
        client.deleteSession("ses_delete");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/session/ses_delete", lastPath.get());
        org.junit.Assert.assertThrows(OpencodeException.class, () -> client.deleteSession("ses_error"));
    }

    /**
     * v2 replaced {@code GET /session/status} (a full idle/busy map) with
     * {@code GET /session/active}, which lists ONLY the running sessions under a
     * {@code data} object - absence is what now means "idle".
     */
    @Test
    public void sessionStatusReadsTheActiveSessionMap() throws Exception {
        Map<String, SessionStatus> statuses = client.getSessionStatus();

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/session/active", lastPath.get());
        assertEquals(1, statuses.size());
        assertEquals("busy", statuses.get("ses_busy").type());
        assertNull("an unlisted session is idle by absence", statuses.get("ses_new"));
    }

    // ---------- abort / interrupt ----------

    @Test
    public void abortSessionPostsToTheInterruptEndpoint() throws Exception {
        client.abortSession("ses_new");

        assertEquals("POST", lastMethod.get());
        assertEquals("v2 renamed /abort to /interrupt", "/api/session/ses_new/interrupt", lastPath.get());
    }

    @Test
    public void abortSessionTolerates4xxAlreadyIdle() throws Exception {
        // the stub answers 404 "session is not active" for ids containing "idle";
        // abort racing a finished session must not surface that as an error
        client.abortSession("ses_idle");

        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_idle/interrupt", lastPath.get());
    }

    @Test
    public void abortDoesNotTreatAuthorizationFailureAsSuccess() {
        org.junit.Assert.assertThrows(OpencodeException.class, () -> client.abortSession("ses_denied"));
    }

    @Test
    public void abortSessionMaps5xxToOpencodeException() {
        try {
            client.abortSession("error");
            fail("expected OpencodeException");
        } catch (OpencodeException expected) {
            assertTrue("message should name the endpoint: " + expected.getMessage(),
                    expected.getMessage().contains("/api/session/error/interrupt"));
            assertTrue("message should name the HTTP status: " + expected.getMessage(),
                    expected.getMessage().contains("500"));
        }
    }

    // ---------- messages ----------

    @Test
    public void getMessagesNormalizesTheNewestFirstEnvelopeToChronological() throws Exception {
        List<ChatEntry> entries = client.getMessages("ses_new");

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/session/ses_new/message", lastPath.get());
        assertEquals("the {\"data\":[…]} envelope must be unwrapped", 2, entries.size());
        // the v2 WIRE is newest-first (the assistant reply leads in the fixture),
        // but getMessages normalizes to CHRONOLOGICAL for its consumers (B-008 /
        // rubberduck F-1: "later in the list = newer" is the client contract -
        // the fleet, the chat transcript and the fakes all work chronologically)
        assertTrue(entries.get(0).isUser());
        assertEquals("a v2 user message carries a flat text, not parts",
                "What is 2+2?", entries.get(0).text());
        assertEquals("assistant", entries.get(1).info().role());
        assertEquals("The answer is $4$.", entries.get(1).text());
    }

    /** The observer's raw read: same endpoint, but the unwrapped {@code data} array, unparsed. */
    @Test
    public void getMessagesJsonReturnsTheRawDataArray() throws Exception {
        com.google.gson.JsonArray raw = client.getMessagesJson("ses_new");

        assertEquals("GET", lastMethod.get());
        assertEquals("/api/session/ses_new/message", lastPath.get());
        assertFalse(raw.isEmpty());
        assertTrue(raw.get(0).isJsonObject());
    }

    // ---------- the asynchronous v2 send path ----------

    /**
     * v2 split v1's single blocking {@code POST /message}: agent, model and the
     * per-request system prompt become session state set beforehand, then
     * {@code POST /prompt} only QUEUES the turn. The client restores the
     * synchronous contract by polling the message list.
     */
    @Test
    public void sendMessageSetsAgentModelAndSystemThenPostsThePrompt() throws Exception {
        ChatEntry reply = client.sendMessage(new ChatRequest("ses_new", "build", "opencode", "glm-5.2",
                "high", "SYSTEM-PROMPT", "What is 2+2?"));

        Recorded agent = first("POST", "/api/session/ses_new/agent");
        assertNotNull("the agent must be set as session state first", agent);
        assertTrue(agent.body().contains("\"agent\":\"build\""));

        Recorded model = first("POST", "/api/session/ses_new/model");
        assertNotNull("the model must be set as session state", model);
        assertTrue("v2 Model.Ref names the model 'id'", model.body().contains("\"id\":\"glm-5.2\""));
        assertTrue(model.body().contains("\"providerID\":\"opencode\""));
        assertTrue("variant folds into the model ref", model.body().contains("\"variant\":\"high\""));

        Recorded synthetic = first("POST", "/api/session/ses_new/synthetic");
        assertNotNull("the per-request system prompt becomes a synthetic message", synthetic);
        assertTrue(synthetic.body().contains("SYSTEM-PROMPT"));

        Recorded prompt = first("POST", "/api/session/ses_new/prompt");
        assertNotNull(prompt);
        assertTrue(prompt.body().contains("What is 2+2?"));

        assertNotNull(reply);
        assertEquals("assistant", reply.info().role());
        assertEquals("The answer is $4$.", reply.text());
        assertEquals("opencode/glm-5.2 (high)", reply.info().modelLabel());
        assertEquals("stop", reply.info().finish());
        assertTrue("the returned turn must be the completed one", reply.info().isComplete());
    }

    /** Only the prompt is posted when the request carries no agent/model/system. */
    @Test
    public void sendMessageOmitsTheStateCallsWhenNothingIsRequested() throws Exception {
        client.sendMessage(ChatRequest.of("ses_new", "hi"));

        assertNull(first("POST", "/api/session/ses_new/agent"));
        assertNull(first("POST", "/api/session/ses_new/model"));
        assertNull(first("POST", "/api/session/ses_new/synthetic"));
        assertNotNull(first("POST", "/api/session/ses_new/prompt"));
    }

    /**
     * The reply poll is the whole point of the v2 send: the first message list
     * still shows the turn streaming (no {@code time.completed}), so the client
     * must fetch again and only return once the assistant message is stamped.
     */
    @Test
    public void sendMessagePollsTheMessageListUntilTheReplyIsComplete() throws Exception {
        serveMessages(STREAMING_TURN, COMPLETED_TURN);

        ChatEntry reply = client.sendMessage(ChatRequest.of("ses_new", "What is 2+2?"));

        assertTrue("the poll must have fetched the list more than once",
                count("GET", "/api/session/ses_new/message") >= 2);
        assertTrue("only a time.completed stamp ends the turn", reply.info().isComplete());
        assertEquals("The answer is $4$.", reply.text());
    }

    /**
     * REGRESSION (the Eclipse chat returned an empty bubble ~200ms after every
     * send): a RESUMED session carries previous turns - a completed assistant
     * message AND an {@code idle} marker. Scanning the whole history for those
     * ends the wait on the first poll and hands back the new, still-empty
     * assistant message. The wait must be anchored to THIS turn's prompt.
     */
    @Test
    public void resumedSessionIgnoresPreviousTurnsWhenWaitingForTheReply() throws Exception {
        promptAck.set("""
                {"data":{"id":"msg_u2","sessionID":"ses_new","type":"user","time":{"created":10}}}
                """);
        serveMessages(RESUMED_STREAMING, RESUMED_COMPLETED);

        ChatEntry reply = client.sendMessage(ChatRequest.of("ses_new", "again"));

        assertEquals("the NEW turn's reply, not the stale one", "msg_a2", reply.info().id());
        assertEquals("fresh answer", reply.text());
        assertTrue(reply.info().isComplete());
        assertTrue("the old idle marker must not end the wait",
                count("GET", "/api/session/ses_new/message") >= 2);
    }

    /**
     * REGRESSION (the chat spun forever on a failing model): a turn that FAILS
     * ends with an {@code idle} marker (outcome=failed) and NO assistant
     * message. The wait must surface the outcome, not poll until the budget.
     */
    @Test
    public void failedTurnSurfacesTheOutcomeInsteadOfSpinning() {
        promptAck.set("""
                {"data":{"id":"msg_u2","sessionID":"ses_new","type":"user","time":{"created":10}}}
                """);
        serveMessages("""
                {"data":[
                  {"id":"msg_idle2","sessionID":"ses_new","type":"idle","time":{"created":11},
                   "outcome":"failed"},
                  {"id":"msg_u2","sessionID":"ses_new","type":"user","time":{"created":10},"text":"again"}
                ]}
                """);

        OpencodeException e = org.junit.Assert.assertThrows(OpencodeException.class,
                () -> client.sendMessage(ChatRequest.of("ses_new", "again")));
        assertTrue("the outcome must be named: " + e.getMessage(),
                e.getMessage().contains("failed") && e.getMessage().contains("without a reply"));
    }

    /**
     * A turn can also end on the terminal {@code idle} message even when the
     * assistant message never gets a completion stamp - otherwise the client
     * would poll until the budget expired.
     */
    @Test
    public void sendMessageAlsoStopsOnTheTerminalIdleMessage() throws Exception {        serveMessages(STREAMING_TURN, """
                {"data":[
                  {"id":"msg_idle","sessionID":"ses_new","type":"idle","outcome":"succeeded",
                   "time":{"created":10}},
                  {"id":"msg_a1","sessionID":"ses_new","type":"assistant","time":{"created":2},
                   "content":[{"type":"text","text":"The answer"}]},
                  {"id":"msg_u1","sessionID":"ses_new","type":"user","time":{"created":1},
                   "text":"What is 2+2?"}
                ]}
                """);

        ChatEntry reply = client.sendMessage(ChatRequest.of("ses_new", "What is 2+2?"));

        assertNotNull(reply);
        assertEquals("assistant", reply.info().role());
        assertEquals("The answer", reply.text());
    }

    // ---------- MCP / skills / config ----------

    @Test
    public void registerMcpPutsTheServerNameInThePath() throws Exception {
        client.registerMcp("eclipse-build", McpServerConfig.enabled("http://127.0.0.1:12345/mcp"));

        assertEquals("v2 registers with PUT, not POST", "PUT", lastMethod.get());
        assertEquals("v2 names the server in the path", "/api/experimental/mcp/eclipse-build",
                lastPath.get());
        String body = lastBody.get();
        assertFalse("v2 carries the name in the PUT path, not the body", body.contains("\"name\""));
        assertTrue(body.contains("\"config\":{"));
        assertTrue(body.contains("\"type\":\"remote\""));
        assertTrue(body.contains("\"url\":\"http://127.0.0.1:12345/mcp\""));
        assertTrue("oauth must be explicitly off", body.contains("\"oauth\":false"));
    }

    /**
     * Regression, re-pointed at v2: {@code GET /mcp} used to be a bare
     * {@code {"<name>":{"status":…}}} map, which array parsing choked on and
     * killed the Server view. v2 answers {@code {"location":…,"data":[…]}} - the
     * client must read the entries out of the envelope, not the envelope itself.
     */
    @Test
    public void getMcpServersParsesTheDataEnvelope() throws Exception {
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

    /**
     * REGRESSION (Eclipse showed 0 MCP servers): the auxiliary lists resolve
     * per LOCATION in v2 - an unscoped call on the shared background service
     * answers for the user's home directory. The scoped variant must emit the
     * nested object in bracket syntax ({@code location[directory]=…}); a plain
     * {@code location=<path>} string is rejected with HTTP 400.
     */
    @Test
    public void scopedAuxiliaryListsEmitLocationBracketSyntax() throws Exception {
        lastQuery.set(null);
        client.getMcpServers("C:\\Development\\GitHub\\Hephaestus");
        String mcpQuery = lastQuery.get();
        assertTrue("mcp scope must use bracket syntax, got: " + mcpQuery,
                mcpQuery != null && mcpQuery.startsWith("location%5Bdirectory%5D=")
                        && mcpQuery.contains("Hephaestus"));

        lastQuery.set(null);
        client.getSkills("C:\\Development\\GitHub\\Hephaestus");
        String skillQuery = lastQuery.get();
        assertTrue("skill scope must use bracket syntax, got: " + skillQuery,
                skillQuery != null && skillQuery.startsWith("location%5Bdirectory%5D="));

        lastQuery.set(null);
        client.getMcpServers(); // unscoped stays unscoped (remote/dedicated servers)
        assertNull("unscoped calls must not emit a location param", lastQuery.get());

        // the same scoping on every other location-resolving auxiliary GET
        String dir = "C:\\Development\\GitHub\\Hephaestus";
        try {
            client.getAgents(dir);
        } catch (OpencodeException expected) {
            // this stub deliberately serves an empty body on /api/agent (the
            // empty-body error-path test) - the QUERY is the assertion target
        }
        assertTrue("agent scope", queriesByPath.get("/api/agent").startsWith("location%5Bdirectory%5D="));
        client.getConfig(dir);
        assertTrue("config scope", queriesByPath.get("/api/config").startsWith("location%5Bdirectory%5D="));
        client.getProviders(dir);
        assertTrue("model catalog scope",
                queriesByPath.get("/api/model").startsWith("location%5Bdirectory%5D="));
        assertTrue("provider catalog scope",
                queriesByPath.get("/api/provider").startsWith("location%5Bdirectory%5D="));
    }

    /**
     * v2 serves {@code GET /config} as an ARRAY of config sources; the default
     * model is a {@code {providerID, model}} object inside one source's
     * {@code info}, not v1's flat {@code "provider/model"} string.
     */
    @Test
    public void getConfigParsesDefaultModelFromTheSourceArray() throws Exception {
        com.opencode.ide.client.model.ConfigInfo config = client.getConfig();
        assertEquals("zai-coding-plan/glm-4.6", config.model());
        String[] parts = config.defaultModelParts();
        assertEquals("zai-coding-plan", parts[0]);
        assertEquals("glm-4.6", parts[1]);
    }

    /**
     * v2 deleted {@code GET /config/providers}. The client rebuilds the same
     * {@link ProviderList} from {@code GET /api/model} (flat models carrying a
     * {@code providerID}) plus {@code GET /api/provider}, so the Providers view
     * keeps its provider-to-models structure.
     */
    @Test
    public void providersAreRebuiltFromTheModelAndProviderEndpoints() throws Exception {
        ProviderList list = client.getProviders();

        assertNotNull(first("GET", "/api/model"));
        assertNotNull(first("GET", "/api/provider"));
        assertEquals(2, list.providers().size());

        Provider opencode = list.providers().get(0);
        assertEquals("opencode", opencode.id());
        assertEquals("OpenCode", opencode.name());
        assertEquals("models must be grouped by providerID", 1, opencode.models().size());

        Model jev = opencode.models().get("jev-1.13");
        assertNotNull("the model map is keyed by model id", jev);
        assertEquals("Jev 1.13", jev.name());
        assertEquals(64000L, jev.limit().context());
        assertEquals("v2 cost is a LIST of price tiers", 0.042, jev.baseCost().input(), 0.0001);

        Provider ollama = list.providers().get(1);
        assertEquals("ollama", ollama.id());
        assertEquals(1, ollama.models().size());
        assertNotNull(ollama.models().get("llama3"));
    }

    // ---------- endpoints v2 removed ----------

    /**
     * v2 has no {@code POST /tui/:action} control endpoint any more. Driving an
     * attached TUI would mean publishing {@code tui.*} events, which this client
     * does not do - so the call reports "nothing driven" WITHOUT any HTTP.
     */
    @Test
    public void tuiActionIsANoOpWithoutAnyHttpCall() throws Exception {
        assertFalse(client.tuiAction("append-prompt", Map.of("text", "hello")));
        assertTrue("v2 must not issue a /tui request at all", recorded().isEmpty());
    }

    /** v2 removed {@code POST /log}; logging stays client-side and issues no request. */
    @Test
    public void logWritesClientSideWithoutAnyHttpCall() throws Exception {
        client.log("opencode-eclipse", "INFO", "hello", null);
        assertTrue("v2 must not issue a /log request at all", recorded().isEmpty());
    }

    // ---------- transport errors ----------

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
}
