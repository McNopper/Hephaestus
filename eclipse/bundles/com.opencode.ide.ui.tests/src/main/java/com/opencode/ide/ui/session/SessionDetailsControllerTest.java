package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.ui.session.SessionDetailsController.MessageRow;
import com.opencode.ide.ui.session.SessionDetailsController.SessionDetails;
import com.opencode.ide.ui.session.SessionDetailsController.ToolLine;
import com.opencode.ide.ui.session.SessionDetailsController.TokenTotals;

/**
 * Unit tests for {@link SessionDetailsController} with a fake
 * {@link OpencodeClient}: no HTTP, no SWT, no Display — the controller is the
 * SWT-free model behind the Session Details view.
 */
public class SessionDetailsControllerTest {

    private static final Session.Time TIME =
            new Session.Time(1_755_000_000_000L, 1_755_000_000_000L, 0L);
    private static final String TIME_LABEL = "2025-08-12T12:00:00Z";

    private final FakeClient client = new FakeClient();

    // ---------- fixtures ----------

    private static ChatMessageInfo user(String id) {
        return new ChatMessageInfo(id, "ses_1", "user", TIME, null, null, null,
                null, null, null, null, null, new Agent.ModelRef("claude-sonnet-4", "anthropic", null),
                0L);
    }

    private static ChatMessageInfo assistant(String id, String providerId, Double cost, Session.Tokens tokens) {
        return new ChatMessageInfo(id, "ses_1", "assistant", TIME, "build", "primary", "stop",
                cost, tokens, providerId, "glm-5.3", "high", null, 0L);
    }

    private static Session session() {
        return new Session("ses_1", null, "Session One", "build", null, null, TIME, 9.0, null,
                null, null);
    }

    // ---------- mapping ----------

