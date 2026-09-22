package com.opencode.ide.client.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Unit tests for {@link SessionObserver}: the poll-based "what is it doing"
 * answer (U-015). Realistic v2 message fixtures (newest first): tool parts
 * with state/input, shell messages, assistant cost/tokens; the session list
 * supplies identity, aggregates and subagent children. Lenient throughout.
 */
public class SessionObserverTest {

    private static final Session.Tokens TOKENS = new Session.Tokens(100, 20, 5, null);

    /** Minimal fake: serves the session list, the active map and one raw message list. */
    private static final class FakeClient implements OpencodeClient {
        List<Session> sessions = List.of();
        Map<String, SessionStatus> active = Map.of();
        JsonArray messages = new JsonArray();
        boolean messagesFail;

        @Override
        public JsonArray getMessagesJson(String sessionId) throws OpencodeException {
            if (messagesFail) {
                throw new OpencodeException("dead server");
            }
            return messages;
        }

        @Override
        public List<Session> getSessions(String directory) {
            return sessions;
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() {
            return active;
        }

        @Override
        public HealthStatus getHealth() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Agent> getAgents() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderList getProviders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigInfo getConfig() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> getSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session createSession(String title, Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerMcp(String name, McpServerConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatEntry sendMessage(ChatRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra) {
            throw new UnsupportedOperationException();
        }
    }

    private static Session session(String id, String parent, String title, String agent) {
        return new Session(id, null, title, agent, parent,
                new Session.ModelRef("k3", "kimi", null), null, 0.0123, TOKENS, null, null);
    }

    private static JsonArray messages(String... entries) {
        JsonArray out = new JsonArray();
        for (String entry : entries) {
            out.add(JsonParser.parseString(entry));
        }
        return out;
    }

    @Test
    public void busyWorkerReportsItsRunningToolAsTheCurrentActivity() {
        FakeClient client = new FakeClient();
        client.sessions = List.of(session("ses_1", null, "Fix the widget", "executor"));
        client.active = Map.of("ses_1", new SessionStatus("busy"));
        // newest first: the running bash is the current activity
        client.messages = messages(
                """
                {"id":"msg_2","type":"assistant","agent":"executor","cost":0.004,"tokens":{"input":60,"output":10},
                 "content":[
                   {"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"mvn verify"}}},
                   {"type":"text","text":"Now I run the build."}]}
                """,
                """
                {"id":"msg_1","type":"assistant","cost":0.002,"tokens":{"input":40,"output":10},
                 "content":[
                   {"type":"tool","tool":"edit","state":{"status":"completed","input":{"filePath":"src/A.java"}}}]}
                """,
                """
                {"id":"msg_0","type":"user","text":"Fix the widget."}
                """);

        SessionObservation o = SessionObserver.observe(client, "ses_1", null);

        assertEquals("ses_1", o.sessionId());
        assertEquals("busy", o.status());
        assertEquals("Fix the widget", o.title());
        assertEquals("executor", o.agent());
        assertEquals("kimi/k3", o.model());
        assertEquals("tool: bash mvn verify", o.activity());
        assertEquals("Now I run the build.", o.lastText());
        assertEquals(2, o.tools().size());
        assertEquals("bash", o.tools().get(0).name());
        assertEquals("running", o.tools().get(0).status());
        assertEquals("mvn verify", o.tools().get(0).target());
        assertEquals("edit", o.tools().get(1).name());
        assertEquals("src/A.java", o.tools().get(1).target());
        // the session row's aggregates win over per-message sums
        assertEquals(0.0123, o.cost(), 0.0001);
        assertEquals(Long.valueOf(125), o.tokens());
        assertTrue(o.shells().isEmpty());
        assertTrue(o.subagents().isEmpty());
    }

    @Test
    public void shellRunsCarryCommandExitAndOutputTail() {
        FakeClient client = new FakeClient();
        client.messages = messages(
                """
                {"id":"msg_2","type":"shell","command":"git status","status":"exited","exit":0,
                 "output":{"output":"clean","size":5}}
                """,
                """
                {"id":"msg_1","type":"shell","command":"ls","status":"running"}
                """);

        SessionObservation o = SessionObserver.observe(client, "ses_9", null);

        assertEquals(2, o.shells().size());
        assertEquals("git status", o.shells().get(0).command());
        assertEquals("exited", o.shells().get(0).status());
        assertEquals(Integer.valueOf(0), o.shells().get(0).exit());
        assertEquals("clean", o.shells().get(0).outputTail());
        assertEquals("idle", o.status());
        assertEquals("the running shell is the current activity", "shell: ls", o.activity());
    }

    @Test
    public void subagentsAreTheSessionsWhoseParentIsTheObservedOne() {
        FakeClient client = new FakeClient();
        client.sessions = List.of(
                session("ses_parent", null, "Parent", "orchestrator"),
                session("ses_child", "ses_parent", "Child work", "executor"),
                session("ses_other", "ses_unrelated", "Not ours", "executor"));
        client.active = Map.of("ses_child", new SessionStatus("busy"));

        SessionObservation o = SessionObserver.observe(client, "ses_parent", "C:/repo");

        assertEquals(1, o.subagents().size());
        SessionObservation.Child child = o.subagents().get(0);
        assertEquals("ses_child", child.sessionId());
        assertEquals("Child work", child.title());
        assertEquals("executor", child.agent());
        assertEquals("busy", child.status());
        assertEquals(Long.valueOf(125), child.tokens());
    }

    @Test
    public void unreachableMessageEndpointYieldsNull() {
        FakeClient client = new FakeClient();
        client.messagesFail = true;
        assertNull(SessionObserver.observe(client, "ses_gone", null));
        assertNull(SessionObserver.observe(client, null, null));
        assertNull(SessionObserver.observe(null, "ses_1", null));
    }

    @Test
    public void junkEntriesAndMissingFieldsDegradeToAbsentValues() {
        FakeClient client = new FakeClient();
        JsonArray junk = new JsonArray();
        junk.add(JsonParser.parseString("42"));
        junk.add(JsonParser.parseString("{\"type\":\"assistant\"}"));
        junk.add(JsonParser.parseString("{\"type\":\"tool\"}")); // message-level junk, ignored
        junk.add(com.google.gson.JsonNull.INSTANCE);
        client.messages = junk;

        SessionObservation o = SessionObserver.observe(client, "ses_1", null);

        assertEquals("idle", o.status());
        assertNull(o.activity());
        assertNull(o.lastText());
        assertNull(o.cost());
        assertNull(o.tokens());
        assertTrue(o.tools().isEmpty());
        assertTrue(o.shells().isEmpty());
        assertTrue(o.subagents().isEmpty());
    }
}
