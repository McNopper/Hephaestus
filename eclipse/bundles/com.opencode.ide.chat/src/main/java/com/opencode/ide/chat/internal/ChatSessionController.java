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

/**
 * Per-view chat session logic (SWT-free): creates and resumes sessions, sends
 * messages through the opencode client, turns {@code message.part.delta}
 * events for the current session into live bubble updates, aborts in-flight
 * replies ({@link #abort()}), and reports everything through the
 * {@link Renderer} (the browser page) and {@link Host} (the owning view)
 * callbacks.
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
     * declaring it stuck. Env: {@code CHAT_LATE_REPLY_POLL_MS} (5 s) and
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
    private OpencodeEventListener eventListener;
    private ChatPermissionAdapter permissionAdapter;

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
        renderer.clear();
        host.statusChanged("New session (created on first message)");
        renderer.notice("Fresh session - your next message starts a new conversation.");
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
        host.runInBackground("Loading chat history " + sid, () -> {
            try {
                List<ChatEntry> entries = connection.getClient().getMessages(sid);
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
                host.runOnUi(() -> {
                    renderer.setMessages(rows);
                    renderer.notice("Resumed session " + sid + " - continuing the conversation.");
                });
            } catch (OpencodeException e) {
                host.runOnUi(() -> host.statusChanged("Error loading history: " + e.getMessage()));
            }
        });
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
        host.runOnUi(() -> renderer.notice("⏳ " + what + " exceeded the POST budget - the reply"
                + " is still running. Stop aborts it; the transcript keeps streaming."));
        startLateReplyWatcher(sid);
        return true;
    }

    /**
     * Watches a session whose reply outlived the POST budget: polls the
     * status until it goes idle (finished - settle from history; a Stop-abort
     * lands here too) or until the cap (stuck - abort server-side, report,
     * release the view). Generation-guarded: dispose() or a superseding
     * watcher kills a stale loop silently.
     */
    private void startLateReplyWatcher(String sid) {
        int generation = ++watcherGeneration;
        host.runInBackground("Watching late opencode reply " + sid, () -> {
            long deadline = System.currentTimeMillis() + lateReplyCap.toMillis();
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
                if (System.currentTimeMillis() >= deadline) {
                    host.info("late reply for " + sid + " exceeded the " + lateReplyCap.toMinutes()
                            + "-minute cap - aborting the stuck run");
                    try {
                        connection.getClient().abortSession(sid);
                    } catch (OpencodeException abortError) {
                        host.error("late-reply abort failed for session " + sid, abortError);
                    }
                    host.runOnUi(() -> renderer.notice("⚠ The reply was still busy after "
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
            ChatEntry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
            if (last == null || last.isUser()) {
                host.runOnUi(() -> renderer.notice(
                        "⚠ The reply did not complete - no assistant answer was recorded."
                                + " Re-send your message."));
            } else {
                settleReply(last);
                host.runOnUi(() -> renderer.notice("Reply completed (after the POST budget)."));
            }
        } catch (OpencodeException e) {
            host.error("late-reply history fetch failed for session " + sid, e);
            host.runOnUi(() -> renderer.notice("⚠ Could not load the finished reply: "
                    + e.getMessage()));
        } finally {
            finishSend();
        }
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
