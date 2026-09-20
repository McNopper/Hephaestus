package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.FileDiff;
import com.opencode.ide.client.model.FileNode;
import com.opencode.ide.client.model.ProjectSummary;
import com.opencode.ide.client.model.SearchMatch;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SymbolResult;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Component test for the H5 client surface (session lifecycle, diff,
 * permissions, commands, project/vcs, file/find, tui, config patch): real
 * {@code HttpOpencodeClient} over real HTTP against a local stub server,
 * verifying paths, methods, bodies and parsing. No Eclipse, no opencode.
 *
 * <p>Migrated to <b>opencode v2</b>: every path is {@code /api}-prefixed, list
 * endpoints answer a {@code {"data":[...]}} envelope, and several endpoints
 * moved - {@code /revert} became {@code /revert/stage}, {@code /unrevert}
 * became {@code DELETE /revert}, {@code /summarize} became {@code /compact},
 * {@code /permissions/:id} became {@code /permission/:id/reply}, and
 * {@code PATCH /config} moved under {@code /experimental}. Session sharing and
 * the {@code /tui} control endpoint are gone entirely.</p>
 */
public class HttpOpencodeClientH5ComponentTest {

    private static com.sun.net.httpserver.HttpServer server;
    private static OpencodeClient client;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeClass
    public static void startStub() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = respond(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
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
    public void resetRecording() {
        lastMethod.set(null);
        lastPath.set(null);
        lastQuery.set(null);
        lastBody.set(null);
    }

    private static byte[] respond(String path) {
        String json = switch (path) {
            case "/api/session/ses_1/diff" -> """
                    {"data":[{"path":"src/a.cpp","before":"HEAD","after":"opencode/ses_1",
                      "content":"--- a/src/a.cpp\\n+++ b/src/a.cpp\\n@@ -1 +1 @@\\n-x\\n+y"}]}
                    """;
            case "/api/session/ses_1/fork" -> """
                    {"id":"ses_fork","projectID":"prj_1","time":{"created":9,"updated":9,"idle":0}}
                    """;
            // /revert/stage answers the boolean; /revert (DELETE) and /compact are status-only
            case "/api/session/ses_1/revert/stage", "/api/session/ses_1/revert",
                 "/api/session/ses_1/compact",
                 "/api/session/ses_1/permission/perm_7/reply" -> "true";
            case "/api/command" -> """
                    {"data":[{"name":"review","description":"request a code review"},
                             {"name":"ship"}]}
                    """;
            // a v2 message is flat: `type` is the role and the parts live in `content`
            case "/api/session/ses_1/command" -> """
                    {"id":"msg_c1","sessionID":"ses_1","type":"assistant",
                     "time":{"created":3,"completed":4},
                     "content":[{"type":"text","text":"command ran"}]}
                    """;
            // /project is NOT a data envelope on the wire - a bare array of
            // v2 Projects ({id, canonical, vcs: <type>, time, sandboxes})
            case "/api/project" -> """
                    [{"id":"prj_1","canonical":"C:/repo","vcs":"git","time":{"created":1,"updated":1},"sandboxes":[]}]
                    """;
            // v2 Vcs.Info: {location, data:{provider, branch:{current, default}}}
            case "/api/vcs" -> """
                    {"location":{"directory":"C:/repo"},"data":{"provider":"git","branch":{"current":"main","default":"main"}}}
                    """;
            case "/api/fs/list" -> """
                    {"location":{"directory":"C:/repo"},"data":[{"path":"src/","type":"directory"},
                              {"path":"CMakeLists.txt","type":"file"}]}
                    """;
            // v2 /fs/find answers FileSystem.Entry objects, not bare strings
            case "/api/fs/find" -> """
                    {"location":{"directory":"C:/repo"},"data":[{"path":"src/a.cpp","type":"file"},
                              {"path":"src/b.cpp","type":"file"}]}
                    """;
            case "/api/experimental/config" -> "{\"model\":\"prov/m2\",\"small_model\":null}";
            default -> "{}";
        };
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @AfterClass
    public static void stopStub() {
        server.stop(0);
    }

    @Test
    public void sessionDiffParsesFilesAndPatch() throws Exception {
        List<FileDiff> diffs = client.getSessionDiff("ses_1", null);
        assertEquals(1, diffs.size());
        assertEquals("src/a.cpp", diffs.get(0).path());
        assertTrue(diffs.get(0).content().contains("+y"));
        assertEquals("GET", lastMethod.get());
        assertEquals("/api/session/ses_1/diff", lastPath.get());
    }

    @Test
    public void forkPostsAndParsesSession() throws Exception {
        Session fork = client.forkSession("ses_1", "msg_2");
        assertEquals("ses_fork", fork.id());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/fork", lastPath.get());
        assertTrue(lastBody.get().contains("\"messageID\":\"msg_2\""));
    }

    /** v2 staged reverts: {@code POST /revert/stage} (v1 posted to {@code /revert}). */
    @Test
    public void revertPostsToTheStageEndpoint() throws Exception {
        assertTrue(client.revertMessage("ses_1", "msg_2", null));
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/revert/stage", lastPath.get());
        assertTrue(lastBody.get().contains("\"messageID\":\"msg_2\""));
    }

    /** v2 clears a revert by DELETEing it (v1 had {@code POST /unrevert}). */
    @Test
    public void unrevertDeletesTheRevert() throws Exception {
        assertTrue(client.unrevertSession("ses_1"));
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/api/session/ses_1/revert", lastPath.get());
    }

    /**
     * v2 renamed {@code /summarize} to {@code /compact} and takes the model as a
     * {@code Model.Ref} object ({@code {"id","providerID"}}) instead of two flat
     * fields.
     */
    @Test
    public void summarizePostsCompactWithAModelRef() throws Exception {
        assertTrue(client.summarizeSession("ses_1", "prov", "m1"));
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/compact", lastPath.get());
        assertTrue("the model ref carries the provider: " + lastBody.get(),
                lastBody.get().contains("\"providerID\":\"prov\""));
        assertTrue("v2 Model.Ref names the model 'id': " + lastBody.get(),
                lastBody.get().contains("\"id\":\"m1\""));
    }

    @Test
    public void summarizeWithoutAModelSendsNoModelRef() throws Exception {
        assertTrue(client.summarizeSession("ses_1", null, null));
        assertEquals("{}", lastBody.get());
    }

    /**
     * v2 answers permission requests at
     * {@code POST /session/:id/permission/:requestID/reply} with a single
     * {@code decision} - v1 posted {@code {response, remember}} to
     * {@code /permissions/:id}.
     */
    @Test
    public void permissionReplyPostsADecision() throws Exception {
        assertTrue(client.respondToPermission("ses_1", "perm_7", "once", false));
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/permission/perm_7/reply", lastPath.get());
        assertEquals("{\"decision\":\"once\"}", lastBody.get());
    }

    /** {@code remember} becomes {@code always}; anything starting with "r" rejects. */
    @Test
    public void permissionDecisionMapsRememberAndReject() throws Exception {
        client.respondToPermission("ses_1", "perm_7", "once", true);
        assertEquals("{\"decision\":\"always\"}", lastBody.get());

        client.respondToPermission("ses_1", "perm_7", "reject", false);
        assertEquals("{\"decision\":\"reject\"}", lastBody.get());

        client.respondToPermission("ses_1", "perm_7", "reject", true);
        assertEquals("a rejection wins over remember", "{\"decision\":\"reject\"}", lastBody.get());

        client.respondToPermission("ses_1", "perm_7", "always", false);
        assertEquals("only 'r…' rejects; everything else is once/always",
                "{\"decision\":\"once\"}", lastBody.get());
    }

    @Test
    public void commandsListAndRun() throws Exception {
        List<CommandInfo> commands = client.getCommands();
        assertEquals(2, commands.size());
        assertEquals("review", commands.get(0).name());

        ChatEntry reply = client.runCommand("ses_1", "review", List.of("src/a.cpp"));
        assertEquals("command ran", reply.text());
        assertEquals("assistant", reply.info().role());
        assertEquals("POST", lastMethod.get());
        assertEquals("/api/session/ses_1/command", lastPath.get());
        assertTrue(lastBody.get().contains("\"command\":\"review\""));
        assertTrue(lastBody.get().contains("src/a.cpp"));
    }

    @Test
    public void projectAndVcsParseLeniently() throws Exception {
        List<ProjectSummary> projects = client.getProjects();
        assertEquals(1, projects.size());
        assertEquals("C:/repo", projects.get(0).worktree());
        assertNull("v2 projects carry no per-project branch", projects.get(0).branch());
        assertEquals("/api/project", lastPath.get());

        VcsInfo vcs = client.getVcsInfo();
        assertEquals("main", vcs.branch());
        assertNull("v2 Vcs.Info no longer reports the remote URL", vcs.repository());
        assertEquals("/api/vcs", lastPath.get());
    }

    @Test
    public void fileTreeFindAndSymbolsParse() throws Exception {
        List<FileNode> nodes = client.listFiles(null);
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(0).isDirectory());
        assertFalse(nodes.get(1).isDirectory());
        assertEquals("the name derives from the path in v2", "src", nodes.get(0).name());

        // v2 has NO text/symbol search: /fs/find only matches file/dir names
        assertTrue(client.findText("add").isEmpty());
        assertTrue(client.findSymbols("add").isEmpty());

        List<String> files = client.findFiles("a.cpp");
        assertEquals(List.of("src/a.cpp", "src/b.cpp"), files);
    }

    /**
     * The server rejects {@code GET /file} without a {@code path} key with
     * HTTP 400 ({@code Missing key at ["path"]}), which used to break the Repo
     * view's very first (root) load. Every listing must carry the key.
     */
    @Test
    public void listFilesAlwaysSendsThePathQueryKey() throws Exception {
        client.listFiles(null);
        assertEquals("/api/fs/list", lastPath.get());
        assertEquals("path=.", lastQuery.get());

        client.listFiles("");
        assertEquals("path=.", lastQuery.get());

        client.listFiles("  ");
        assertEquals("path=.", lastQuery.get());

        client.listFiles(".");
        assertEquals("path=.", lastQuery.get());

        client.listFiles("cpp/src");
        assertEquals("path=cpp%2Fsrc", lastQuery.get());

        // the server answers with Windows separators; they must survive encoding
        client.listFiles("cpp\\src\\");
        assertEquals("path=cpp%5Csrc%5C", lastQuery.get());
    }

    /** v2 moved the mutable config surface under {@code /experimental}. */
    @Test
    public void configPatchSendsChangesToTheExperimentalEndpoint() throws Exception {
        ConfigInfo config = client.patchConfig(Map.of("model", "prov/m2"));
        assertEquals("PATCH", lastMethod.get());
        assertEquals("/api/experimental/config", lastPath.get());
        assertTrue(lastBody.get().contains("\"model\":\"prov/m2\""));
        assertNotNull(config);
    }

    /**
     * v2 deleted {@code POST /tui/:action}: steering an attached TUI is an
     * event-publishing concern now. The client reports "nothing driven" and,
     * crucially, issues NO request - a stray POST would 404 every time.
     */
    @Test
    public void tuiActionReportsNotDrivenWithoutTouchingTheServer() throws Exception {
        assertFalse(client.tuiAction("append-prompt", Map.of("text", "hello")));
        assertFalse(client.tuiAction("open-models", null));
        assertNull("no HTTP request may be issued for a TUI action", lastPath.get());
    }
}