    @Test
    public void userAndAssistantMessagesAreMappedInOrder() {
        client.messages = List.of(
                new ChatEntry(user("u1"), List.of(new ChatPart("text", "Fix the build", null, null))),
                new ChatEntry(assistant("a1", "zai", null, null),
                        List.of(new ChatPart("text", "Done.", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertNull(snapshot.errorNote());
        assertEquals(2, snapshot.rows().size());
        MessageRow user = snapshot.rows().get(0);
        assertEquals("user", user.role());
        assertEquals("Fix the build", user.text());
        assertEquals("anthropic/claude-sonnet-4", user.modelLabel());
        assertEquals(TIME_LABEL, user.timeLabel());
        assertNull(user.agent());
        MessageRow assistant = snapshot.rows().get(1);
        assertEquals("assistant", assistant.role());
        assertEquals("Done.", assistant.text());
        assertEquals("zai/glm-5.3 (high)", assistant.modelLabel());
        assertEquals("build", assistant.agent());
    }

    @Test
    public void toolPartsBecomeToolLinesAndNullToolNameIsSkipped() {
        client.messages = List.of(new ChatEntry(assistant("a1", "zai", null, null), List.of(
                new ChatPart("text", "Running tools", null, null),
                new ChatPart("tool", null, "read", new ChatPart.ToolState("completed")),
                new ChatPart("tool", null, null, new ChatPart.ToolState("running")),
                new ChatPart("tool", null, "bash", new ChatPart.ToolState("error")))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        List<ToolLine> tools = snapshot.rows().get(0).tools();
        assertEquals(2, tools.size());
        assertEquals(new ToolLine("read", "completed"), tools.get(0));
        assertEquals(new ToolLine("bash", "error"), tools.get(1));
    }

    @Test
    public void reasoningPartsAreConcatenatedIntoTheRow() {
        client.messages = List.of(new ChatEntry(assistant("a1", "zai", null, null), List.of(
                new ChatPart("reasoning", "Because ", null, null),
                new ChatPart("reasoning", "of this.", null, null),
                new ChatPart("text", "Answer", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        MessageRow row = snapshot.rows().get(0);
        assertEquals("Answer", row.text());
        assertEquals("Because of this.", row.reasoning());
    }

    @Test
    public void nullInfoEntryYieldsNullSafeRow() {
        client.messages = List.of(
                new ChatEntry(null, List.of(new ChatPart("text", "orphan", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        MessageRow row = snapshot.rows().get(0);
        assertNull(row.role());
        assertEquals("orphan", row.text());
        assertEquals("", row.modelLabel());
        assertEquals("", row.timeLabel());
        assertTrue(row.tools().isEmpty());
    }

    // ---------- header aggregation ----------

    @Test
    public void headerAggregatesCostTokensAndLastAssistantModel() {
        client.sessions = List.of(session());
        client.messages = List.of(
                new ChatEntry(user("u1"), List.of(new ChatPart("text", "go", null, null))),
                new ChatEntry(assistant("a1", "anthropic", 0.5,
                        new Session.Tokens(100, 50, 10, new Session.Cache(5, 2))), List.of()),
                new ChatEntry(assistant("a2", "zai", 1.25,
                        new Session.Tokens(1, 1, 0, null)), List.of()),
                new ChatEntry(user("u2"), List.of(new ChatPart("text", "thanks", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertEquals("Session One", snapshot.title());
        assertEquals("zai/glm-5.3 (high)", snapshot.modelLabel()); // LAST assistant, not the user's
        assertEquals(Double.valueOf(1.75), snapshot.totalCost());
        TokenTotals tokens = snapshot.tokens();
        assertEquals(Long.valueOf(101), tokens.input());
        assertEquals(Long.valueOf(51), tokens.output());
        assertEquals(Long.valueOf(10), tokens.reasoning());
        assertEquals(Long.valueOf(5), tokens.cacheRead());
        assertEquals(Long.valueOf(2), tokens.cacheWrite());
        assertTrue(tokens.summary().contains("in 101"));
        assertTrue(tokens.summary().contains("out 51"));
    }

    @Test
    public void nullCostAndTokensAreTolerated() {
        client.messages = List.of(
                new ChatEntry(assistant("a1", "zai", null, null), List.of()),
                new ChatEntry(assistant("a2", "zai", 1.0, null), List.of()));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertEquals(Double.valueOf(1.0), snapshot.totalCost());
        assertNull(snapshot.tokens());
    }

    @Test
    public void unknownSessionYieldsNullTitleButStillLoadsMessages() {
        client.messages = List.of(new ChatEntry(user("u1"), List.of(new ChatPart("text", "hi", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_404", () -> client).load();

        assertNull(snapshot.title());
        assertEquals(1, snapshot.rows().size());
        assertNull(snapshot.errorNote());
    }

    // v2 has no session share endpoint, so the snapshot no longer carries a
    // shareUrl — the tests that asserted it are gone with the feature.

    // ---------- empty / failure ----------

    @Test
    public void emptyHistoryYieldsEmptyRowsWithNote() {
        client.sessions = List.of(session());
        client.messages = List.of();

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertTrue(snapshot.rows().isEmpty());
        assertEquals(SessionDetailsController.EMPTY_NOTE, snapshot.errorNote());
        assertEquals("Session One", snapshot.title()); // header still resolved
    }

    @Test
    public void throwingSupplierYieldsErrorNoteInsteadOfException() {
        SessionDetailsController controller = new SessionDetailsController("ses_1",
                () -> {
                    throw new RuntimeException(new OpencodeException("boom"));
                });

        SessionDetails snapshot = controller.load();

        assertEquals("ses_1", snapshot.sessionId());
        assertTrue(snapshot.rows().isEmpty());
        assertEquals("boom", snapshot.errorNote());
    }

    @Test
    public void throwingMessageLoadYieldsErrorNote() {
        client.throwOnMessages = true;

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertTrue(snapshot.rows().isEmpty());
        assertEquals("session gone", snapshot.errorNote());
        assertNull(snapshot.title());
    }

    // ---------- fork (at a message / at the latest) ----------

    @Test
    public void rowsCarryTheServerMessageIdForForkAtMessage() {
        client.messages = List.of(
                new ChatEntry(user("u1"), List.of(new ChatPart("text", "hi", null, null))),
                new ChatEntry(assistant("a1", "zai", null, null), List.of()));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertEquals("u1", snapshot.rows().get(0).id());
        assertEquals("a1", snapshot.rows().get(1).id());
    }

    @Test
    public void forkAtMessageCallsForkSessionWithThatMessageId() {
        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).fork("msg_5");

        assertTrue(result.success());
        assertEquals("ses_fork", result.detail());
        assertNull(result.error());
        assertEquals("ses_1", client.forkSessionId);
        assertEquals("msg_5", client.forkMessageId);
    }

    @Test
    public void forkWithoutMessageIdForksAtTheLatestMessage() {
        new SessionDetailsController("ses_1", () -> client).fork(null);

        assertEquals("ses_1", client.forkSessionId);
        assertNull(client.forkMessageId);
    }

    @Test
    public void forkFailureYieldsAFailureResultInsteadOfThrowing() {
        client.forkFailure = new OpencodeException("boom");

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).fork("msg_5");

        assertFalse(result.success());
        assertEquals("boom", result.error());
    }

    @Test
    public void forkOfASessionWithoutIdYieldsThePlaceholderDetail() {
        client.forkResult = new Session(null, null, null, null, null, null, null, null, null,
                null, null);

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).fork("msg_5");

        // the established controller contract maps a missing fork id to "?"
        // (not a failure); the view treats "?" as "no session to open"
        assertTrue(result.success());
        assertEquals("?", result.detail());
    }

    // ---------- fake client (only what the controller touches works) ----------

    private static final class FakeClient implements OpencodeClient {
        List<ChatEntry> messages = List.of();
        List<Session> sessions = List.of();
        boolean throwOnMessages;
        String forkSessionId;
        String forkMessageId;
        OpencodeException forkFailure;
        Session forkResult;

        @Override
        public Session forkSession(String sessionId, String messageId) throws OpencodeException {
            if (forkFailure != null) {
                throw forkFailure;
            }
            forkSessionId = sessionId;
            forkMessageId = messageId;
            if (forkResult != null) {
                return forkResult;
            }
            return new Session("ses_fork", null, "Fork of Session One", null, null, null, null,
                    null, null, null, null);
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
            if (throwOnMessages) {
                throw new OpencodeException("session gone");
            }
            return messages;
        }

        @Override
        public List<Session> getSessions() throws OpencodeException {
            return sessions;
        }

        @Override
        public HealthStatus getHealth() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Agent> getAgents() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderList getProviders() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigInfo getConfig() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session createSession(String title, Path directory) throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerMcp(String name, com.opencode.ide.client.McpServerConfig config)
                throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatEntry sendMessage(com.opencode.ide.client.ChatRequest request) throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra)
                throws OpencodeException {
            throw new UnsupportedOperationException();
        }
    }
}
