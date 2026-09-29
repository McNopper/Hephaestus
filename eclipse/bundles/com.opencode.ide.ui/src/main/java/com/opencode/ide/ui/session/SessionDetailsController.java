package com.opencode.ide.ui.session;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.opencode.ide.client.DefaultModels;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.ui.model.SessionSections;
import com.opencode.ide.ui.model.SessionShells;
import com.opencode.ide.ui.model.SessionSubagents;

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

    /**
     * The fixed prompt behind {@link #suggestTitle()} (U-046 slice 2): one
     * transient {@code POST .../session/{id}/generate} completion that never
     * mutates the session history.
     */
    public static final String TITLE_PROMPT =
            "Suggest a concise title (at most six words) for this session based on its history. Reply with the title only.";

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
            return build(sessions, messages, subagents(sessions, client), shellTasks(client));
        } catch (Exception e) {
            return new SessionDetails(sessionId, null, null, null, null, List.of(), List.of(),
                    List.of(), message(e));
        }
    }

    /**
     * U-041: the session's subagent children ({@code parentID} nesting) with
     * their live status; the active map and the session list degrade to
     * empty independently — an unreadable status map reads as idle, an
     * unreadable list hides the section, neither fails the transcript.
     */
    private List<SessionSubagents.Row> subagents(List<Session> sessions, OpencodeClient client) {
        Map<String, SessionStatus> statuses = Map.of();
        try {
            Map<String, SessionStatus> map = client.getSessionStatus();
            if (map != null) {
                statuses = map;
            }
        } catch (Exception e) {
            // statuses are optional decoration: children render as idle
        }
        return SessionSubagents.rows(sessionId, sessions, statuses);
    }

    /**
     * U-041: the session's shell tasks. The transcript (raw message list) is
     * the association — its {@code type:"shell"} messages carry the
     * {@code sh_} task ids — and the live task list overlays them (see
     * {@link SessionShells}). Either fetch degrading to empty only shrinks
     * the section; the transcript keeps rendering.
     */
    private List<SessionShells.Row> shellTasks(OpencodeClient client) {
        JsonArray messages = new JsonArray();
        try {
            JsonArray raw = client.getMessagesJson(sessionId);
            if (raw != null) {
                messages = raw;
            }
        } catch (Exception e) {
            // no raw list: no associable tasks, an honest empty section
        }
        List<com.opencode.ide.client.model.ShellTask> live = List.of();
        try {
            List<com.opencode.ide.client.model.ShellTask> tasks = client.listShellTasks();
            if (tasks != null) {
                live = tasks;
            }
        } catch (Exception e) {
            // live overlay optional: transcript rows still show their status
        }
        return SessionShells.rows(messages, live);
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
     * U-048: this session's working directory — the import target when a
     * picked export is imported "into the connection's directory". Blocking
     * (one session-list GET); call from a background job. {@code null} when
     * unknown: the import then goes unscoped, the service default.
     */
    public String directory() {
        try {
            return sessionDirectory(clientSupplier.get());
        } catch (Exception e) {
            return null;
        }
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

    /**
     * U-048, phase 1 of the two-phase revert ({@code POST
     * .../session/{id}/revert/stage} via {@code revertMessage}): stages the
     * revert boundary BEFORE this message. The server's default also restores
     * the working-tree files the messages after the boundary touched (the
     * client verb cannot send {@code files:false}); the transcript itself is
     * untouched until {@link #commitRevert()}.
     */
    public LifecycleResult stageRevert(String messageId) {
        try {
            clientSupplier.get().revertMessage(sessionId(), messageId);
            return new LifecycleResult(true,
                    "revert staged at " + messageId + " (files restored; commit to cut the history)", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-048, phase 2 ({@code POST .../session/{id}/revert/commit}): cut the
     * session's message history back to the staged boundary. Irreversible —
     * call it only after {@link #stageRevert(String)} and an explicit
     * confirmation.
     */
    public LifecycleResult commitRevert() {
        try {
            clientSupplier.get().commitSessionRevert(sessionId());
            return new LifecycleResult(true, "revert committed (history cut to the staged boundary)", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-048, the undo ({@code DELETE .../session/{id}/revert}): restore the
     * working-tree files from the staged snapshot and clear the staged
     * revert. A no-op when nothing is staged.
     */
    public LifecycleResult undoRevert() {
        try {
            clientSupplier.get().unrevertSession(sessionId());
            return new LifecycleResult(true, "staged revert undone (files restored, stage cleared)", null);
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

    /**
     * U-046 slice 2: the skills attachable to this session, scoped to the
     * session's own directory when known (v2 resolves the catalog per
     * location); empty on failure - the picker degrades to "none available".
     */
    public List<SkillInfo> skills() {
        try {
            OpencodeClient client = clientSupplier.get();
            List<SkillInfo> skills = client.listSkills(sessionDirectory(client));
            return skills == null ? List.of() : skills;
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * U-046 slice 2: activate ("attach") a skill on this session
     * (EXPERIMENTAL {@code POST .../session/{id}/skill}); the server appends
     * a {@code skill} message and resumes.
     */
    public LifecycleResult attachSkill(String skillId) {
        try {
            clientSupplier.get().attachSkill(sessionId(), skillId);
            return new LifecycleResult(true, "skill attached", null);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-046 slice 2: one-shot title suggestion ({@code POST .../session/{id}/generate}
     * with the fixed {@link #TITLE_PROMPT}) - a transient completion that
     * never mutates the session history. The suggestion is OFFERED to the
     * user; renaming happens only through {@link #rename(String)} once
     * accepted.
     */
    public LifecycleResult suggestTitle() {
        try {
            String title = titleSuggestion(clientSupplier.get().generateOnSession(sessionId(), TITLE_PROMPT));
            return title == null ? LifecycleResult.failure("no suggestion returned") : LifecycleResult.ok(title);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Cleans a generate answer into a usable title suggestion: stripped,
     * wrapping double quotes removed; {@code null} when nothing usable
     * remains. Pure - unit-testable.
     */
    public static String titleSuggestion(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.strip();
        if (title.length() >= 2 && title.startsWith("\"") && title.endsWith("\"")) {
            title = title.substring(1, title.length() - 1).strip();
        }
        return title.isBlank() ? null : title;
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

    /**
     * U-041: one shell task's captured output tail ({@code GET /api/shell/:id/output});
     * {@code null} on failure (unknown/evicted task) so callers degrade to
     * the transcript's tail instead of an error.
     */
    public String shellOutput(String shellId) {
        try {
            return clientSupplier.get().shellTaskOutput(shellId);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return null;
        }
    }

    /** U-041: reap a finished shell task ({@code DELETE /api/shell/:id}). */
    public LifecycleResult removeShellTask(String shellId) {
        try {
            clientSupplier.get().removeShellTask(shellId);
            return LifecycleResult.ok("shell task removed: " + shellId);
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-048: mark the session viewed ({@code POST .../session/{id}/view}) —
     * the read marker behind the server's unread/idle badges. The idle
     * argument is the epoch-millis watermark the viewer observed: "now"
     * asserts everything idled up to now counts as seen (the v2.0.19
     * {@code Session.Viewed} semantics). Fire-and-forget by design — the
     * view ignores the outcome; a server without the route must not produce
     * error noise on every refresh.
     */
    public LifecycleResult markViewed() {
        try {
            clientSupplier.get().markSessionViewed(sessionId(), System.currentTimeMillis());
            return LifecycleResult.ok("viewed");
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-048: FULL-REPLACE the session's environment variables ({@code PUT
     * .../session/{id}/environment}). There is no GET counterpart on the
     * wire, so the caller's map comes from the user's editor, never from a
     * read-back — anything omitted here is removed server-side.
     */
    public LifecycleResult replaceEnvironment(Map<String, String> variables) {
        try {
            clientSupplier.get().replaceSessionEnvironment(sessionId(), variables);
            int size = variables == null ? 0 : variables.size();
            return LifecycleResult.ok("environment replaced (" + size + " variables)");
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            return new LifecycleResult(false, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * U-048: import a session from an export document's payloads
     * ({@code POST /api/experimental/session/import}); {@code directoryOrNull}
     * scopes the import like {@code createSession}'s location body
     * ({@code null} = the server default). A conflict (same session id
     * already exists) and every other failure surface as the error side of
     * the result.
     *
     * @return the imported session id on success
     */
    public LifecycleResult importSession(Map<String, Object> info, List<Map<String, Object>> messages,
            String directoryOrNull) {
        try {
            Map<String, Object> imported = clientSupplier.get().importSession(info, messages, directoryOrNull);
            String id = imported == null ? null : String.valueOf(imported.get("id"));
            return (id == null || "null".equals(id))
                    ? LifecycleResult.failure("the service returned no session id")
                    : LifecycleResult.ok(id);
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

    private SessionDetails build(List<Session> sessions, List<ChatEntry> messages,
            List<SessionSubagents.Row> subagents, List<SessionShells.Row> shellTasks) {
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
                totals, subagents, shellTasks, List.copyOf(rows), note);
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
     * Immutable snapshot: header aggregates, the session's subagent children
     * and shell tasks (U-041 sections), the ordered message rows, or an
     * {@code errorNote} when loading failed / the history is empty.
     */
    public record SessionDetails(String sessionId, String title, String modelLabel,
            Double totalCost, TokenTotals tokens, List<SessionSubagents.Row> subagents,
            List<SessionShells.Row> shellTasks, List<MessageRow> rows, String errorNote) {

        public SessionDetails {
            subagents = (subagents == null) ? List.of() : List.copyOf(subagents);
            shellTasks = (shellTasks == null) ? List.of() : List.copyOf(shellTasks);
            rows = (rows == null) ? List.of() : List.copyOf(rows);
        }

        /**
         * The tree's root nodes: the U-041 sections (subagents, shell tasks)
         * above the message rows — the composition lives in
         * {@link SessionSections} so the view stays a thin shell.
         */
        public List<Object> roots() {
            List<Object> roots = new ArrayList<>(SessionSections.roots(subagents, shellTasks));
            roots.addAll(rows);
            return roots;
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
