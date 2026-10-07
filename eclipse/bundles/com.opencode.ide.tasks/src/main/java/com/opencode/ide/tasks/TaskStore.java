package com.opencode.ide.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The Markdown task store: one directory per project under the configured
 * root, one {@code <id>.md} file per task (see {@link TaskFileCodec}), plus a
 * {@code _meta.json} sidecar holding the per-prefix id counters, the sprint
 * counter and the sprint metadata (goal/status/timestamps - the board itself
 * is derived from the tasks).
 *
 * <p>Layout:</p>
 * <pre>
 * &lt;root&gt;/
 *   &lt;project&gt;/
 *     _meta.json     # {"seq": {"T": 5}, "counter": 2, "sprints": {...}}
 *     .lock          # cross-process lock file (never deleted)
 *     T-001.md
 *     T-002.md
 * </pre>
 *
 * <h2>Congestion and atomicity</h2>
 * Every operation runs inside a <em>directory-wide transaction</em>: an
 * in-JVM {@link ReentrantLock} per lock file (shared across store instances)
 * plus an OS {@link FileLock} on {@code <project>/.lock} so the Eclipse-hosted
 * endpoint and the standalone stdio tool (separate processes) serialize. Writes
 * go to a temp file in the same directory followed by
 * {@code Files.move(REPLACE_EXISTING)} - on Windows this is the safe replace
 * pattern ({@code ATOMIC_MOVE + REPLACE_EXISTING} is undefined per spec).
 * Multi-file operations (plan/close sprint) validate every precondition before
 * writing the first file so a rejected call never leaves partial state; a crash
 * mid-write can, and is documented as such.
 *
 * <h2>Semantics</h2>
 * Field names, status machine, claim ordering (priority desc, then
 * created_at asc, then id), lax update rules, sprint force-set on plan, and
 * the traceability pairing all mirror the retired Python {@code pm} MCP
 * server one-for-one; see the skills in {@code .opencode/skills/pm-*}.
 */
public final class TaskStore {

    /** Task id not found (maps to an isError tool result, like the pm server's KeyError). */
    public static final class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    /** Invalid value / state transition (maps to an isError tool result, like the pm server's ValueError). */
    public static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    /** Immutable create-parameters record (all optional except title). */
    public record CreateSpec(
            String title, String description, String type, String role, String priority,
            Integer storyPoints, List<String> acceptanceCriteria, List<String> labels,
            String epic, String idPrefix) {

        /** Builder-ish factory with pm defaults. */
        public static CreateSpec of(String title) {
            return new CreateSpec(title, "", "task", "developer", "medium", 0,
                    List.of(), List.of(), null, "T");
        }
    }

        /** Now, truncated to the store's millisecond precision so in-memory state always equals the persisted state. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static final Logger LOG = Logger.getLogger(TaskStore.class.getName());
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final Gson GSON = new Gson();
    private static final Set<String> ARTIFACT_KINDS = Set.of("file", "git", "path", "url", "doc");
    /** Artifact kinds whose ref is a repo path task_doctor can check for existence. */
    private static final Set<String> PATH_ARTIFACT_KINDS = Set.of("file", "path");
    private static final String INVALIDATION_BY = "h6";
    /** The readiness report's severity order: most urgent kind first. */
    private static final List<StageReadiness.Kind> READINESS_SEVERITY = List.of(
            StageReadiness.Kind.STALE, StageReadiness.Kind.BLOCKED, StageReadiness.Kind.WAIT_UPSTREAM,
            StageReadiness.Kind.RUNNING, StageReadiness.Kind.READY, StageReadiness.Kind.NOT_APPLICABLE);

    private final Path root;

    /** @param root the store root; each project gets a subdirectory (created on demand). */
    public TaskStore(Path root) {
        this.root = root;
    }

    /** The configured store root. */
    public Path root() {
        return root;
    }

    // ------------------------------------------------------------------
    // Operations (pm MCP server parity)
    // ------------------------------------------------------------------

    /** Creates a task (status=product-backlog) with a freshly minted id. */
    public Task create(String project, CreateSpec spec) {
        return create(project, spec, null);
    }

