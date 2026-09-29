package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SkillInfo;
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

    // ---------- U-046 slice 2: attach skill / suggest title ----------

    @Test
    public void attachSkillSendsTheSessionAndSkillId() {
        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).attachSkill("cpp-tools");

        assertTrue(result.success());
        assertEquals("skill attached", result.detail());
        assertEquals("ses_1", client.attachSessionId);
        assertEquals("cpp-tools", client.attachSkillId);
    }

    @Test
    public void attachSkillFailureYieldsAFailureResultInsteadOfThrowing() {
        client.attachFailure = new OpencodeException("404 SkillNotFoundError");

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).attachSkill("nope");

        assertFalse(result.success());
        assertEquals("404 SkillNotFoundError", result.error());
    }

    @Test
    public void skillsScopeToTheSessionDirectoryAndDegradeToEmpty() {
        client.sessions = List.of(new Session("ses_1", null, "Session One", null, null, null,
                null, null, null, null, new Session.Location("C:\\repo")));
        client.skills = List.of(new SkillInfo("cpp-tools", "cpp-tools", "C++ execution utility"));

        assertEquals(1, new SessionDetailsController("ses_1", () -> client).skills().size());
        assertEquals("C:\\repo", client.listSkillsDirectory);

        client.throwOnListSkills = true;
        assertTrue("an unreadable catalog degrades to empty", //
                new SessionDetailsController("ses_1", () -> client).skills().isEmpty());
    }

    @Test
    public void suggestTitleSendsTheFixedPromptAndCleansTheAnswer() {
        client.generateAnswer = "  \"Fix the failing build\"  ";

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).suggestTitle();

        assertTrue(result.success());
        assertEquals("Fix the failing build", result.detail());
        assertEquals("ses_1", client.generateSessionId);
        assertEquals(SessionDetailsController.TITLE_PROMPT, client.generatePrompt);
    }

    @Test
    public void suggestTitleFailureAndEmptyAnswersYieldFailureResults() {
        client.generateAnswer = "   ";
        assertFalse(new SessionDetailsController("ses_1", () -> client).suggestTitle().success());

        client.generateAnswer = null;
        assertFalse("a null completion is no suggestion",
                new SessionDetailsController("ses_1", () -> client).suggestTitle().success());

        client.generateFailure = new OpencodeException("generate unavailable");
        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).suggestTitle();
        assertFalse(result.success());
        assertEquals("generate unavailable", result.error());
    }

    /** The pure cleaning of a generate answer into a usable title suggestion. */
    @Test
    public void titleSuggestionStripsWhitespaceAndWrappingQuotes() {
        assertEquals("plain", SessionDetailsController.titleSuggestion("  plain "));
        assertEquals("quoted", SessionDetailsController.titleSuggestion("\"quoted\""));
        assertEquals("inner \"quotes\" stay", SessionDetailsController.titleSuggestion("inner \"quotes\" stay"));
        assertNull(SessionDetailsController.titleSuggestion(null));
        assertNull(SessionDetailsController.titleSuggestion("   "));
        assertNull(SessionDetailsController.titleSuggestion("\"\""));
    }

    // ---------- U-041: subagent nesting + shell tasks in the snapshot ----------

    @Test
    public void subagentsAndShellTasksLandInTheSnapshot() {
        client.sessions = List.of(
                session(),
                new Session("ses_child", null, "Explore", "explore", "ses_1", null, TIME,
                        0.02, new Session.Tokens(100, 20, 5, null), null, null));
        client.active = java.util.Map.of("ses_child", new SessionStatus("busy"));
        client.rawMessages = com.google.gson.JsonParser.parseString("""
                [
                  {"id":"msg_2","type":"shell","shellID":"sh_1","command":"git status",
                   "status":"exited","exit":0,"output":{"output":"clean","cursor":5,"size":5,"truncated":false}},
                  {"id":"msg_1","type":"shell","shellID":"sh_2","command":"make verify","status":"running"}
                ]
                """).getAsJsonArray();
        client.shellTasks = List.of(new com.opencode.ide.client.model.ShellTask(
                "sh_2", "running", "make verify", "C:\\repo", null, null,
                new com.opencode.ide.client.model.ShellTask.Time(1L, null)));
        client.messages = List.of(new ChatEntry(user("u1"), List.of(new ChatPart("text", "hi", null, null))));

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertNull(snapshot.errorNote());
        assertEquals(1, snapshot.subagents().size());
        assertEquals("ses_child", snapshot.subagents().get(0).sessionId());
        assertEquals("busy", snapshot.subagents().get(0).status());
        assertEquals(Long.valueOf(125), snapshot.subagents().get(0).tokens());
        assertEquals(2, snapshot.shellTasks().size());
        assertEquals("the live task floats to the top", "sh_2", snapshot.shellTasks().get(0).shellId());
        assertTrue(snapshot.shellTasks().get(0).live());
        assertEquals("sh_1", snapshot.shellTasks().get(1).shellId());
        assertEquals("clean", snapshot.shellTasks().get(1).outputTail());
        assertEquals(1, snapshot.rows().size());
    }

    @Test
    public void rootsComposeTheSectionsAboveTheMessages() {
        client.sessions = List.of(
                session(),
                new Session("ses_child", null, "Explore", "explore", "ses_1", null, TIME,
                        null, null, null, null));
        client.rawMessages = com.google.gson.JsonParser.parseString(
                "[{\"type\":\"shell\",\"shellID\":\"sh_1\",\"command\":\"ls\",\"status\":\"exited\"}]")
                .getAsJsonArray();
        client.messages = List.of(new ChatEntry(user("u1"), List.of(new ChatPart("text", "hi", null, null))));

        List<Object> roots = new SessionDetailsController("ses_1", () -> client).load().roots();

        assertEquals(3, roots.size()); // Subagents section + Shell tasks section + 1 message
        assertTrue(roots.get(0) instanceof com.opencode.ide.ui.model.SessionSections.Section);
        assertEquals("Subagents (1)",
                ((com.opencode.ide.ui.model.SessionSections.Section) roots.get(0)).label());
        assertTrue(roots.get(1) instanceof com.opencode.ide.ui.model.SessionSections.Section);
        assertEquals("Shell tasks (1)",
                ((com.opencode.ide.ui.model.SessionSections.Section) roots.get(1)).label());
        assertTrue(roots.get(2) instanceof MessageRow);
    }

    @Test
    public void subagentAndShellFetchFailuresDegradeToEmptySectionsNotErrors() {
        client.messages = List.of(new ChatEntry(user("u1"), List.of(new ChatPart("text", "hi", null, null))));
        client.throwOnStatus = true;
        client.throwOnRawMessages = true;
        client.throwOnShellTasks = true;

        SessionDetails snapshot = new SessionDetailsController("ses_1", () -> client).load();

        assertNull("the transcript still loads", snapshot.errorNote());
        assertTrue(snapshot.subagents().isEmpty());
        assertTrue(snapshot.shellTasks().isEmpty());
    }

    @Test
    public void directoryResolvesFromTheSessionListOrNullWhenUnknown() {
        client.sessions = List.of(new Session("ses_1", null, "S", null, null, null, TIME,
                null, null, null, new Session.Location("C:\\repo")));

        assertEquals("C:\\repo", new SessionDetailsController("ses_1", () -> client).directory());
        assertNull("an unknown session is unscoped",
                new SessionDetailsController("ses_404", () -> client).directory());

        client.throwOnSessions = true;
        assertNull("an unreadable session list degrades to unscoped",
                new SessionDetailsController("ses_1", () -> client).directory());
    }

    // ---------- U-041: shell output / remove ----------

    @Test
    public void shellOutputReturnsTheTailAndNullOnFailure() {
        client.shellOutput = "BUILD SUCCESSFUL";
        assertEquals("BUILD SUCCESSFUL",
                new SessionDetailsController("ses_1", () -> client).shellOutput("sh_1"));
        assertEquals("sh_1", client.shellOutputId);

        client.shellOutputFailure = new OpencodeException("task evicted");
        assertNull("a gone task degrades to null (callers keep the transcript tail)",
                new SessionDetailsController("ses_1", () -> client).shellOutput("sh_1"));
    }

    @Test
    public void removeShellTaskSendsTheTaskIdAndSurfacesFailures() {
        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).removeShellTask("sh_1");

        assertTrue(result.success());
        assertEquals("sh_1", client.removedShellTaskId);

        client.removeShellTaskFailure = new OpencodeException("boom");
        SessionDetailsController.LifecycleResult failure =
                new SessionDetailsController("ses_1", () -> client).removeShellTask("sh_1");
        assertFalse(failure.success());
        assertEquals("boom", failure.error());
    }

    // ---------- U-048: markViewed / environment / import / revert ----------

    @Test
    public void markViewedPostsNowAsTheIdleWatermark() {
        long before = System.currentTimeMillis();

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).markViewed();

        long after = System.currentTimeMillis();
        assertTrue(result.success());
        assertEquals("ses_1", client.viewedSessionId);
        assertTrue("the idle watermark is the epoch-millis the viewer observed (>= before)",
                client.viewedIdleMillis >= before);
        assertTrue("and bounded by now", client.viewedIdleMillis <= after);
    }

    @Test
    public void replaceEnvironmentSendsTheFullMapAndSurfacesFailures() {
        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client)
                        .replaceEnvironment(java.util.Map.of("CI", "true"));

        assertTrue(result.success());
        assertEquals("ses_1", client.environmentSessionId);
        assertEquals(java.util.Map.of("CI", "true"), client.environmentVariables);

        client.environmentFailure = new OpencodeException("409");
        assertFalse(new SessionDetailsController("ses_1", () -> client)
                .replaceEnvironment(java.util.Map.of()).success());
    }

    @Test
    public void importSessionReturnsTheNewSessionId() {
        client.importedSession = java.util.Map.of("id", "ses_imported");

        SessionDetailsController.LifecycleResult result =
                new SessionDetailsController("ses_1", () -> client).importSession(
                        java.util.Map.of("title", "Imported"), List.of(), "C:\\repo");

        assertTrue(result.success());
        assertEquals("ses_imported", result.detail());
        assertEquals("C:\\repo", client.importDirectory);
        assertEquals("Imported", client.importInfo.get("title"));
    }

    @Test
    public void importConflictsAndMissingIdsSurfaceAsFailures() {
        client.importFailure = new OpencodeException("409 ConflictError: session already exists");
        SessionDetailsController.LifecycleResult conflict =
                new SessionDetailsController("ses_1", () -> client).importSession(
                        java.util.Map.of(), List.of(), null);
        assertFalse(conflict.success());
        assertEquals("409 ConflictError: session already exists", conflict.error());

        client.importFailure = null;
        client.importedSession = java.util.Map.of(); // no id in the answer
        assertFalse("an answer without an id is no success",
                new SessionDetailsController("ses_1", () -> client).importSession(
                        java.util.Map.of(), List.of(), null).success());
    }

    /** U-048 pins: the two-phase revert delegates to the three client verbs. */
    @Test
    public void revertActionsDelegateStageCommitAndUndo() {
        SessionDetailsController controller = new SessionDetailsController("ses_1", () -> client);

        assertTrue(controller.stageRevert("msg_5").success());
        assertEquals("msg_5", client.revertStagedMessageId);

        assertTrue(controller.commitRevert().success());
        assertEquals("ses_1", client.revertCommittedSessionId);

        assertTrue(controller.undoRevert().success());
        assertEquals("ses_1", client.revertClearedSessionId);

        client.revertFailure = new OpencodeException("session busy");
        assertFalse(controller.stageRevert("msg_5").success());
        assertFalse(controller.commitRevert().success());
        assertFalse(controller.undoRevert().success());
    }

    // ---------- fake client (only what the controller touches works) ----------

    private static final class FakeClient extends com.opencode.ide.ui.testsupport.StubClient {
        List<ChatEntry> messages = List.of();
        List<Session> sessions = List.of();
        boolean throwOnMessages;
        boolean throwOnSessions;
        String forkSessionId;
        String forkMessageId;
        OpencodeException forkFailure;
        Session forkResult;

        // U-041: subagents + shell tasks
        java.util.Map<String, SessionStatus> active = java.util.Map.of();
        boolean throwOnStatus;
        com.google.gson.JsonArray rawMessages = new com.google.gson.JsonArray();
        boolean throwOnRawMessages;
        List<com.opencode.ide.client.model.ShellTask> shellTasks = List.of();
        boolean throwOnShellTasks;
        String shellOutput;
        String shellOutputId;
        OpencodeException shellOutputFailure;
        String removedShellTaskId;
        OpencodeException removeShellTaskFailure;

        // U-048: view marker / environment / import / revert
        String viewedSessionId;
        long viewedIdleMillis;
        String environmentSessionId;
        java.util.Map<String, String> environmentVariables;
        OpencodeException environmentFailure;
        java.util.Map<String, Object> importedSession;
        OpencodeException importFailure;
        String importDirectory;
        java.util.Map<String, Object> importInfo;
        String revertStagedMessageId;
        String revertCommittedSessionId;
        String revertClearedSessionId;
        OpencodeException revertFailure;

        // U-046 slice 2: attach skill / suggest title / skill catalog
        String attachSessionId;
        String attachSkillId;
        OpencodeException attachFailure;
        List<SkillInfo> skills = List.of();
        String listSkillsDirectory;
        boolean throwOnListSkills;
        String generateSessionId;
        String generatePrompt;
        String generateAnswer;
        OpencodeException generateFailure;

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
            if (throwOnSessions) {
                throw new OpencodeException("sessions unreadable");
            }
            return sessions;
        }

        @Override
        public java.util.Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
            if (throwOnStatus) {
                throw new OpencodeException("status unreadable");
            }
            return active;
        }

        @Override
        public com.google.gson.JsonArray getMessagesJson(String sessionId) throws OpencodeException {
            if (throwOnRawMessages) {
                throw new OpencodeException("raw messages unreadable");
            }
            return rawMessages;
        }

        @Override
        public List<com.opencode.ide.client.model.ShellTask> listShellTasks() throws OpencodeException {
            if (throwOnShellTasks) {
                throw new OpencodeException("shell tasks unreadable");
            }
            return shellTasks;
        }

        @Override
        public String shellTaskOutput(String id) throws OpencodeException {
            if (shellOutputFailure != null) {
                throw shellOutputFailure;
            }
            shellOutputId = id;
            return shellOutput;
        }

        @Override
        public void removeShellTask(String id) throws OpencodeException {
            if (removeShellTaskFailure != null) {
                throw removeShellTaskFailure;
            }
            removedShellTaskId = id;
        }

        @Override
        public void markSessionViewed(String sessionId, long idleMillis) throws OpencodeException {
            viewedSessionId = sessionId;
            viewedIdleMillis = idleMillis;
        }

        @Override
        public void replaceSessionEnvironment(String sessionId, java.util.Map<String, String> variables)
                throws OpencodeException {
            if (environmentFailure != null) {
                throw environmentFailure;
            }
            environmentSessionId = sessionId;
            environmentVariables = variables;
        }

        @Override
        public java.util.Map<String, Object> importSession(java.util.Map<String, Object> info,
                List<java.util.Map<String, Object>> messages, String directoryOrNull)
                throws OpencodeException {
            if (importFailure != null) {
                throw importFailure;
            }
            importInfo = info;
            importDirectory = directoryOrNull;
            return importedSession;
        }

        @Override
        public boolean revertMessage(String sessionId, String messageId) throws OpencodeException {
            if (revertFailure != null) {
                throw revertFailure;
            }
            revertStagedMessageId = messageId;
            return true;
        }

        @Override
        public void commitSessionRevert(String sessionId) throws OpencodeException {
            if (revertFailure != null) {
                throw revertFailure;
            }
            revertCommittedSessionId = sessionId;
        }

        @Override
        public boolean unrevertSession(String sessionId) throws OpencodeException {
            if (revertFailure != null) {
                throw revertFailure;
            }
            revertClearedSessionId = sessionId;
            return true;
        }

        @Override
        public void attachSkill(String sessionId, String skillId) throws OpencodeException {
            if (attachFailure != null) {
                throw attachFailure;
            }
            attachSessionId = sessionId;
            attachSkillId = skillId;
        }

        @Override
        public List<SkillInfo> listSkills(String directory) throws OpencodeException {
            if (throwOnListSkills) {
                throw new OpencodeException("skills unreadable");
            }
            listSkillsDirectory = directory;
            return skills;
        }

        @Override
        public String generateOnSession(String sessionId, String prompt) throws OpencodeException {
            if (generateFailure != null) {
                throw generateFailure;
            }
            generateSessionId = sessionId;
            generatePrompt = prompt;
            return generateAnswer;
        }

    }
}
