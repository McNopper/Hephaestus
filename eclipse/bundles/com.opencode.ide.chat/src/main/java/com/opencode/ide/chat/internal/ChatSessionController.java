package com.opencode.ide.chat.internal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.chat.ChatPermissionAdapter;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.DefaultModels;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Per-view chat session logic (SWT-free): creates and resumes sessions, sends
 * messages through the opencode client, turns {@code session.text.delta} and
 * {@code session.reasoning.delta} events for the current session into live
 * bubble updates, aborts in-flight
 * replies ({@link #abort()}), forks the session at any history message or
 * from a queued request ({@link #forkAt}/{@link #forkQueued}), manages the
 * server-side session inbox of parked prompts ({@link #refreshInbox}/
 * {@link #steerInbox}/{@link #deliverInboxNext}/{@link #cancelInbox}),
 * surfaces and answers the session's open question forms (U-014:
 * {@link #refreshForms} polling while a send is in flight, on resume and
 * after each settle; {@link #replyForm}/{@link #cancelForm}), undoes
 * and redoes exchanges through the server's revert/unrevert endpoints
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

    /**
     * One built-in slash command this chat handles locally (opencode TUI
     * parity): its {@code name} (without the leading slash) and a one-line
     * description. {@link #BUILT_IN_COMMANDS} is the single registry both
     * {@link #builtInSlashCommand(String)} and the {@code /help} notice
     * derive from, so recognition and documentation can never drift.
     */
    public record BuiltInCommand(String name, String description) {
    }

    /**
     * The built-in slash commands handled locally, never sent to the server
     * as custom commands (opencode TUI parity: the TUI handles these itself).
     */
    public static final List<BuiltInCommand> BUILT_IN_COMMANDS = List.of(
            new BuiltInCommand("help", "list the built-in slash commands"),
            new BuiltInCommand("init", "guided setup: create or update AGENTS.md for this repository"),
            new BuiltInCommand("thinking", "toggle the visibility of thinking/reasoning blocks"),
            new BuiltInCommand("undo", "undo the last exchange (message and replies; files from the git snapshot)"),
            new BuiltInCommand("redo", "redo the undone exchange"),
            new BuiltInCommand("share", "share the session as a link (not implemented for v2 sessions yet)"),
            new BuiltInCommand("unshare", "remove the shared session link (not implemented for v2 sessions yet)"));

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

        /**
         * Replaces the composer queue row (the session inbox's parked
         * prompts, T-005 management surface); an empty list hides the row.
         * Default no-op so renderers (and test fakes) without the row stay
         * compiling.
         */
        default void setInboxItems(List<InboxEntry> items) {
        }

        /**
         * Replaces the open question forms (U-014): one answerable card per
         * form the server lists for the current session (fields passed
         * through verbatim - the page renders the field union leniently); an
         * empty list hides the area. Default no-op so renderers (and test
         * fakes) without forms stay compiling.
         */
        default void setForms(List<FormCard> forms) {
        }

        /**
         * Pushes the reasoning-visibility preference into the page
         * ({@code /thinking} and the toolbar toggle; re-applied after every
         * history render so the preference survives across messages). Default
         * no-op so renderers without the concept stay compiling.
         */
        default void setReasoningVisible(boolean visible) {
        }
    }

    /** Ambient services the controller needs from its host view. */
    public interface Host {

        /** Runs {@code task} in a named background job (never on the UI thread). */
        void runInBackground(String jobName, Runnable task);

        /** Dispatches {@code task} to the UI thread. */
        void runOnUi(Runnable task);

        /**
         * Schedules {@code task} on the UI thread after {@code delayMillis}
         * (the U-014 form-poll tick; the controller re-arms it while a
         * submission is in flight). Default no-op so hosts (and existing test
         * fakes) without a timer stay compiling - the poll then simply does
         * not re-arm, the deterministic refreshes keep working.
         */
        default void schedulePoll(long delayMillis, Runnable task) {
        }

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

        /**
         * The current session id changed (session created on first send,
         * resumed, or dropped by New Session - {@code null} then). U-039
         * continuity: the host persists the id so the next workspace start
         * can offer to restore the conversation. Default no-op so hosts
         * (and existing test fakes) without persistence stay compiling.
         */
        default void sessionChanged(String sessionId) {
        }

        /**
         * The reasoning-visibility preference changed through the
         * {@code /thinking} command (the controller already pushed it to the
         * renderer); the host persists it and syncs its own toggle control.
         * Default no-op so existing test fakes stay compiling.
         */
        default void reasoningVisibilityChanged(boolean visible) {
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

    /**
     * One prompt parked in the session's server-side inbox (the v2 Alt+Enter
     * queue, fed by {@link #send(OutgoingMessage, String)} with {@code
     * "queue"}): the server message id - the path parameter of the
     * PATCH/DELETE manage verbs - plus the display text for the queue row.
     */
    public record InboxEntry(String id, String text) {
    }

    /**
     * One open question form of the current session (U-014): the form id -
     * the path parameter of the reply/cancel verbs - the display title, and
     * the fields passed through VERBATIM (the service owns the field schema,
     * a union of String/Number/Integer/Boolean/Multiselect/External field
     * objects; the page renders whatever discriminator/label/options keys
     * they carry).
     */
    public record FormCard(String id, String title, List<Map<String, Object>> fields) {

        /** Compact constructor: {@code null} fields become empty (nothing renderable). */
        public FormCard {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * One {@code @}-alias reference root proposal (the U-047 remainder of
     * U-012): the catalog id plus the name a pick inserts into the composer
     * as {@code @<name> } plain text.
     */
    public record ReferenceProposal(String id, String name) {
    }

    /**
     * The {@code @}-dropdown's answer (U-047): alias reference roots ABOVE
     * the file matches - the two groups the page renders (and the host's
     * keyboard selection spans) as one merged list, aliases first.
     */
    public record ReferenceProposals(List<ReferenceProposal> aliases, List<String> paths) {

        /** Compact constructor: {@code null} groups become empty. */
        public ReferenceProposals {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
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
     * {@code /session/active} kept saying busy).
     */
    private volatile long lastStreamActivityMillis;
    /**
     * Wall-clock when the current send began (0 = none). Anchors the late
     * settle: a tail assistant entry provably OLDER than the send is stale
     * history from a previous turn, never this turn's reply.
     */
    private volatile long sendStartedAtMillis;
    /** Skew allowance for the send-time anchor (same-machine clocks, generous). */
    private static final Duration STALE_REPLY_SKEW = Duration.ofMinutes(1);
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
    /**
     * Whether thinking/reasoning blocks are visible ({@code /thinking} and
     * the toolbar toggle flip it); pushed to the renderer on change and
     * re-applied after every history render so the preference survives
     * across messages.
     */
    private volatile boolean reasoningVisible = true;
    /** One-shot title for the NEXT created session ({@code /init}); null = default. */
    private volatile String pendingSessionTitle;
    /** Proposal cap of the {@code @}-file autocomplete (U-012). */
    private static final int MAX_FILE_PROPOSALS = 8;
    /**
     * Delay between form-poll ticks while a submission is in flight (U-014):
     * a run blocked on an unanswered form never settles its POST, so the
     * forms are re-read on a timer until it does. Env:
     * {@code CHAT_FORM_POLL_MS} (2 s) - same env-knob discipline as the
     * late-reply budgets; the delay itself is invisible to the tests (their
     * {@link Host#schedulePoll} fake runs the tick on demand).
     */
    private static final long FORM_POLL_DELAY_MS =
            envDuration("CHAT_FORM_POLL_MS", Duration.ofSeconds(2)).toMillis();
    /**
     * Form ids already noticed in the transcript (U-014 AC3): the pending
     * state notice fires ONCE per form, not on every poll tick.
     */
    private final java.util.Set<String> noticedForms = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The {@code @}-alias reference catalog (U-047), fetched once per view
     * lifetime and cached - the dropdown re-filters locally per keystroke.
     * Volatile: written on a background job, read by later ones.
     */
    private volatile List<ReferenceProposal> referenceCatalog;

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
            // v2 split the single v1 "message.part.delta" (which carried a
            // "field" discriminator) into one event type PER CHANNEL, so the
            // channel is the event NAME now - no payload inspection needed.
            boolean textDelta = "session.text.delta".equals(event.type());
            if (!textDelta && !"session.reasoning.delta".equals(event.type())) {
                return;
            }
            // v2 payload: {sessionID, assistantMessageID, ordinal, delta} -
            // flat, and the message id is assistantMessageID (v1: messageID).
            String deltaSession = event.string("sessionID");
            String messageId = event.string("assistantMessageID");
            String delta = event.string("delta");
            if (!sid.equals(deltaSession) || messageId == null || delta == null || delta.isEmpty()) {
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
                if (textDelta) {
                    renderer.appendDelta(messageId, delta);
                } else {
                    renderer.appendReasoningDelta(messageId, delta);
                }
            });
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
        renderer.setInboxItems(List.of()); // the parked prompts belonged to the old session
        renderer.setForms(List.of()); // so did its open question forms (U-014)
        host.statusChanged("New session (created on first message)");
        renderer.notice("Fresh session - your next message starts a new conversation.");
        fireUndoRedoChanged();
        host.sessionChanged(null); // U-039: an abandoned session is not restored on restart
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
        host.sessionChanged(sid);
        host.runInBackground("Loading chat history " + sid, () -> {
            try {
                List<ChatEntry> entries = connection.getClient().getMessages(sid);
                historyHasUserMessage = lastUserMessageId(entries) != null;
                List<Map<String, Object>> rows = historyRows(entries);
                host.runOnUi(() -> {
                    renderer.setMessages(rows);
                    renderer.setReasoningVisible(reasoningVisible); // the preference survives across messages
                    renderer.notice("Resumed session " + sid + " - continuing the conversation.");
                    fireUndoRedoChanged();
                });
            } catch (OpencodeException e) {
                host.runOnUi(() -> host.statusChanged("Error loading history: " + e.getMessage()));
            }
        });
        refreshInbox(); // the resumed session may hold parked prompts (T-005)
        refreshForms(); // ... and open question forms (U-014)
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
                // scope to the connection's working directory: unscoped, the
                // shared service resolves these lists for the user's home dir
                String scopeDir = connection.workingDirectory();
                List<Agent> agents = connection.getClient().getAgents(scopeDir);
                ProviderList providers = connection.getClient().getProviders(scopeDir);
                String[] fallback = DefaultModels.resolve(connection.getClient().getConfig(scopeDir), providers);
                host.runOnUi(() -> listener.loaded(agents, providers, fallback));
            } catch (OpencodeException e) {
                host.runOnUi(() -> listener.failed(e));
            }
        });
    }

    // ---------- sending ----------

    /** Sends one prompt; renders the echo immediately and the final reply on completion. */
    public void send(OutgoingMessage message) {
        send(message, null);
    }

    /**
     * Sends with an explicit delivery (T-005 send-time parity): {@code null}
     * or {@code "steer"} sends normally (interrupting an active run), {@code
     * "queue"} parks the prompt in the session inbox (v2 Alt+Enter).
     */
    public void send(OutgoingMessage message, String delivery) {
        if (sending) {
            return;
        }
        sending = true;
        streamedMids.clear();
        sendStartedAtMillis = System.currentTimeMillis();
        try {
            host.sendingChanged(true);
            host.info("send: begin (" + message.text().length() + " chars)");
            renderer.appendUser(message.text());
            host.runInBackground("Sending opencode chat message", () -> runSendJob(message, delivery));
            // the run may block on an unanswered form mid-flight (U-014): a
            // POST that never settles must still surface the form card
            host.schedulePoll(FORM_POLL_DELAY_MS, this::pollFormsTick);
        } catch (Throwable t) {
            host.error("send failed unexpectedly", t);
            sending = false;
            host.sendingChanged(false);
        }
    }

    private void runSendJob(OutgoingMessage message, String delivery) {
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
                    String scopeDir = connection.workingDirectory();
                    fallback = DefaultModels.resolve(
                            connection.getClient().getConfig(scopeDir),
                            connection.getClient().getProviders(scopeDir));
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
            ChatEntry reply = connection.getClient().sendMessage(request, null, delivery);
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
            // the composer queue row follows the server inbox: a parked
            // prompt (delivery=queue) surfaces, a delivered one leaves
            refreshInbox();
            // the same for open question forms (U-014): a form the settled
            // run raised surfaces, an answered one leaves
            refreshForms();
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
        sendStartedAtMillis = System.currentTimeMillis();
        try {
            host.sendingChanged(true);
            host.info("send command: begin (" + command + ")");
            renderer.appendUser(echoText(selection, command, arguments));
            host.runInBackground("Running opencode command " + command,
                    () -> runCommandJob(command, arguments));
            // a command run may block on an unanswered form too (U-014)
            host.schedulePoll(FORM_POLL_DELAY_MS, this::pollFormsTick);
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
            // open question forms of the settled command run (U-014)
            refreshForms();
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

    // ---------- session inbox (T-005 management surface) ----------

    /**
     * Re-reads the session's server-side inbox and renders it as the
     * composer queue row ({@link Renderer#setInboxItems}). The row only ever
     * shows what the server holds - parked prompts appear, delivered or
     * cancelled ones disappear. Refreshed after every send settles, on
     * resume, and after each manage action below.
     */
    public void refreshInbox() {
        String sid = sessionId;
        if (sid == null) {
            return; // nothing can be parked before the first message
        }
        host.runInBackground("Refreshing session inbox " + sid, () -> {
            try {
                List<InboxEntry> entries = readInbox(sid);
                host.runOnUi(() -> renderer.setInboxItems(entries));
            } catch (OpencodeException e) {
                // older server / transient failure: the row stays as it was
                host.error("inbox refresh failed for session " + sid, e);
            }
        });
    }

    /**
     * Delivers one queued prompt NOW (v2's {@code steer} semantics): the
     * PATCH moves it out of the inbox and interrupts the active run.
     */
    public void steerInbox(String messageId) {
        updateInbox(messageId, "steer", "steer the queued prompt");
    }

    /**
     * Delivers one queued prompt AFTER the active run (v2's {@code queue}
     * semantics): the PATCH keeps it parked for delivery on run completion.
     */
    public void deliverInboxNext(String messageId) {
        updateInbox(messageId, "queue", "schedule the queued prompt");
    }

    /**
     * Cancels one queued prompt ({@code DELETE /session/:id/inbox/:msgID}).
     * All three manage verbs re-read the inbox afterwards: on failure the
     * server still lists the item, so the row keeps it and a notice says
     * what went wrong - a parked prompt is never lost silently.
     */
    public void cancelInbox(String messageId) {
        String sid = sessionId;
        if (sid == null || messageId == null || messageId.isBlank()) {
            return;
        }
        host.runInBackground("Cancelling queued prompt " + messageId, () -> {
            try {
                connection.getClient().cancelInboxItem(sid, messageId);
                host.info("inbox: " + messageId + " cancelled");
            } catch (OpencodeException e) {
                host.error("inbox cancel failed for " + messageId, e);
                host.runOnUi(() -> renderer.notice(
                        "\u26A0 Could not cancel the queued prompt: " + e.getMessage()));
            } finally {
                refreshInbox();
            }
        });
    }

    private void updateInbox(String messageId, String delivery, String what) {
        String sid = sessionId;
        if (sid == null || messageId == null || messageId.isBlank()) {
            return;
        }
        host.runInBackground("Updating queued prompt " + messageId, () -> {
            try {
                connection.getClient().updateInboxItem(sid, messageId, delivery);
                host.info("inbox: " + messageId + " delivery=" + delivery);
            } catch (OpencodeException e) {
                host.error("inbox update failed for " + messageId, e);
                host.runOnUi(() -> renderer.notice(
                        "\u26A0 Could not " + what + ": " + e.getMessage()));
            } finally {
                refreshInbox();
            }
        });
    }

    /**
     * Maps the raw server inbox JSON to renderable entries. Lenient on
     * purpose (the shape is the service's): the id is the flat {@code id}
     * or the wrapped {@code info.id}; the text is the flat {@code text} or
     * the concatenated {@code text} parts of a message-shaped item. Items
     * without a usable id are skipped - they cannot be steered or cancelled.
     */
    private List<InboxEntry> readInbox(String sid) throws OpencodeException {
        List<InboxEntry> entries = new ArrayList<>();
        for (JsonObject item : connection.getClient().listInbox(sid)) {
            if (item == null) {
                continue;
            }
            String id = inboxId(item);
            if (id == null || id.isBlank()) {
                continue;
            }
            entries.add(new InboxEntry(id, inboxText(item)));
        }
        return entries;
    }

    /** The id of an inbox item: flat {@code id}, or the wrapped {@code info.id}. */
    private static String inboxId(JsonObject item) {
        String id = jsonString(item, "id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        JsonElement info = item.get("info");
        return info != null && info.isJsonObject() ? jsonString(info.getAsJsonObject(), "id") : null;
    }

    /** The display text of an inbox item (see {@link #readInbox}). */
    private static String inboxText(JsonObject item) {
        String text = jsonString(item, "text");
        if (text != null && !text.isBlank()) {
            return text;
        }
        JsonElement parts = item.get("parts");
        if (parts != null && parts.isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement part : parts.getAsJsonArray()) {
                if (part.isJsonObject() && "text".equals(jsonString(part.getAsJsonObject(), "type"))) {
                    String chunk = jsonString(part.getAsJsonObject(), "text");
                    if (chunk != null && !chunk.isEmpty()) {
                        joined.append(chunk);
                    }
                }
            }
            if (joined.length() > 0) {
                return joined.toString();
            }
        }
        return "(queued prompt)";
    }

    /** @return the string member, or {@code null} when absent/not a string. */
    private static String jsonString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    // ---------- question prompts / forms (U-014) ----------

    /**
     * Re-reads the session's open question forms and renders them as
     * answerable cards ({@link Renderer#setForms}). Refreshed on resume,
     * after every send/command settles, after every answer/cancel below, and
     * by the in-flight poll tick ({@link #pollFormsTick}) - a run blocked on
     * an unanswered form never settles its POST, so the tick is what surfaces
     * the card mid-run. A form without a usable id is skipped (it cannot be
     * answered or cancelled); a failed read degrades silently (older server,
     * transient failure - the cards stay as they were).
     */
    public void refreshForms() {
        String sid = sessionId;
        if (sid == null) {
            return; // no session yet - nothing can have asked a question
        }
        host.runInBackground("Refreshing chat forms " + sid, () -> {
            try {
                List<FormCard> cards = formCardsOf(connection.getClient().listForms(sid));
                boolean fresh = false;
                for (FormCard card : cards) {
                    fresh |= noticedForms.add(card.id()); // one notice per form id
                }
                boolean noticed = fresh;
                host.runOnUi(() -> {
                    renderer.setForms(cards);
                    if (noticed) {
                        renderer.notice("\u2753 The session asked a question - answer the form below"
                                + " (the run waits for you).");
                    }
                });
            } catch (OpencodeException e) {
                // older server / transient failure: the cards stay as they were
                host.error("form refresh failed for session " + sid, e);
            }
        });
    }

    /**
     * Maps the raw server form maps to renderable cards. Lenient on purpose
     * (the service owns the schema): the id is required - it is the path
     * parameter of the reply/cancel verbs - the title falls back to
     * "Question", and the fields pass through VERBATIM for the page's lenient
     * field rendering.
     */
    private static List<FormCard> formCardsOf(List<Map<String, Object>> rawForms) {
        List<FormCard> cards = new ArrayList<>();
        if (rawForms == null) {
            return cards;
        }
        for (Map<String, Object> raw : rawForms) {
            if (raw == null) {
                continue;
            }
            String id = mapString(raw, "id");
            if (id == null) {
                continue; // no id - the form can never be answered or cancelled
            }
            String title = mapString(raw, "title");
            List<Map<String, Object>> fields = new ArrayList<>();
            Object rawFields = raw.get("fields");
            if (rawFields instanceof List<?> list) {
                for (Object field : list) {
                    if (field instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> fieldMap = (Map<String, Object>) map;
                        fields.add(fieldMap);
                    }
                }
            }
            cards.add(new FormCard(id, title == null ? "Question" : title, fields));
        }
        return cards;
    }

    /** @return the member as a non-blank string, or {@code null}. */
    private static String mapString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Answers one open form ({@code POST /session/:id/form/:formID/reply},
     * U-014): {@code values} echoes the card's inputs keyed by the form's own
     * field keys. Runs on a background job and re-reads the forms afterwards
     * - a failed answer keeps the card and says why (no silent hang).
     */
    public void replyForm(String formId, Map<String, Object> values) {
        String sid = sessionId;
        if (sid == null || formId == null || formId.isBlank()) {
            return;
        }
        Map<String, Object> answer = values == null ? Map.of() : values;
        host.runInBackground("Answering chat form " + formId, () -> {
            try {
                connection.getClient().replyForm(sid, formId, answer);
                host.info("form: " + formId + " answered");
            } catch (OpencodeException e) {
                host.error("form reply failed for " + formId, e);
                host.runOnUi(() -> renderer.notice("\u26A0 Could not answer the form: " + e.getMessage()));
            } finally {
                refreshForms();
            }
        });
    }

    /**
     * Cancels one open form ({@code DELETE /session/:id/form/:formID},
     * U-014) - same re-read-and-keep-on-failure semantics as the answer path.
     */
    public void cancelForm(String formId) {
        String sid = sessionId;
        if (sid == null || formId == null || formId.isBlank()) {
            return;
        }
        host.runInBackground("Cancelling chat form " + formId, () -> {
            try {
                connection.getClient().cancelForm(sid, formId);
                host.info("form: " + formId + " cancelled");
            } catch (OpencodeException e) {
                host.error("form cancel failed for " + formId, e);
                host.runOnUi(() -> renderer.notice("\u26A0 Could not cancel the form: " + e.getMessage()));
            } finally {
                refreshForms();
            }
        });
    }

    /**
     * One form-poll tick (U-014): while a submission is in flight its POST
     * may be BLOCKED on an unanswered form (the run waits for the answer), so
     * the settle path can never surface the card - the tick re-reads the
     * forms periodically instead. It re-arms itself through
     * {@link Host#schedulePoll} and stops once the run settled (the settle
     * refresh owns the final state).
     */
    private void pollFormsTick() {
        if (!sending) {
            return; // nothing in flight - the settle/resume refreshes own the state
        }
        refreshForms();
        host.schedulePoll(FORM_POLL_DELAY_MS, this::pollFormsTick);
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
        // /init's guided setup names its session (U-047 TUI parity); the
        // pending title is one-shot - consumed by THIS send whether or not a
        // session is created, so it can never leak into a later one
        String title = pendingSessionTitle;
        pendingSessionTitle = null;
        if (sid == null) {
            // scope the session to the project: v2 takes the location in the
            // POST body; without it the shared service lands the session in
            // the user's home dir (wrong agents/config/permissions)
            String dir = connection.workingDirectory();
            if (title == null || title.isBlank()) {
                title = "Eclipse Chat";
            }
            Session session = connection.getClient().createSession(title,
                    dir == null || dir.isBlank() ? null : java.nio.file.Path.of(dir));
            sid = session.id();
            sessionId = sid;
            String finalSid = sid;
            host.runOnUi(() -> {
                host.statusChanged("Session " + finalSid);
                host.sessionChanged(finalSid); // U-039 continuity
            });
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
            host.runOnUi(() -> {
                renderer.setAssistantText(mid, finalText, reasoning, meta, tools);
                renderer.setReasoningVisible(reasoningVisible); // the preference survives across messages
            });
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
                    () -> runSendJob(submission.message(), null));
        }
    }

    // ---------- late replies (POST budget exceeded) ----------

    /**
     * True when the failure is a timeout of the asynchronous prompt
     * round-trip rather than a server error: the prompt/command POST's HTTP
     * budget ({@code HttpTimeoutException}) or the reply wait's budget /
     * no-progress window ({@code OpencodeException.ReplyTimeout}, B-024 -
     * default cap 60 minutes, stall window 10 minutes). A healthy run may
     * legitimately stream far longer than the old fixed budget, and its
     * reply keeps arriving over SSE.
     */
    private static boolean isPromptTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException
                    || t instanceof OpencodeException.ReplyTimeout) {
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
     * the stuck run. Probe {@code GET /session/active} instead: busy/retry
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
            // the map lists busy sessions only (v1 since opencode 1.18.23,
            // and v2's /session/active by definition): an absent entry is
            // idle - the reply finished around the budget boundary; settle it
            // from the authoritative history
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
     * {@code /session/active} staying "busy" alone no longer kills a
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
            // anchor to THIS turn: a failed turn can leave the tail at an idle
            // marker - or, when the projection drops the queued user message
            // (observed live on provider/agent failures), at a PREVIOUS turn's
            // assistant. Never settle a provably stale assistant entry; an
            // unknown timestamp (created=0) is kept - we cannot prove it stale.
            boolean staleAssistant = last != null && !last.isUser() && last.info() != null
                    && "assistant".equals(last.info().role())
                    && last.info().time() != null && last.info().time().created() > 0
                    && last.info().time().created() < sendStartedAtMillis - STALE_REPLY_SKEW.toMillis();
            if (last == null || last.isUser() || staleAssistant) {
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
     * custom commands) plus {@code /init}, {@code /help}, {@code /thinking},
     * {@code /share} and {@code /unshare} (U-047 TUI parity). An exact match
     * against {@link #BUILT_IN_COMMANDS} wins only: {@code /undo now} or
     * {@code /undone} are ordinary input.
     *
     * @return the command name (e.g. {@code "undo"}) for an exact
     *         (case-insensitive, whitespace-trimmed) match, {@code null} for
     *         anything else
     */
    public static String builtInSlashCommand(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("/")) {
            return null;
        }
        String name = trimmed.substring(1);
        for (BuiltInCommand command : BUILT_IN_COMMANDS) {
            if (command.name().equalsIgnoreCase(name)) {
                return command.name();
            }
        }
        return null;
    }

    // ---------- built-in commands: /init, /help, /thinking, /share (U-047) ----------

    /**
     * The fixed prompt {@code /init} sends to the current session: a guided
     * AGENTS.md setup (the model inspects the repository, then creates or
     * updates the file). A canned prompt exactly like a custom command - no
     * new server verb involved.
     */
    static final String INIT_PROMPT = """
            Please set up AGENTS.md for this repository:

            1. Inspect the repository first: its structure, build system, test \
            entry points and any existing documentation (README, CONTRIBUTING, \
            existing AGENTS.md).
            2. If AGENTS.md already exists, review it against what you found and \
            update anything stale; keep what is still accurate.
            3. If it does not exist, create it covering: what the project is, how \
            to build it, how to run the tests, the code layout, and the \
            conventions a coding agent must follow in this repository.
            4. Keep it concise and factual - only commands and conventions you \
            verified against the repository files, nothing invented.""";

    /**
     * {@code /init}: sends {@link #INIT_PROMPT} to the current session (a
     * fresh one is created titled "Initialize AGENTS.md") through the normal
     * send path - the model then creates/updates AGENTS.md with its tools.
     * Refused while a reply streams (same rule as every local command).
     */
    public void runInitCommand() {
        if (sending) {
            renderer.notice("\u26A0 A reply is still streaming - abort it before running /init.");
            return;
        }
        pendingSessionTitle = "Initialize AGENTS.md";
        renderer.notice("\u2318 /init: asking the model to create or update AGENTS.md"
                + " for this repository\u2026");
        send(new OutgoingMessage(null, null, null, null, null, INIT_PROMPT));
    }

    /**
     * {@code /help}: renders the built-in slash commands with one-liners.
     * Derived from {@link #BUILT_IN_COMMANDS} - the same registry
     * {@link #builtInSlashCommand(String)} recognizes - so the list can
     * never drift from what is actually handled.
     */
    public void showHelp() {
        StringBuilder help = new StringBuilder("Built-in slash commands:");
        for (BuiltInCommand command : BUILT_IN_COMMANDS) {
            help.append("\n/").append(command.name()).append(" - ").append(command.description());
        }
        help.append("\nAnything else starting with / runs the project's custom commands"
                + " (.opencode/command/, offered by the picker as you type).");
        renderer.notice(help.toString());
    }

    // ---------- /thinking (reasoning visibility) ----------

    /** @return whether thinking/reasoning blocks are currently visible. */
    public boolean isReasoningVisible() {
        return reasoningVisible;
    }

    /**
     * Sets the reasoning-visibility preference and pushes it to the renderer
     * (the toolbar toggle's path; the controller re-applies it after every
     * history render).
     */
    public void setReasoningVisible(boolean visible) {
        reasoningVisible = visible;
        renderer.setReasoningVisible(visible);
    }

    /**
     * {@code /thinking}: flips the visibility of thinking/reasoning blocks
     * (live and history; the page hides them via a CSS class, so toggling
     * back is complete without re-rendering), pushes the new value to the
     * page and tells the host so it persists the preference and syncs its
     * toggle control.
     */
    public void toggleThinking() {
        boolean next = !reasoningVisible;
        setReasoningVisible(next);
        renderer.notice(next
                ? "\u2699 Thinking visible - reasoning blocks are shown (toggle with /thinking)."
                : "\u2699 Thinking hidden - reasoning blocks are collapsed (toggle with /thinking).");
        host.reasoningVisibilityChanged(next);
    }

    // ---------- /share + /unshare (v2 verdict: no server API) ----------

    /**
     * {@code /share} / {@code /unshare}: sharing is NOT implemented for v2
     * sessions. Investigated against opencode v2.0.19: the server OpenAPI
     * has no share route, and the TUI's own handlers are stubs that report
     * "Sharing is not implemented for V2 sessions yet" (a legacy
     * {@code share} config field and a v1 {@code share_url} storage column
     * exist, but nothing serves them). So the commands surface that verdict
     * as a notice instead of faking a link.
     *
     * @param unshare {@code true} for {@code /unshare}, {@code false} for
     *            {@code /share}
     */
    public void shareNotAvailable(boolean unshare) {
        String what = unshare ? "Unsharing" : "Sharing";
        renderer.notice("\u26A0 " + what + " is not implemented for V2 sessions yet - the opencode"
                + " v2 server API has no share endpoint (the TUI's /" + (unshare ? "unshare" : "share")
                + " reports the same), so no share link can be created or removed here.");
    }

    // ---------- @-references: files (U-012) + alias roots (U-047) ----------

    /**
     * Queries the server's file-find surface for the {@code @}-file
     * autocomplete ({@code GET /fs/find}, scoped to the connection's working
     * directory) and filters the answer: case-insensitive matches in the
     * file NAME, prefix matches ranked first, capped at
     * {@value #MAX_FILE_PROPOSALS} entries. A blank query short-circuits to
     * an empty list (nothing propose-able), a failed search degrades to
     * empty - the dropdown just closes.
     *
     * @param query the text after the {@code @} (no leading slash handling;
     *            may be empty)
     * @param callback receives the capped matches on the UI thread
     */
    public void findFiles(String query, Consumer<List<String>> callback) {
        if (query == null || query.isBlank()) {
            host.runOnUi(() -> callback.accept(List.of()));
            return;
        }
        host.runInBackground("Searching files " + query, () -> {
            List<String> result = fileMatchesNow(query);
            host.runOnUi(() -> callback.accept(result));
        });
    }

    /**
     * Queries the {@code @}-dropdown's TWO proposal groups (U-047): alias
     * reference roots ({@code GET /api/reference}, fetched once per view and
     * cached, filtered locally) ABOVE the file matches of
     * {@link #findFiles}. Either half degrades to empty on its own failure -
     * the dropdown then shows the other group; an EMPTY catalog answers
     * files only (this repo's reference catalog is currently empty, the
     * alias group simply never renders). A blank query short-circuits to
     * both-empty, exactly like the file-only autocomplete before it.
     *
     * @param query the text after the {@code @} (may be empty)
     * @param callback receives the two groups on the UI thread
     */
    public void findProposals(String query, Consumer<ReferenceProposals> callback) {
        if (query == null || query.isBlank()) {
            host.runOnUi(() -> callback.accept(new ReferenceProposals(List.of(), List.of())));
            return;
        }
        host.runInBackground("Searching references and files " + query, () -> {
            List<ReferenceProposal> aliases = referenceMatchesNow(query);
            List<String> paths = fileMatchesNow(query);
            host.runOnUi(() -> callback.accept(new ReferenceProposals(aliases, paths)));
        });
    }

    /** The scoped file search + fuzzy filter, degrading to empty on failure. */
    private List<String> fileMatchesNow(String query) {
        try {
            return filterFileMatches(
                    connection.getClient().findFiles(query, connection.workingDirectory()), query);
        } catch (OpencodeException e) {
            host.error("file search failed for @" + query, e);
            return List.of();
        }
    }

    /**
     * The alias half of the dropdown: the cached catalog (loaded on first
     * use), fuzzy-filtered by the query. A failed catalog read degrades to
     * empty AND is not cached - the next query retries, files still answer.
     */
    private List<ReferenceProposal> referenceMatchesNow(String query) {
        try {
            List<ReferenceProposal> catalog = referenceCatalog;
            if (catalog == null) {
                catalog = referencesOf(connection.getClient().listReferences());
                referenceCatalog = catalog;
            }
            return filterReferenceMatches(catalog, query);
        } catch (OpencodeException e) {
            host.error("reference catalog failed for @" + query, e);
            return List.of(); // alias roots degrade silently - files still answer
        }
    }

    /**
     * Maps the raw reference catalog ({@code GET /api/reference}, the
     * service's Reference.Info maps) to proposals, leniently: the name is
     * the display and insert value, falling back to the id; entries with
     * neither are skipped (nothing to propose or insert).
     */
    public static List<ReferenceProposal> referencesOf(List<Map<String, Object>> catalog) {
        List<ReferenceProposal> proposals = new ArrayList<>();
        if (catalog == null) {
            return proposals;
        }
        for (Map<String, Object> raw : catalog) {
            if (raw == null) {
                continue;
            }
            String id = mapString(raw, "id");
            String name = mapString(raw, "name");
            if (name == null) {
                name = id; // a nameless entry is proposed by its id
            }
            if (id == null) {
                id = name;
            }
            if (id == null) {
                continue; // neither id nor name - unusable
            }
            proposals.add(new ReferenceProposal(id, name));
        }
        return proposals;
    }

    /**
     * The fuzzy filter over the reference catalog: case-insensitive substring
     * match on the proposal's NAME (its insert value), name-prefix matches
     * ranked first, capped at {@value #MAX_FILE_PROPOSALS} - the same rules
     * {@link #filterFileMatches} applies to file names.
     */
    public static List<ReferenceProposal> filterReferenceMatches(List<ReferenceProposal> catalog,
            String query) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<ReferenceProposal> prefixed = new ArrayList<>();
        List<ReferenceProposal> contained = new ArrayList<>();
        for (ReferenceProposal proposal : catalog) {
            if (proposal == null || proposal.name() == null || proposal.name().isBlank()) {
                continue;
            }
            String name = proposal.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(needle)) {
                prefixed.add(proposal);
            } else if (name.contains(needle)) {
                contained.add(proposal);
            }
        }
        List<ReferenceProposal> matches = new ArrayList<>(prefixed.size() + contained.size());
        matches.addAll(prefixed);
        matches.addAll(contained);
        return matches.size() > MAX_FILE_PROPOSALS
                ? List.copyOf(matches.subList(0, MAX_FILE_PROPOSALS)) : List.copyOf(matches);
    }

    /**
     * The fuzzy filter over the served paths: case-insensitive substring
     * match on the file name (the segment after the last separator),
     * name-prefix matches ranked before name-suffix/substring ones, capped
     * at {@value #MAX_FILE_PROPOSALS}.
     */
    public static List<String> filterFileMatches(List<String> paths, String query) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> prefixed = new ArrayList<>();
        List<String> contained = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String name = fileNameOf(path).toLowerCase(Locale.ROOT);
            if (name.startsWith(needle)) {
                prefixed.add(path);
            } else if (name.contains(needle)) {
                contained.add(path);
            }
        }
        List<String> matches = new ArrayList<>(prefixed.size() + contained.size());
        matches.addAll(prefixed);
        matches.addAll(contained);
        return matches.size() > MAX_FILE_PROPOSALS
                ? List.copyOf(matches.subList(0, MAX_FILE_PROPOSALS)) : List.copyOf(matches);
    }

    /** The segment after the last {@code /} or {@code \} of a path. */
    private static String fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 || slash == path.length() - 1 ? path : path.substring(slash + 1);
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
            boolean reverted = connection.getClient().revertMessage(sid, lastUser);
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
            renderer.setReasoningVisible(reasoningVisible); // the preference survives across messages
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
            VcsInfo vcs = connection.getClient().getVcsInfo(connection.workingDirectory());
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