    /**
     * Creates a task with an initial V-model pipeline {@code stage} (validated
     * against {@link VStages}; {@code null} leaves the ticket untracked,
     * exactly like a legacy ticket).
     */
    public Task create(String project, CreateSpec spec, String stage) {
        if (spec.role() != null && (spec.role().isBlank())) {
            throw new Invalid("role must be a non-empty string, got '" + spec.role() + "'");
        }
        if (spec.type() != null && !Task.VALID_TYPES.contains(spec.type())) {
            throw new Invalid("type must be one of " + Task.VALID_TYPES + ", got '" + spec.type() + "'");
        }
        if (spec.priority() != null && !Task.PRIORITY_ORDER.containsKey(spec.priority())) {
            throw new Invalid("priority must be one of " + List.of("low", "medium", "high", "critical")
                    + ", got '" + spec.priority() + "'");
        }
        if (stage != null && !VStages.isValid(stage)) {
            throw new Invalid("stage must be one of " + VStages.STAGES + " (or null), got '" + stage + "'");
        }
        requireNoNullItems(spec.acceptanceCriteria(), "acceptance_criteria");
        requireNoNullItems(spec.labels(), "labels");
        return transaction(project, data -> {
            String id = nextId(data, spec.idPrefix() == null ? "T" : spec.idPrefix());
            Task t = new Task();
            t.id = id;
            t.title = spec.title();
            t.description = spec.description() == null ? "" : spec.description();
            t.type = spec.type() == null ? "task" : spec.type();
            t.role = spec.role() == null ? "developer" : spec.role();
            t.stage = stage;
            t.priority = spec.priority() == null ? "medium" : spec.priority();
            t.storyPoints = spec.storyPoints() == null ? 0 : spec.storyPoints();
            if (spec.acceptanceCriteria() != null) {
                t.acceptanceCriteria = new ArrayList<>(spec.acceptanceCriteria());
            }
            if (spec.labels() != null) {
                t.labels = new ArrayList<>(spec.labels());
            }
            t.epic = spec.epic();
            t.createdAt = now();
            t.updatedAt = t.createdAt;
            t.history("created", null);
            data.tasks.put(id, t);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * Gets one task by id. A project that does not exist on disk is
     * "no such ticket" - reads never materialize the project directory
     * (review S5).
     */
    public Task get(String project, String id) {
        if (projectDirectoryMissing(project)) {
            throw new NotFound("ticket " + id + " not found in project " + project);
        }
        return transaction(project, data -> {
            Task t = data.tasks.get(id);
            if (t == null) {
                throw new NotFound("ticket " + id + " not found in project " + project);
            }
            return t;
        });
    }

    /**
     * The project subdirectory names of this store root (one per directory
     * that looks like a project: contains at least one {@code .md} file).
     * Sorted; never null; the empty store yields an empty list.
     */
    public List<String> projects() {
        try (var stream = java.nio.file.Files.list(root)) {
            return stream
                    .filter(java.nio.file.Files::isDirectory)
                    .filter(dir -> {
                        try (var files = java.nio.file.Files.list(dir)) {
                            return files.anyMatch(p -> p.getFileName().toString().endsWith(".md"));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Lists tasks with optional role/status/sprint/blocked filters (creation
     * order). A project that does not exist on disk reads as empty - reads
     * never materialize the project directory (review S5: task_doctor on a
     * typo'd project used to create it).
     */
    public List<Task> list(String project, String role, String status, String sprint, Boolean blocked) {
        if (projectDirectoryMissing(project)) {
            return new ArrayList<>(); // mutable: callers sort in place (readiness, backlog)
        }
        List<Task> out = new ArrayList<>(transaction(project, data -> new ArrayList<>(data.tasks.values())));
        if (role != null) {
            out.removeIf(t -> !role.equals(t.role));
        }
        if (status != null) {
            out.removeIf(t -> !status.equals(t.status));
        }
        if (sprint != null) {
            out.removeIf(t -> !sprint.equals(t.sprint));
        }
        if (blocked != null) {
            out.removeIf(t -> t.isBlocked() != blocked);
        }
        return out;
    }

    /**
     * Lax update of mutable fields, matching the pm server: protected fields
     * ({@code id}, {@code created_at}, {@code history}, {@code comments}) are
     * silently dropped, explicit nulls clear the nullable fields, only
     * {@code role} and {@code status} are validated, and no transition graph
     * is enforced. One rule beyond validation: {@code status} and
     * {@code blocked} move as ONE state transition (U-067) - blocked=true
     * enters the blocked state (resume = the requested or current status),
     * blocked=false leaves it, and a ticket left {@code done} can never be
     * blocked (structural - live incidents W-006/W-007). Divergence: unknown fields
     * are dropped rather than stored (the file format keeps unknown
     * <em>keys</em> from hand edits, but tool updates cannot introduce new
     * ones).
     *
     * @param changes snake_case field name -> new value (String/Number/Boolean/List/JsonElement)
     */
    public Task update(String project, String id, Map<String, Object> changes) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            List<String> applied = new ArrayList<>();
            String requestedStatus = null;
            Boolean requestedBlocked = null;
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                switch (e.getKey()) {
                    case "title" -> {
                        t.title = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "description" -> {
                        t.description = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "type" -> {
                        t.type = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "priority" -> {
                        t.priority = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "role" -> {
                        String role = string(e.getValue());
                        if (role == null || role.isBlank()) {
                            throw new Invalid("role must be a non-empty string");
                        }
                        t.role = role;
                        applied.add(e.getKey());
                    }
                    case "stage" -> {
                        String stage = string(e.getValue());
                        if (stage != null && !VStages.isValid(stage)) {
                            throw new Invalid("stage must be one of " + VStages.STAGES + " (or null), got '" + stage + "'");
                        }
                        t.stage = stage;
                        applied.add(e.getKey());
                    }
                    case "model" -> {
                        t.model = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "status" -> {
                        String status = string(e.getValue());
                        if (status == null || !Task.VALID_STATUSES.contains(status)) {
                            throw new Invalid("status must be one of " + Task.VALID_STATUSES);
                        }
                        requestedStatus = status;
                        applied.add(e.getKey());
                    }
                    case "story_points" -> {
                        t.storyPoints = intOf(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "assignee" -> {
                        t.assignee = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    // U-067: status/blocked form ONE state move - stashed in
                    // the loop, applied coherently after it (order-free, so
                    // the board's send-back drop contract works in any key order)
                    case "blocked" -> {
                        requestedBlocked = truthy(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "blocker" -> {
                        t.blocker = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "sprint" -> {
                        t.sprint = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "epic" -> {
                        t.epic = string(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "acceptance_criteria" -> {
                        t.acceptanceCriteria = stringList(e.getValue());
                        applied.add(e.getKey());
                    }
                    case "labels" -> {
                        t.labels = stringList(e.getValue());
                        applied.add(e.getKey());
                    }
                    default -> { /* protected or unknown: silently dropped (pm parity) */ }
                }
            }
            // U-067: the state door - one coherent move regardless of the
            // key order the caller used (enter with a retargeted resume, or
            // leave back to resume_to; a plain status move leaves cleanup to
            // the commit-time invariant)
            if (requestedBlocked != null && requestedBlocked) {
                if ("done".equals(t.status)) {
                    throw new Invalid("a done ticket is never blocked (U-067: done is terminal)");
                }
                String resume = requestedStatus != null && !"blocked".equals(requestedStatus)
                        ? requestedStatus : t.status;
                t.enterBlocked(t.blocker);
                if (!"blocked".equals(resume)) {
                    t.resumeTo = resume;
                }
            } else if (requestedBlocked != null && t.isBlocked()) {
                t.leaveBlocked();
            } else if (requestedStatus != null) {
                t.status = requestedStatus;
            }
            if (!applied.isEmpty()) {
                t.updatedAt = now();
                t.history("updated:" + String.join(",", applied), null);
                data.changed.add(id);
            }
            return t;
        });
    }

    /**
     * The V-model pipeline hand-forward: moves a ticket whose stage's work is
     * finished on to the <em>next</em> stage. Quality gate: the ticket must be
     * {@code in-review} or {@code done} - an unfinished stage never advances.
     * One transaction: stage and role move to the next stage's, status resets
     * to {@code product-backlog} (the next stage's backlog is fed by the
     * previous stage), the assignee is cleared. A blocked ticket cannot advance at all: the
     * status guard below admits only in-review/done (U-067 - blocked is a
     * state; clear it first).
     *
     * @throws Invalid when the ticket has no (valid) stored stage - legacy
     *                 tickets must be staged explicitly, no guessing; when the
     *                 status is not in-review/done; or when the ticket already
     *                 sits at the V tip ({@code test-requirements}).
     */
    public Task advance(String project, String id, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (t.stage == null || !VStages.isValid(t.stage)) {
                throw new Invalid("ticket has no stage; set one first");
            }
            if (!"in-review".equals(t.status) && !"done".equals(t.status)) {
                throw new Invalid("cannot advance ticket in status '" + t.status
                        + "' (the stage's work must be finished: in-review or done)");
            }
            String next = VStages.next(t.stage);
            if (next == null) {
                throw new Invalid("ticket is at the V tip stage '" + t.stage
                        + "'; there is no next stage");
            }
            t.stage = next;
            t.role = VStages.roleOf(next);
            t.status = "product-backlog";
            t.assignee = null;
            t.updatedAt = now();
            t.history("advanced to " + next, by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * The V-model feedback loop: sends a ticket back to the <em>previous</em>
     * stage with a reason. The hand-back is unmissable: the ticket lands in
     * the BLOCKED state with {@code resume_to=product-backlog} - clearing it
     * returns to the previous stage's backlog (U-067), carrying the blocker
     * text {@code "sent back from <old stage>: <reason>"}. The assignee is
     * cleared; role follows the previous stage.
     *
     * @throws Invalid when the ticket has no (valid) stored stage, when the
     *                 reason is blank, or when the ticket sits at
     *                 {@code requirements} (no previous stage).
     */
    public Task sendBack(String project, String id, String reason, String by) {
        // U-023: a question is not a defect. A 'clarification:' hand-back
        // routes to the ORIGINATOR agent instead of blocking for a human;
        // plain send-backs keep the blocking semantics below.
        if (reason != null && reason.strip().toLowerCase(java.util.Locale.ROOT).startsWith("clarification:")) {
            return clarify(project, id, reason.strip().substring("clarification:".length()).strip(), by);
        }
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (t.stage == null || !VStages.isValid(t.stage)) {
                throw new Invalid("ticket has no stage; set one first");
            }
            if (reason == null || reason.isBlank()) {
                throw new Invalid("reason must be a non-empty string");
            }
            String prev = VStages.previous(t.stage);
            if (prev == null) {
                throw new Invalid("ticket is at the first stage 'requirements'; there is no previous stage");
            }
            String from = t.stage;
            t.stage = prev;
            t.role = VStages.roleOf(prev);
            t.assignee = null;
            // U-067: the hand-back IS the blocked state - clearing returns to
            // the previous stage's product backlog (resume_to)
            t.enterBlocked("sent back from " + from + ": " + reason);
            t.resumeTo = "product-backlog";
            t.updatedAt = now();
            t.history("sent back to " + prev + ": " + reason, by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * U-029 stage pass-through: the current stage has no applicable change -
     * the ticket VISITS it and advances with a recorded rationale instead of
     * a full dispatch. The transition is exactly {@link #advance}'s (next
     * stage + role, product-backlog, assignee cleared), no artifact required;
     * the history carries {@code stage N passed: reason} (author + timestamp)
     * so the flow trace counts the stage as visited. The V tip can never pass;
     * a blank reason is rejected (FR-001..003 of
     * {@code docs/requirements/U-029-stage-pass-through.md}).
     *
     * @throws Invalid when the ticket has no (valid) stored stage, when the
     *                 reason is blank, or when the ticket sits at the V tip
     */
    public Task passStage(String project, String id, String reason, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (t.stage == null || !VStages.isValid(t.stage)) {
                throw new Invalid("ticket has no stage; set one first");
            }
            if (reason == null || reason.isBlank()) {
                throw new Invalid("reason must be a non-empty string");
            }
            String next = VStages.next(t.stage);
            if (next == null) {
                throw new Invalid("ticket is at the V tip stage '" + t.stage
                        + "'; a stage pass is never allowed here");
            }
            int number = VStages.STAGES.indexOf(t.stage) + 1;
            t.stage = next;
            t.role = VStages.roleOf(next);
            t.status = "product-backlog";
            t.assignee = null;
            t.updatedAt = now();
            t.history("stage " + number + " passed: " + reason, by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * U-031 HORIZONTAL resolution route: a blocked ticket reports to its
     * V-level pair stage (e.g. {@code test-design} -&gt; {@code design}) with
     * the reason - exactly like a vertical {@link #sendBack} but sideways:
     * product backlog of the pair stage, assignee cleared, the blocked flag
     * raised with {@code "reported from <stage>: <reason>"} and a
     * {@code "reported to <stage>: <reason>"} history event.
     *
     * @throws Invalid when the ticket has no (valid) stored stage or the
     *                 reason is blank
     */
    public Task reportHorizontal(String project, String id, String reason, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (t.stage == null || !VStages.isValid(t.stage)) {
                throw new Invalid("ticket has no stage; set one first");
            }
            if (reason == null || reason.isBlank()) {
                throw new Invalid("reason must be a non-empty string");
            }
            String from = t.stage;
            String pair = VStages.pairOf(from);
            t.stage = pair;
            t.role = VStages.roleOf(pair);
            t.assignee = null;
            // U-067: sideways hand-back is the blocked state too - clearing
            // returns to the pair stage's product backlog
            t.enterBlocked("reported from " + from + ": " + reason);
            t.resumeTo = "product-backlog";
            t.updatedAt = now();
            t.history("reported to " + pair + ": " + reason, by);
            data.changed.add(id);
            return t;
        });
    }

    /** Round-trips on one clarification pair before it escalates to the human (U-023). */
    public static final int CLARIFICATION_LIMIT = 3;

    /**
     * U-023 clarification loop: a stage agent needing clarification routes
     * the QUESTION to the ORIGINATOR instead of blocking for a human. The
     * originator is the upstream-stage ticket of the same epic (the epic
     * parent {@code u.id == t.epic} or a sibling {@code u.epic == t.epic},
     * as the readiness chain walks it), else the epic's requirements-stage
     * ticket; with neither, the ticket escalates to NEEDS-HUMAN. The
     * requester stays runnable - the existing inputs-changed/STALE machinery
     * re-runs it when the originator's artifact updates. More than
     * {@link #CLARIFICATION_LIMIT} clarifications on the same pair escalate
     * (the pair is stuck, not unclear). A blank question is rejected.
     *
     * @throws Invalid when the ticket has no (valid) stored stage or the
     *                 question is blank
     */
    public Task clarify(String project, String id, String question, String by) {
        if (question == null || question.isBlank()) {
            throw new Invalid("question must be a non-empty string");
        }
        Task requester = get(project, id);
        if (requester.stage == null || !VStages.isValid(requester.stage)) {
            throw new Invalid("ticket has no stage; set one first");
        }
        long trips = requester.history.stream()
                .filter(event -> event.action().startsWith("clarification to"))
                .count();
        Task originator = originatorOf(requester, list(project, null, null, null, null));
        if (originator == null || trips >= CLARIFICATION_LIMIT) {
            String why = originator == null ? "no agent route (originator absent)"
                    : "round-trip limit " + CLARIFICATION_LIMIT + " exceeded";
            return transaction(project, data -> {
                Task t = require(data, project, id);
                t.enterBlocked("clarification escalated to NEEDS-HUMAN (" + why + "): " + question);
                t.updatedAt = now();
                t.history("clarification escalated to NEEDS-HUMAN: " + question, by);
                data.changed.add(id);
                return t;
            });
        }
        return transaction(project, data -> {
            Task t = require(data, project, id);
            Task o = require(data, project, originator.id);
            o.history("clarification from " + t.stage + " (" + t.id + "): " + question, by);
            o.status = "product-backlog";
            o.assignee = null;
            o.updatedAt = now();
            t.history("clarification to " + o.stage + " (" + o.id + "): " + question, by);
            t.updatedAt = now();
            data.changed.add(id);
            data.changed.add(o.id);
            return t;
        });
    }

    /** The U-023 originator of a clarification: upstream epic ticket, else the epic's requirements ticket, else null. */
    private static Task originatorOf(Task requester, List<Task> all) {
        String upstream = StageReadiness.upstreamStage(requester.stage);
        Task fallback = null;
        for (Task candidate : all) {
            if (candidate == null || candidate.id.equals(requester.id) || requester.epic == null) {
                continue;
            }
            boolean inEpic = candidate.id.equals(requester.epic)
                    || candidate.epic != null && candidate.epic.equals(requester.epic);
            if (!inEpic) {
                continue;
            }
            if (upstream != null && upstream.equals(candidate.stage)) {
                return candidate;
            }
            if (fallback == null && "requirements".equals(candidate.stage)) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    /** B-007 FR-009/010: originator retries per stage visit before doubt escalates to a human. */
    public static final int REVIEW_DOUBT_RETRY_LIMIT = 1;

    /**
     * B-007 doubt round-trip (FR-009..FR-012): reviewer doubt - or a
     * heuristic checkpoint refusal (FR-011) - routes back to the ORIGINATOR
     * instead of blocking for a human. The ticket returns to its own stage's
     * backlog (same mechanics as {@link #advance}, without the stage move:
     * product backlog, assignee cleared) with the doubt recorded as an
     * attributable comment and a {@code review doubt retry (n/N)} history
     * marker. Exactly {@link #REVIEW_DOUBT_RETRY_LIMIT} retry per stage visit
     * (C-004: a visit ends at the next stage transition - {@code advanced
     * to}, {@code sent back to}, {@code reported to}, {@code stage N
     * passed}, a direct stage update, or ticket creation); a doubt that
     * recurs inside the same visit escalates the ticket to blocked -
     * {@code blocked} is the needs-a-human signal and is reached only after
     * the originator had its attempt (U-023 round-trip doctrine).
     *
     * @param reason the reviewer's doubt or the checkpoint's refusal reason
     * @return the ticket after the routing (product-backlog retry, or blocked)
     * @throws Invalid when the ticket has no (valid) stored stage or the
     *                 reason is blank
     */
    public Task routeReviewDoubt(String project, String id, String reason, String by) {
        if (reason == null || reason.isBlank()) {
            throw new Invalid("reason must be a non-empty string");
        }
        Task current = get(project, id);
        if (current.stage == null || !VStages.isValid(current.stage)) {
            throw new Invalid("ticket has no stage; set one first");
        }
        long consumed = doubtRetriesInVisit(current);
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (consumed >= REVIEW_DOUBT_RETRY_LIMIT) {
                t.enterBlocked("review doubt unresolved after " + REVIEW_DOUBT_RETRY_LIMIT
                        + " originator retries: " + reason);
                t.updatedAt = now();
                t.comments.add(new Task.Comment(now(), by,
                        "review doubt escalated to NEEDS-HUMAN (stage " + t.stage + "): " + reason));
                t.history("review doubt escalated to NEEDS-HUMAN: " + reason, by);
                data.changed.add(id);
                return t;
            }
            t.status = "product-backlog";
            t.assignee = null;
            t.updatedAt = now();
            t.comments.add(new Task.Comment(now(), by,
                    "review doubt retry (" + (consumed + 1) + "/" + REVIEW_DOUBT_RETRY_LIMIT
                            + ") for stage " + t.stage + " - routed to the originator: " + reason));
            t.history("review doubt retry (" + (consumed + 1) + "/" + REVIEW_DOUBT_RETRY_LIMIT
                    + ") for stage " + t.stage + ": " + reason, by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * Doubt retries already consumed in the ticket's CURRENT stage visit
     * (FR-010 bookkeeping, Q-003): history markers since the most recent
     * stage transition - or since creation when the stage never moved.
     */
    private static long doubtRetriesInVisit(Task t) {
        long doubts = 0;
        for (int i = t.history.size() - 1; i >= 0; i--) {
            String action = t.history.get(i).action();
            if (isStageTransition(action)) {
                break;
            }
            if (action.startsWith("review doubt")) {
                doubts++;
            }
        }
        return doubts;
    }

    /** Stage-visit boundary events (Q-003): any recorded move that resets the doubt budget. */
    private static boolean isStageTransition(String action) {
        return action.startsWith("advanced to ")
                || action.startsWith("sent back to ")
                || action.startsWith("reported to ")
                || (action.startsWith("stage ") && action.contains(" passed: "))
                || action.startsWith("updated:stage")
                || action.startsWith("created");
    }

    /**
     * B-007 FR-006: store-side stage evidence - ticket writes attributable
     * to a run window (comments, recorded artifacts, history) after
     * {@code since}. Definition-leg runs legitimately deliver through the
     * {@code task_*} tools into the MAIN store instead of worktree edits;
     * those writes ARE produced work and the settle check must count them
     * (the caller passes the result down as the merge's empty-branch
     * allowance). The window starts after the engine's own pre-claim writes,
     * so bookkeeping never masquerades as evidence (C-002: inspected from
     * store state, no side channel).
     */
    public boolean hasStoreSideWrites(String project, String id, java.time.Instant since) {
        Task t = get(project, id);
        if (since == null) {
            return false;
        }
        for (Task.Comment c : t.comments) {
            if (c.ts() != null && c.ts().isAfter(since)) {
                return true;
            }
        }
        for (Task.Artifact a : t.artifacts) {
            if (a.ts() != null && a.ts().isAfter(since)) {
                return true;
            }
        }
        for (Task.HistoryEvent e : t.history) {
            if (e.ts() != null && e.ts().isAfter(since)) {
                return true;
            }
        }
        return false;
    }

    /**
     * O-002: creates a NEW task-store project - the scaffold a user would
     * otherwise hand-write under {@code .opencode/tasks/} (the directory plus
     * the store's own {@code _meta.json} shape: id seq, counter, sprints).
     * Only this project is created; every other project keeps its history,
     * actuals and fleet ownership bindings untouched.
     *
     * @throws Invalid when the project already exists (pick another name)
     */
    public void newProject(String project, String by) {
        String name = sanitizeProject(project);
        Path dir = root.resolve(name);
        if (Files.isDirectory(dir)) {
            throw new Invalid("project '" + name + "' already exists");
        }
        try {
            Files.createDirectories(dir);
            writeAtomic(dir.resolve("_meta.json"), "{\"seq\":{},\"counter\":0,\"sprints\":{}}");
        } catch (java.io.IOException e) {
            throw new UncheckedIo("task store I/O failed: " + e.getMessage(), e);
        }
    }

    /**
     * O-002: resets a project - every ticket of THIS project (live and
     * archived) and its wave history are removed, so a user repurposing the
     * repo never hand-deletes store files. The confirmation dialog lists
     * what is lost BEFORE this runs. Cross-project safety is STRUCTURAL:
     * only files under {@code <root>/<project>/} are touched - other
     * projects keep their tickets/actuals and the fleet's {@code
     * <id>.project} ownership bindings stay intact. The id counters survive
     * the reset on purpose: ids are never reused (the crash-recovery rule).
     *
     * @return what was lost ({@code project}, {@code tickets_removed},
     *         {@code archived_removed}, {@code sprints_cleared})
     * @throws Invalid when the project does not exist
     */
    public java.util.Map<String, Object> resetProject(String project, String by) {
        String name = sanitizeProject(project);
        Path dir = root.resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new Invalid("project '" + name + "' does not exist");
        }
        int tickets = 0;
        int archived = 0;
        try {
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".md") && !fileName.startsWith("_")) {
                        Files.deleteIfExists(file);
                        tickets++;
                    }
                }
            }
            Path archive = dir.resolve("_archive");
            if (Files.isDirectory(archive)) {
                try (var stream = Files.list(archive)) {
                    for (Path file : stream.filter(Files::isRegularFile).toList()) {
                        Files.deleteIfExists(file);
                        archived++;
                    }
                }
                Files.deleteIfExists(archive);
            }
            // wave history is part of what is lost; the id seq is NOT reset
            Path meta = dir.resolve("_meta.json");
            JsonObject doc = Files.isRegularFile(meta)
                    ? JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            doc.remove("sprints");
            doc.add("sprints", new JsonObject());
            writeAtomic(meta, GSON.toJson(doc));
        } catch (java.io.IOException e) {
            throw new UncheckedIo("task store I/O failed: " + e.getMessage(), e);
        }
        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("project", name);
        report.put("tickets_removed", tickets);
        report.put("archived_removed", archived);
        report.put("sprints_cleared", true);
        return report;
    }

    /**
     * U-038: park a ticket for maintenance - status {@code paused} (visible,
     * never blocked and never NEEDS-HUMAN), the reason in history. Resume is
     * a plain status update back to {@code in-progress}: the branch and its
     * checkpointed WIP survived the shutdown.
     */
    public Task setPaused(String project, String id, String reason, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.status = "paused";
            t.assignee = null;
            t.updatedAt = now();
            t.history("paused: " + (reason == null || reason.isBlank() ? "maintenance" : reason), by);
            data.changed.add(id);
            return t;
        });
    }

    /** Enters the blocked STATE with a reason (U-067: resume remembers the current status). */
    public Task setBlocked(String project, String id, String blocker, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if ("done".equals(t.status)) {
                throw new Invalid("a done ticket is never blocked (U-067: done is terminal)");
            }
            t.enterBlocked(blocker);
            t.updatedAt = now();
            t.history("blocked:" + blocker, by);
            data.changed.add(id);
            return t;
        });
    }

    /** Leaves the blocked STATE back to resume_to (U-067; default sprint-backlog). */
    public Task clearBlocked(String project, String id, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.leaveBlocked();
            t.updatedAt = now();
            t.history("unblocked", by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * Atomically claims the next matching task (-&gt; in-progress, assignee set).
     * Only sprint-backlog tasks of the given role that are not blocked are
     * claimable; ordering is priority desc, then created_at asc, then id.
     * Returns {@code null} when nothing is claimable (worker loops stop on this).
     */
    public Task claim(String project, String role, String status, String by) {
        String want = status == null ? "sprint-backlog" : status;
        if (!"sprint-backlog".equals(want)) {
            throw new Invalid("claim status must be one of [sprint-backlog], got '" + want + "'"
                    + " (only sprint-backlog tickets are claimable)");
        }
        return transaction(project, data -> {
            List<Task> candidates = new ArrayList<>();
            for (Task t : data.tasks.values()) {
                if (role.equals(t.role) && want.equals(t.status) && !t.isBlocked()) {
                    candidates.add(t);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            candidates.sort(Comparator
                    .comparing((Task t) -> -Task.PRIORITY_ORDER.getOrDefault(t.priority, 0))
                    .thenComparing(t -> t.createdAt == null ? Instant.EPOCH : t.createdAt)
                    .thenComparing(t -> t.id));
            Task t = candidates.get(0);
            String who = by == null || by.isBlank() ? role : by;
            t.status = "in-progress";
            t.assignee = who;
            t.updatedAt = now();
            t.history("claimed by " + who, by);
            data.changed.add(t.id);
            return t;
        });
    }

    /** Returns an unstarted (or in-progress) task to sprint-backlog; clears the assignee. */
    public Task release(String project, String id, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (!"sprint-backlog".equals(t.status) && !"in-progress".equals(t.status)) {
                throw new Invalid("cannot release ticket in status '" + t.status + "'");
            }
            t.status = "sprint-backlog";
            t.assignee = null;
            t.updatedAt = now();
            t.history("released by " + (by == null ? "?" : by), by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * Revalidates auto-dispatch readiness against the current whole-project
     * snapshot and, when explicitly allowed, reopens a STALE done ticket.
     * Readiness, status transition and its audit comment share one project
     * transaction/OS lock: upstream edits cannot land between check and write.
     * READY tickets and non-done STALE tickets are returned without a write.
     *
     * <p>This is preparation, not a claim. The caller must keep its dispatch
     * reservation through the subsequent launch; later project edits are still
     * allowed. A rejected preparation never changes the ticket.</p>
     *
     * @throws Invalid if readiness is neither READY nor explicitly allowed STALE
     * @throws NotFound if the ticket does not exist
     */
    public Task prepareAutoDispatch(String project, String id, boolean includeStale, String by) {
        return transaction(project, data -> {
            Task ticket = require(data, project, id);
            StageReadiness.Readiness verdict = StageReadiness.evaluate(
                    new ArrayList<>(data.tasks.values())).get(id);
            StageReadiness.Kind kind = verdict.kind();
            if (kind != StageReadiness.Kind.READY && !(includeStale && kind == StageReadiness.Kind.STALE)) {
                throw new Invalid("ticket " + id + " is not ready for auto-dispatch: " + kind + ": " + verdict.reason());
            }
            if ("done".equals(ticket.status)) {
                // A fresh done ticket is NOT_APPLICABLE and was rejected above.
                ticket.status = "sprint-backlog";
                ticket.assignee = null;
                ticket.updatedAt = now();
                ticket.history("reopened for stale rework", by);
                ticket.comments.add(new Task.Comment(ticket.updatedAt, by,
                        (by == null ? "auto-dispatch" : by) + " stale rework: " + verdict.reason()));
                data.changed.add(id);
            }
            return ticket;
        });
    }

    /** Appends a comment. */
    public Task addComment(String project, String id, String comment, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.comments.add(new Task.Comment(now(), by, comment));
            t.updatedAt = now();
            data.changed.add(id);
            return t;
        });
    }

    /** Records an artifact (file/git/path/url/doc) on a task. */
    public Task addArtifact(String project, String id, String kind, String ref, String note, String by) {
        if (!ARTIFACT_KINDS.contains(kind)) {
            throw new Invalid("kind must be one of ['doc', 'file', 'git', 'path', 'url'], got '" + kind + "'");
        }
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.artifacts.add(new Task.Artifact(kind, ref, note == null ? "" : note, by, now()));
            t.updatedAt = now();
            t.history("artifact:" + kind + ":" + ref, by);
            data.changed.add(id);
            return t;
        });
    }

    /**
     * Removes an artifact by 0-based index - the correction counterpart of
     * {@link #addArtifact}: a wrong ref is removed and re-recorded instead of
     * hand-edited in the ticket file (U-053). The removal is history-recorded
     * as {@code artifact removed:<kind>:<ref>}, so the audit trail keeps what
     * was dropped.
     *
     * @throws Invalid when the index is out of range (like {@link #removeTodo})
     */
    public Task removeArtifact(String project, String id, int index, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (index < 0 || index >= t.artifacts.size()) {
                throw new Invalid("artifact index " + index + " out of range (have "
                        + t.artifacts.size() + ")");
            }
            Task.Artifact removed = t.artifacts.remove(index);
            t.updatedAt = now();
            t.history("artifact removed:" + removed.kind() + ":" + removed.ref(), by);
            data.changed.add(id);
            return t;
        });
    }

    /** Appends a todo (checklist item; single-line text — newlines would corrupt the file format). */
    public Task addTodo(String project, String id, String text, boolean done, String by) {
        if (text == null || text.contains("\n") || text.contains("\r")) {
            throw new Invalid("todo text must be a single line (no newline characters)");
        }
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.todos.add(new Task.Todo(text, done));
            t.updatedAt = now();
            t.history("todo_added:" + text, by);
            data.changed.add(id);
            return t;
        });
    }

    /** Flips a todo's done flag by 0-based index. */
    public Task toggleTodo(String project, String id, int index, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (index < 0 || index >= t.todos.size()) {
                throw new Invalid("todo index " + index + " out of range (have " + t.todos.size() + ")");
            }
            Task.Todo old = t.todos.get(index);
            t.todos.set(index, new Task.Todo(old.text(), !old.done()));
            t.updatedAt = now();
            t.history("todo_toggled:" + index, by);
            data.changed.add(id);
            return t;
        });
    }

    /** Removes a todo by 0-based index. */
    public Task removeTodo(String project, String id, int index, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            if (index < 0 || index >= t.todos.size()) {
                throw new Invalid("todo index " + index + " out of range (have " + t.todos.size() + ")");
            }
            Task.Todo removed = t.todos.remove(index);
            t.updatedAt = now();
            t.history("todo_removed:" + removed.text(), by);
            data.changed.add(id);
            return t;
        });
    }

    /** Prioritized product backlog (product-backlog only; priority desc, created asc). */
    public List<Task> backlog(String project) {
        List<Task> out = list(project, null, "product-backlog", null, null);
        out.sort(prioritized());
        return out;
    }

    /** Wave Kanban grouped by status; all six status columns always present (paused = parked for maintenance). */
    public Map<String, List<Task>> board(String project, String sprint) {
        Map<String, List<Task>> out = new LinkedHashMap<>();
        for (String s : Task.VALID_STATUSES) {
            out.put(s, new ArrayList<>());
        }
        for (Task t : list(project, null, null, sprint, null)) {
            List<Task> column = out.get(t.status);
            if (column != null) {
                column.add(t);
            }
        }
        return out;
    }

    /**
     * Creates (or reuses) a sprint and commits the given tasks into it. Like
     * the pm server this force-sets every listed task to {@code sprint-backlog}
     * (even in-progress/done ones); all task ids are validated before the
     * first file is written.
     */
    public Task.Sprint planSprint(String project, String sprintId, List<String> ticketIds, String goal) {
        return transaction(project, data -> {
            String sid = sprintId == null || sprintId.isBlank() ? nextSprintId(data) : sprintId;
            if (ticketIds != null) {
                for (String tid : ticketIds) {
                    if (!data.tasks.containsKey(tid)) {
                        throw new Invalid("ticket " + tid + " not found");
                    }
                }
            }
            Task.Sprint sprint = data.sprints.get(sid);
            if (sprint == null) {
                sprint = new Task.Sprint(sid, goal == null ? "" : goal, "active", now(), null);
            } else if (goal != null && !goal.isEmpty()) {
                sprint = new Task.Sprint(sid, goal, sprint.status(), sprint.createdAt(), sprint.closedAt());
            }
            if (ticketIds != null) {
                for (String tid : ticketIds) {
                    Task t = data.tasks.get(tid);
                    if (t == null || t.isBlocked()) {
                        throw new Invalid("cannot plan ticket " + tid
                                + (t == null ? " (not found)" : " - it is blocked; clear it first"));
                    }
                    t.sprint = sid;
                    t.status = "sprint-backlog";
                    t.updatedAt = now();
                    t.history("planned into " + sid, null);
                    data.changed.add(tid);
                }
            }
            data.sprints.put(sid, sprint);
            data.metaDirty = true;
            return sprint;
        });
    }

    /**
     * Closes a sprint; unfinished tasks return to product-backlog. Done
     * tickets keep their sprint/status, but a stale blocked flag on one is
     * cleared (a done ticket is never blocked).
     */
    /**
     * Archives a ticket (user direction 2026-09-19: done tickets leave the
     * active board at some point): the {@code <id>.md} file moves to
     * {@code <project>/_archive/} — the archive keeps the full ticket
     * record (history, artifacts, actuals), but the live listing (and with
     * it the board, readiness and dispatch) no longer sees it. The loader
     * only scans top-level {@code *.md}, so the move is the whole trick.
     */
    public Map<String, Object> archive(String project, String id, String by) {
        return transaction(project, data -> {
            Task t = require(data, project, id);
            t.updatedAt = now();
            t.history("archived", by);
            try {
                Path archiveDir = data.dir.resolve("_archive");
                Files.createDirectories(archiveDir);
                Path target = archiveDir.resolve(id + ".md");
                if (!TaskFileCodec.isValidId(id)) {
                    throw new Invalid("refusing to archive ticket with unsafe id '" + id + "'");
                }
                writeAtomic(target, TaskFileCodec.write(t));
                Files.deleteIfExists(data.dir.resolve(id + ".md"));
            } catch (IOException e) {
                throw new UncheckedIo("cannot archive " + id + ": " + e.getMessage(), e);
            }
            data.tasks.remove(id);
            data.changed.remove(id);   // persist must not rewrite it to the live dir
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("archived", true);
            return out;
        });
    }

    /**
     * U-027 wave close with SAFE auto-archiving: the done tickets of the
     * closed wave are archived only when no live ticket of their epic chain
     * still needs them as upstream (archiving never orphans downstream
     * stages). The plain {@link #closeSprint(String, String)} never archives.
     *
     * @param autoArchive archive the unneeded done tickets of the wave
     * @return the plain close result plus {@code auto_archived} (the ids moved
     *         to {@code _archive/})
     */
    public Map<String, Object> closeSprint(String project, String sprintId, boolean autoArchive) {
        Map<String, Object> result = closeSprint(project, sprintId);
        if (!autoArchive) {
            return result;
        }
        List<String> archivedIds = new java.util.ArrayList<>();
        List<Task> live = list(project, null, null, null, null);
        for (Task candidate : live) {
            if (!"done".equals(candidate.status)) {
                continue;
            }
            boolean needed = live.stream()
                    .anyMatch(u -> u != candidate && !"done".equals(u.status)
                            && inSameEpicChain(u, candidate));
            if (!needed) {
                archive(project, candidate.id, "auto-archive");
                archivedIds.add(candidate.id);
            }
        }
        result.put("auto_archived", List.copyOf(archivedIds));
        return result;
    }

    /** The U-023/U-027 epic chain: the epic parent ({@code u.id == t.epic}) or a sibling ({@code u.epic == t.epic}). */
    static boolean inSameEpicChain(Task a, Task b) {
        if (a == null || b == null || a.id.equals(b.id)) {
            return false;
        }
        return a.id.equals(b.epic) || b.id.equals(a.epic)
                || a.epic != null && a.epic.equals(b.epic);
    }

    /**
     * The project's archived tickets ({@code _archive/*.md}), newest-last by
     * file name; read-only view over the same codec. An absent archive
     * directory reads as empty.
     */
    public List<Task> archived(String project) {
        Path dir = root.resolve(TaskStore.sanitizeProject(project)).resolve("_archive");
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.startsWith(".") && !n.startsWith("_");
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIo("cannot list archive " + dir, e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<Task> out = new ArrayList<>();
        for (Path p : files) {
            try {
                out.add(TaskFileCodec.read(Files.readString(p, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "skipping unparsable archived ticket " + p + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    public Map<String, Object> closeSprint(String project, String sprintId) {
        return transaction(project, data -> {
            Task.Sprint sprint = data.sprints.get(sprintId);
            if (sprint == null) {
                throw new NotFound("sprint " + sprintId + " not found");
            }
            List<String> returned = new ArrayList<>();
            for (Task t : data.tasks.values()) {
                if (!sprintId.equals(t.sprint)) {
                    continue;
                }
                if ("done".equals(t.status)) {
                    // NOT archived here (U-025 lesson, live 2026-09-19:
                    // archiving on wave close orphaned WAIT_UPSTREAM
                    // children). The opt-in overload closeSprint(project,
                    // sprintId, autoArchive) archives done tickets no live
                    // epic chain still needs, but no production caller uses
                    // it yet: readiness callers do not pass the archive pool
                    // (only StageReadiness.evaluate(live, archived) can).
                    continue;
                }
                t.sprint = null;
                if (t.isBlocked()) {
                    // U-067: a blocked ticket stays blocked on wave close -
                    // clearing it later returns it to the product backlog
                    t.resumeTo = "product-backlog";
                    t.history("returned from " + sprintId + " (stays blocked)", null);
                } else {
                    t.status = "product-backlog";
                    t.history("returned from " + sprintId, null);
                }
                t.updatedAt = now();
                data.changed.add(t.id);
                returned.add(t.id);
            }
            Task.Sprint closed = new Task.Sprint(sprintId, sprint.goal(), "closed",
                    sprint.createdAt(), now());
            data.sprints.put(sprintId, closed);
            data.metaDirty = true;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sprint", closed);
            out.put("returned_to_backlog", returned);
            return out;
        });
    }

    /**
     * The definition&lt;-&gt;verification traceability matrix (D-001 decision:
     * THREE pairing signals - a role+epic link, the ticket's own V-journey,
     * and test-shaped artifacts; pm requirements count as definitions). The
     * matrix row reports the signals ({@code via}) so the audit can tell a
     * real pair from inflation; a definition with no signal at all stays an
     * orphan.
     */
    public Map<String, Object> traceability(String project) {
        List<Task> tickets = list(project, null, null, null, null);
        Map<String, Task> byId = new HashMap<>();
        for (Task t : tickets) {
            byId.put(t.id, t);
        }
        List<Map<String, Object>> matrix = new ArrayList<>();
        List<String> orphanDefinitions = new ArrayList<>();
        List<String> orphanVerifications = new ArrayList<>();
        for (Task t : tickets) {
            List<String> links = new ArrayList<>();
            for (Task other : tickets) {
                if (t.id.equals(other.epic)) {
                    links.add(other.id);
                }
            }
            String verifies = null;
            List<String> verifiedBy = new ArrayList<>();
            List<String> via = new ArrayList<>();
            // signal 2: the ticket itself walked BOTH legs (a definition entry
            // then a test-* stage). advance() rewrites the role on the way into
            // the test leg, so this is a verification-role row pairing with its
            // own definition history - not an orphan.
            boolean bothLegs = enteredDefinitionLeg(t) && reachedTestStage(t);
            if (Task.VERIFICATION_ROLES.contains(t.role)) {
                verifies = t.epic != null && byId.containsKey(t.epic) ? t.epic
                        : (bothLegs ? t.id : null);
                if (verifies == null) {
                    orphanVerifications.add(t.id);
                } else {
                    via.add(t.id.equals(verifies) ? "journey" : "epic:" + verifies);
                }
            }
            boolean selfVerified = via.contains("journey");
            if (Task.DEFINITION_ROLES.contains(t.role)) {
                for (Task other : tickets) {
                    if (t.id.equals(other.epic) && Task.VERIFICATION_ROLES.contains(other.role)) {
                        verifiedBy.add(other.id);
                        via.add("epic:" + other.id);
                    }
                }
                // signal 3: it recorded a test-shaped artifact (*.tests/ path)
                if (hasTestArtifact(t)) {
                    selfVerified = true;
                    via.add("artifacts");
                }
                if (verifiedBy.isEmpty() && !selfVerified) {
                    orphanDefinitions.add(t.id);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id);
            row.put("role", t.role);
            row.put("status", t.status);
            row.put("epic", t.epic);
            row.put("links", links);
            row.put("verifies", verifies);
            row.put("verified_by", verifiedBy);
            row.put("self_verified", selfVerified);
            row.put("via", via);
            matrix.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matrix", matrix);
        out.put("ticket_count", tickets.size());
        out.put("orphan_definitions", orphanDefinitions);
        out.put("orphan_verifications", orphanVerifications);
        return out;
    }

    /** U-070: a recorded test-shaped artifact (a {@code *.tests/} path). */
    private static boolean hasTestArtifact(Task t) {
        if (t.artifacts == null) {
            return false;
        }
        for (Task.Artifact a : t.artifacts) {
            if (a != null && a.ref() != null && a.ref().contains(".tests/")) {
                return true;
            }
        }
        return false;
    }

    /** U-070: the ticket's own journey entered a test-* stage. */
    private static boolean reachedTestStage(Task t) {
        if (t.history == null) {
            return false;
        }
        for (Task.HistoryEvent e : t.history) {
            if (e != null && e.action() != null && e.action().startsWith("advanced to test-")) {
                return true;
            }
        }
        return false;
    }

    /** U-070: the journey entered the definition leg (advanced past requirements). */
    private static boolean enteredDefinitionLeg(Task t) {
        if (t.history == null) {
            return false;
        }
        for (Task.HistoryEvent e : t.history) {
            if (e == null || e.action() == null || !e.action().startsWith("advanced to ")) {
                continue;
            }
            String target = e.action().substring("advanced to ".length());
            if (!target.startsWith("test-") && VStages.isValid(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Persists the H6 invalidation markers: every ticket whose current
     * {@link StageReadiness} verdict is STALE, and whose last history event
     * is not already the matching marker, gets one appended - the action
     * {@code "inputs changed: upstream <id> updated <ts>"} names the changed
     * upstream and its updated_at, {@code by} is {@code "h6"} - plus an
     * updated_at touch, so the ticket itself shows why a re-run is due.
     * Idempotent: a second run without further upstream changes appends
     * nothing (the marked ticket's fresh updated_at is no longer older than
     * the upstream's; the last-event guard also catches same-millisecond
     * races). One transaction over the whole project; the verdicts come from
     * one snapshot, so cascading staleness (a marked ticket invalidating its
     * own downstream) is picked up by the next run.
     *
     * @return the number of tickets newly marked (0 when nothing is stale).
     */
    public int recordInvalidations(String project) {
        return transaction(project, data -> {
            List<Task> tickets = new ArrayList<>(data.tasks.values());
            Map<String, StageReadiness.Readiness> verdicts = StageReadiness.evaluate(tickets);
            int marked = 0;
            for (Task t : tickets) {
                StageReadiness.Readiness verdict = verdicts.get(t.id);
                if (verdict == null || verdict.kind() != StageReadiness.Kind.STALE) {
                    continue;
                }
                Task upstream = StageReadiness.staleCause(t, tickets);
                if (upstream == null) {
                    continue;
                }
                String action = "inputs changed: upstream " + upstream.id
                        + " updated " + Task.formatTs(upstream.updatedAt);
                if (!t.history.isEmpty() && action.equals(t.history.get(t.history.size() - 1).action())) {
                    continue;
                }
                t.updatedAt = now();
                t.history(action, INVALIDATION_BY);
                data.changed.add(t.id);
                marked++;
            }
            return marked;
        });
    }

    /**
     * The H6 dispatch-readiness report for the PM agent: one compact row per
     * ticket - {@code id}, {@code stage}, {@code kind}, {@code reason} from
     * {@link StageReadiness#evaluate} (tickets without a stage included as
     * NOT_APPLICABLE with a null stage) - ordered by kind severity (STALE,
     * BLOCKED, WAIT_UPSTREAM, RUNNING, READY, NOT_APPLICABLE) then id, so
     * "what's runnable right now" reads top-down.
     */
    public List<Map<String, Object>> readiness(String project) {
        List<Task> tickets = list(project, null, null, null, null);
        Map<String, StageReadiness.Readiness> verdicts = StageReadiness.evaluate(tickets);
        tickets.sort(Comparator
                .comparing((Task t) -> READINESS_SEVERITY.indexOf(verdicts.get(t.id).kind()))
                .thenComparing(t -> t.id));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task t : tickets) {
            StageReadiness.Readiness r = verdicts.get(t.id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id);
            row.put("stage", t.stage);
            row.put("kind", r.kind().name());
            row.put("reason", r.reason());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Store self-check (lint): human-readable reports of store damage and
     * inconsistent flag combinations - ticket files the store cannot parse
     * (every other read skips them, so a corrupted file would otherwise
     * vanish silently), tickets that are {@code done} but still carry the
     * blocked flag, tickets with a sprint set while sitting in
     * {@code product-backlog}, and {@code file}/{@code path} artifact refs
     * that do not resolve under the repo root (U-053: two corrupted refs sat
     * unnoticed for days). Glob/brace/enumerated/prose-like refs are skipped,
     * and so is the whole resolution check when the repo root cannot be
     * determined from the store root's layout - one report line per file or
     * ticket, sorted; empty when the store is consistent. Read-only.
     */
    public List<String> inconsistencies(String project) {
        if (projectDirectoryMissing(project)) {
            return new ArrayList<>();
        }
        List<Task> tasks = new ArrayList<>();
        List<String> out = new ArrayList<>(transaction(project, data -> {
            tasks.addAll(data.tasks.values());
            return data.unparsable;
        }));
        for (Task t : tasks) {
            if (t.sprint != null && "product-backlog".equals(t.status)) {
                out.add(t.id + ": status=product-backlog but sprint=" + t.sprint);
            }
        }
        Path repoRoot = repoRootOrNull();
        if (repoRoot != null) {
            for (Task t : tasks) {
                for (Task.Artifact a : t.artifacts) {
                    if (a.kind() == null || !PATH_ARTIFACT_KINDS.contains(a.kind())
                            || !isPlainPathRef(a.ref())) {
                        continue;
                    }
                    if (!artifactRefResolves(repoRoot, a.ref())) {
                        out.add(t.id + ": artifact " + a.kind() + " ref does not resolve under "
                                + repoRoot + ": " + a.ref());
                    }
                }
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * One-shot migration from the retired Python pm store document (one JSON
     * doc per project). Imports tickets, per-prefix counters and sprint
     * metadata; returns the number of imported tasks.
     */
    public int importPmJson(String project, String pmJson) {
        JsonElement parsed = JsonParser.parseString(pmJson);
        if (!parsed.isJsonObject()) {
            throw new Invalid("pm export must be a JSON object");
        }
        JsonObject doc = parsed.getAsJsonObject();
        return transaction(project, data -> {
            int count = 0;
            if (doc.has("tickets") && doc.getAsJsonObject("tickets").size() > 0) {
                for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("tickets").entrySet()) {
                    Task t = fromPmTicket(e.getValue().getAsJsonObject());
                    if (!TaskFileCodec.isValidId(t.id)) {
                        throw new Invalid("ticket '" + e.getKey() + "' has an unusable id '" + t.id
                                + "'; ids double as file names");
                    }
                    data.tasks.put(t.id, t);
                    data.changed.add(t.id);
                    count++;
                }
            }
            if (doc.has("seq")) {
                for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("seq").entrySet()) {
                    int v = e.getValue().getAsInt();
                    if (v > data.seq.getOrDefault(e.getKey(), 0)) {
                        data.seq.put(e.getKey(), v);
                    }
                }
                data.metaDirty = true;
            }
            if (doc.has("counter")) {
                data.counter = Math.max(data.counter, doc.get("counter").getAsInt());
                data.metaDirty = true;
            }
            if (doc.has("sprints")) {
                readSprints(doc, data);
                data.metaDirty = true;
            }
            return count;
        });
    }

    // ------------------------------------------------------------------
    // Transaction engine
    // ------------------------------------------------------------------

    /** {@code _meta.json}'s {@code sprints} map -> the in-memory sprint table (one parser for both load paths). */
    private static void readSprints(JsonObject doc, ProjectData data) {
        for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("sprints").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            data.sprints.put(e.getKey(), new Task.Sprint(
                    strOrNull(s, "id"), strOrNull(s, "goal"), strOrNull(s, "status"),
                    instantOrNull(s, "created_at"), instantOrNull(s, "closed_at")));
        }
    }

    private static final class ProjectData {
        final Map<String, Task> tasks = new LinkedHashMap<>();
        final Map<String, Task.Sprint> sprints = new LinkedHashMap<>();
        final Map<String, Integer> seq = new LinkedHashMap<>();
        int counter;
        final Set<String> changed = new HashSet<>();
        boolean metaDirty;
        final Path dir;
        /** Ticket files {@link #load} could not parse: "file: reason" (task_doctor reports them). */
        final List<String> unparsable = new ArrayList<>();

        ProjectData(Path dir) {
            this.dir = dir;
        }
    }

    /**
     * Whether the project's directory does not exist on disk yet - read
     * entry points short-circuit on this so they never materialize a
     * directory as a side effect (writes still create it, as they must).
     */
    private boolean projectDirectoryMissing(String project) {
        return !Files.isDirectory(root.resolve(sanitizeProject(project)));
    }

    /**
     * The repository root this store lives in, or null when it cannot be
     * determined. The store does not know its repo: it is by convention
     * rooted at {@code <repo>/.opencode/tasks} (the stdio launcher's default
     * and the Eclipse preference's derived root both use exactly that), so
     * the repo root is two levels up the store root's parents - but only
     * when the immediate parent really is a directory named
     * {@code .opencode}. Any other layout (a temp fixture, a custom root)
     * means the repo root is unknown, and every repo-root-dependent check
     * (task_doctor's artifact resolution) must skip rather than resolve refs
     * against an accidental parent directory.
     */
    private Path repoRootOrNull() {
        Path opencode = root.toAbsolutePath().normalize().getParent();
        if (opencode == null || opencode.getFileName() == null
                || !".opencode".equals(opencode.getFileName().toString())) {
            return null;
        }
        Path repo = opencode.getParent();
        return repo != null && Files.isDirectory(repo) ? repo : null;
    }

    /**
     * Whether an artifact ref looks like a plain path the doctor dares to
     * resolve: no glob or brace characters ({@code { } * ?}), no commas
     * (enumerations) and no spaces (prose). Anything else is left alone -
     * the doctor checks paths, not prose.
     */
    private static boolean isPlainPathRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        for (char c : ref.toCharArray()) {
            if (c == '{' || c == '}' || c == '*' || c == '?' || c == ',' || c == ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the ref resolves to an existing file under the repo root
     * (absolute refs resolve to themselves). A ref that cannot even form a
     * path (a {@code :} in a Windows-relative ref, a stray URL) counts as
     * unresolvable - that is exactly the corruption the doctor exists to
     * surface.
     */
    private static boolean artifactRefResolves(Path repoRoot, String ref) {
        try {
            return Files.exists(repoRoot.resolve(ref));
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private <T> T transaction(String project, Function<ProjectData, T> work) {
        Path dir = root.resolve(sanitizeProject(project));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIo("cannot create project directory " + dir, e);
        }
        Path lockFile = dir.resolve(".lock");
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(
                lockFile.toAbsolutePath().normalize(), k -> new ReentrantLock());
        jvmLock.lock();
        try {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock fileLock;
            try {
                fileLock = acquire(channel, lockFile);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            try {
                ProjectData data = load(dir);
                T result = work.apply(data);
                for (String changedId : data.changed) {
                    Task changed = data.tasks.get(changedId);
                    if (changed != null) {
                        normalizeBlockedState(changed);
                    }
                }
                if (!data.changed.isEmpty() || data.metaDirty) {
                    persist(data);
                }
                return result;
            } finally {
                try {
                    fileLock.release();
                } catch (IOException ignored) {
                    // lock dies with the channel anyway
                }
                channel.close();
            }
        } catch (IOException e) {
            throw new UncheckedIo("task store IO error in " + dir, e);
        } finally {
            jvmLock.unlock();
        }
    }

    private static FileLock acquire(FileChannel channel, Path lockFile) throws IOException {
        long deadline = System.nanoTime() + LOCK_TIMEOUT.toNanos();
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException e) {
                // Another channel in this JVM bypassed the reentrant lock; retry.
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("timed out acquiring the task store lock at " + lockFile);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for the task store lock", ie);
            }
        }
    }

    private ProjectData load(Path dir) {
        ProjectData data = new ProjectData(dir);
        Path meta = dir.resolve("_meta.json");
        if (Files.isRegularFile(meta)) {
            try {
                JsonObject doc = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
                if (doc.has("seq")) {
                    for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("seq").entrySet()) {
                        data.seq.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
                data.counter = doc.has("counter") ? doc.get("counter").getAsInt() : 0;
                if (doc.has("sprints")) {
                    readSprints(doc, data);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "unreadable _meta.json in " + dir + " (" + e.getMessage() + "); recovering", e);
            }
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".md") && !n.startsWith(".") && !n.startsWith("_");
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIo("cannot list " + dir, e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : files) {
            try {
                Task t = TaskFileCodec.read(Files.readString(p, StandardCharsets.UTF_8));
                data.tasks.put(t.id, t);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "skipping unparsable task file " + p + ": " + e.getMessage(), e);
                data.unparsable.add(p.getFileName() + ": unparsable ticket file, invisible to every other"
                        + " task_* read (" + e.getMessage() + ")");
            }
        }
        if (data.tasks.values().stream().anyMatch(t -> t.id != null)) {
            // One-time seq recovery when _meta.json is lost: never reuse ids.
            for (Task t : data.tasks.values()) {
                int dash = t.id.lastIndexOf('-');
                if (dash > 0) {
                    String prefix = t.id.substring(0, dash);
                    try {
                        int n = Integer.parseInt(t.id.substring(dash + 1));
                        if (n > data.seq.getOrDefault(prefix, 0)) {
                            data.seq.put(prefix, n);
                        }
                    } catch (NumberFormatException ignored) {
                        // non-numeric suffix: nothing to recover
                    }
                }
            }
        }
        return data;
    }

    private void persist(ProjectData data) throws IOException {
        for (String id : data.changed) {
            Task t = data.tasks.get(id);
            if (!TaskFileCodec.isValidId(id)) {
                // Defence in depth: the id is the file name, so an id that is not a
                // single safe path segment could steer the write out of the project dir.
                throw new Invalid("refusing to write ticket with unsafe id '" + id + "'");
            }
            Path target = data.dir.resolve(id + ".md");
            writeAtomic(target, TaskFileCodec.write(t));
        }
        if (data.metaDirty) {
            JsonObject meta = new JsonObject();
            JsonObject seq = new JsonObject();
            data.seq.forEach(seq::addProperty);
            meta.add("seq", seq);
            meta.addProperty("counter", data.counter);
            JsonObject sprints = new JsonObject();
            data.sprints.forEach((id, s) -> sprints.add(id, s.toJson()));
            meta.add("sprints", sprints);
            writeAtomic(data.dir.resolve("_meta.json"), GSON.toJson(meta));
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Task require(ProjectData data, String project, String id) {
        Task t = data.tasks.get(id);
        if (t == null) {
            throw new NotFound("ticket " + id + " not found in project " + project);
        }
        return t;
    }

    /**
     * U-067 invariant layer, applied on commit to every changed ticket:
     * resume_to exists ONLY while the ticket is in the blocked state (and a
     * done ticket never keeps stale blocker text - W-006/W-007). Entering
     * the state is a semantic decision (Task.enterBlocked); this only
     * removes what the state forbids.
     */
    private static void normalizeBlockedState(Task t) {
        if (!t.isBlocked()) {
            t.resumeTo = null;
        } else if (t.resumeTo != null && (!Task.VALID_STATUSES.contains(t.resumeTo)
                || "blocked".equals(t.resumeTo))) {
            t.resumeTo = null;
        }
        if ("done".equals(t.status) && t.blocker != null) {
            // the engine outcome is visible in history (the old
            // clearBlockedWhenDone contract, live incident W-006)
            t.blocker = null;
            t.history("unblocked (done)", null);
        }
    }

    private static String nextId(ProjectData data, String prefixRaw) {
        String prefix = prefixRaw == null ? "T" : prefixRaw.replaceAll("[^A-Za-z0-9_-]", "");
        if (prefix.isEmpty()) {
            prefix = "T";
        }
        int n = data.seq.getOrDefault(prefix, 0) + 1;
        // guard: if the candidate file was hand-deleted while seq was lost, bump past it
        while (data.tasks.containsKey(String.format("%s-%03d", prefix, n))) {
            n++;
        }
        data.seq.put(prefix, n);
        data.metaDirty = true;
        return String.format("%s-%03d", prefix, n);
    }

    private static String nextSprintId(ProjectData data) {
        data.counter = data.counter + 1;
        data.metaDirty = true;
        return String.format("S-%02d", data.counter);
    }

    private static Comparator<Task> prioritized() {
        return Comparator
                .comparing((Task t) -> -Task.PRIORITY_ORDER.getOrDefault(t.priority, 0))
                .thenComparing(t -> t.createdAt == null ? Instant.EPOCH : t.createdAt)
                .thenComparing(t -> t.id);
    }

    /** Project names become directory names: separators fold to underscores, traversal is rejected. */
    public static String sanitizeProject(String project) {
        if (project == null || project.isBlank()) {
            throw new Invalid("project must be a non-empty string");
        }
        String safe = project.replace("/", "_").replace("\\", "_").replace(":", "_");
        if (safe.matches("\\.+")) {
            throw new Invalid("invalid project name '" + project + "'");
        }
        return safe;
    }

    private static String string(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JsonElement e) {
            return e.isJsonNull() ? null : e.getAsString();
        }
        return String.valueOf(v);
    }

    private static int intOf(Object v) {
        if (v instanceof JsonElement e && !e.isJsonPrimitive()) {
            throw new Invalid("story_points must be an integer");
        }
        try {
            if (v instanceof JsonElement e) {
                return e.getAsInt();
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new Invalid("story_points must be an integer, got: " + v);
        }
    }

    /** Coerces an update value to a boolean: Boolean, JsonElement primitive, or string ("true"/"false"). */
    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof JsonElement e && e.isJsonPrimitive()) {
            return e.getAsBoolean();
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof JsonElement e && e.isJsonArray()) {
            for (JsonElement item : e.getAsJsonArray()) {
                out.add(item.isJsonNull() ? null : item.getAsString());
            }
        } else if (v instanceof JsonElement e && e.isJsonNull()) {
            return out; // explicit null clears the list
        } else if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                out.add(o == null ? null : String.valueOf(o));
            }
        } else {
            // Anything else (a bare string, number, object) used to silently
            // clear the list - a plausible agent mistake that lost data.
            throw new Invalid("expected an array of strings, got: " + v);
        }
        if (out.contains(null)) {
            throw new Invalid("list items must be non-null strings (a null item would corrupt the file format)");
        }
        return out;
    }

    /** Rejects null items up front so a create can never persist an unreadable file. */
    private static void requireNoNullItems(List<String> items, String field) {
        if (items == null) {
            return;
        }
        // Note: List.of(...).contains(null) throws NPE, so iterate explicitly.
        for (String item : items) {
            if (item == null) {
                throw new Invalid(field + " items must be non-null strings"
                        + " (a null item would corrupt the file format)");
            }
        }
    }

    private static Task fromPmTicket(JsonObject o) {
        Task t = new Task();
        t.id = strOrNull(o, "id");
        t.title = orEmpty(strOrNull(o, "title"));
        t.description = orEmpty(strOrNull(o, "description"));
        t.type = orDefault(strOrNull(o, "type"), "task");
        t.status = orDefault(strOrNull(o, "status"), "product-backlog");
        t.resumeTo = o.has("resume_to") && !o.get("resume_to").isJsonNull()
                ? o.get("resume_to").getAsString() : null;
        if (o.has("blocked") && !o.get("blocked").isJsonNull() && o.get("blocked").getAsBoolean()
                && !"blocked".equals(t.status)) {
            // U-067: the pm server still sends the legacy flag - migrate on
            // import exactly like TaskFileCodec does on file read
            t.resumeTo = t.status;
            t.status = "blocked";
        }
        t.blocker = strOrNull(o, "blocker");
        t.sprint = strOrNull(o, "sprint");
        t.storyPoints = o.has("story_points") && !o.get("story_points").isJsonNull()
                ? o.get("story_points").getAsInt() : 0;
        t.priority = orDefault(strOrNull(o, "priority"), "medium");
        t.role = orDefault(strOrNull(o, "role"), "developer");
        t.assignee = strOrNull(o, "assignee");
        t.epic = strOrNull(o, "epic");
        t.labels = jsonStrings(o, "labels");
        t.acceptanceCriteria = jsonStrings(o, "acceptance_criteria");
        if (o.has("todos")) {
            for (JsonElement e : o.getAsJsonArray("todos")) {
                JsonObject todo = e.getAsJsonObject();
                String text = strOrNull(todo, "text");
                // import sanitization: newlines would corrupt the Todos section
                if (text != null) {
                    text = text.replaceAll("[\\r\\n]+", " ");
                }
                t.todos.add(new Task.Todo(text,
                        todo.has("done") && todo.get("done").getAsBoolean()));
            }
        }
        if (o.has("artifacts")) {
            for (JsonElement e : o.getAsJsonArray("artifacts")) {
                JsonObject a = e.getAsJsonObject();
                t.artifacts.add(new Task.Artifact(strOrNull(a, "kind"), strOrNull(a, "ref"),
                        orEmpty(strOrNull(a, "note")), strOrNull(a, "by"), instantOrNull(a, "ts")));
            }
        }
        if (o.has("comments")) {
            for (JsonElement e : o.getAsJsonArray("comments")) {
                JsonObject c = e.getAsJsonObject();
                t.comments.add(new Task.Comment(instantOrNull(c, "ts"), strOrNull(c, "by"),
                        orEmpty(strOrNull(c, "text"))));
            }
        }
        if (o.has("history")) {
            for (JsonElement e : o.getAsJsonArray("history")) {
                JsonObject h = e.getAsJsonObject();
                t.history.add(new Task.HistoryEvent(instantOrNull(h, "ts"), strOrNull(h, "action"),
                        strOrNull(h, "by")));
            }
        }
        t.createdAt = instantOrNull(o, "created_at");
        t.updatedAt = instantOrNull(o, "updated_at");
        if (t.createdAt == null) {
            t.createdAt = now();
        }
        if (t.updatedAt == null) {
            t.updatedAt = t.createdAt;
        }
        return t;
    }

    private static List<String> jsonStrings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                out.add(e.isJsonNull() ? null : e.getAsString());
            }
        }
        return out;
    }

    private static String strOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Instant instantOrNull(JsonObject o, String key) {
        String s = strOrNull(o, key);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s).truncatedTo(ChronoUnit.MILLIS);
        } catch (java.time.format.DateTimeParseException e) {
            // tolerate the Python store's "+00:00" offsets
            try {
                return java.time.OffsetDateTime.parse(s).toInstant().truncatedTo(ChronoUnit.MILLIS);
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orDefault(String s, String dflt) {
        return s == null ? dflt : s;
    }

    /** IOException wrapped into a runtime exception (store-level failure, not an agent-visible error). */
    public static final class UncheckedIo extends RuntimeException {
        UncheckedIo(String message, IOException cause) {
            super(message, cause);
        }
    }
}
