package com.opencode.ide.ui.session;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.opencode.ide.client.DefaultModels;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;

/**
 * SWT-free controller behind the Session Details view: loads the message
 * history of one session ({@code GET /session/:id/message}) plus the session
 * list (for the title) and maps it into an immutable
 * {@link SessionDetails} snapshot the view can render.
 *
 * <p>{@link #load()} never throws: supplier/client failures (including
 * {@link com.opencode.ide.client.OpencodeException} wrapped by the supplier in
 * a runtime exception) are converted into a snapshot whose {@code errorNote}
 * carries the message. Blocking is fine here — the view wraps {@code load()}
 * in a background job via {@code ViewLoadSupport}.</p>
 */
public final class SessionDetailsController {

    /** Shown as the error note when the session has no messages at all. */
    public static final String EMPTY_NOTE = "No messages in this session yet.";

    private final String sessionId;
    private final Supplier<OpencodeClient> clientSupplier;
    /**
     * Provider/model of the last assistant message seen by {@link #load()}
     * ({@code [providerId, modelId]}); {@code null} until an assistant reply
     * carries both. Read by {@link #summarize()} so the summary lands on the
     * model the session actually used.
     */
    private volatile String[] lastAssistantModel;

    public SessionDetailsController(String sessionId, Supplier<OpencodeClient> clientSupplier) {
        this.sessionId = sessionId;
        this.clientSupplier = clientSupplier;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * @return the snapshot for this session; on any failure a snapshot with an
     *         {@code errorNote} (never {@code null}, never throws).
     */
    public SessionDetails load() {
        try {
            OpencodeClient client = clientSupplier.get();
            List<ChatEntry> messages = client.getMessages(sessionId);
            List<Session> sessions = client.getSessions();
            return build(sessions, messages);
        } catch (Exception e) {
            return new SessionDetails(sessionId, null, null, null, null, List.of(), message(e));
        }
    }

    // ---------- session lifecycle actions (fork / summarize) ----------
    //
    // v2 dropped session sharing entirely: there is no /session/:id/share path
    // on a 2.0.x server (the surviving "share" is a config setting), so the
    // controller no longer offers share()/unshare() — a lifecycle action that
    // can only ever 404 is worse than none.

    /** This session's working directory, or {@code null} (= unscoped) when unknown. */
    private String sessionDirectory(OpencodeClient client) {
        try {
            for (Session s : client.getSessions()) {
                if (s != null && sessionId.equals(s.id())) {
                    return s.directory();
                }
            }
        } catch (Exception e) {
            // an unreadable session list degrades to unscoped (global catalog)
        }
        return null;
    }

    /**
     * {@code POST /session/:id/fork} — fork this session at {@code messageId}
     * ({@code null} = at the latest message).
     *
     * @return the new session id, or the failure reason; never throws.
     */
    /**
     * Renames the session (v2 {@code PATCH /api/session/{id}} - capability
     * alignment 2026-09-25; U-002 was skipped for "no title endpoint").
     */
    public LifecycleResult rename(String title) {
        try {
            clientSupplier.get().renameSession(sessionId(), title);
            return new LifecycleResult(true, "renamed to: " + title, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2 snapshots: remember the session state at the selected message. */
    public LifecycleResult stageSnapshot(String messageId) {
        try {
            clientSupplier.get().revertMessage(sessionId(), messageId);
            return new LifecycleResult(true, "snapshot staged at " + messageId, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2 snapshots: restore the staged snapshot. */
    public LifecycleResult restoreSnapshot() {
        try {
            clientSupplier.get().commitSessionRevert(sessionId());
            return new LifecycleResult(true, "snapshot restored", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2 snapshots: discard the staged snapshot. */
    public LifecycleResult discardSnapshot() {
        try {
            clientSupplier.get().unrevertSession(sessionId());
            return new LifecycleResult(true, "snapshot discarded", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** Wave A (2026-09-25): move the session to another directory (v2 move). */
    public LifecycleResult move(String directory) {
        try {
            clientSupplier.get().moveSession(sessionId(), directory);
            return new LifecycleResult(true, "moved to " + directory, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** Wave A: switch the session's agent mid-run (v2 {@code /agent}). */
    public LifecycleResult switchAgent(String agent) {
        try {
            clientSupplier.get().switchSessionAgent(sessionId(), agent);
            return new LifecycleResult(true, "agent switched to " + agent, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** Wave A: switch the session's model mid-run (v2 {@code /model}). */
    public LifecycleResult switchModel(String model) {
        try {
            clientSupplier.get().switchSessionModel(sessionId(), model);
            return new LifecycleResult(true, "model switched to " + model, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** Wave A: compact the session's context (v2 {@code /compact}). */
    public LifecycleResult compact() {
        try {
            clientSupplier.get().compactSession(sessionId());
            return new LifecycleResult(true, "context compacted", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** Wave A: the export document as raw text (null on failure). */
    public String export() {
        try {
            return clientSupplier.get().exportSession(sessionId());
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return null;
        }
    }

    /** Wave A: the session log as raw text (null on failure). */
    public String sessionLog() {
        try {
            return clientSupplier.get().sessionLog(sessionId());
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return null;
        }
    }

    /** Wave A: session statistics from the service (empty on failure). */
    public java.util.Map<String, Object> stats() {
        try {
            return clientSupplier.get().sessionStats();
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return java.util.Map.of();
        }
    }

    /** Wave A: the session's controlled terminal (PersistentPty.ReadResult; empty on failure). */
    public java.util.Map<String, Object> terminal() {
        try {
            return clientSupplier.get().readSessionTerminal(sessionId());
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return java.util.Map.of();
        }
    }

    /** v2 forms: the session's open forms (slice 3, 2026-09-25). */
    public java.util.List<java.util.Map<String, Object>> openForms() {
        try {
            return clientSupplier.get().listForms(sessionId());
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** v2 forms: answer one with values collected from the schema-driven dialog. */
    public LifecycleResult replyForm(String formID, java.util.Map<String, Object> values) {
        try {
            clientSupplier.get().replyForm(sessionId(), formID, values);
            return new LifecycleResult(true, "form answered", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2 forms: cancel one. */
    public LifecycleResult cancelForm(String formID) {
        try {
            clientSupplier.get().cancelForm(sessionId(), formID);
            return new LifecycleResult(true, "form cancelled", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2: push the session to the background (adoption 2026-09-25, U-042). */
    public LifecycleResult sendToBackground() {
        try {
            clientSupplier.get().backgroundSession(sessionId());
            return new LifecycleResult(true, "session sent to the background", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /** v2: run a shell in the session's context (adoption 2026-09-25, U-041). */
    public LifecycleResult runShell(String command) {
        try {
            clientSupplier.get().runShell(sessionId(), null, command);
            return new LifecycleResult(true, "shell started: " + command, null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    public LifecycleResult fork(String messageId) {
        try {
            Session forked = clientSupplier.get().forkSession(sessionId, messageId);
            String forkId = (forked == null || forked.id() == null || forked.id().isBlank()) ? "?"
                    : forked.id();
            return LifecycleResult.ok(forkId);
        } catch (Exception e) {
            return LifecycleResult.failure(message(e));
        }
    }

    /**
     * Compacts the session via the client's {@code summarizeSession} (v2 maps
     * it to {@code /compact} internally). The model is
     * the one the session's last assistant message used; only when none was
     * tracked does it fetch config + providers to resolve the connection
     * default ({@link DefaultModels#resolve}).
     *
     * @return the {@code provider/model} used, or the failure reason; never throws.
     */
    public LifecycleResult summarize() {
        try {
            OpencodeClient client = clientSupplier.get();
            String[] model = pickSummarizeModel(lastAssistantModel, null, null); // no IO
            if (model == null) {
                // scope to THIS session's location: v2 resolves the config and
                // provider catalogs per directory (unscoped on the shared
                // service = the user's home, possibly a different catalog)
                String dir = sessionDirectory(client);
                model = DefaultModels.resolve(client.getConfig(dir), client.getProviders(dir));
            }
            if (model == null) {
                return LifecycleResult.failure("no provider/model available");
            }
            boolean accepted = client.summarizeSession(sessionId, model[0], model[1]);
            return accepted ? LifecycleResult.ok(model[0] + "/" + model[1])
                    : LifecycleResult.failure("server declined to summarize");
        } catch (Exception e) {
            return LifecycleResult.failure(message(e));
        }
    }

    /**
     * Prefers the session's tracked model ({@code [provider, model]}, complete
     * = both parts non-blank); falls back to the validated connection default.
     * Pure — unit-testable.
     */
    public static String[] pickSummarizeModel(String[] tracked, ConfigInfo config, ProviderList providers) {
        if (tracked != null && tracked.length == 2
                && tracked[0] != null && !tracked[0].isBlank()
                && tracked[1] != null && !tracked[1].isBlank()) {
            return tracked;
        }
        return DefaultModels.resolve(config, providers);
    }

    private SessionDetails build(List<Session> sessions, List<ChatEntry> messages) {
        Session self = findSession(sessions);
        String title = (self == null) ? null : self.title();
        List<MessageRow> rows = new ArrayList<>();
        String lastAssistantModelLabel = null;
        Double totalCost = null;
        TokenTotals totals = null;
        for (ChatEntry entry : messages == null ? List.<ChatEntry>of() : messages) {
            if (entry == null) {
                continue;
            }
            rows.add(toRow(entry));
            ChatMessageInfo info = entry.info();
            if (info != null && "assistant".equals(info.role())) {
                if (!info.modelLabel().isEmpty()) {
                    lastAssistantModelLabel = info.modelLabel();
                }
                if (info.providerId() != null && info.modelId() != null) {
                    lastAssistantModel = new String[] { info.providerId(), info.modelId() };
                }
                if (info.cost() != null) {
                    totalCost = (totalCost == null ? 0.0 : totalCost) + info.cost();
                }
                if (info.tokens() != null) {
                    totals = TokenTotals.add(totals, info.tokens());
                }
            }
        }
        String note = rows.isEmpty() ? EMPTY_NOTE : null;
        return new SessionDetails(sessionId, title, lastAssistantModelLabel, totalCost,
                totals, List.copyOf(rows), note);
    }

    private Session findSession(List<Session> sessions) {
        if (sessions == null) {
            return null;
        }
        for (Session session : sessions) {
            if (session != null && sessionId != null && sessionId.equals(session.id())) {
                return session;
            }
        }
        return null;
    }

    private static MessageRow toRow(ChatEntry entry) {
        ChatMessageInfo info = entry.info();
        List<ToolLine> tools = new ArrayList<>();
        for (ChatPart part : entry.parts()) {
            if (part != null && part.isTool() && part.tool() != null) {
                tools.add(new ToolLine(part.tool(), part.stateName()));
            }
        }
        return new MessageRow(
                info == null ? null : info.id(),
                info == null ? null : info.role(),
                info == null ? null : info.agent(),
                info == null ? "" : info.modelLabel(),
                timeLabel(info == null ? null : info.time()),
                entry.text(),
                entry.reasoning(),
                List.copyOf(tools));
    }

    /** Deterministic, locale-free label: ISO-8601 instant, seconds precision ("" when unknown). */
    static String timeLabel(Session.Time time) {
        if (time == null) {
            return "";
        }
        long millis = time.created() > 0 ? time.created() : time.updated();
        return millis > 0 ? Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString() : "";
    }

    /** Deepest available message (unwraps supplier-wrapped {@code OpencodeException}s). */
    private static String message(Throwable t) {
        Throwable cause = t;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String m = cause == null ? null : cause.getMessage();
        return (m == null || m.isBlank()) ? (cause == null ? "unknown error" : cause.getClass().getSimpleName()) : m;
    }

    // ---------- snapshot records (immutable; the view renders, never mutates) ----------

    /**
     * Outcome of a session lifecycle action ({@link #fork},
     * {@link #summarize}): {@code detail} carries the new session id (fork) or
     * the {@code provider/model} used (summarize); failures carry a
     * human-readable {@code error} instead of an exception.
     */
    public record LifecycleResult(boolean success, String detail, String error) {

        static LifecycleResult ok(String detail) {
            return new LifecycleResult(true, detail, null);
        }

        static LifecycleResult failure(String error) {
            return new LifecycleResult(false, null, error);
        }
    }

    /**
     * Immutable snapshot: header aggregates plus the ordered message rows, or
     * an {@code errorNote} when loading failed / the history is empty.
     */
    public record SessionDetails(String sessionId, String title, String modelLabel,
            Double totalCost, TokenTotals tokens, List<MessageRow> rows, String errorNote) {

        public SessionDetails {
            rows = (rows == null) ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * One rendered message: the server-assigned message id (drives
     * fork-at-message), text/reasoning plus its tool calls.
     */
    public record MessageRow(String id, String role, String agent, String modelLabel,
            String timeLabel, String text, String reasoning, List<ToolLine> tools) {

        public MessageRow {
            tools = (tools == null) ? List.of() : List.copyOf(tools);
        }
    }

    /** One tool call part ({@code name} never {@code null}; {@code state} may be). */
    public record ToolLine(String name, String state) {
    }

    /** Per-field token sums ({@code null} fields were never reported by any message). */
    public record TokenTotals(Long input, Long output, Long reasoning, Long cacheRead, Long cacheWrite) {

        public boolean isEmpty() {
            return input == null && output == null && reasoning == null
                    && cacheRead == null && cacheWrite == null;
        }

        /** Human-readable summary for the view header, e.g. {@code "in 1,234 • out 567 • reasoning 12"}. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            append(sb, "in ", input);
            append(sb, " • out ", output);
            append(sb, " • reasoning ", reasoning);
            if (cacheRead != null || cacheWrite != null) {
                sb.append(" • cache ");
                if (cacheRead != null) {
                    sb.append(String.format(Locale.ROOT, "%,dr", cacheRead));
                }
                if (cacheRead != null && cacheWrite != null) {
                    sb.append('/');
                }
                if (cacheWrite != null) {
                    sb.append(String.format(Locale.ROOT, "%,dw", cacheWrite));
                }
            }
            return sb.toString();
        }

        private static void append(StringBuilder sb, String label, Long value) {
            if (value != null) {
                sb.append(label).append(String.format(Locale.ROOT, "%,d", value));
            }
        }

        static TokenTotals add(TokenTotals acc, Session.Tokens tokens) {
            if (tokens == null) {
                return acc;
            }
            return new TokenTotals(
                    plus(acc == null ? null : acc.input(), tokens.input()),
                    plus(acc == null ? null : acc.output(), tokens.output()),
                    plus(acc == null ? null : acc.reasoning(), tokens.reasoning()),
                    tokens.cache() == null
                            ? (acc == null ? null : acc.cacheRead())
                            : plus(acc == null ? null : acc.cacheRead(), tokens.cache().read()),
                    tokens.cache() == null
                            ? (acc == null ? null : acc.cacheWrite())
                            : plus(acc == null ? null : acc.cacheWrite(), tokens.cache().write()));
        }

        private static Long plus(Long acc, long value) {
            return (acc == null ? 0L : acc) + value;
        }
    }
}
