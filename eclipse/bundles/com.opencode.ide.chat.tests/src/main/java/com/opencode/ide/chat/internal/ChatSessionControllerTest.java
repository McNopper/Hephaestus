package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeConnectionException;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Unit tests for the session logic extracted from the (formerly ~700-line)
 * ChatView: sending (session creation, model fallback, final render, failure
 * notices), slash-command execution, the TUI-style pending queue (typing
 * ahead while a reply streams, auto-dispatch, edit/remove), abort, undo/redo
 * through the server's revert/unrevert endpoints (including the non-git-repo
 * warning and enablement), resume, live deltas, and disposal - against fake
 * connection/renderer/host collaborators, with inline executors (no SWT
 * anywhere).
 */
public class ChatSessionControllerTest {

    private FakeConnection connection;
    private RecordingRenderer renderer;
    private FakeHost host;
    private ChatSessionController controller;

    @Before
    public void setUp() {
        connection = new FakeConnection();
        renderer = new RecordingRenderer();
        host = new FakeHost();
        controller = new ChatSessionController(connection, renderer, host);
    }

    // ---------- sending ----------

    @Test
    public void sendCreatesSessionEchoesPromptAndRendersFinalReply() {
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", "high", "sys", "hello"));

        assertEquals(List.of("hello"), renderer.users);
        assertEquals(1, connection.client.requests.size());
        assertEquals(new ChatRequest("ses_1", "build", "prov", "m1", "high", "sys", "hello"),
                connection.client.requests.get(0));
        assertTrue("final render expected, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_1:done||prov/mod|"));
        assertEquals(List.of("Session ses_1"), host.statuses);
        assertFalse(controller.isSending());
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
        // explicit model pick: no default-model lookups against the server
        assertEquals(0, connection.client.configCalls);
        assertEquals(0, connection.client.providersCalls);
        // the original log markers
        assertTrue(host.infos.contains("send: begin (5 chars)"));
        assertTrue(host.infos.contains("send job: running"));
    }

    /**
     * REGRESSION (live 2026-09-20): a chat session created WITHOUT the
     * connection's working directory lands in the shared service's home scope
     * - wrong agents, wrong config, wrong permissions. The session must be
     * created against the connection's working directory.
     */
    @Test
    public void sessionIsCreatedScopedToTheConnectionsWorkingDirectory() {
        connection.workingDirectory = "C:/work/repo";
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", "high", "sys", "hello"));

        assertEquals(List.of(Path.of("C:/work/repo")), connection.client.createdSessionDirs);
    }

    /** No working directory (e.g. a dedicated remote) stays unscoped. */
    @Test
    public void sessionCreationWithoutWorkingDirectoryStaysUnscoped() {
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", "high", "sys", "hello"));

        assertEquals(1, connection.client.createdSessionDirs.size());
        assertNull(connection.client.createdSessionDirs.get(0));
    }

    @Test
    public void sendFinalRenderCarriesToolParts() {
        connection.client.reply = new ChatEntry(
                new ChatMessageInfo("msg_t", "ses_1", "assistant", null, null, null, null,
                        null, null, "prov", "mod", null, null, 1_700_000_000_000L),
                List.of(new ChatPart("text", "done", null, null),
                        new ChatPart("tool", null, "read", new ChatPart.ToolState("completed")),
                        new ChatPart("tool", null, "cmake_build", new ChatPart.ToolState("error")),
                        new ChatPart("step-start", null, null, null)));
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertTrue("tool lines expected in the final render, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_t:done||prov/mod|read/completed,cmake_build/error"));
    }

    @Test
    public void sendWithoutExplicitModelUsesRememberedDefault() {
        controller.setDefaultModel("prov", "m1");
        controller.send(new ChatSessionController.OutgoingMessage(
                null, null, null, null, null, "hi"));

        assertEquals(1, connection.client.requests.size());
        assertEquals("prov", connection.client.requests.get(0).providerId());
        assertEquals("m1", connection.client.requests.get(0).modelId());
        assertEquals(0, connection.client.configCalls);
        assertEquals(0, connection.client.providersCalls);
    }

    @Test
    public void sendWithoutAnyModelShowsNoticeAndSendsNothing() {
        // no remembered default; client reports no providers -> nothing resolvable
        controller.send(new ChatSessionController.OutgoingMessage(
                null, null, null, null, null, "hi"));

        assertEquals(List.of("⚠ No model available on the server."), renderer.notices);
        assertTrue(connection.client.requests.isEmpty());
        assertFalse(controller.isSending());
        assertEquals(Boolean.TRUE, host.sendingStates.get(0));
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
    }

    @Test
    public void sendFailureShowsNoticeAndStopsSending() {
        connection.client.sendFailure = new OpencodeException("boom");
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertEquals(List.of("⚠ Send failed: boom"), renderer.notices);
        assertTrue(renderer.assistants.isEmpty());
        assertFalse(controller.isSending());
    }

    // ---------- late replies (POST budget exceeded) ----------

    @Test
    public void sendTimeoutWithIdleSessionSettlesFromHistory() {
        connection.client.sendFailure = promptTimeout();
        connection.client.history = List.of(entry("u1", "user", "hi"),
                entry("msg_9", "assistant", "late done"));
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertTrue("late final render expected, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_9:late done||prov/mod|"));
        assertTrue("no failure notice expected, got: " + renderer.notices,
                renderer.notices.stream().noneMatch(n -> n.startsWith("⚠")));
        assertFalse(controller.isSending());
    }

    @Test
    public void sendTimeoutWithBusySessionWatchesThenSettlesFromHistory() {
        ChatSessionController tight = new ChatSessionController(connection, renderer, host,
                Duration.ofMillis(1), Duration.ofMinutes(30));
        connection.client.sendFailure = promptTimeout();
        connection.client.busyPolls = 1; // recovery probe sees busy, watcher poll sees idle
        connection.client.history = List.of(entry("u1", "user", "hi"),
                entry("msg_9", "assistant", "late done"));
        tight.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        // No still-running banner anymore (user feedback 2026-09-18): a
        // long generation is normal - the watcher runs silently and the
        // only notice is the completion one
        assertFalse("no banner notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.startsWith("\u23f3")));
        assertTrue("late final render expected, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_9:late done||prov/mod|"));
        assertFalse(tight.isSending());
        assertTrue(connection.client.abortCalls.isEmpty());
    }

    @Test
    public void sendTimeoutStuckBusyIsAbortedAtTheCap() {
        ChatSessionController tight = new ChatSessionController(connection, renderer, host,
                Duration.ofMillis(1), Duration.ofMillis(20));
        connection.client.sendFailure = promptTimeout();
        connection.client.busyPolls = Integer.MAX_VALUE;
        tight.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertEquals(List.of("ses_1"), connection.client.abortCalls);
        assertTrue("aborted notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("aborted")));
        assertFalse(tight.isSending());
    }

    @Test
    public void sendTimeoutWithoutAssistantReplyReportsIncomplete() {
        connection.client.sendFailure = promptTimeout();
        connection.client.history = List.of(entry("u1", "user", "hi"));
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertTrue("incomplete notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("did not complete")));
        assertTrue(renderer.assistants.isEmpty());
        assertFalse(controller.isSending());
    }

    /**
     * REGRESSION (same stale-history class as the empty-bubble bug): a failed
     * turn can leave a PREVIOUS turn's assistant at the history tail (the
     * projection can drop the queued user message - observed live on the
     * AgentNotFoundError turns). The late settle must never render stale
     * history as this turn's reply.
     */
    @Test
    public void sendTimeoutNeverSettlesAProvablyStaleAssistant() {
        connection.client.sendFailure = promptTimeout();
        long old = System.currentTimeMillis() - 3_600_000;
        ChatMessageInfo staleInfo = new ChatMessageInfo("msg_old", "ses_1", "assistant",
                new Session.Time(old, old, 0), null, null, null, null, null, "prov", "mod", null, null, old);
        connection.client.history = List.of(
                new ChatEntry(staleInfo, List.of(new ChatPart("text", "stale reply", null, null))));
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertTrue("incomplete notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("did not complete")));
        assertTrue("stale history must not settle: " + renderer.assistants, renderer.assistants.isEmpty());
        assertFalse(controller.isSending());
    }

    /** The projection may legitimately drop the user echo - a FRESH tail assistant still settles. */
    @Test
    public void sendTimeoutSettlesAFreshAssistantEvenWithoutTheUserEcho() {
        connection.client.sendFailure = promptTimeout();
        long now = System.currentTimeMillis();
        ChatMessageInfo freshInfo = new ChatMessageInfo("msg_new", "ses_1", "assistant",
                new Session.Time(now, now, 0), null, null, null, null, null, "prov", "mod", null, null, now);
        connection.client.history = List.of(
                new ChatEntry(freshInfo, List.of(new ChatPart("text", "fresh reply", null, null))));
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));

        assertTrue("fresh reply must settle, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_new:fresh reply||prov/mod|"));
        assertFalse(controller.isSending());
    }

    private static OpencodeException promptTimeout() {
        return new OpencodeConnectionException(
                "opencode POST /session/ses_1/prompt timed out after 300s",
                new HttpTimeoutException("request timed out"));
    }

    @Test
    public void secondSendIsIgnoredWhileOneIsInFlight() {
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "first"));
        assertTrue(controller.isSending());

        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "second"));
        assertEquals(List.of("first"), renderer.users);

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertFalse(controller.isSending());
        assertEquals(1, connection.client.requests.size());
        assertEquals("first", connection.client.requests.get(0).text());
    }

    // ---------- slash commands ----------

    @Test
    public void sendCommandCreatesSessionEchoesAndRunsTheCommand() {
        controller.sendCommand(commandSelection("/build debug it", "build", List.of("debug it")));

        assertEquals(List.of("/build debug it"), renderer.users);
        assertEquals(List.of(new FakeClient.CommandCall("ses_1", "build", List.of("debug it"))),
                connection.client.commandCalls);
        assertTrue("final render expected, got: " + renderer.assistants,
                renderer.assistants.contains("final:msg_1:done||prov/mod|"));
        assertEquals(List.of("Session ses_1"), host.statuses);
        assertTrue(host.jobs.contains("Running opencode command build"));
        assertFalse(controller.isSending());
    }

    @Test
    public void sendCommandWithoutEchoTextEchoesReconstructedSlashText() {
        controller.sendCommand(commandSelection(" ", "build", List.of("now")));

        assertEquals(List.of("/build now"), renderer.users);
    }

    @Test
    public void secondCommandIsIgnoredWhileOneIsInFlight() {
        host.holdBackground = true;
        controller.sendCommand(commandSelection("/build one", "build", List.of("one")));
        assertTrue(controller.isSending());

        controller.sendCommand(commandSelection("/build two", "build", List.of("two")));
        assertEquals(List.of("/build one"), renderer.users);

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertEquals(1, connection.client.commandCalls.size());
        assertFalse(controller.isSending());
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
    }

    @Test
    public void sendCommandFailureShowsNoticeAndStopsSending() {
        connection.client.commandFailure = new OpencodeException("boom");
        controller.sendCommand(commandSelection("/build", "build", List.of()));

        assertTrue(renderer.notices.contains("⚠ Command failed: boom"));
        assertFalse(controller.isSending());
    }

    @Test
    public void sendCommandIgnoresNullAndNonCommandSelections() {
        controller.sendCommand(null);
        controller.sendCommand(new CommandComposer.CommandSelection(
                CommandComposer.Kind.MESSAGE, null, List.of(), "hi"));

        assertTrue(renderer.users.isEmpty());
        assertTrue(connection.client.commandCalls.isEmpty());
        assertFalse(controller.isSending());
    }

    @Test
    public void commandReplySettlesTheStreamedBubble() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.sendCommand(commandSelection("/build now", "build", List.of("now")));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"par\"}"));
        host.queuedBackground.forEach(Runnable::run);

        assertTrue("final render must target the streamed mid, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.startsWith("final:msg_stream:")));
        assertTrue(renderer.assistants.contains("stop:msg_stream"));
    }

    private static CommandComposer.CommandSelection commandSelection(String text, String name,
            List<String> arguments) {
        return new CommandComposer.CommandSelection(CommandComposer.Kind.COMMAND,
                new CommandInfo(name, null), arguments, text);
    }

    // ---------- abort ----------

    @Test
    public void abortWhileSendingPostsAbortForTheSessionAndNotifies() {
        controller.resume("ses_9");
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));
        assertTrue(controller.isSending());

        controller.abort();
        assertTrue("interrupted notice expected, got: " + renderer.notices,
                renderer.notices.contains("⏹ Aborted by user."));
        assertTrue(host.jobs.contains("Aborting opencode chat ses_9"));
        // the abort POST runs on the background job, never the calling thread
        assertTrue(connection.client.abortCalls.isEmpty());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertEquals(List.of("ses_9"), connection.client.abortCalls);
        // the aborted send job still completes (the server unblocks its reply
        // call) and re-enables the send button
        assertFalse(controller.isSending());
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
    }

    @Test
    public void abortWithoutSendOrSessionIsANoOp() {
        controller.abort();
        assertTrue(renderer.notices.isEmpty());
        assertTrue(connection.client.abortCalls.isEmpty());

        // sending, but the session is not created yet (first send job queued)
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));
        controller.abort();
        assertTrue("nothing to abort yet, got: " + renderer.notices,
                renderer.notices.isEmpty());

        host.queuedBackground.forEach(Runnable::run);
        assertTrue(connection.client.abortCalls.isEmpty());
        assertFalse(controller.isSending());
    }

    @Test
    public void abortReleasesAStuckReplyCallAndSubmitsTheQueue() {
        // user report 2026-09-16: after an abort whose reply POST never
        // unblocked, every later send silently queued forever. The settle
        // watch must release the view AND dispatch the queued submissions.
        ChatSessionController quick = new ChatSessionController(connection, renderer, host,
                Duration.ofMillis(5), Duration.ofMinutes(30), Duration.ofMillis(120));
        quick.resume("ses_9");
        host.holdBackground = true;
        quick.send(new ChatSessionController.OutgoingMessage(null, "prov", "m1", null, null, "first"));
        assertTrue(quick.isSending());
        quick.submit(null, new ChatSessionController.OutgoingMessage(null, "prov", "m1", null, null, "second"));
        assertEquals(List.of("second"), quick.queuedPrompts());

        quick.abort();
        // run ONLY the abort job; the send job stays held, so the reply POST
        // never settles and the settle watch must force the release
        host.queuedBackground.get(host.queuedBackground.size() - 1).run();

        assertTrue("release notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("view was released")));
        assertTrue("the queued submission is dispatched (echo rendered)",
                renderer.users.contains("second"));
        assertTrue("the drained submission owns the in-flight flag", quick.isSending());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertEquals("both messages went out, oldest first", List.of("first", "second"),
                connection.client.requests.stream().map(ChatRequest::text).toList());
        assertFalse(quick.isSending());
    }

    @Test
    public void abortFailureIsLoggedAndShownAsNotice() {
        connection.client.abortFailure = new OpencodeException("nope");
        controller.resume("ses_9");
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi"));
        controller.abort();
        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(1, connection.client.abortCalls.size());
        assertTrue(renderer.notices.contains("⚠ Abort failed: nope"));
        assertTrue(host.infos.contains("ERROR abort failed for session ses_9"));
    }

    // ---------- pending queue (TUI-style typing ahead) ----------

    private static ChatSessionController.OutgoingMessage msg(String text) {
        return new ChatSessionController.OutgoingMessage(null, "prov", "m1", null, null, text);
    }

    @Test
    public void submitWhileIdleSendsImmediately() {
        boolean queued = controller.submit(null, msg("hi"));

        assertFalse(queued);
        assertEquals(List.of("hi"), renderer.users);
        assertEquals(1, connection.client.requests.size());
        assertTrue(controller.queuedPrompts().isEmpty());
        assertFalse(controller.isSending());
    }

    @Test
    public void submitWhileSendingQueuesAndAutoSendsOnCompletion() {
        host.holdBackground = true;
        controller.send(msg("first"));
        assertTrue(controller.isSending());

        boolean queued = controller.submit(null, msg("second"));
        assertTrue(queued);
        assertEquals(List.of("first"), renderer.users); // the echo waits
        assertEquals(List.of("second"), controller.queuedPrompts());
        assertEquals(0, connection.client.requests.size()); // the send job is held
        assertTrue(host.infos.contains("queue: submission waiting (1 queued)"));

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        // the first reply settled, the queued submission auto-dispatched
        assertEquals(List.of("first", "second"), renderer.users);
        assertEquals(2, connection.client.requests.size());
        assertEquals("second", connection.client.requests.get(1).text());
        assertTrue(controller.queuedPrompts().isEmpty());
        assertFalse(controller.isSending());
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
    }

    @Test
    public void queuedSubmissionsDispatchInOrder() {
        host.holdBackground = true;
        controller.send(msg("a"));
        controller.submit(null, msg("b"));
        controller.submit(null, msg("c"));
        controller.submit(null, msg("d"));
        assertEquals(List.of("b", "c", "d"), controller.queuedPrompts());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(List.of("a", "b", "c", "d"), renderer.users);
        assertEquals(4, connection.client.requests.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(String.valueOf((char) ('a' + i)), connection.client.requests.get(i).text());
        }
        assertTrue(controller.queuedPrompts().isEmpty());
        assertFalse(controller.isSending());
    }

    @Test
    public void queuedCommandRunsAfterTheInFlightMessage() {
        host.holdBackground = true;
        controller.send(msg("first"));
        assertTrue(controller.submit(commandSelection("/build now", "build", List.of("now")), null));
        assertEquals(List.of("/build now"), controller.queuedPrompts());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(List.of("first", "/build now"), renderer.users);
        assertEquals(List.of(new FakeClient.CommandCall("ses_1", "build", List.of("now"))),
                connection.client.commandCalls);
        assertTrue(controller.queuedPrompts().isEmpty());
        assertFalse(controller.isSending());
    }

    @Test
    public void queuedPromptCanBeRemovedAndOutOfRangeRemovalIsSafe() {
        host.holdBackground = true;
        controller.send(msg("a"));
        controller.submit(null, msg("b"));
        controller.submit(null, msg("c"));

        assertEquals("b", controller.removeQueuedPrompt(0));
        assertNull(controller.removeQueuedPrompt(9));
        assertEquals(List.of("c"), controller.queuedPrompts());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(List.of("a", "c"), renderer.users); // "b" never sent
        assertEquals(2, connection.client.requests.size());
    }

    @Test
    public void invalidSubmissionsAreNeverQueued() {
        host.holdBackground = true;
        controller.send(msg("first"));

        assertFalse(controller.submit(null, null));
        assertFalse(controller.submit(new CommandComposer.CommandSelection(
                CommandComposer.Kind.MESSAGE, new CommandInfo("build", null), List.of(), "hi"), null));
        assertFalse(controller.submit(new CommandComposer.CommandSelection(
                CommandComposer.Kind.COMMAND, null, List.of(), null), null));
        assertTrue(controller.queuedPrompts().isEmpty());

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertEquals(1, connection.client.requests.size());
    }

    @Test
    public void newSessionRefusalKeepsThePendingQueue() {
        host.holdBackground = true;
        controller.send(msg("first"));
        controller.submit(null, msg("queued"));

        controller.startNewSession();

        assertTrue(renderer.notices.contains(
                "⚠ A reply is still streaming - abort it before starting a new session."));
        assertEquals(List.of("queued"), controller.queuedPrompts());
    }

    @Test
    public void abortWithQueuedSubmissionSendsItAfterTheAbortedReplySettles() {
        controller.subscribe();
        controller.send(msg("hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(msg("again"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"par\"}"));
        controller.submit(null, msg("next"));
        assertTrue(controller.isSending());

        controller.abort();
        assertTrue("abort must stop the cursor, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_stream")));
        assertTrue(renderer.notices.contains("⏹ Aborted by user."));

        // the aborted send job unblocks, then the queued submission auto-sends
        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(List.of("ses_1"), connection.client.abortCalls);
        assertEquals(3, connection.client.requests.size()); // hello, again, next
        assertEquals("next", connection.client.requests.get(2).text());
        assertTrue(controller.queuedPrompts().isEmpty());
        assertFalse(controller.isSending());
        assertEquals(Boolean.FALSE, host.sendingStates.get(host.sendingStates.size() - 1));
    }

    // ---------- forking (TUI parity: at a message / from a queued request) ----------

    @Test
    public void forkAtMessageCallsForkSessionAndSwitchesToTheFork() {
        controller.resume("ses_42");

        controller.forkAt("msg_7");

        assertEquals(List.of(new FakeClient.ForkCall("ses_42", "msg_7")),
                connection.client.forkCalls);
        assertEquals(1, host.forks.size());
        assertEquals("ses_fork1", host.forks.get(0)[0]);
        assertEquals("ses_42", host.forks.get(0)[1]);
        assertNull("a plain fork-at-message moves no draft", host.forks.get(0)[2]);
        assertTrue("switch notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("Forked to session ses_fork1")));
        assertTrue(host.jobs.contains("Forking session ses_42"));
    }

    @Test
    public void forkAtBlankMessageIdForksAtTheLatestMessage() {
        controller.resume("ses_42");

        controller.forkAt(null);
        controller.forkAt("   ");

        assertEquals(List.of(new FakeClient.ForkCall("ses_42", null),
                new FakeClient.ForkCall("ses_42", null)), connection.client.forkCalls);
        assertEquals(2, host.forks.size());
    }

    @Test
    public void forkWithoutASessionIsRefused() {
        controller.forkAt("msg_1");

        assertTrue(connection.client.forkCalls.isEmpty());
        assertTrue(host.forks.isEmpty());
        assertTrue(renderer.notices.contains("⚠ Nothing to fork yet - send a message first."));
    }

    @Test
    public void forkFailureShowsANoticeAndDoesNotSwitch() {
        connection.client.forkFailure = new OpencodeException("boom");
        controller.resume("ses_42");

        controller.forkAt("msg_7");

        assertEquals(1, connection.client.forkCalls.size());
        assertTrue(renderer.notices.contains("⚠ Fork failed: boom"));
        assertTrue(host.forks.isEmpty());
    }

    @Test
    public void forkOfASessionWithoutIdIsReportedNotSwitched() {
        connection.client.forkResult = new Session(null, null, null, null, null, null, null, null, null, null, null);
        controller.resume("ses_42");

        controller.forkAt("msg_7");

        assertTrue("no id - no switch, got: " + host.forks, host.forks.isEmpty());
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("Fork failed")));
    }

    @Test
    public void forkWhileAReplyStreamsIsAllowedAndTheOriginalKeepsRunning() {
        controller.send(msg("seed")); // completes: ses_1 exists
        host.holdBackground = true;
        controller.send(msg("streaming")); // reply in flight

        controller.forkAt("msg_0");

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertEquals(List.of(new FakeClient.ForkCall("ses_1", "msg_0")),
                connection.client.forkCalls);
        assertEquals(1, host.forks.size());
        assertEquals("ses_1", host.forks.get(0)[1]);
        assertEquals("the original messages still went out", List.of("seed", "streaming"),
                connection.client.requests.stream().map(ChatRequest::text).toList());
        assertFalse(controller.isSending());
    }

    @Test
    public void queuedSubmissionCanBeForkedIntoANewSessionBeforeDispatch() {
        controller.send(msg("seed")); // completes: ses_1 exists
        host.holdBackground = true;
        controller.send(msg("first")); // reply in flight
        controller.submit(null, msg("second"));
        assertEquals(List.of("second"), controller.queuedPrompts());

        controller.forkQueued(0);

        assertTrue("the submission left the queue immediately", controller.queuedPrompts().isEmpty());
        assertTrue("the pending list is told about the removal", host.queueChanges >= 1);

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run); // held send job + fork job

        assertEquals("fork at the current head (latest message)",
                List.of(new FakeClient.ForkCall("ses_1", null)), connection.client.forkCalls);
        assertEquals(1, host.forks.size());
        assertEquals("ses_fork1", host.forks.get(0)[0]);
        assertEquals("ses_1", host.forks.get(0)[1]);
        assertEquals("the queued text moved into the fork's input", "second", host.forks.get(0)[2]);
        assertTrue("the queued prompt is never dispatched to the original session",
                connection.client.requests.stream().noneMatch(r -> "second".equals(r.text())));
        assertEquals(List.of("seed", "first"),
                connection.client.requests.stream().map(ChatRequest::text).toList());
        assertTrue(host.infos.contains("queue: submission forked into ses_fork1 (before dispatch)"));
    }

    @Test
    public void queuedForkOfABadIndexIsANoOp() {
        controller.send(msg("seed")); // completes: ses_1 exists
        host.holdBackground = true;
        controller.send(msg("first"));
        controller.submit(null, msg("second"));

        controller.forkQueued(5);

        assertEquals(List.of("second"), controller.queuedPrompts());
        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
        assertTrue(connection.client.forkCalls.isEmpty());
        assertTrue(host.forks.isEmpty());
    }

    @Test
    public void queuedForkFailurePutsThePromptBackIntoTheQueue() {
        controller.send(msg("seed")); // completes: ses_1 exists
        host.holdBackground = true;
        controller.send(msg("first"));
        controller.submit(null, msg("second"));
        connection.client.forkFailure = new OpencodeException("boom");

        controller.forkQueued(0);
        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);

        assertTrue(host.forks.isEmpty());
        assertEquals("user input is never lost - the prompt is re-queued",
                List.of("second"), controller.queuedPrompts());
        assertTrue("the pending list is told about the re-queue", host.queueChanges >= 2);
        assertTrue("re-queue notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.contains("Fork failed (prompt re-queued): boom")));
        assertTrue("never dispatched on the failed fork path",
                connection.client.requests.stream().noneMatch(r -> "second".equals(r.text())));
    }

    // ---------- undo / redo (revert through the server) ----------

    @Test
    public void undoRevertsLastUserExchangeReloadsHistoryAndEnablesRedo() {
        connection.client.history = List.of(
                entry("u1", "user", "one"),
                entry("a1", "assistant", "answer one"),
                entry("u2", "user", "two"),
                entry("a2", "assistant", "answer two"));
        controller.resume("ses_42");
        // the server drops reverted messages from GET /session/:id/message
        connection.client.onRevert = () -> connection.client.history = List.of(
                entry("u1", "user", "one"),
                entry("a1", "assistant", "answer one"));

        controller.undoLastTurn();

        assertEquals("revert at the newest user message (its replies go with it)",
                List.of(new FakeClient.RevertCall("ses_42", "u2")),
                connection.client.revertCalls);
        assertTrue(host.jobs.contains("Reverting last exchange ses_42"));
        assertEquals("history re-rendered without the reverted tail", 2,
                renderer.histories.get(renderer.histories.size() - 1).size());
        assertTrue("undo marker notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.startsWith("\u21A9")
                        && n.contains("git snapshot")));
        boolean[] state = host.undoRedoStates.get(host.undoRedoStates.size() - 1);
        assertTrue("one exchange left - undo again possible", state[0]);
        assertTrue("the server holds the reverted messages - redo enabled", state[1]);
        assertTrue(controller.canUndo());
        assertTrue(controller.canRedo());
    }

    @Test
    public void repeatedUndoRevertsEarlierExchanges() {
        connection.client.history = List.of(
                entry("u1", "user", "one"), entry("a1", "assistant", "answer one"),
                entry("u2", "user", "two"), entry("a2", "assistant", "answer two"));
        controller.resume("ses_42");
        connection.client.onRevert = () -> connection.client.history = List.of(
                entry("u1", "user", "one"), entry("a1", "assistant", "answer one"));

        controller.undoLastTurn();
        controller.undoLastTurn();

        assertEquals("each undo reverts the then-newest user message",
                List.of(new FakeClient.RevertCall("ses_42", "u2"),
                        new FakeClient.RevertCall("ses_42", "u1")),
                connection.client.revertCalls);
    }

    @Test
    public void undoWithoutASessionIsRefused() {
        controller.undoLastTurn();

        assertTrue(connection.client.revertCalls.isEmpty());
        assertTrue(renderer.notices.contains("\u26A0 Nothing to undo yet - send a message first."));
    }

    @Test
    public void undoWhileAReplyStreamsIsRefused() {
        controller.resume("ses_42");
        host.holdBackground = true;
        controller.send(msg("hi"));
        assertTrue(controller.isSending());

        controller.undoLastTurn();

        assertTrue("a revert during generation would race the streaming transcript",
                connection.client.revertCalls.isEmpty());
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("abort it before undoing")));

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run); // settle the held send
    }

    @Test
    public void undoWithoutAnyUserMessageNotifiesAndDisablesUndo() {
        connection.client.history = List.of(entry("a1", "assistant", "only an answer"));
        controller.resume("ses_42");

        controller.undoLastTurn();

        assertTrue(connection.client.revertCalls.isEmpty());
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("Nothing to undo")));
        boolean[] state = host.undoRedoStates.get(host.undoRedoStates.size() - 1);
        assertFalse(state[0]);
        assertFalse(state[1]);
    }

    @Test
    public void undoWhenTheServerHasNothingToRevertIsReportedAndDisablesUndo() {
        connection.client.history = List.of(entry("u1", "user", "one"));
        connection.client.revertResult = false;
        controller.resume("ses_42");

        controller.undoLastTurn();

        assertEquals(1, connection.client.revertCalls.size());
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("nothing left to revert")));
        assertFalse("the server's answer is the truth, not the local history view",
                host.undoRedoStates.get(host.undoRedoStates.size() - 1)[0]);
    }

    @Test
    public void undoTellsPlainlyWhenTheProjectIsNotAGitRepo() {
        connection.client.vcs = new VcsInfo(null, null);
        connection.client.history = List.of(
                entry("u1", "user", "one"), entry("a1", "assistant", "answer"));
        controller.resume("ses_42");

        controller.undoLastTurn();

        assertTrue("plain non-git warning expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.startsWith("\u21A9")
                        && n.contains("not a git repo") && n.contains("NOT restored")));
        assertEquals(1, connection.client.revertCalls.size());
    }

    @Test
    public void undoFailureShowsANotice() {
        connection.client.revertFailure = new OpencodeException("boom");
        connection.client.history = List.of(entry("u1", "user", "one"));
        controller.resume("ses_42");

        controller.undoLastTurn();

        assertTrue(renderer.notices.contains("\u26A0 Undo failed: boom"));
        assertTrue(host.infos.contains("ERROR undo failed for session ses_42"));
    }

    @Test
    public void redoRestoresTheRevertedExchangeAndDisablesRedo() {
        List<ChatEntry> full = List.of(
                entry("u1", "user", "one"), entry("a1", "assistant", "answer one"),
                entry("u2", "user", "two"), entry("a2", "assistant", "answer two"));
        connection.client.history = full;
        controller.resume("ses_42");
        connection.client.onRevert = () -> connection.client.history = List.of(
                entry("u1", "user", "one"), entry("a1", "assistant", "answer one"));
        controller.undoLastTurn();
        connection.client.onRevert = null;
        connection.client.onUnrevert = () -> connection.client.history = full;

        controller.redoReverted();

        assertEquals(List.of("ses_42"), connection.client.unrevertCalls);
        assertTrue(host.jobs.contains("Restoring reverted exchange ses_42"));
        assertTrue("redo marker notice expected, got: " + renderer.notices,
                renderer.notices.stream().anyMatch(n -> n.startsWith("\u21B7")));
        assertEquals("history re-rendered with the restored exchange", 4,
                renderer.histories.get(renderer.histories.size() - 1).size());
        boolean[] state = host.undoRedoStates.get(host.undoRedoStates.size() - 1);
        assertTrue(state[0]);
        assertFalse("everything is back - nothing left to redo", state[1]);
        assertFalse(controller.canRedo());
    }

    @Test
    public void redoWhenTheServerHoldsNothingIsReportedAndDisablesRedo() {
        connection.client.unrevertResult = false;
        controller.resume("ses_42");

        controller.redoReverted();

        assertEquals(List.of("ses_42"), connection.client.unrevertCalls);
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("Nothing to redo")));
        assertFalse(host.undoRedoStates.get(host.undoRedoStates.size() - 1)[1]);
    }

    @Test
    public void redoWithoutASessionIsRefused() {
        controller.redoReverted();

        assertTrue(connection.client.unrevertCalls.isEmpty());
        assertTrue(renderer.notices.contains("\u26A0 Nothing to redo yet - send a message first."));
    }

    @Test
    public void redoFailureShowsANotice() {
        connection.client.unrevertFailure = new OpencodeException("boom");
        controller.resume("ses_42");

        controller.redoReverted();

        assertTrue(renderer.notices.contains("\u26A0 Redo failed: boom"));
        assertTrue(host.infos.contains("ERROR redo failed for session ses_42"));
    }

    @Test
    public void builtInSlashCommandsAreRecognizedExactly() {
        assertEquals("undo", ChatSessionController.builtInSlashCommand("/undo"));
        assertEquals("undo", ChatSessionController.builtInSlashCommand(" /UNDO "));
        assertEquals("redo", ChatSessionController.builtInSlashCommand("/Redo"));
        assertNull(ChatSessionController.builtInSlashCommand(null));
        assertNull(ChatSessionController.builtInSlashCommand(""));
        assertNull(ChatSessionController.builtInSlashCommand("undo"));
        assertNull(ChatSessionController.builtInSlashCommand("/undo now"));
        assertNull(ChatSessionController.builtInSlashCommand("/undone"));
        assertNull(ChatSessionController.builtInSlashCommand("redo /redo"));
    }

    @Test
    public void sendCompletingEnablesUndo() {
        controller.send(msg("hello"));

        assertTrue(controller.canUndo());
        assertFalse(controller.canRedo());
        boolean[] state = host.undoRedoStates.get(host.undoRedoStates.size() - 1);
        assertTrue(state[0]);
        assertFalse(state[1]);
    }

    @Test
    public void commandCompletingEnablesUndo() {
        controller.sendCommand(commandSelection("/build", "build", List.of()));

        assertTrue(controller.canUndo());
    }

    @Test
    public void resumeComputesUndoEnablementFromTheServedHistory() {
        connection.client.history = List.of(entry("u1", "user", "one"));
        controller.resume("ses_42");
        assertTrue(controller.canUndo());
        assertFalse(controller.canRedo());

        connection.client.history = List.of();
        controller.resume("ses_43");
        assertFalse(controller.canUndo());
    }

    @Test
    public void startNewSessionResetsUndoRedoEnablement() {
        controller.send(msg("hello"));
        assertTrue(controller.canUndo());

        controller.startNewSession();

        assertFalse(controller.canUndo());
        assertFalse(controller.canRedo());
        boolean[] state = host.undoRedoStates.get(host.undoRedoStates.size() - 1);
        assertFalse(state[0]);
        assertFalse(state[1]);
    }

    // ---------- live deltas ----------

    @Test
    public void textDeltasForThisSessionRenderLive() {
        controller.subscribe();
        assertEquals(2, connection.listeners.size()); // delta listener + permission adapter

        // before any session exists, deltas are ignored
        connection.fire(deltaEvent("{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"m\",\"delta\":\"x\"}"));
        assertTrue(renderer.deltas.isEmpty());

        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi")); // creates ses_1, completes
        // deltas render while a send is in flight (hold the job so the send
        // cannot settle first — a delta after it settled is a late orphan)
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi again"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_1\",\"delta\":\"chunk\"}"));
        assertEquals(List.of("start:msg_1"), renderer.assistants.stream()
                .filter(a -> a.startsWith("start:")).toList());
        assertEquals(List.of("msg_1:chunk"), renderer.deltas);

        // a reasoning delta streams through the thinking channel, not the body
        connection.fire(reasoningEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_1\",\"delta\":\"ponder\"}"));
        assertEquals(1, renderer.deltas.size());
        assertEquals(List.of("msg_1:ponder"), renderer.reasonings);

        // other session / empty delta / foreign event type / missing id: all ignored
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_other\",\"assistantMessageID\":\"msg_1\",\"delta\":\"x\"}"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_1\",\"delta\":\"\"}"));
        // v2 has its own events per channel, so a tool event is simply not a
        // delta type (v1 filtered on a "field":"tool" member instead)
        connection.fire(typedEvent("session.tool.called",
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_1\",\"id\":\"call_1\"}"));
        connection.fire(deltaEvent("{\"sessionID\":\"ses_1\",\"delta\":\"x\"}"));
        assertEquals(1, renderer.deltas.size());
        assertEquals(1, renderer.reasonings.size());
        host.queuedBackground.forEach(Runnable::run); // settle the held send

        controller.dispose();
        assertTrue(connection.listeners.isEmpty());
    }

    @Test
    public void reasoningDeltasStreamWhileSendingAndAreIgnoredOtherwise() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "hi again"));
        connection.fire(reasoningEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_r\",\"delta\":\"ponder\"}"));
        connection.fire(reasoningEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_r\",\"delta\":\"ing\"}"));

        assertEquals(List.of("msg_r:ponder", "msg_r:ing"), renderer.reasonings);
        assertTrue("reasoning must not leak into the text body, got: " + renderer.deltas,
                renderer.deltas.isEmpty());
        assertTrue(renderer.assistants.contains("start:msg_r"));
        host.queuedBackground.forEach(Runnable::run); // settle the held send

        // once the send settled, a reasoning delta is a late orphan: no bubble
        connection.fire(reasoningEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_late\",\"delta\":\"x\"}"));
        assertFalse("late reasoning delta must not create a bubble, got: " + renderer.assistants,
                renderer.assistants.contains("start:msg_late"));
        assertTrue("late reasoning delta must not stream, got: " + renderer.reasonings,
                renderer.reasonings.stream().noneMatch(r -> r.startsWith("msg_late:")));
    }

    // ---------- resume / new session ----------

    @Test
    public void resumeWhileSendingIsRefused() {
        // review N1: the running job would settle the OLD reply into the NEW
        // transcript - same refusal as startNewSession
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "in flight"));
        assertTrue(controller.isSending());

        connection.client.history = List.of(entry("a1", "assistant", "other session"));
        controller.resume("ses_42");

        assertNull("the session must not switch under a running send (none created yet)",
                controller.sessionId());
        assertTrue(renderer.histories.isEmpty());
        assertTrue(renderer.notices.stream().anyMatch(n -> n.contains("still streaming")));

        host.holdBackground = false;
        host.queuedBackground.forEach(Runnable::run);
    }

    @Test
    public void resumeRendersHistoryAndNotice() {
        connection.client.history = List.of(
                entry("u1", "user", "question"),
                entry("a1", "assistant", "answer"));
        controller.resume("ses_42");

        assertEquals("ses_42", controller.sessionId());
        assertEquals(1, renderer.histories.size());
        List<Map<String, Object>> rows = renderer.histories.get(0);
        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).get("role"));
        assertEquals("question", rows.get(0).get("text"));
        assertEquals("a1", rows.get(1).get("id"));
        assertEquals("prov/mod", rows.get(1).get("meta"));
        assertEquals(List.of("Resumed session ses_42 - continuing the conversation."),
                renderer.notices);
        assertTrue(host.jobs.contains("Loading chat history ses_42"));
    }

    @Test
    public void resumePassesToolPartsToTheRenderer() {
        connection.client.history = List.of(
                entry("u1", "user", "question"),
                new ChatEntry(
                        new ChatMessageInfo("a1", "ses_1", "assistant", null, null, null, null,
                                null, null, "prov", "mod", null, null, 1_700_000_000_000L),
                        List.of(new ChatPart("text", "answer", null, null),
                                new ChatPart("tool", null, "read", new ChatPart.ToolState("completed")),
                                new ChatPart("tool", null, "cmake_build", new ChatPart.ToolState("error")))));
        controller.resume("ses_42");

        List<Map<String, Object>> rows = renderer.histories.get(0);
        assertEquals(List.of(), rows.get(0).get("tools")); // user rows carry an empty list
        @SuppressWarnings("unchecked")
        List<ChatSessionController.ToolLine> tools =
                (List<ChatSessionController.ToolLine>) rows.get(1).get("tools");
        assertEquals(2, tools.size());
        assertEquals("read", tools.get(0).name());
        assertEquals("completed", tools.get(0).state());
        assertEquals("cmake_build", tools.get(1).name());
        assertEquals("error", tools.get(1).state());
    }

    @Test
    public void resumeFailureUpdatesStatus() {
        connection.client.historyFailure = new OpencodeException("gone");
        controller.resume("ses_42");

        assertEquals(List.of("Error loading history: gone"), host.statuses);
        assertTrue(renderer.histories.isEmpty());
    }

    @Test
    public void newSessionClearsTranscriptAndUsesAFreshSessionOnTheNextSend() {
        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "one"));
        assertEquals("ses_1", controller.sessionId());

        controller.startNewSession();
        assertNull(controller.sessionId());
        assertEquals(1, renderer.clears);
        assertTrue(host.statuses.contains("New session (created on first message)"));
        assertTrue(renderer.notices.contains(
                "Fresh session - your next message starts a new conversation."));

        controller.send(new ChatSessionController.OutgoingMessage(
                null, "prov", "m1", null, null, "two"));
        assertEquals("ses_2", controller.sessionId());
        assertEquals(2, connection.client.createdSessions.size());
    }

    // ---------- selectors ----------

    @Test
    public void selectorDataDeliversAgentsProvidersAndResolvedDefault() {
        Agent agent = new Agent("build", "Build", "d", "primary", Boolean.FALSE,
                null, null, null, null, null);
        Model model = new Model("m1", "m1", "prov", null, null, null, null, null,
                null, null, null, null, null, null);
        Provider provider = new Provider("prov", "Prov", null, null, null, null,
                Map.of("m1", model));
        connection.client.agents = List.of(agent);
        connection.client.providers = new ProviderList(List.of(provider), Map.of());

        final List<Agent> gotAgents = new ArrayList<>();
        final List<String[]> gotDefault = new ArrayList<>();
        controller.loadSelectorData(new ChatSessionController.SelectorDataListener() {
            @Override
            public void loaded(List<Agent> agents, ProviderList providers, String[] defaultModel) {
                gotAgents.addAll(agents);
                gotDefault.add(defaultModel);
            }

            @Override
            public void failed(OpencodeException error) {
                throw new AssertionError(error);
            }
        });

        assertEquals(1, gotAgents.size());
        assertSame(agent, gotAgents.get(0));
        assertEquals(1, gotDefault.size());
        assertEquals("prov", gotDefault.get(0)[0]);
        assertEquals("m1", gotDefault.get(0)[1]);
        assertTrue(host.jobs.contains("Loading opencode agents and models"));
    }

    @Test
    public void selectorDataFailureIsForwarded() {
        connection.client.agentsFailure = new OpencodeException("down");
        final List<OpencodeException> failures = new ArrayList<>();
        controller.loadSelectorData(new ChatSessionController.SelectorDataListener() {
            @Override
            public void loaded(List<Agent> agents, ProviderList providers, String[] defaultModel) {
                throw new AssertionError("expected failure");
            }

            @Override
            public void failed(OpencodeException error) {
                failures.add(error);
            }
        });

        assertEquals(1, failures.size());
        assertEquals("down", failures.get(0).getMessage());
    }

    // ---------- helpers / fakes ----------

    // ---------- streaming cursor lifecycle ----------

    @Test
    public void finalRenderTargetsTheStreamedBubbleEvenWhenIdsDiffer() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "again"));
        // deltas stream into msg_stream while the POST is still in flight; the
        // reply carries a DIFFERENT id — the final render must hit the streamed
        // bubble or its blinking cursor never stops
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"par\"}"));
        connection.client.reply = entry("msg_reply", "assistant", "done");
        host.queuedBackground.forEach(Runnable::run);

        assertTrue("final render must target the streamed mid, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.startsWith("final:msg_stream:")));
        assertTrue("stream stop expected after the send settles, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_stream")));
    }

    @Test
    public void failedSendStopsTheStreamingCursor() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "again"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"par\"}"));
        connection.client.sendFailure = new OpencodeException("boom");
        host.queuedBackground.forEach(Runnable::run);

        assertTrue(renderer.notices.stream().anyMatch(n -> n.startsWith("⚠ Send failed")));
        assertTrue("cursor stop must fire even on failure, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_stream")));
    }

    @Test
    public void abortStopsTheStreamingCursorImmediately() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "again"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"par\"}"));

        controller.abort();

        assertTrue("abort must stop the cursor, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_stream")));
        assertTrue(renderer.notices.contains("⏹ Aborted by user."));
        host.queuedBackground.clear(); // never completes; nothing more to assert
    }

    @Test
    public void toolRoundSecondMessageStopsCursorAndFinalRenderTargetsLastBubble() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "again"));
        // tool round: the assistant streams TWO messages (think -> tool -> answer);
        // both bubbles must lose their cursor, the reply lands in the LAST one
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_a\",\"delta\":\"thinking\"}"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_b\",\"delta\":\"answer\"}"));
        connection.client.reply = entry("msg_reply", "assistant", "done");
        host.queuedBackground.forEach(Runnable::run);

        assertTrue("final render must target the last streamed mid, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.startsWith("final:msg_b:done")));
        assertTrue("first streamed bubble must also be stopped, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_a")));
        assertTrue(renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_b")));
    }

    @Test
    public void emptyReplyKeepsStreamedBubblesAndStopsCursors() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        host.holdBackground = true;
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "again"));
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_stream\",\"delta\":\"partial table\"}"));
        // observed with tool runs: the POST reply carries no authoritative text;
        // wiping the bubble would lose the streamed answer, so it must be kept
        // (the page finalizes the raw stream as markdown on cursor stop)
        connection.client.reply = entry("msg_reply", "assistant", "");
        host.queuedBackground.forEach(Runnable::run);

        assertTrue("cursor stop expected, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("stop:msg_stream")));
        assertFalse("empty reply must not wipe the streamed bubble, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.startsWith("final:msg_stream")));
    }

    @Test
    public void lateDeltaAfterSendSettledIsIgnored() {
        controller.subscribe();
        controller.send(new ChatSessionController.OutgoingMessage(
                "build", "prov", "m1", null, null, "hello")); // creates ses_1, completes
        // a late SSE event for an unknown mid must not spawn an orphan bubble
        // (nobody would ever stop its blinking cursor)
        connection.fire(deltaEvent(
                "{\"sessionID\":\"ses_1\",\"assistantMessageID\":\"msg_late\",\"delta\":\"x\"}"));

        assertFalse("late delta must not create a bubble, got: " + renderer.assistants,
                renderer.assistants.stream().anyMatch(a -> a.equals("start:msg_late")));
        assertTrue("late delta must not stream text, got: " + renderer.deltas,
                renderer.deltas.stream().noneMatch(d -> d.startsWith("msg_late:")));
    }

    /**
     * A v2 streaming delta. The channel is the EVENT NAME
     * ({@code session.text.delta} vs {@code session.reasoning.delta}), and
     * the payload is flat: {@code {sessionID, assistantMessageID, ordinal,
     * delta}} — v1 packed both into one {@code message.part.delta} with a
     * {@code field} discriminator and a {@code messageID}.
     */
    private static OpencodeEvent deltaEvent(String propertiesJson) {
        return typedEvent("session.text.delta", propertiesJson);
    }

    /** A v2 reasoning delta (the thinking channel). */
    private static OpencodeEvent reasoningEvent(String propertiesJson) {
        return typedEvent("session.reasoning.delta", propertiesJson);
    }

    private static OpencodeEvent typedEvent(String type, String propertiesJson) {
        JsonObject properties = new Gson().fromJson(propertiesJson, JsonObject.class);
        return new OpencodeEvent(type, properties);
    }

    private static ChatEntry entry(String id, String role, String text) {
        // trailing stamp: v2's time.completed (14th ChatMessageInfo component)
        ChatMessageInfo info = new ChatMessageInfo(id, "ses_1", role, null, null, null,
                null, null, null, "prov", "mod", null, null, 1_700_000_000_000L);
        return new ChatEntry(info, List.of(new ChatPart("text", text, null, null)));
    }

    private static final class RecordingRenderer implements ChatSessionController.Renderer {
        final List<String> users = new ArrayList<>();
        final List<String> assistants = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        final List<String> reasonings = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final List<List<Map<String, Object>>> histories = new ArrayList<>();
        int clears;

        @Override
        public void appendUser(String text) {
            users.add(text);
        }

        @Override
        public void startAssistant(String messageId) {
            assistants.add("start:" + messageId);
        }

        @Override
        public void appendDelta(String messageId, String text) {
            deltas.add(messageId + ":" + text);
        }

        @Override
        public void appendReasoningDelta(String messageId, String text) {
            reasonings.add(messageId + ":" + text);
        }

        @Override
        public void setAssistantText(String messageId, String text, String reasoning, String meta,
                List<ChatSessionController.ToolLine> tools) {
            StringBuilder toolText = new StringBuilder();
            for (ChatSessionController.ToolLine tool : tools) {
                if (toolText.length() > 0) {
                    toolText.append(",");
                }
                toolText.append(tool.name()).append("/").append(tool.state());
            }
            assistants.add("final:" + messageId + ":" + text + "|" + reasoning + "|" + meta
                    + "|" + toolText);
        }

        @Override
        public void stopStream(String messageId) {
            assistants.add("stop:" + messageId);
        }

        @Override
        public void setMessages(List<Map<String, Object>> rows) {
            histories.add(rows);
        }

        @Override
        public void notice(String text) {
            notices.add(text);
        }

        @Override
        public void clear() {
            clears++;
        }
    }

    private static final class FakeHost implements ChatSessionController.Host {
        final List<String> infos = new ArrayList<>();
        final List<String> jobs = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<Boolean> sendingStates = new ArrayList<>();
        /** One entry per fork completion: {@code [forkId, fromId, draft]}. */
        final List<String[]> forks = new ArrayList<>();
        /** One entry per undo/redo enablement change: {@code [canUndo, canRedo]}. */
        final List<boolean[]> undoRedoStates = new ArrayList<>();
        final List<Runnable> queuedBackground = new ArrayList<>();
        int queueChanges;
        boolean holdBackground;

        @Override
        public void runInBackground(String jobName, Runnable task) {
            jobs.add(jobName);
            if (holdBackground) {
                queuedBackground.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public void runOnUi(Runnable task) {
            task.run();
        }

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            infos.add("ERROR " + message);
        }

        @Override
        public void statusChanged(String description) {
            statuses.add(description);
        }

        @Override
        public void sendingChanged(boolean sending) {
            sendingStates.add(sending);
        }

        @Override
        public void forked(String forkSessionId, String fromSessionId, String draftPrompt) {
            forks.add(new String[] { forkSessionId, fromSessionId, draftPrompt });
        }

        @Override
        public void queueChanged() {
            queueChanges++;
        }

        @Override
        public void undoRedoChanged(boolean canUndo, boolean canRedo) {
            undoRedoStates.add(new boolean[] { canUndo, canRedo });
        }
    }

    private static final class FakeConnection implements ChatServerConnection {
        final FakeClient client = new FakeClient();
        final List<OpencodeEventListener> listeners = new ArrayList<>();
        String workingDirectory;

        @Override
        public OpencodeClient getClient() {
            return client;
        }

        @Override
        public String workingDirectory() {
            return workingDirectory;
        }

        @Override
        public void addEventListener(OpencodeEventListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeEventListener(OpencodeEventListener listener) {
            listeners.remove(listener);
        }

        void fire(OpencodeEvent event) {
            for (OpencodeEventListener listener : List.copyOf(listeners)) {
                listener.onEvent(event);
            }
        }
    }

    private static final class FakeClient implements OpencodeClient {
        record CommandCall(String sessionId, String command, List<String> arguments) {
        }

        record ForkCall(String sessionId, String messageId) {
        }

        record RevertCall(String sessionId, String messageId) {
        }

        final List<ChatRequest> requests = new ArrayList<>();
        final List<String> createdSessions = new ArrayList<>();
        final List<Path> createdSessionDirs = new ArrayList<>();
        final List<String> abortCalls = new ArrayList<>();
        final List<CommandCall> commandCalls = new ArrayList<>();
        final List<ForkCall> forkCalls = new ArrayList<>();
        final List<RevertCall> revertCalls = new ArrayList<>();
        final List<String> unrevertCalls = new ArrayList<>();
        int sessionCounter;
        int forkCounter;
        List<Agent> agents = List.of();
        OpencodeException agentsFailure;
        ProviderList providers = new ProviderList(List.of(), Map.of());
        ChatEntry reply = entry("msg_1", "assistant", "done");
        OpencodeException sendFailure;
        OpencodeException commandFailure;
        List<ChatEntry> history = List.of();
        OpencodeException historyFailure;
        OpencodeException abortFailure;
        OpencodeException forkFailure;
        /** Non-null: returned as the fork session; null: a fresh ses_forkN id. */
        Session forkResult;
        Map<String, SessionStatus> sessionStatuses = Map.of();
        OpencodeException statusFailure;
        int busyPolls; // > 0: report ses_1 busy for this many calls, then idle
        int configCalls;
        int providersCalls;
        boolean revertResult = true;
        boolean unrevertResult = true;
        OpencodeException revertFailure;
        OpencodeException unrevertFailure;
        /** Simulates the server dropping restored/re-reverted messages from GET /message. */
        Runnable onRevert;
        Runnable onUnrevert;
        VcsInfo vcs = new VcsInfo("main", "git@github.com:o/r.git");

        @Override
        public HealthStatus getHealth() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Agent> getAgents() throws OpencodeException {
            if (agentsFailure != null) {
                throw agentsFailure;
            }
            return agents;
        }

        @Override
        public ProviderList getProviders() {
            providersCalls++;
            return providers;
        }

        @Override
        public ConfigInfo getConfig() {
            configCalls++;
            return null;
        }

        @Override
        public List<Session> getSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
            if (statusFailure != null) {
                throw statusFailure;
            }
            if (busyPolls > 0) {
                busyPolls--;
                return Map.of("ses_1", new SessionStatus("busy"));
            }
            return sessionStatuses;
        }

        @Override
        public Session createSession(String title, Path directory) {
            createdSessions.add(title);
            createdSessionDirs.add(directory);
            sessionCounter++;
            return new Session("ses_" + sessionCounter, null, title, null, null, null, null, null, null, null, null);
        }

        @Override
        public void registerMcp(String name, McpServerConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
            if (historyFailure != null) {
                throw historyFailure;
            }
            return history;
        }

        @Override
        public ChatEntry sendMessage(ChatRequest request) throws OpencodeException {
            requests.add(request);
            if (sendFailure != null) {
                throw sendFailure;
            }
            return reply;
        }

        @Override
        public void abortSession(String sessionId) throws OpencodeException {
            abortCalls.add(sessionId);
            if (abortFailure != null) {
                throw abortFailure;
            }
        }

        @Override
        public Session forkSession(String sessionId, String messageId) throws OpencodeException {
            forkCalls.add(new ForkCall(sessionId, messageId));
            if (forkFailure != null) {
                throw forkFailure;
            }
            if (forkResult != null) {
                return forkResult;
            }
            forkCounter++;
            return new Session("ses_fork" + forkCounter, null, "Fork", null, null, null, null, null, null, null, null);
        }

        @Override
        public ChatEntry runCommand(String sessionId, String command, List<String> arguments)
                throws OpencodeException {
            commandCalls.add(new CommandCall(sessionId, command, arguments));
            if (commandFailure != null) {
                throw commandFailure;
            }
            return reply;
        }

        @Override
        public boolean revertMessage(String sessionId, String messageId)
                throws OpencodeException {
            revertCalls.add(new RevertCall(sessionId, messageId));
            if (revertFailure != null) {
                throw revertFailure;
            }
            if (onRevert != null) {
                onRevert.run();
            }
            return revertResult;
        }

        @Override
        public boolean unrevertSession(String sessionId) throws OpencodeException {
            unrevertCalls.add(sessionId);
            if (unrevertFailure != null) {
                throw unrevertFailure;
            }
            if (onUnrevert != null) {
                onUnrevert.run();
            }
            return unrevertResult;
        }

        @Override
        public VcsInfo getVcsInfo() {
            return vcs;
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra) {
            // not needed here
        }
    }
}
