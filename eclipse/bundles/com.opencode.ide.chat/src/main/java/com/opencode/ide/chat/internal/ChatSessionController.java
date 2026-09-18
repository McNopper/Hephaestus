package com.opencode.ide.chat.internal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.opencode.ide.chat.ChatPermissionAdapter;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.DefaultModels;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Per-view chat session logic (SWT-free): creates and resumes sessions, sends
 * messages through the opencode client, turns {@code message.part.delta}
 * events for the current session into live bubble updates, aborts in-flight
 * replies ({@link #abort()}), forks the session at any history message or
 * from a queued request ({@link #forkAt}/{@link #forkQueued}), undoes and
 * redoes exchanges through the server's revert/unrevert endpoints
 * ({@link #undoLastTurn()}/{@link #redoReverted()}), and reports everything
 * through the {@link Renderer} (the browser page) and {@link Host} (the
 * owning view) callbacks.
 */
public final class ChatSessionController {

    /**
     * One tool-call line for compact rendering: the tool name plus a coarse
     * state ({@code running}/{@code completed}/{@code error}) extracted from
     * the {@code tool} parts of a {@link ChatEntry}.
     */
    public record ToolLine(String name, String state) {
    }

    /** Rendering surface driven by the controller (implemented by {@link ChatPage}). */
    public interface Renderer {

        void appendUser(String text);

        void startAssistant(String messageId);

        void appendDelta(String messageId, String text);

        /** Appends one streamed reasoning chunk (surfaced while generating). */
        void appendReasoningDelta(String messageId, String text);

        void setAssistantText(String messageId, String text, String reasoning, String meta,
                List<ToolLine> tools);

        /** Stops the streaming cursor of a bubble (send completed/failed or aborted). */
        void stopStream(String messageId);

        void setMessages(List<Map<String, Object>> rows);

        void notice(String text);

        void clear();
    }

    /** Ambient services the controller needs from its host view. */
    public interface Host {

        /** Runs {@code task} in a named background job (never on the UI thread). */
        void runInBackground(String jobName, Runnable task);

        /** Dispatches {@code task} to the UI thread. */
        void runOnUi(Runnable task);

        /** Info-level bundle log. */
        void info(String message);

        /** Error-level bundle log. */
        void error(String message, Throwable throwable);

        /** View content description changed (e.g. current session id). */
        void statusChanged(String description);

        /** A message is in flight ({@code true}) or done ({@code false}) - send button state. */
        void sendingChanged(boolean sending);

        /**
         * A fork of the current session completed ({@link #forkAt} /
         * {@link #forkQueued}): the host switches to the fork - the original
         * session stays untouched. {@code draftPrompt} carries the queued text
         * a queued-request fork moved into the fork's input ({@code null} for
         * a plain fork-at-message).
         */
        void forked(String forkSessionId, String fromSessionId, String draftPrompt);

        /**
         * The pending queue changed outside the submit/dispatch cycle (a
         * queued submission was taken over by a fork, or re-queued after a
         * failed fork) - the pending list should re-read the controller.
         */
        void queueChanged();

        /**
         * Undo/redo enablement changed. The flags follow the server state:
         * they flip on the history the server served (a user message to
         * revert exists) and on the revert/unrevert POST results (the server
         * holds reverted messages). Default no-op so hosts without
         * undo/redo controls (and existing test fakes) stay compiling.
         */
        default void undoRedoChanged(boolean canUndo, boolean canRedo) {
        }
    }

    /** One prompt to send: the typed text plus the current agent/model/variant pick. */
    public record OutgoingMessage(
            String agent,
            String providerId,
            String modelId,
            String variant,
            String system,
            String text) {
    }

    /** Receives the agents/models fetched for the selector combos. */
    public interface SelectorDataListener {

        void loaded(List<Agent> agents, ProviderList providers, String[] defaultModel);

        void failed(OpencodeException error);
    }

    /**
     * One submission waiting for the in-flight reply to finish (opencode TUI
     * parity): the display text shown in the pending list plus the fully
     * resolved payload - exactly one of {@code command}/{@code message} is
     * non-null, and agent/model picks are captured at enqueue time so later
     * selector changes do not rewrite a queued message.
     */
    public record PendingSubmission(String display, CommandComposer.CommandSelection command,
            OutgoingMessage message) {
    }

    private final ChatServerConnection connection;
    private final Renderer renderer;
    private final Host host;

    private volatile String sessionId;
    private volatile boolean sending;
    private volatile String[] defaultModelParts;
    /**
     * Every mid that streamed a bubble during the current send, in arrival
     * order (a tool round produces several assistant messages). The last one
     * is the final-render target; all of them must get a cursor stop.
     */
    private final List<String> streamedMids = new CopyOnWriteArrayList<>();
    /**
     * Submissions waiting for the in-flight reply. Guarded by itself: the
     * enqueue decision (in {@link #submit}) and the drain decision (in
     * {@link #finishSend}) must be mutually exclusive, or a submission queued
     * in the instant the send settles could sit in the queue with nothing
     * left to dispatch it.
     */
    private final ArrayDeque<PendingSubmission> queue = new ArrayDeque<>();
    /**
     * Late-reply watcher defaults: how often to poll a session whose reply
     * outlived the POST budget, and how long to keep watching before
     * declaring it stuck - the SILENCE cap: the deadline is reset by every
     * streamed delta, so a run that keeps streaming is never stuck. Env:
     * {@code CHAT_LATE_REPLY_POLL_MS} (5 s) and
     * {@code CHAT_LATE_REPLY_CAP_MS} (30 min) - the same env-knob
     * discipline as ClientTuning/FleetTuning (review S4: no mutable public
     * statics). The env is immutable in-process, so tests inject explicit
     * budgets through the test constructor instead.
     */
    private static final Duration LATE_REPLY_POLL_DEFAULT =
            envDuration("CHAT_LATE_REPLY_POLL_MS", Duration.ofSeconds(5));
    private static final Duration LATE_REPLY_CAP_DEFAULT =
            envDuration("CHAT_LATE_REPLY_CAP_MS", Duration.ofMinutes(30));
    /**
     * How long abort() waits for the blocked reply POST to unblock before
     * force-releasing the view. Env: {@code CHAT_ABORT_SETTLE_MS} (10 s).
     */
    private static final Duration ABORT_SETTLE_DEFAULT =
            envDuration("CHAT_ABORT_SETTLE_MS", Duration.ofSeconds(10));
    private final Duration lateReplyPoll;
    private final Duration lateReplyCap;
    private final Duration abortSettle;
    /** Bumped on dispose and by every new watcher: stale watchers exit silently. */
    private volatile int watcherGeneration;
    /**
     * Wall-clock of the last streamed delta for the current session (0 =
     * none yet). The late-reply watcher treats STREAM PROGRESS as life: a
     * run that keeps streaming is never "stuck", however long it takes -
     * the cap applies to SILENCE, not to total runtime (user report
     * 2026-09-17: healthy 30-minute Kimi generations were aborted because
     * /session/status kept saying busy).
     */
    private volatile long lastStreamActivityMillis;
    private OpencodeEventListener eventListener;
    private ChatPermissionAdapter permissionAdapter;
    /**
     * Undo/redo enablement, both derived from server answers only: the last
     * history the server served carries a user message to revert, and a
     * revert POST succeeded whose messages an unrevert can restore. Never
     * guessed locally - the server is the authority (another client may have
     * reverted or sent messages in between).
     */
    private volatile boolean historyHasUserMessage;
    private volatile boolean serverHasReverted;

    public ChatSessionController(ChatServerConnection connection, Renderer renderer, Host host) {
        this(connection, renderer, host, LATE_REPLY_POLL_DEFAULT, LATE_REPLY_CAP_DEFAULT, ABORT_SETTLE_DEFAULT);
    }

    /**
     * Test seam: explicit late-reply watcher budgets (env vars cannot be
     * set from within the JVM). Public because the tests live in a separate
     * OSGi bundle - package-private access fails across class loaders.
     */
    public ChatSessionController(ChatServerConnection connection, Renderer renderer, Host host,
            Duration lateReplyPoll, Duration lateReplyCap) {
        this(connection, renderer, host, lateReplyPoll, lateReplyCap, ABORT_SETTLE_DEFAULT);
    }

    /** Full test seam incl. the abort settle budget. */
    public ChatSessionController(ChatServerConnection connection, Renderer renderer, Host host,
            Duration lateReplyPoll, Duration lateReplyCap, Duration abortSettle) {
        this.connection = connection;
        this.renderer = renderer;
        this.host = host;
        this.lateReplyPoll = lateReplyPoll == null || lateReplyPoll.isZero()
                ? LATE_REPLY_POLL_DEFAULT : lateReplyPoll;
        this.lateReplyCap = lateReplyCap == null || lateReplyCap.isZero()
                ? LATE_REPLY_CAP_DEFAULT : lateReplyCap;
        this.abortSettle = abortSettle == null || abortSettle.isZero()
                ? ABORT_SETTLE_DEFAULT : abortSettle;
    }

    private static Duration envDuration(String envVar, Duration fallback) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long millis = Long.parseLong(value.trim());
            return millis > 0 ? Duration.ofMillis(millis) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- lifecycle ----------

    /** Subscribes to the opencode event stream (deltas for this session). */
    public void subscribe() {
        eventListener = event -> {
            if (event == null || event.type() == null) {
                return;
            }
            String sid = sessionId;
            if (sid == null) {
                return;
            }
            if ("message.part.delta".equals(event.type())) {
                String partSession = event.string("sessionID");
                String messageId = event.string("messageID");
                String field = event.string("field");
                String delta = event.string("delta");
                if (!sid.equals(partSession) || messageId == null || delta == null || delta.isEmpty()) {
                    return;
                }
                boolean textPart = "text".equals(field);
                if (!textPart && !"reasoning".equals(field)) {
                    return;
                }
                // A delta for an unknown mid while nothing is in flight is a
                // late event after the send settled (or another client's
                // message): rendering it would orphan a bubble whose cursor
                // nobody ever stops. Continuations of known bubbles are fine.
                boolean known = streamedMids.contains(messageId);
                if (!sending && !known) {
                    return;
                }
                if (!known) {
                    streamedMids.add(messageId);
                }
                // stream progress = life for the late-reply watcher
                lastStreamActivityMillis = System.currentTimeMillis();
                host.runOnUi(() -> {
                    renderer.startAssistant(messageId);
                    if (textPart) {
                        renderer.appendDelta(messageId, delta);
                    } else {
                        renderer.appendReasoningDelta(messageId, delta);
                    }
                });
            }
        };
        connection.addEventListener(eventListener);
        permissionAdapter = new ChatPermissionAdapter(() -> {
            try {
                return connection.getClient();
            } catch (OpencodeException e) {
                return null;
            }
        });
        connection.addEventListener(permissionAdapter);
    }

    /** Unsubscribes from the event stream (view dispose). */
    public void dispose() {
        watcherGeneration++; // kill any late-reply watcher still polling
        if (eventListener != null) {
            try {
                connection.removeEventListener(eventListener);
            } catch (Throwable ignored) {
                // best-effort during dispose
            }
            eventListener = null;
        }
        if (permissionAdapter != null) {
            try {
                connection.removeEventListener(permissionAdapter);
            } catch (Throwable ignored) {
                // best-effort during dispose
            }
            permissionAdapter = null;
        }
    }

    /** @return true while a message is in flight (guards double sends). */
    public boolean isSending() {
        return sending;
    }

    /** @return the current session id, or {@code null} before the first message. */
    public String sessionId() {
        return sessionId;
    }

    /** Remembers the default model used when the user has not picked one. */
    public void setDefaultModel(String providerId, String modelId) {
        defaultModelParts = (providerId == null || modelId == null)
                ? null
                : new String[] { providerId, modelId };
    }

    // ---------- sessions ----------

    /**
     * Drops the current session; the next message starts a fresh one.
     * Refused while a send is in flight: the running job would settle the OLD
     * reply into the NEW, empty transcript. Abort first, then start fresh
     * (the queue then drains into the old session before the switch).
     */
    public void startNewSession() {
        if (sending) {
            renderer.notice("\u26A0 A reply is still streaming - abort it before starting a new session.");
            return;
        }
        clearQueued(); // defensive: a fresh conversation starts with an empty queue
        sessionId = null;
        historyHasUserMessage = false;
        serverHasReverted = false;
        renderer.clear();
        host.statusChanged("New session (created on first message)");
        renderer.notice("Fresh session - your next message starts a new conversation.");
        fireUndoRedoChanged();
    }

    /** Resumes {@code sid}: loads its history into the transcript. */
    public void resume(String sid) {
        if (sending) {
            // review N1: the running job would settle the OLD reply into the
            // NEW transcript - same refusal (and reason) as startNewSession
            renderer.notice("\u26A0 A reply is still streaming - abort it before resuming another session.");
            return;
        }
        sessionId = sid;
        serverHasReverted = false; // the resumed session's reverted state is unknown here
        host.runInBackground("Loading chat history " + sid, () -> {
            try {
                List<ChatEntry> entries = connection.getClient().getMessages(sid);
                historyHasUserMessage = lastUserMessageId(entries) != null;
                List<Map<String, Object>> rows = historyRows(entries);
                host.runOnUi(() -> {
                    renderer.setMessages(rows);
                    renderer.notice("Resumed session " + sid + " - continuing the conversation.");
                    fireUndoRedoChanged();
                });
            } catch (OpencodeException e) {
                host.runOnUi(() -> host.statusChanged("Error loading history: " + e.getMessage()));
            }
        });
    }

    /** Maps served history entries into the renderer's transcript rows. */
    private static List<Map<String, Object>> historyRows(List<ChatEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatEntry entry : entries) {
            String meta = (entry.info() != null) ? entry.info().modelLabel() : "";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", entry.isUser() ? "user" : "assistant");
            row.put("id", entry.info() != null && entry.info().id() != null ? entry.info().id() : "");
            row.put("text", entry.text());
            row.put("reasoning", entry.reasoning());
            row.put("meta", meta);
            row.put("tools", toolLinesOf(entry));
            rows.add(row);
        }
        return rows;
    }

    /** Compact tool-call lines of an entry ({@code tool} parts: name + state). */
    private static List<ToolLine> toolLinesOf(ChatEntry entry) {
        if (entry == null) {
            return List.of();
        }
        List<ToolLine> tools = new ArrayList<>();
        for (ChatPart part : entry.parts()) {
            if (part.isTool()) {
                tools.add(new ToolLine(
                        part.tool() != null ? part.tool() : "",
                        part.stateName() != null ? part.stateName() : ""));
            }
        }
        return tools;
    }

    // ---------- selectors ----------

    /** Fetches agents/providers plus the default model for the selector combos. */
    public void loadSelectorData(SelectorDataListener listener) {
        host.runInBackground("Loading opencode agents and models", () -> {
            try {
                List<Agent> agents = connection.getClient().getAgents();
                ProviderList providers = connection.getClient().getProviders();
                String[] fallback = DefaultModels.resolve(connection.getClient().getConfig(), providers);
                host.runOnUi(() -> listener.loaded(agents, providers, fallback));
            } catch (OpencodeException e) {
                host.runOnUi(() -> listener.failed(e));
            }
        });
    }

    // ---------- sending ----------

    /** Sends one prompt; renders the echo immediately and the final reply on completion. */
    public void send(OutgoingMessage message) {
        if (sending) {
            return;
        }
        sending = true;
        streamedMids.clear();
        try {
            host.sendingChanged(true);
            host.info("send: begin (" + message.text().length() + " chars)");
            renderer.appendUser(message.text());
            host.runInBackground("Sending opencode chat message", () -> runSendJob(message));
        } catch (Throwable t) {
            host.error("send failed unexpectedly", t);
            sending = false;
            host.sendingChanged(false);
        }
    }

    private void runSendJob(OutgoingMessage message) {
        boolean handedOff = false;
        String sid = null;
        try {
            host.info("send job: running");
            sid = ensureSession();

            String providerId = message.providerId();
            String modelId = message.modelId();
            if (providerId == null || modelId == null) {
                String[] fallback = defaultModelParts;
                if (fallback == null) {
                    fallback = DefaultModels.resolve(
                            connection.getClient().getConfig(),
                            connection.getClient().getProviders());
                }
                if (fallback == null) {
                    host.runOnUi(() -> renderer.notice("⚠ No model available on the server."));
                    return;
                }
                providerId = fallback[0];
                modelId = fallback[1];
            }

            ChatRequest request = new ChatRequest(sid, message.agent(), providerId, modelId,
                    message.variant(), message.system(), message.text());
            ChatEntry reply = connection.getClient().sendMessage(request);
            settleReply(reply);
            historyHasUserMessage = true; // the prompt is recorded server-side now
            host.runOnUi(this::fireUndoRedoChanged);
        } catch (OpencodeException e) {
            if (sid != null && isPromptTimeout(e)) {
                handedOff = recoverFromPromptTimeout(sid, "Send");
            }
            if (!handedOff) {
                String failure = e.getMessage();
                host.runOnUi(() -> renderer.notice("⚠ Send failed: " + failure));
            }
        } finally {
            if (!handedOff) {
                finishSend();
            }
        }
    }

    /**
     * Sends a picked custom slash command ({@code POST /session/:id/command}):
     * renders the echo immediately, creates the session if this is the first
     * submission, and settles the reply through the same streaming/final-render
     * path as a normal send. A no-op while another submission is in flight.
     */
    public void sendCommand(CommandComposer.CommandSelection selection) {
        if (sending || selection == null || selection.kind() != CommandComposer.Kind.COMMAND
                || selection.command() == null || selection.command().name() == null) {
            return;
        }
        String command = selection.command().name();
        List<String> arguments = selection.arguments() == null ? List.of() : selection.arguments();
        sending = true;
        streamedMids.clear();
        try {
            host.sendingChanged(true);
            host.info("send command: begin (" + command + ")");
            renderer.appendUser(echoText(selection, command, arguments));
            host.runInBackground("Running opencode command " + command,
                    () -> runCommandJob(command, arguments));
        } catch (Throwable t) {
            host.error("command failed unexpectedly", t);
            sending = false;
            host.sendingChanged(false);
        }
    }

    private void runCommandJob(String command, List<String> arguments) {
        boolean handedOff = false;
        String sid = null;
        try {
            host.info("command job: running");
            sid = ensureSession();
            ChatEntry reply = connection.getClient().runCommand(sid, command, arguments);
            settleReply(reply);
            historyHasUserMessage = true; // the command run is recorded as a user message
            host.runOnUi(this::fireUndoRedoChanged);
        } catch (OpencodeException e) {
            if (sid != null && isPromptTimeout(e)) {
                handedOff = recoverFromPromptTimeout(sid, "Command");
            }
            if (!handedOff) {
                String failure = e.getMessage();
                host.runOnUi(() -> renderer.notice("⚠ Command failed: " + failure));
            }
        } finally {
            if (!handedOff) {
                finishSend();
            }
        }
    }

    // ---------- pending queue (TUI parity) ----------

    /**
     * Submits one message or slash command: sends it immediately when no reply
     * is in flight, otherwise queues it for automatic dispatch when the
     * current reply completes - typing ahead during generation behaves like
     * the opencode TUI. Exactly one of {@code command}/{@code message} must
     * be non-null; invalid submissions are dropped (never queued).
     *
     * @return true when the submission was queued (a reply was in flight)
     */
    public boolean submit(CommandComposer.CommandSelection command, OutgoingMessage message) {
        if (!validSubmission(command, message)) {
            return false;
        }
        synchronized (queue) {
            if (sending) {
                queue.add(new PendingSubmission(displayOf(command, message), command, message));
                int size = queue.size();
                host.info("queue: submission waiting (" + size + " queued)");
                return true;
            }
            if (!queue.isEmpty()) {
                // a leftover queue (an aborted run whose release never
                // dispatched): the user's next send flushes everything,
                // oldest first (user direction 2026-09-16)
                queue.add(new PendingSubmission(displayOf(command, message), command, message));
                sending = true;
                host.sendingChanged(true);
                finishSend();
                return true;
            }
        }
        dispatchNow(command, message);
        return false;
    }

    private static boolean validSubmission(CommandComposer.CommandSelection command,
            OutgoingMessage message) {
        if (command == null && message == null) {
            return false;
        }
        if (command != null) {
            return message == null && command.kind() == CommandComposer.Kind.COMMAND
                    && command.command() != null && command.command().name() != null;
        }
        return true;
    }

    private void dispatchNow(CommandComposer.CommandSelection command, OutgoingMessage message) {
        if (command != null) {
            sendCommand(command);
        } else {
            send(message);
        }
    }

    /** Display text of a submission: the message, or a reconstructed {@code /cmd args}. */
    private static String displayOf(CommandComposer.CommandSelection command, OutgoingMessage message) {
        if (command != null) {
            return echoText(command, command.command().name(),
                    command.arguments() == null ? List.of() : command.arguments());
        }
        return message == null ? "" : message.text();
    }

    /** @return snapshot of the queued display texts, in dispatch order. */
    public List<String> queuedPrompts() {
        synchronized (queue) {
            List<String> displays = new ArrayList<>(queue.size());
            for (PendingSubmission pending : queue) {
                displays.add(pending.display());
            }
            return displays;
        }
    }

    /**
     * Removes one queued submission (the pending list's Edit/Remove actions).
     * @return the removed display text, or {@code null} for a bad index
     */
    public String removeQueuedPrompt(int index) {
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return null;
            }
            int at = 0;
            for (Iterator<PendingSubmission> it = queue.iterator(); it.hasNext();) {
                PendingSubmission pending = it.next();
                if (at++ == index) {
                    it.remove();
                    host.info("queue: submission removed (" + queue.size() + " left)");
                    return pending.display();
                }
            }
            return null;
        }
    }

    /** Drops every queued submission. @return how many were dropped */
    public int clearQueued() {
        synchronized (queue) {
            int dropped = queue.size();
            queue.clear();
            return dropped;
        }
    }

    // ---------- forking (opencode TUI parity) ----------

    /**
     * {@code POST /session/:id/fork} - forks the current session at
     * {@code messageId} (null/blank = the latest message) and hands the new
     * session to {@link Host#forked}, which switches to it; the original
     * session is untouched (a fork is a server-side copy). Safe while a reply
     * streams: the switch happens in a NEW chat window, so an in-flight reply
     * keeps rendering here undisturbed.
     */
    public void forkAt(String messageId) {
        String sid = sessionId;
        if (sid == null) {
            renderer.notice("\u26A0 Nothing to fork yet - send a message first.");
            return;
        }
        String at = (messageId == null || messageId.isBlank()) ? null : messageId;
        host.runInBackground("Forking session " + sid, () -> {
            try {
                String forkId = forkSessionAt(sid, at);
                host.info("fork: " + sid + " at " + (at == null ? "latest" : at) + " -> " + forkId);
                host.runOnUi(() -> {
                    renderer.notice("\u2442 Forked to session " + forkId + " - the original is untouched.");
                    host.forked(forkId, sid, null);
                });
            } catch (OpencodeException e) {
                host.runOnUi(() -> renderer.notice("\u26A0 Fork failed: " + e.getMessage()));
            }
        });
    }

    /**
     * Forks one QUEUED (not yet sent) submission into a new session before it
     * is dispatched - the opencode TUI's "fork a session from a queued
     * request": forks the current session at its head (the latest message)
     * and moves the queued text into the fork's input. The submission leaves
     * this view's queue immediately; if the fork POST fails it is re-queued at
     * its old position, so user input is never lost.
     */
    public void forkQueued(int index) {
        String sid = sessionId;
        if (sid == null) {
            renderer.notice("\u26A0 Nothing to fork yet - send a message first.");
            return;
        }
        PendingSubmission pending = takeQueued(index);
        if (pending == null) {
            return; // bad index / already drained: nothing to fork
        }
        host.runOnUi(host::queueChanged); // the pending list loses the row now
        host.runInBackground("Forking queued submission", () -> {
            try {
                String forkId = forkSessionAt(sid, null);
                String draft = pending.display();
                host.info("queue: submission forked into " + forkId + " (before dispatch)");
                host.runOnUi(() -> {
                    renderer.notice("\u2442 Queued prompt moved into fork " + forkId + ".");
                    host.forked(forkId, sid, draft);
                });
            } catch (OpencodeException e) {
                requeueAt(pending, index);
                host.runOnUi(host::queueChanged);
                host.runOnUi(() -> renderer.notice(
                        "\u26A0 Fork failed (prompt re-queued): " + e.getMessage()));
            }
        });
    }

    /** Posts the fork and validates the answer; never returns a blank id. */
    private String forkSessionAt(String sessionId, String messageId) throws OpencodeException {
        Session forked = connection.getClient().forkSession(sessionId, messageId);
        String forkId = (forked == null) ? null : forked.id();
        if (forkId == null || forkId.isBlank()) {
            throw new OpencodeException("server returned no fork session");
        }
        return forkId;
    }

    /** Removes and returns one queued submission ({@code null} for a bad index). */
    private PendingSubmission takeQueued(int index) {
        synchronized (queue) {
            if (index < 0 || index >= queue.size()) {
                return null;
            }
            int at = 0;
            for (Iterator<PendingSubmission> it = queue.iterator(); it.hasNext();) {
                PendingSubmission pending = it.next();
                if (at++ == index) {
                    it.remove();
                    return pending;
                }
            }
            return null;
        }
    }

    /** Puts a failed-fork submission back at (near) its old queue position. */
    private void requeueAt(PendingSubmission pending, int index) {
        synchronized (queue) {
            List<PendingSubmission> items = new ArrayList<>(queue);
            items.add(Math.min(Math.max(index, 0), items.size()), pending);
            queue.clear();
            queue.addAll(items);
        }
    }

    /** Creates the session on first use and reports its id as the view status. */
    private String ensureSession() throws OpencodeException {
        String sid = sessionId;
        if (sid == null) {
            Session session = connection.getClient().createSession("Eclipse Chat");
            sid = session.id();
            sessionId = sid;
            String finalSid = sid;
            host.runOnUi(() -> host.statusChanged("Session " + finalSid));
        }
        return sid;
    }

    /**
     * Renders the authoritative reply of a finished submission. The final
     * render MUST target a bubble the deltas streamed into — the POST response
     * id and the SSE messageID can differ across server versions, and a
     * mismatched id would orphan the streaming bubble with its blinking
     * cursor. With tool rounds there are several streamed bubbles: the reply
     * is the LAST.
     */
    private void settleReply(ChatEntry reply) {
        String lastStreamed = streamedMids.isEmpty() ? null
                : streamedMids.get(streamedMids.size() - 1);
        boolean streamedContent = !streamedMids.isEmpty();
        boolean replyEmpty = reply == null || reply.text() == null || reply.text().isEmpty();
        if (streamedContent && replyEmpty) {
            // The server returned no authoritative text (observed when the
            // run used tools): keep the streamed bubbles — the cursor stop
            // finalizes their accumulated text through the full markdown
            // pipeline on the page.
            host.info("send job: empty reply, keeping " + streamedMids.size() + " streamed bubble(s)");
        } else {
            String mid = lastStreamed != null ? lastStreamed
                    : ((reply != null && reply.info() != null) ? reply.info().id() : "assistant");
            String finalText = (reply != null) ? reply.text() : "";
            String reasoning = (reply != null) ? reply.reasoning() : "";
            String meta = metaFor(reply);
            List<ToolLine> tools = toolLinesOf(reply);
            host.runOnUi(() ->
                    renderer.setAssistantText(mid, finalText, reasoning, meta, tools));
        }
    }

    /**
     * Settles one finished submission: stops every streamed bubble's cursor
     * and, when submissions are waiting (TUI-style typing ahead), hands over
     * to the next one - or clears the in-flight flag when the queue is empty.
     */
    private void finishSend() {
        PendingSubmission next;
        synchronized (queue) {
            next = queue.poll();
            if (next == null) {
                // Flip the flag under the lock (volatile): any SSE delta
                // arriving after this sees sending == false and must not
                // spawn an orphan bubble, and any concurrent submit() sees
                // the flip and dispatches itself instead of queueing.
                sending = false;
            }
        }
        stopStreamedCursors();
        if (next != null) {
            // The next submission takes over: `sending` stays up (no
            // orphan-delta window, no Stop-control flicker), its echo lands
            // after the previous reply's cursor stops, and re-firing
            // sendingChanged(true) tells the view to refresh the pending list.
            host.info("queue: dispatching next submission (" + queueSize() + " still queued)");
            host.runOnUi(() -> {
                renderer.appendUser(next.display());
                host.sendingChanged(true);
            });
            startSubmissionJob(next);
            return;
        }
        host.runOnUi(() -> host.sendingChanged(false));
    }

    /** Stops every streamed bubble's cursor and forgets their mids. */
    private void stopStreamedCursors() {
        // every streamed bubble gets its cursor stopped (first, middle, last)
        List<String> streamed = List.copyOf(streamedMids);
        streamedMids.clear();
        for (String mid : streamed) {
            // belt and braces: even on failure/abort the cursor must stop
            host.runOnUi(() -> renderer.stopStream(mid));
        }
    }

    private int queueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** Starts the background job of a drained queued submission. */
    private void startSubmissionJob(PendingSubmission submission) {
        if (submission.command() != null) {
            String command = submission.command().command().name();
            List<String> arguments = submission.command().arguments() == null
                    ? List.of() : submission.command().arguments();
            host.runInBackground("Running opencode command " + command,
                    () -> runCommandJob(command, arguments));
        } else {
            host.runInBackground("Sending opencode chat message",
                    () -> runSendJob(submission.message()));
        }
    }

    // ---------- late replies (POST budget exceeded) ----------

    /**
     * True when the failure is the blocking prompt/command POST exceeding its
     * HTTP budget ({@code ClientTuning.PROMPT_TIMEOUT}, 5 minutes by default)
     * rather than a server error: a healthy run may legitimately stream for
     * longer than the budget, and its reply keeps arriving over SSE.
     */
    private static boolean isPromptTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recovery after the prompt/command POST exceeded its budget. The old
     * behavior (clear {@code sending}, show a failure) disabled the Stop
     * control and orphaned every later bubble - a stuck-busy session could
     * not be aborted from the UI at all, and every re-send timed out behind
     * the stuck run. Probe {@code GET /session/status} instead: busy/retry
     * hands the submission to the late-reply watcher (Stop stays armed,
     * deltas keep rendering, the reply settles from history once idle); an
     * idle session is settled from history right away.
     *
     * @return true when the late-reply path owns the submission
     */
    private boolean recoverFromPromptTimeout(String sid, String what) {
        String type = null;
        try {
            Map<String, SessionStatus> statuses = connection.getClient().getSessionStatus();
            SessionStatus status = statuses == null ? null : statuses.get(sid);
            type = status == null ? null : status.type();
        } catch (OpencodeException e) {
            host.error("late-reply status probe failed for session " + sid, e);
            return false; // server unreachable - the plain failure notice says enough
        }
        if (!"busy".equals(type) && !"retry".equals(type)) {
            // the map lists busy sessions only since opencode 1.18.23: an
            // absent entry is idle - the reply finished around the budget
            // boundary; settle it from the authoritative history
            host.info(what.toLowerCase() + " POST timed out but session " + sid
                    + " is idle - settling from history");
            finalizeLateReply(sid);
            return true;
        }
        // No user-visible notice here (user feedback 2026-09-18: the ⏳
        // banner read as a problem every long reply): a healthy long
        // generation is the NORMAL case - the streaming transcript, the
        // waiting spinner and the Stop control already say "still running".
        // The watcher settles silently; only genuine failures notice.
        host.info(what + " POST timed out but session " + sid
                + " is busy - late-reply watcher took over (transcript keeps streaming)");
        startLateReplyWatcher(sid);
        return true;
    }

    /**
     * Watches a session whose reply outlived the POST budget: polls the
     * status until it goes idle (finished - settle from history; a Stop-abort
     * lands here too) or until the SILENCE cap trips (abort server-side,
     * report, release the view). Generation-guarded: dispose() or a
     * superseding watcher kills a stale loop silently.
     *
     * <p>Progress-aware (user report 2026-09-17): every streamed delta
     * moves {@link #lastStreamActivityMillis}, and the deadline is
     * {@code lastActivity + cap} - a run that keeps streaming is never
     * stuck, however long it takes. The cap measures SILENCE (no delta for
     * the full window), which tolerates quiet tool phases up to the cap.
     * {@code /session/status} staying "busy" alone no longer kills a
     * healthy generation.</p>
     */
    private void startLateReplyWatcher(String sid) {
        int generation = ++watcherGeneration;
        lastStreamActivityMillis = System.currentTimeMillis();
        host.runInBackground("Watching late opencode reply " + sid, () -> {
            long deadline = lastStreamActivityMillis + lateReplyCap.toMillis();
            while (generation == watcherGeneration && sid.equals(sessionId)) {
                String type = null;
                boolean probed = false;
                try {
                    Map<String, SessionStatus> statuses = connection.getClient().getSessionStatus();
                    SessionStatus status = statuses == null ? null : statuses.get(sid);
                    type = status == null ? null : status.type();
                    probed = true;
                } catch (OpencodeException e) {
                    host.error("late-reply poll failed for session " + sid, e);
                    // transient probe failure: keep polling until the cap
                }
                if (probed && !"busy".equals(type) && !"retry".equals(type)) {
                    finalizeLateReply(sid);
                    return;
                }
                deadline = lastStreamActivityMillis + lateReplyCap.toMillis();
                if (System.currentTimeMillis() >= deadline) {
                    host.info("late reply for " + sid + " went silent for "
                            + lateReplyCap.toMinutes() + " minutes - aborting the stuck run");
                    try {
                        connection.getClient().abortSession(sid);
                    } catch (OpencodeException abortError) {
                        host.error("late-reply abort failed for session " + sid, abortError);
                    }
                    host.runOnUi(() -> renderer.notice("⚠ The reply went silent for "
                            + lateReplyCap.toMinutes() + " minutes - the stuck run was aborted."
                            + " Re-send your message."));
                    finishSend();
                    return;
                }
                try {
                    Thread.sleep(lateReplyPoll.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (generation == watcherGeneration) {
                // the session switched under us (resume while watching) -
                // release the view; a superseded or disposed watcher stays silent
                finishSend();
            }
        });
    }

    /**
     * Settles a timed-out submission from the authoritative history: the last
     * assistant entry is the final reply ({@link #settleReply} targets the
     * last streamed bubble exactly like the POST path).
     */
    private void finalizeLateReply(String sid) {
        try {
            List<ChatEntry> entries = connection.getClient().getMessages(sid);
            historyHasUserMessage = lastUserMessageId(entries) != null;
            ChatEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (last == null || last.isUser()) {
                host.runOnUi(() -> renderer.notice(
                        "⚠ The reply did not complete - no assistant answer was recorded."
                                + " Re-send your message."));
            } else {
                settleReply(last);
                host.runOnUi(() -> renderer.notice("Reply completed (after the POST budget)."));
            }
            host.runOnUi(this::fireUndoRedoChanged);
        } catch (OpencodeException e) {
            host.error("late-reply history fetch failed for session " + sid, e);
            host.runOnUi(() -> renderer.notice("⚠ Could not load the finished reply: "
                    + e.getMessage()));
        } finally {
            finishSend();
        }
    }

    // ---------- undo / redo (revert, opencode TUI parity) ----------

    /**
     * Recognizes the built-in slash commands this chat handles locally
     * instead of sending them to the server: {@code /undo} and {@code /redo}
     * (opencode TUI parity - the TUI handles these itself, they are not
     * custom commands). An exact match wins only: {@code /undo now} or
     * {@code /undone} are ordinary input.
     *
     * @return {@code "undo"} or {@code "redo"} for an exact (case-insensitive,
     *         whitespace-trimmed) match, {@code null} for anything else
     */
    public static String builtInSlashCommand(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if ("/undo".equalsIgnoreCase(trimmed)) {
            return "undo";
        }
        if ("/redo".equalsIgnoreCase(trimmed)) {
            return "redo";
        }
        return null;
    }

    /** @return true while a revert is possible (a session with a user message exists). */
    public boolean canUndo() {
        return sessionId != null && historyHasUserMessage;
    }

    /** @return true while the server holds reverted messages an unrevert restores. */
    public boolean canRedo() {
        return sessionId != null && serverHasReverted;
    }

    /**
     * Undoes the last exchange ({@code POST /session/:id/revert} at the
     * newest user message): the user message and every reply after it leave
     * the conversation, and the file changes the reverted turn made are
     * restored from the git snapshot the server took before it. The
     * transcript re-renders from the authoritative history and a marker
     * notice says what happened - plainly warning that file changes were
     * NOT restored when the project is not a git repo. Refused while a reply
     * is in flight (a revert during generation would race the streaming
     * transcript).
     */
    public void undoLastTurn() {
        String sid = sessionId;
        if (sid == null) {
            renderer.notice("\u26A0 Nothing to undo yet - send a message first.");
            return;
        }
        if (sending) {
            renderer.notice("\u26A0 A reply is still streaming - abort it before undoing.");
            return;
        }
        host.runInBackground("Reverting last exchange " + sid, () -> runUndoJob(sid));
    }

    private void runUndoJob(String sid) {
        try {
            List<ChatEntry> entries = connection.getClient().getMessages(sid);
            String lastUser = lastUserMessageId(entries);
            if (lastUser == null) {
                historyHasUserMessage = false; // the server says there is no user message
                host.runOnUi(() -> {
                    renderer.notice("\u26A0 Nothing to undo - this session has no user message to revert.");
                    fireUndoRedoChanged();
                });
                return;
            }
            boolean reverted = connection.getClient().revertMessage(sid, lastUser, null);
            host.info("undo: revert(" + sid + ", " + lastUser + ") -> " + reverted);
            if (!reverted) {
                historyHasUserMessage = false; // the server refused: nothing left to revert
                host.runOnUi(() -> {
                    renderer.notice("\u26A0 Nothing to undo - the server has nothing left to revert.");
                    fireUndoRedoChanged();
                });
                return;
            }
            serverHasReverted = true;
            renderAfterRevertChange(sid, undoNotice(isGitRepository()));
        } catch (OpencodeException e) {
            host.error("undo failed for session " + sid, e);
            host.runOnUi(() -> renderer.notice("\u26A0 Undo failed: " + e.getMessage()));
        }
    }

    /**
     * Redoes the undone exchange ({@code POST /session/:id/unrevert}): the
     * server restores every message it reverted for this session, the
     * transcript re-renders from the authoritative history, and redo
     * enablement drops (everything is back). Always posts when a session
     * exists - the server is the authority, so a manual {@code /redo} after
     * resuming a session reverted elsewhere still works; a {@code false}
     * answer just reports "nothing to redo".
     */
    public void redoReverted() {
        String sid = sessionId;
        if (sid == null) {
            renderer.notice("\u26A0 Nothing to redo yet - send a message first.");
            return;
        }
        if (sending) {
            renderer.notice("\u26A0 A reply is still streaming - abort it before redoing.");
            return;
        }
        host.runInBackground("Restoring reverted exchange " + sid, () -> runRedoJob(sid));
    }

    private void runRedoJob(String sid) {
        try {
            boolean restored = connection.getClient().unrevertSession(sid);
            host.info("redo: unrevert(" + sid + ") -> " + restored);
            if (!restored) {
                serverHasReverted = false;
                host.runOnUi(() -> {
                    renderer.notice("\u26A0 Nothing to redo - the server holds no reverted messages.");
                    fireUndoRedoChanged();
                });
                return;
            }
            serverHasReverted = false;
            renderAfterRevertChange(sid, "\u21B7 Redone - the reverted exchange is back.");
        } catch (OpencodeException e) {
            host.error("redo failed for session " + sid, e);
            host.runOnUi(() -> renderer.notice("\u26A0 Redo failed: " + e.getMessage()));
        }
    }

    /**
     * Re-renders the transcript from the authoritative history after a
     * revert/unrevert (the marker notice makes the state change visible) and
     * re-fires undo/redo enablement from what the server served.
     */
    private void renderAfterRevertChange(String sid, String notice) throws OpencodeException {
        List<ChatEntry> entries = connection.getClient().getMessages(sid);
        historyHasUserMessage = lastUserMessageId(entries) != null;
        List<Map<String, Object>> rows = historyRows(entries);
        host.runOnUi(() -> {
            renderer.setMessages(rows);
            renderer.notice(notice);
            fireUndoRedoChanged();
        });
    }

    /**
     * @return the undo notice, telling plainly that file changes were NOT
     *         restored when the project is not a git repo (opencode reverts
     *         files via git snapshots)
     */
    private static String undoNotice(boolean gitRepository) {
        if (gitRepository) {
            return "\u21A9 Undid the last exchange (message and replies)"
                    + " - file changes restored from the git snapshot.";
        }
        return "\u21A9 Undid the last exchange (message and replies)"
                + " - \u26A0 this project is not a git repo: file changes were NOT restored"
                + " (opencode reverts files via git snapshots).";
    }

    /**
     * {@code GET /vcs}: both fields null means "not inside a git repository"
     * (see {@link VcsInfo}). A failed probe degrades to "assume repo" - the
     * revert itself succeeded, and a wrong warning would be worse than none.
     */
    private boolean isGitRepository() {
        try {
            VcsInfo vcs = connection.getClient().getVcsInfo();
            return vcs != null && (vcs.branch() != null || vcs.repository() != null);
        } catch (OpencodeException e) {
            host.error("vcs probe failed while undoing", e);
            return true;
        }
    }

    /** The newest user message id of a served history ({@code null} when there is none). */
    private static String lastUserMessageId(List<ChatEntry> entries) {
        String lastUser = null;
        for (ChatEntry entry : entries) {
            if (entry.isUser() && entry.info() != null
                    && entry.info().id() != null && !entry.info().id().isBlank()) {
                lastUser = entry.info().id();
            }
        }
        return lastUser;
    }

    /** Notifies the host of the current undo/redo enablement (UI thread). */
    private void fireUndoRedoChanged() {
        host.undoRedoChanged(canUndo(), canRedo());
    }

    // ---------- abort ----------

    /**
     * Aborts the in-flight reply: shows an interrupted notice immediately, then
     * POSTs the abort endpoint on a background thread (never the UI thread).
     * The {@code sending} flag itself is normally cleared by the aborted send
     * job when the server unblocks its reply call; queued submissions then
     * auto-send as usual. Belt and braces for the abnormal case (user report
     * 2026-09-16: after an abort whose POST never unblocked, every later send
     * silently queued forever): a bounded settle-watch releases the view and
     * drains the queue when the job does not settle within
     * {@code CHAT_ABORT_SETTLE_MS} - the user's next send can never no-op.
     * A no-op when nothing is in flight or the session does not exist yet
     * (first message still creating it).
     */
    public void abort() {
        String sid = sessionId;
        if (!sending || sid == null) {
            return;
        }
        for (String mid : List.copyOf(streamedMids)) {
            renderer.stopStream(mid); // no interrupted bubble may keep blinking
        }
        renderer.notice("⏹ Aborted by user.");
        host.runInBackground("Aborting opencode chat " + sid, () -> {
            try {
                connection.getClient().abortSession(sid);
                host.info("abort: posted for session " + sid);
            } catch (OpencodeException e) {
                host.error("abort failed for session " + sid, e);
                host.runOnUi(() -> renderer.notice("⚠ Abort failed: " + e.getMessage()));
            }
            long deadline = System.currentTimeMillis() + abortSettle.toMillis();
            while (sending && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (sending) {
                host.info("abort: the reply call did not settle within " + abortSettle.toSeconds()
                        + "s - releasing the view (queued submissions drain)");
                host.runOnUi(() -> renderer.notice("⚠ Aborted - the reply call did not settle;"
                        + " the view was released."));
                finishSend();
            }
        });
    }

    private static String metaFor(ChatEntry reply) {
        if (reply == null || reply.info() == null) {
            return "";
        }
        return reply.info().modelLabel();
    }

    /** Echo for a command submission: the typed text, or a reconstructed {@code /cmd args}. */
    private static String echoText(CommandComposer.CommandSelection selection, String command,
            List<String> arguments) {
        if (selection.message() != null && !selection.message().isBlank()) {
            return selection.message();
        }
        return "/" + command + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
    }
}
