package com.opencode.ide.tasks;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.regex.Pattern;

/**
 * The Markdown task store: one directory per project under the configured root, one {@code <id>.md}
 * file per task (see {@link TaskFileCodec}), plus a {@code _meta.json} sidecar holding the
 * per-prefix id counters (the board itself is derived from the tasks).
 *
 * <p>Layout:
 *
 * <pre>
 * &lt;root&gt;/
 *   &lt;project&gt;/
 *     _meta.json     # {"seq": {"T": 5}, "counter": 2}
 *     .lock          # cross-process lock file (never deleted)
 *     T-001.md
 *     T-002.md
 * </pre>
 *
 * <h2>Congestion and atomicity</h2>
 *
 * Every operation runs inside a <em>directory-wide transaction</em>: an in-JVM {@link
 * ReentrantLock} per lock file (shared across store instances) plus an OS {@link FileLock} on
 * {@code <project>/.lock} so the Eclipse-hosted endpoint and the standalone stdio tool (separate
 * processes) serialize. Writes go to a temp file in the same directory followed by {@code
 * Files.move(REPLACE_EXISTING)} - on Windows this is the safe replace pattern ({@code ATOMIC_MOVE +
 * REPLACE_EXISTING} is undefined per spec). Multi-file operations validate every precondition
 * before writing the first file so a rejected call never leaves partial state; a crash mid-write
 * can, and is documented as such.
 *
 * <h2>Semantics</h2>
 *
 * Field names, claim ordering (priority desc, then created_at asc, then id), lax update rules and
 * the traceability pairing mirror the retired Python {@code pm} MCP server; the STATUS MODEL is the
 * owner's 2026-11-20 seven-state pump - no backlogs, born ready (or paused while the basis is
 * open), see {@link Task#VALID_STATUSES} - and supersedes the pm statuses. The skills in {@code
 * .opencode/skills/pm-*} predate that model.
 */
public final class TaskStore {

  /** Task id not found (maps to an isError tool result, like the pm server's KeyError). */
  public static final class NotFound extends RuntimeException {
    NotFound(String message) {
      super(message);
    }
  }

  /**
   * Invalid value / state transition (maps to an isError tool result, like the pm server's
   * ValueError).
   */
  public static final class Invalid extends RuntimeException {
    Invalid(String message) {
      super(message);
    }
  }

  /** Immutable create-parameters record (all optional except title). */
  public record CreateSpec(
      String title,
      String description,
      String type,
      String role,
      String priority,
      Integer storyPoints,
      List<String> acceptanceCriteria,
      List<String> labels,
      String idPrefix,
      Boolean blocked,
      String blocker,
      String basis,
      String review) {

    /**
     * Pre-T-155 12-arg shape (no review field). Kept so existing call sites keep their signature;
     * new code uses the canonical 13-arg form.
     */
    public CreateSpec(
        String title,
        String description,
        String type,
        String role,
        String priority,
        Integer storyPoints,
        List<String> acceptanceCriteria,
        List<String> labels,
        String idPrefix,
        Boolean blocked,
        String blocker,
        String basis) {
      this(
          title,
          description,
          type,
          role,
          priority,
          storyPoints,
          acceptanceCriteria,
          labels,
          idPrefix,
          blocked,
          blocker,
          basis,
          null);
    }

    /**
     * Legacy 10-arg shape (pre B-026/U-110): no blocked state and no basis link at birth. Kept so
     * existing call sites keep their signature; new code uses the canonical 13-arg form.
     */
    public CreateSpec(
        String title,
        String description,
        String type,
        String role,
        String priority,
        Integer storyPoints,
        List<String> acceptanceCriteria,
        List<String> labels,
        String idPrefix) {
      this(
          title,
          description,
          type,
          role,
          priority,
          storyPoints,
          acceptanceCriteria,
          labels,
          idPrefix,
          null,
          null,
          null);
    }

    /** Builder-ish factory with pm defaults. */
    public static CreateSpec of(String title) {
      return new CreateSpec(title, "", "task", "developer", "medium", 0, List.of(), List.of(), "T");
    }
  }

  /**
   * Now, truncated to the store's millisecond precision so in-memory state always equals the
   * persisted state.
   */
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

  /**
   * A doc artifact ref shaped like a ticket id (T-224 check 5, rubberduck N4): uppercase prefix,
   * dash, digits - the shape of every minted id ({@code T-224}, {@code A-001}, {@code AE-6}). Only
   * such refs are validated as ticket references; paths, TEMP logs, git refs, urls and prose-like
   * refs are not ticket ids and stay out of the check (the doctor checks paths as paths, not
   * prose).
   */
  private static final Pattern TICKET_ID_REF = Pattern.compile("[A-Z]+-\\d+");

  private static final String INVALIDATION_BY = "h6";

  /** The readiness report's severity order: most urgent kind first. */
  private static final List<StageReadiness.Kind> READINESS_SEVERITY =
      List.of(
          StageReadiness.Kind.STALE,
          StageReadiness.Kind.BLOCKED,
          StageReadiness.Kind.WAIT_UPSTREAM,
          StageReadiness.Kind.RUNNING,
          StageReadiness.Kind.READY,
          StageReadiness.Kind.NOT_APPLICABLE);

  private final Path root;

  /**
   * @param root the store root; each project gets a subdirectory (created on demand).
   */
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

  /**
   * Creates a task (born {@code ready} - or born {@code paused} while its basis is open, or born
   * blocked when blocked=true, U-067; owner model 2026-11-20: no backlogs exist) with a freshly
   * minted id.
   */
  public Task create(String project, CreateSpec spec) {
    return create(project, spec, null);
  }

  /**
   * Creates a task with an initial V-model pipeline {@code stage} (validated against {@link
   * VStages}; {@code null} leaves the ticket untracked, exactly like a legacy ticket).
   */
  public Task create(String project, CreateSpec spec, String stage) {
    if (spec.role() != null && (spec.role().isBlank())) {
      throw new Invalid("role must be a non-empty string, got '" + spec.role() + "'");
    }
    requireType(spec.type());
    requirePriority(spec.priority());
    if (stage != null && !VStages.isValid(stage)) {
      throw new Invalid(
          "stage must be one of " + VStages.STAGES + " (or null), got '" + stage + "'");
    }
    boolean bornBlocked = Boolean.TRUE.equals(spec.blocked());
    if (bornBlocked && (spec.blocker() == null || spec.blocker().isBlank())) {
      throw new Invalid("blocked=true requires a non-empty blocker reason (B-026)");
    }
    if (spec.blocker() != null && !spec.blocker().isBlank() && !bornBlocked) {
      throw new Invalid("blocker requires blocked=true (B-026)");
    }
    // U-107 (T-029 decision): ids are minted as prefix + "-%03d" - a prefix that is blank or
    // ends in '-' produces a malformed id (C--001). Reject loudly; never silently sanitize.
    if (spec.idPrefix() != null) {
      String trimmedPrefix = spec.idPrefix().trim();
      if (trimmedPrefix.isEmpty() || trimmedPrefix.endsWith("-")) {
        throw new Invalid(
            "id_prefix must not be blank or end in '-' (it is minted as <prefix>-NNN; got '"
                + spec.idPrefix()
                + "')");
      }
    }
    requireNoNullItems(spec.acceptanceCriteria(), "acceptance_criteria");
    requireNoNullItems(spec.labels(), "labels");
    return transaction(
        project,
        data -> {
          String id = nextId(data, spec.idPrefix() == null ? "T" : spec.idPrefix());
          Task t = new Task();
          t.id = id;
          // T-175 (owner directive 2026-11-17): the store encodes the stage in the NAME - a
          // staged ticket's title carries its `[stage] ` tag from birth, so every creation path
          // (division, pairing, goal intake, import, chat) inherits it without knowing.
          t.title = encodeStageTag(spec.title(), stage);
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

          if (spec.basis() != null && !linkExists(project, spec.basis(), data)) {
            throw new Invalid(
                "basis ticket " + spec.basis() + " not found in project " + project + " (U-110)");
          }
          t.basis = spec.basis();
          // Owner rule 2026-11-20 (no backlogs): birth computes the state - ready when nothing
          // upstream is open, paused while the basis is still open (the dependency wait; the
          // basis's completion resumes it - the auto-resume in update()).
          if (spec.basis() != null && !basisSatisfied(data, spec.basis())) {
            t.status = "paused";
          }
          // T-155: review: human|ai - validated LOUDLY at the mint point, and `human`
          // only on the first/last stage (or stageless) per the owner rule of 2026-11-17
          t.review = normalizeReview(spec.review());
          if ("human".equals(t.review)) {
            requireHumanReviewStageAllowed(stage);
          }
          if (bornBlocked) {
            // B-026/U-067: blocked is a STATE entered at birth - the status moves to
            // blocked and resume_to remembers the birth state (ready/paused) for the one door out.
            t.enterBlocked(spec.blocker());
          }
          t.createdAt = now();
          t.updatedAt = t.createdAt;
          t.inputsChangedAt = t.createdAt; // D-003: a new ticket's inputs start at birth
          t.history("created", null);
          data.tasks.put(id, t);
          data.changed.add(id);
          return t;
        });
  }

  /**
   * Gets one task by id. A project that does not exist on disk is "no such ticket" - reads never
   * materialize the project directory (review S5).
   *
   * <p>T-240: the RETURNED ticket is the live pool (the working set - board/readiness/dispatch
   * semantics are untouched), but the VERDICT is honest about WHY an id missed: an id parked in
   * {@code _archive/} is history, not a hole, and names its archive file ({@link #notFound}). A
   * genuinely absent id keeps the exact historical message.
   */
  public Task get(String project, String id) {
    if (projectDirectoryMissing(project)) {
      throw new NotFound("ticket " + id + " not found in project " + project);
    }
    return transaction(
        project,
        data -> {
          Task t = data.tasks.get(id);
          if (t == null) {
            throw notFound(data, project, id);
          }
          return t;
        });
  }

  /**
   * The project subdirectory names of this store root (one per directory that looks like a project:
   * contains at least one {@code .md} ticket file OR the store's own {@code _meta.json} sidecar).
   * Sorted; never null; the empty store yields an empty list.
   *
   * <p>T-226 (N2): {@code _meta.json} counts as project evidence on purpose - {@link #newProject}
   * writes exactly that sidecar (plus the directory) and {@link #resetProject} removes every ticket
   * while KEEPING the sidecar, so the old {@code >=1 *.md} rule hid a freshly scaffolded project
   * until its first ticket landed, and made a reset project vanish from the list even though it
   * still exists on disk (and cannot be re-created - {@link #newProject} refuses an existing
   * directory). A directory with neither marker (a stray folder, a phantom left by some old write)
   * stays invisible.
   */
  public List<String> projects() {
    try (var stream = java.nio.file.Files.list(root)) {
      return stream
          .filter(java.nio.file.Files::isDirectory)
          .filter(
              dir -> {
                try (var files = java.nio.file.Files.list(dir)) {
                  return files.anyMatch(
                      p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".md") || "_meta.json".equals(name);
                      });
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
   * Lists tasks with optional role/status/blocked filters (creation order). A project that does not
   * exist on disk reads as empty - reads never materialize the project directory (review S5:
   * task_doctor on a typo'd project used to create it).
   */
  public List<Task> list(String project, String role, String status, Boolean blocked) {
    if (projectDirectoryMissing(project)) {
      return new ArrayList<>(); // mutable: callers sort in place (readiness, ready-queue)
    }
    List<Task> out =
        new ArrayList<>(transaction(project, data -> new ArrayList<>(data.tasks.values())));
    if (role != null) {
      out.removeIf(t -> !role.equals(t.role));
    }
    if (status != null) {
      out.removeIf(t -> !status.equals(t.status));
    }
    if (blocked != null) {
      out.removeIf(t -> t.isBlocked() != blocked);
    }
    return out;
  }

  /**
   * The birth/resume test (owner model 2026-11-20): a basis counts as satisfied once it reached a
   * verdict - {@code in-review} (engine verdict pending) or a terminal state. An absent basis
   * cannot hold a ticket hostage (create() already validated the link when it was set).
   */
  private static boolean basisSatisfied(ProjectData data, String basisId) {
    if (basisId == null) {
      return true;
    }
    Task b = data.tasks.get(basisId);
    return b == null || "in-review".equals(b.status) || b.isTerminal();
  }

  /**
   * The ticket's VERDICT (T-225): its status, or - while it sits in the blocked state - the {@code
   * resume_to} it remembers there. The chain walk deliberately re-opens terminal and paused hops
   * (T-221: setBlocked's chain loop has no terminal/paused check, pinned by ChainBlockTest), so a
   * chain-blocked done ticket is still a done ticket for the terminal guards below.
   */
  private static String verdictStatus(Task t) {
    return t.isBlocked() && t.resumeTo != null ? t.resumeTo : t.status;
  }

  /** The terminal verdict pair (owner model 2026-11-20): {@code done} / {@code not-implemented}. */
  private static boolean terminalStatus(String status) {
    return "done".equals(status) || "not-implemented".equals(status);
  }

  /**
   * T-226 (S1): whether the readiness verdict GATES this claim candidate on its basis. Only a
   * basis-linked ticket can be gated: {@link StageReadiness} routes basis-linked rows to the
   * derivation verdict (WAIT_UPSTREAM until the basis is done/in-review AND carries its artifact,
   * STALE when the basis changed after this ticket ran), while a basis-less row's WAIT_UPSTREAM is
   * the legacy flow-history signal - a scan concern, not a claim gate (see {@link #claim}). The
   * remaining gate kinds never reach this predicate for a claim candidate: BLOCKED == isBlocked()
   * (filtered before) and RUNNING needs a non-ready status (filtered before).
   */
  private static boolean basisGated(Task t, Map<String, StageReadiness.Readiness> verdicts) {
    if (t.basis == null) {
      return false;
    }
    StageReadiness.Readiness verdict = verdicts.get(t.id);
    if (verdict == null) {
      return false;
    }
    return verdict.kind() == StageReadiness.Kind.WAIT_UPSTREAM
        || verdict.kind() == StageReadiness.Kind.STALE;
  }

  /**
   * Lax update of mutable fields, matching the pm server: protected fields ({@code id}, {@code
   * created_at}, {@code history}, {@code comments}) are silently dropped, explicit nulls clear the
   * nullable fields (a null {@code priority} restores the {@code medium} default and a null {@code
   * type} the {@code task} default, like create), the constrained fields are validated ({@code
   * role}, {@code status}, {@code priority} (T-249), {@code type} (T-253), {@code stage}, {@code
   * review}, {@code basis}, integer {@code story_points}), and no ENTRY transition graph is
   * enforced (any LIVE status may be requested - the enforced edges are the two refusals below).
   * One rule beyond validation: {@code status} and {@code blocked} move as ONE state transition
   * (U-067) - blocked=true enters the blocked state (resume = the requested or current status),
   * blocked=false leaves it - and a ticket whose VERDICT ({@link #verdictStatus}) is {@code done}
   * or {@code not-implemented} can never be blocked by DIRECT entry (structural - live incidents
   * W-006/W-007) nor EXIT its terminal status (T-225: it would become claimable again,
   * contradicting {@link Task#isTerminal()}). The chain walk remains the one deliberate exception:
   * it re-opens terminal and paused hops, keeping their pre-block verdict in {@code resume_to}
   * (T-221, pinned by ChainBlockTest). No caller re-opens a terminal ticket through this verb (grep
   * 2026-11-26: task_update, the Board's setStatus/setStage drops and the /goal verbs only ENTER a
   * terminal status, move a live one, or resume {@code paused -> ready}; there is no
   * archive/restore verb) - if a reopen is ever needed it must be an explicit verb that records
   * history. Divergence: unknown fields are dropped rather than stored (the file format keeps
   * unknown <em>keys</em> from hand edits, but tool updates cannot introduce new ones).
   *
   * @param changes snake_case field name -> new value (String/Number/Boolean/List/JsonElement)
   */
  public Task update(String project, String id, Map<String, Object> changes) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          String priorStatus = t.status;
          String priorAssignee = t.assignee;
          List<String> applied = new ArrayList<>();
          String requestedStatus = null;
          Boolean requestedBlocked = null;
          for (Map.Entry<String, Object> e : changes.entrySet()) {
            switch (e.getKey()) {
              case "title" -> {
                // T-175: the tag follows the CURRENT stage - a rename re-states it, never drops it
                t.title = encodeStageTag(string(e.getValue()), t.stage);
                applied.add(e.getKey());
              }
              case "description" -> {
                t.description = string(e.getValue());
                applied.add(e.getKey());
              }
              case "type" -> {
                // T-253: the enum gate at the verb - create validated, update assigned as-is, so
                // 'banana' reached the store through McpDispatcher (the inputSchema enum is
                // declarative and never enforced there); now one shared gate, one message.
                String type = string(e.getValue());
                requireType(type);
                // null follows create's default (never a `type: null` row on disk)
                t.type = type == null ? "task" : type;
                applied.add(e.getKey());
              }
              case "priority" -> {
                // T-249: the enum gate at the verb - create validated, update used to assign
                // as-is, so 'urgent' reached the store through McpDispatcher (the inputSchema
                // enum is declarative and never enforced there) and sorted as weight 0.
                String priority = string(e.getValue());
                requirePriority(priority);
                // null follows create's default (never a `priority: null` row on disk)
                t.priority = priority == null ? "medium" : priority;
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
                  throw new Invalid(
                      "stage must be one of " + VStages.STAGES + " (or null), got '" + stage + "'");
                }
                t.stage = stage;
                // T-175: the name tag follows the move (and is stripped when the stage clears)
                t.title = encodeStageTag(t.title, stage);
                applied.add(e.getKey());
              }
              case "model" -> {
                t.model = string(e.getValue());
                applied.add(e.getKey());
              }
              case "review" -> {
                // T-155: normalized/validated loudly here; the stage constraint is
                // checked after the loop (the stage is immutable through this verb)
                t.review = normalizeReview(string(e.getValue()));
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

              // Owner rule 2026-11-19: re-joining is a VERB, not a hand edit - basis is
              // re-pointable through task_update (validated live+archive, null clears), so
              // chains stay correctable without escaping the API.
              case "basis" -> {
                String b = string(e.getValue());
                if (b != null && !linkExists(project, b, data)) {
                  throw new Invalid(
                      "basis ticket " + b + " not found in project " + project + " (U-110)");
                }
                t.basis = b;
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
              default -> {
                /* protected or unknown: silently dropped (pm parity) */
              }
            }
          }
          // The claim door (T-113 double-claim, 2026-11-16): task_claim stays the
          // atomic path; a bare update may not TAKE a live claim - status ->
          // in-progress while a different actor already holds the assignee is
          // refused check-before-act. Release (assignee -> null), idempotent
          // re-writes by the same holder, and the first claim stay open.
          if ("in-progress".equals(requestedStatus)
              && "in-progress".equals(priorStatus)
              && priorAssignee != null
              && t.assignee != null
              && !priorAssignee.equals(t.assignee)) {
            throw new Invalid(
                "already claimed by " + priorAssignee + " - task_release first (T-113)");
          }
          // U-067: the state door - one coherent move regardless of the
          // key order the caller used (enter with a retargeted resume, or
          // leave back to resume_to; a plain status move leaves cleanup to
          // the commit-time invariant)
          // T-225: the VERDICT (status, or resume_to while blocked - the chain walk keeps a
          // terminal hop's verdict there) decides the terminal guards below.
          String verdict = verdictStatus(t);
          if (requestedBlocked != null && requestedBlocked) {
            if (terminalStatus(verdict)) {
              throw new Invalid(
                  "a "
                      + verdict
                      + " ticket is never blocked by direct entry (U-067:"
                      + " done/not-implemented are terminal; the chain walk re-opens it with"
                      + " resume_to)");
            }
            if ("paused".equals(t.status)) {
              throw new Invalid(
                  "a paused ticket is never blocked by direct entry (U-038:"
                      + " paused is the dependency wait - resume it first)");
            }
            String resume =
                requestedStatus != null && !"blocked".equals(requestedStatus)
                    ? requestedStatus
                    : t.status;
            t.enterBlocked(t.blocker);
            if (!"blocked".equals(resume)) {
              t.resumeTo = resume;
            }
          } else if (requestedBlocked != null && t.isBlocked()) {
            t.leaveBlocked();
          } else if (requestedStatus != null) {
            // U-067/U-038: a bare status write may not enter the blocked STATE from a
            // terminal or parked status either - the invariant holds on every entry path,
            // not only on task_set_blocked and the blocked-flag door above. The verdict
            // covers the chain-blocked hop too (its status reads "blocked"; its terminal
            // verdict lives in resume_to).
            if ("blocked".equals(requestedStatus)
                && (terminalStatus(verdict) || "paused".equals(t.status))) {
              throw new Invalid(
                  "a "
                      + (terminalStatus(verdict) ? verdict : t.status)
                      + " ticket is never blocked by direct entry (U-067/U-038"
                      + (terminalStatus(verdict) ? ": terminal" : ": resume it first")
                      + ")");
            }
            // T-225 (S4): terminal is a one-way door through this verb - exiting a terminal
            // verdict to a live status silently un-terminates the ticket (it becomes claimable
            // again, contradicting Task.isTerminal: "neither is ever claimable"). ENTERING a
            // terminal status from a live one and terminal -> terminal re-verdicts stay open.
            if (terminalStatus(verdict) && !terminalStatus(requestedStatus)) {
              throw new Invalid(
                  "ticket "
                      + id
                      + " cannot exit terminal status '"
                      + verdict
                      + "' to '"
                      + requestedStatus
                      + "' (T-225: done/not-implemented are one-way through task_update -"
                      + " reopening needs an explicit verb that records history)");
            }
            t.status = requestedStatus;
          }
          // Owner rule 2026-11-20 (the pump): the moment a ticket completes, the tickets
          // WAITING on it resume - paused -> ready, automatically.
          if ("done".equals(requestedStatus) || "not-implemented".equals(requestedStatus)) {
            for (Task u : data.tasks.values()) {
              if (u != t && "paused".equals(u.status) && t.id.equals(u.basis)) {
                u.status = "ready";
                u.updatedAt = now();
                u.history("resumed: basis " + t.id + " completed", null);
                data.changed.add(u.id);
              }
            }
          }
          // U-110: done-time enforcement - the DEFINITION stage's product is the point of the V,
          // so a definition-stage ticket cannot reach done bare (the old advance-time gate moved
          // here; verification stages and legacy unstaged tickets are exempt).
          if ("done".equals(t.status)
              && t.stage != null
              && !VStages.isVerification(t.stage)
              && (t.artifacts == null || t.artifacts.isEmpty())) {
            throw new Invalid(
                "cannot done a definition-stage ticket ('"
                    + t.stage
                    + "') without a recorded artifact: record the stage's product (or its"
                    + " rationale) with task_add_artifact first");
          }
          if ("human".equals(t.review)) {
            requireHumanReviewStageAllowed(t.stage);
          }
          if (!applied.isEmpty()) {
            t.updatedAt = now();
            // D-003 option B: only status/stage/basis/role writes are INPUT changes - they
            // bump the inputs stamp; title/model/assignee/labels & co. stay bookkeeping (W-006).

            if (applied.contains("status")
                || applied.contains("stage")
                || applied.contains("basis")
                || applied.contains("role")) {
              t.inputsChangedAt = t.updatedAt;
            }
            t.history("updated:" + String.join(",", applied), null);
            data.changed.add(id);
          }
          return t;
        });
  }

  /**
   * T-155: normalizes/validates the review field AT THE VERB - {@code human}/{@code ai}, blank
   * becomes {@code null} (= the ai/engine default); anything else is rejected loudly, never
   * sanitized (U-107/U-109 pattern).
   */
  private static String normalizeReview(String review) {
    if (review == null || review.isBlank()) {
      return null;
    }
    String v = review.trim();
    if (!"human".equals(v) && !"ai".equals(v)) {
      throw new Invalid(
          "review must be 'human' or 'ai' (absent = ai = engine verdict), got '" + review + "'");
    }
    return v;
  }

  /**
   * The priority enum gate shared by {@link #create} and {@link #update} (T-249): any non-null
   * value outside {@link Task#PRIORITY_ORDER}'s key set is refused with ONE message wording, so the
   * two verbs cannot drift (the update side used to be a bare assignment - the T-241 probe
   * persisted priority 'urgent' through McpDispatcher, where it then sorted as weight 0). The
   * message lists the values explicitly instead of printing the key set because {@link
   * Task#PRIORITY_ORDER} is a {@code Map.of} - keySet iteration order is unspecified. {@code null}
   * passes through: create defaults it to {@code medium} at assignment, and update's case does the
   * same, so no {@code priority: null} row can ever be written.
   */
  private static void requirePriority(String priority) {
    if (priority != null && !Task.PRIORITY_ORDER.containsKey(priority)) {
      throw new Invalid(
          "priority must be one of "
              + List.of("low", "medium", "high", "critical")
              + ", got '"
              + priority
              + "'");
    }
  }

  /**
   * The type enum gate shared by {@link #create} and {@link #update} (T-253) - the exact twin of
   * {@link #requirePriority}: create validated type at the mint point while update assigned it
   * as-is, so the T-249 review's {@code type:'banana'} probe persisted through McpDispatcher (the
   * inputSchema enum is declarative there, never enforced). The message is byte-identical to the
   * check this call replaced inside create, so the two verbs cannot drift. {@link Task#VALID_TYPES}
   * is an ordered {@code List} (unlike the {@code Map.of} behind {@link #requirePriority}), so
   * printing it is stable. {@code null} passes through: create defaults it to {@code task} at
   * assignment, and update's case does the same, so no {@code type: null} row can ever be written.
   */
  private static void requireType(String type) {
    if (type != null && !Task.VALID_TYPES.contains(type)) {
      throw new Invalid("type must be one of " + Task.VALID_TYPES + ", got '" + type + "'");
    }
  }

  /**
   * T-155/AC3 (owner rule 2026-11-17): {@code review: human} may live only on the FIRST stage, the
   * LAST stage, or a stageless ticket - a mid-V ticket never parks waiting for a human: there the
   * agent escalates back to the origin or the work passes on (division).
   */
  private static void requireHumanReviewStageAllowed(String stage) {
    if (stage == null || stage.isEmpty()) {
      return;
    }
    String first = VStages.STAGES.get(0);
    String last = VStages.STAGES.get(VStages.STAGES.size() - 1);
    if (first.equals(stage) || last.equals(stage)) {
      return;
    }
    throw new Invalid(
        "review: human is only allowed on the first stage ('"
            + first
            + "'), the last stage ('"
            + last
            + "') or a stageless ticket (owner rule 2026-11-17) - mid-V tickets move by"
            + " escalation to the origin or by division; got stage '"
            + stage
            + "'");
  }

  /**
   * T-175 (owner directive 2026-11-17): the store encodes the stage in the ticket NAME - a staged
   * ticket's title carries a leading {@code [<stage>]} tag so the stage is visible wherever the
   * name shows (chat listings, board, comments) without opening the frontmatter. Every write point
   * routes through this: create (birth tag), title update (a rename cannot drop the tag), stage
   * update (the tag follows a Board owner-override move; clearing the stage strips it). Idempotent
   * (a known tag is stripped before re-adding - never doubled), tag-exclusive (only {@link VStages}
   * tags are recognized - a caller's own {@code [WIP] ...} bracket survives to the right of the
   * stage tag), and never empties a title (a name that is nothing but its tag stays when there is
   * no stage to restate). Stageless tickets never carry a V-stage tag. Existing tickets are NOT
   * rewritten here - they gain/lose the tag on their next title/stage write (the directive is "for
   * the future").
   */
  public static String encodeStageTag(String title, String stage) {
    if (title == null) {
      return null;
    }
    String base = stripStageTag(title);
    if (stage == null || stage.isBlank()) {
      return base.isEmpty() ? title : base;
    }
    if (base.isEmpty()) {
      return "[" + stage + "]";
    }
    return "[" + stage + "] " + base;
  }

  /** Removes a leading {@code [<VStages>]} tag (with or without trailing text); others stay. */
  private static String stripStageTag(String title) {
    for (String s : VStages.STAGES) {
      if (title.startsWith("[" + s + "] ")) {
        return title.substring(s.length() + 3);
      }
      if (title.equals("[" + s + "]")) {
        return "";
      }
    }
    return title;
  }

  /** B-007 FR-009/010: originator retries per stage visit before doubt escalates to a human. */
  public static final int REVIEW_DOUBT_RETRY_LIMIT = 1;

  /**
   * B-007 doubt round-trip (FR-009..FR-012): reviewer doubt - or a heuristic checkpoint refusal
   * (FR-011) - routes back to the ORIGINATOR instead of blocking for a human. The ticket returns to
   * ready (same mechanics as a stage advance, without the stage move: ready, assignee cleared) with
   * the doubt recorded as an attributable comment and a {@code review doubt retry (n/N)} history
   * marker. Exactly {@link #REVIEW_DOUBT_RETRY_LIMIT} retry per stage visit (C-004: a visit ends at
   * the next stage transition - {@code advanced to}, {@code sent back to}, {@code reported to},
   * {@code stage N passed}, a direct stage update, or ticket creation); a doubt that recurs inside
   * the same visit escalates the ticket to blocked - {@code blocked} is the needs-a-human signal
   * and is reached only after the originator had its attempt (U-023 round-trip doctrine).
   *
   * @param reason the reviewer's doubt or the checkpoint's refusal reason
   * @return the ticket after the routing (ready retry, or blocked)
   * @throws Invalid when the ticket has no (valid) stored stage, the reason is blank, or its
   *     VERDICT is terminal or paused (T-226: both of this verb's writes - the {@code ready} retry
   *     and the escalation's enterBlocked - would otherwise EXIT a non-open state)
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
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          // T-226 (T-225 review follow-up 1): the terminal guard this verb was missing. Its two
          // status writes - t.status = "ready" (the retry) and enterBlocked (the escalation) -
          // used to be able to EXIT a terminal verdict when the verb was mis-invoked outside the
          // in-review flow (today's callers only reach it at in-review, which is why no test ever
          // hit it). Verdict-based via T-225's verdictStatus: a chain-blocked hop (the T-221 chain
          // walk re-opens terminal hops, keeping their verdict in resume_to) is judged by
          // resume_to - only THIS verb refuses; setBlocked's chain walk itself stays untouched.
          String verdict = verdictStatus(t);
          if (terminalStatus(verdict)) {
            throw new Invalid(
                "ticket "
                    + id
                    + " cannot take a review doubt while its verdict is '"
                    + verdict
                    + "' (T-226: routeReviewDoubt expects an OPEN ticket - the doubt round-trips"
                    + " an in-review ticket back to its originator; done/not-implemented are"
                    + " terminal - reopening needs an explicit verb that records history)");
          }
          // Same family as setBlocked's direct-entry refusals (U-038): a paused ticket parked for
          // maintenance would silently unpark on the ready write, and the escalation branch would
          // enterBlocked over the dependency wait - only the resume path moves paused.
          if ("paused".equals(verdict)) {
            throw new Invalid(
                "ticket "
                    + id
                    + " cannot take a review doubt while it is paused (T-226:"
                    + " routeReviewDoubt expects an OPEN ticket - paused is the dependency wait"
                    + " (U-038): only the resume path moves it)");
          }
          if (consumed >= REVIEW_DOUBT_RETRY_LIMIT) {
            t.enterBlocked(
                "review doubt unresolved after "
                    + REVIEW_DOUBT_RETRY_LIMIT
                    + " originator retries: "
                    + reason);
            t.updatedAt = now();
            t.comments.add(
                new Task.Comment(
                    now(),
                    by,
                    "review doubt escalated to NEEDS-HUMAN (stage " + t.stage + "): " + reason));
            t.history("review doubt escalated to NEEDS-HUMAN: " + reason, by);
            data.changed.add(id);
            return t;
          }
          t.status = "ready";
          t.assignee = null;
          t.updatedAt = now();
          t.comments.add(
              new Task.Comment(
                  now(),
                  by,
                  "review doubt retry ("
                      + (consumed + 1)
                      + "/"
                      + REVIEW_DOUBT_RETRY_LIMIT
                      + ") for stage "
                      + t.stage
                      + " - routed to the originator: "
                      + reason));
          t.history(
              "review doubt retry ("
                  + (consumed + 1)
                  + "/"
                  + REVIEW_DOUBT_RETRY_LIMIT
                  + ") for stage "
                  + t.stage
                  + ": "
                  + reason,
              by);
          data.changed.add(id);
          return t;
        });
  }

  /**
   * Doubt retries already consumed in the ticket's CURRENT stage visit (FR-010 bookkeeping, Q-003):
   * history markers since the most recent stage transition - or since creation when the stage never
   * moved.
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
   * B-007 FR-006: store-side stage evidence - ticket writes attributable to a run window (comments,
   * recorded artifacts, history) after {@code since}. Definition-leg runs legitimately deliver
   * through the {@code task_*} tools into the MAIN store instead of worktree edits; those writes
   * ARE produced work and the settle check must count them (the caller passes the result down as
   * the merge's empty-branch allowance). The window starts after the engine's own pre-claim writes,
   * so bookkeeping never masquerades as evidence (C-002: inspected from store state, no side
   * channel).
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
   * O-002: creates a NEW task-store project - the scaffold a user would otherwise hand-write under
   * {@code .harness/tasks/} (the directory plus the store's own {@code _meta.json} shape: id seq,
   * counter). Only this project is created; every other project keeps its history, actuals and
   * fleet ownership bindings untouched.
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
      writeAtomic(dir.resolve("_meta.json"), "{\"seq\":{},\"counter\":0}");
    } catch (java.io.IOException e) {
      throw new UncheckedIo("task store I/O failed: " + e.getMessage(), e);
    }
  }

  /**
   * O-002: resets a project - every ticket of THIS project (live and archived) is removed, so a
   * user repurposing the repo never hand-deletes store files. The confirmation dialog lists what is
   * lost BEFORE this runs. Cross-project safety is STRUCTURAL: only files under {@code
   * <root>/<project>/} are touched - other projects keep their tickets/actuals and the fleet's
   * {@code <id>.project} ownership bindings stay intact. The id counters survive the reset on
   * purpose: ids are never reused (the crash-recovery rule).
   *
   * @return what was lost ({@code project}, {@code tickets_removed}, {@code archived_removed})
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
      // the id seq is NOT reset; stale sprint records are dropped with the
      // owner's no-sprint model (2026-11-20)
      Path meta = dir.resolve("_meta.json");
      JsonObject doc =
          Files.isRegularFile(meta)
              ? JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8))
                  .getAsJsonObject()
              : new JsonObject();
      doc.remove("sprints");
      writeAtomic(meta, GSON.toJson(doc));
    } catch (java.io.IOException e) {
      throw new UncheckedIo("task store I/O failed: " + e.getMessage(), e);
    }
    java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
    report.put("project", name);
    report.put("tickets_removed", tickets);
    report.put("archived_removed", archived);
    return report;
  }

  /**
   * U-038: park a ticket for maintenance - status {@code paused} (visible, never blocked by direct
   * entry - the chain walk can still re-open it, keeping the pre-block state in {@code resume_to} -
   * and never NEEDS-HUMAN), the reason in history. Resume is a plain status update back to {@code
   * in-progress}: the branch and its checkpointed WIP survived the shutdown.
   *
   * @throws Invalid when the ticket's verdict is terminal (T-225: mirrors setBlocked's direct-entry
   *     refusal - a done/not-implemented ticket is never paused)
   */
  public Task setPaused(String project, String id, String reason, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          // T-225 (S3): the mirror of setBlocked's terminal refusal (961-966) - pausing a
          // terminal verdict would silently un-done it (the V-walk skips paused rows, so a
          // division that already ran becomes invisible). The intake path stays intact:
          // /goal pause early-returns on done (GoalIntake.java:106) and paused (:109), so it
          // only ever reaches setPaused with an OPEN goal ticket.
          String verdict = verdictStatus(t);
          if (terminalStatus(verdict)) {
            throw new Invalid(
                "a "
                    + verdict
                    + " ticket is never paused (T-225: done/not-implemented are terminal -"
                    + " setPaused mirrors setBlocked's direct-entry refusal)");
          }
          t.status = "paused";
          t.assignee = null;
          t.updatedAt = now();
          t.history("paused: " + (reason == null || reason.isBlank() ? "maintenance" : reason), by);
          data.changed.add(id);
          return t;
        });
  }

  /**
   * Enters the blocked STATE with a reason (U-067: resume remembers the current status) and, per
   * the owner's chain rule (2026-11-20), escalates UP the basis links: the whole ticket chain goes
   * back to the requirement, which is blocked as well. Every hop carries a {@code [chain:
   * <origin>]} marker so clearing the origin walks back DOWN and restores each hop's exact
   * pre-block state. Chain hops bypass the direct-entry refusals (even a done or paused hop joins -
   * its state is remembered in resume_to); a hop already blocked keeps its own reason.
   */
  public Task setBlocked(String project, String id, String blocker, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          if (t.isTerminal()) {
            throw new Invalid(
                "a "
                    + t.status
                    + " ticket is never blocked by direct entry (U-067:"
                    + " done/not-implemented are terminal; the chain walk re-opens it with"
                    + " resume_to)");
          }
          if ("paused".equals(t.status)) {
            throw new Invalid(
                "a paused ticket is never blocked by direct entry (U-038:"
                    + " paused is the dependency wait - resume it first)");
          }
          t.enterBlocked(blocker);
          t.updatedAt = now();
          t.history("blocked:" + blocker, by);
          data.changed.add(id);
          String marker = "[chain: " + id + "] ";
          String next = t.basis;
          while (next != null) {
            Task hop = data.tasks.get(next);
            if (hop == null || hop.isBlocked()) {
              break; // the chain stops at an absent or already-blocked hop (its reason stands)
            }
            hop.enterBlocked(marker + (blocker == null ? "" : blocker));
            hop.updatedAt = now();
            hop.history("blocked by chain from " + id, by);
            data.changed.add(hop.id);
            next = hop.basis;
          }
          return t;
        });
  }

  /**
   * Leaves the blocked STATE back to resume_to (U-067; default ready) and walks back DOWN the chain
   * this block created: every hop still carrying this origin's {@code [chain: <id>]} marker is
   * cleared too, each returning to ITS pre-block state (owner rule 2026-11-20).
   */
  public Task clearBlocked(String project, String id, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          t.leaveBlocked();
          t.updatedAt = now();
          t.history("unblocked", by);
          data.changed.add(id);
          String marker = "[chain: " + id + "] ";
          for (Task u : data.tasks.values()) {
            if (u.isBlocked() && u.blocker != null && u.blocker.startsWith(marker)) {
              u.leaveBlocked();
              u.updatedAt = now();
              u.history("unblocked (chain from " + id + ")", by);
              data.changed.add(u.id);
            }
          }
          return t;
        });
  }

  /**
   * Atomically claims the next matching task (-&gt; in-progress, assignee set). Only READY,
   * UNASSIGNED tasks of the given role that are not blocked are claimable (owner model 2026-11-20:
   * born ready, claimed into the pump - the assignee shields acceptance instances); ordering is
   * priority desc, then created_at asc, then id. Returns {@code null} when nothing is claimable
   * (worker loops stop on this).
   *
   * <p>T-226 (S1): the candidate set is additionally filtered by the SAME {@link
   * StageReadiness#evaluate} verdict the ticker's step-1 scan reports. The verdicts are computed
   * INSIDE this transaction - the in-JVM plus OS file lock already serializes claim against every
   * reader/writer, so the evaluation and the claim commit in one critical section and no other
   * actor can invalidate the verdicts between "scan" and "claim" (the atomicity argument: the lock
   * makes filtering under it race-free by construction).
   *
   * <p>What gets skipped: a <em>basis-linked</em> candidate whose verdict is {@code WAIT_UPSTREAM}
   * or {@code STALE}. This is the exact birth/readiness split S1 named - {@link #basisSatisfied}
   * counts an in-review basis as satisfied with NO artifact check, while readiness requires the
   * artifact (U-081), so such a child sits {@code status: ready} while its derivation gate says
   * wait; a blind role-claim used to scoop it. {@code STALE} re-runs belong to the ticker's
   * dedicated step 4b (targeted claim by id + the re-run brief), never to a blind claim without the
   * brief. {@code BLOCKED} was never claimable ({@code !t.isBlocked()} below; readiness's {@code
   * BLOCKED} == isBlocked) and {@code RUNNING} cannot occur (the status filter requires {@code
   * ready}).
   *
   * <p>What stays claimable: a <em>basis-less</em> candidate whatever its flow-history verdict
   * reads. It has no derivation gate to wait on - the legacy flow verdict ({@code no done/in-review
   * ticket in upstream stage}) is a dispatch-scan signal, not a claim gate - and the dispatch
   * doctrine still hands such work to its worker (AGENTS V-enforcement: "non-bug work with nothing
   * above it at all is forced to write the missing abstraction first"). Pinned by T-220's
   * doubt-loop fixture, which claims a fresh staged ticket through this verb. A stageless candidate
   * ({@code NOT_APPLICABLE}) is outside the stage pipeline entirely and keeps its plain role-claim.
   */
  public Task claim(String project, String role, String status, String by) {
    String want = status == null ? "ready" : status;
    if (!"ready".equals(want)) {
      throw new Invalid(
          "claim status must be one of [ready], got '"
              + want
              + "'"
              + " (only ready tickets are claimable)");
    }
    return transaction(
        project,
        data -> {
          List<Task> tickets = new ArrayList<>(data.tasks.values());
          // T-226 (S1): the ticker's own verdict, evaluated under the claim lock (see the javadoc)
          Map<String, StageReadiness.Readiness> verdicts =
              StageReadiness.evaluate(tickets, archived(project));
          List<Task> candidates = new ArrayList<>();
          for (Task t : tickets) {
            if (role.equals(t.role)
                && want.equals(t.status)
                && !t.isBlocked()
                && t.assignee == null
                && !basisGated(t, verdicts)) {
              candidates.add(t);
            }
          }
          if (candidates.isEmpty()) {
            return null;
          }
          candidates.sort(
              Comparator.comparing((Task t) -> -Task.PRIORITY_ORDER.getOrDefault(t.priority, 0))
                  .thenComparing(t -> t.createdAt == null ? Instant.EPOCH : t.createdAt)
                  .thenComparing(t -> t.id));
          Task t = candidates.get(0);
          String who = by == null || by.isBlank() ? role : by;
          t.status = "in-progress";
          t.assignee = who;
          t.updatedAt = now();
          t.inputsChangedAt = t.updatedAt; // D-003: claim is a status move (design's list)
          t.history("claimed by " + who, by);
          data.changed.add(t.id);
          return t;
        });
  }

  /** Returns an unstarted (or in-progress) task to ready; clears the assignee. */
  public Task release(String project, String id, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          if (!"ready".equals(t.status) && !"in-progress".equals(t.status)) {
            throw new Invalid("cannot release ticket in status '" + t.status + "'");
          }
          t.status = "ready";
          t.assignee = null;
          t.updatedAt = now();
          t.history("released by " + (by == null ? "?" : by), by);
          data.changed.add(id);
          return t;
        });
  }

  /** Appends a comment. */
  public Task addComment(String project, String id, String comment, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          t.comments.add(new Task.Comment(now(), by, comment));
          t.updatedAt = now();
          data.changed.add(id);
          return t;
        });
  }

  /** Records an artifact (file/git/path/url/doc) on a task. */
  public Task addArtifact(
      String project, String id, String kind, String ref, String note, String by) {
    if (!ARTIFACT_KINDS.contains(kind)) {
      throw new Invalid(
          "kind must be one of ['doc', 'file', 'git', 'path', 'url'], got '" + kind + "'");
    }
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          t.artifacts.add(new Task.Artifact(kind, ref, note == null ? "" : note, by, now()));
          t.updatedAt = now();
          t.inputsChangedAt = t.updatedAt; // D-003: artifact writes are input changes
          t.history("artifact:" + kind + ":" + ref, by);
          data.changed.add(id);
          return t;
        });
  }

  /**
   * Removes an artifact by 0-based index - the correction counterpart of {@link #addArtifact}: a
   * wrong ref is removed and re-recorded instead of hand-edited in the ticket file (U-053). The
   * removal is history-recorded as {@code artifact removed:<kind>:<ref>}, so the audit trail keeps
   * what was dropped.
   *
   * @throws Invalid when the index is out of range (like {@link #removeTodo})
   */
  public Task removeArtifact(String project, String id, int index, String by) {
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          if (index < 0 || index >= t.artifacts.size()) {
            throw new Invalid(
                "artifact index " + index + " out of range (have " + t.artifacts.size() + ")");
          }
          Task.Artifact removed = t.artifacts.remove(index);
          t.updatedAt = now();
          t.inputsChangedAt = t.updatedAt; // D-003: artifact writes are input changes (remove too)
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
    return transaction(
        project,
        data -> {
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
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          if (index < 0 || index >= t.todos.size()) {
            throw new Invalid(
                "todo index " + index + " out of range (have " + t.todos.size() + ")");
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
    return transaction(
        project,
        data -> {
          Task t = require(data, project, id);
          if (index < 0 || index >= t.todos.size()) {
            throw new Invalid(
                "todo index " + index + " out of range (have " + t.todos.size() + ")");
          }
          Task.Todo removed = t.todos.remove(index);
          t.updatedAt = now();
          t.history("todo_removed:" + removed.text(), by);
          data.changed.add(id);
          return t;
        });
  }

  /**
   * Kanban grouped by status; all seven status columns always present (owner model 2026-11-20:
   * ready / in-progress / in-review / done / not-implemented / paused / blocked - no backlogs).
   */
  public Map<String, List<Task>> board(String project) {
    Map<String, List<Task>> out = new LinkedHashMap<>();
    for (String s : Task.VALID_STATUSES) {
      out.put(s, new ArrayList<>());
    }
    for (Task t : list(project, null, null, null)) {
      List<Task> column = out.get(t.status);
      if (column != null) {
        column.add(t);
      }
    }
    return out;
  }

  /**
   * Archives a ticket (user direction 2026-09-19: done tickets leave the active board at some
   * point): the {@code <id>.md} file moves to {@code <project>/_archive/} — the archive keeps the
   * full ticket record (history, artifacts, actuals), but the live listing (and with it the board,
   * readiness and dispatch) no longer sees it. The loader only scans top-level {@code *.md}, so the
   * move is the whole trick.
   */
  public Map<String, Object> archive(String project, String id, String by) {
    return transaction(
        project,
        data -> {
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
          data.changed.remove(id); // persist must not rewrite it to the live dir
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("id", id);
          out.put("archived", true);
          return out;
        });
  }

  /**
   * T-183 - the chain-safe DONE sweep for the board's backlog of history: archives every DONE
   * ticket no live basis-chain ticket still needs (the project-wide variant of U-060's rule) and -
   * with {@code before} - recent work on the board. Each move is an independent {@link #archive}
   * transaction, exactly like the U-060 auto-archiver; the snapshot is taken before the first move.
   *
   * @param before optional ISO-8601 instant: only tickets CREATED strictly before it are candidates
   *     ({@code null} = all done)
   * @return {@code archived} (moved ids) + {@code skipped} ({@code id:reason} for recent /
   *     chain-needed - the reasons a candidate stayed visible)
   */
  public Map<String, Object> archiveDone(String project, String by, String before) {
    Instant cutoff = null;
    if (before != null && !before.isBlank()) {
      try {
        cutoff = Instant.parse(before.trim());
      } catch (RuntimeException e) {
        throw new Invalid(
            "before must be an ISO-8601 instant (e.g. 2026-11-14T00:00:00Z), got '" + before + "'");
      }
    }
    List<Task> all = list(project, null, null, null);
    List<String> moved = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (Task t : all) {
      if (t == null || !t.isTerminal()) {
        continue;
      }
      if (cutoff != null && t.createdAt != null && t.createdAt.isAfter(cutoff)) {
        skipped.add(t.id + ":recent");
        continue;
      }
      boolean needed =
          all.stream().anyMatch(u -> u != null && u != t && !u.isTerminal() && inSameChain(u, t));
      if (needed) {
        skipped.add(t.id + ":chain-needed");
        continue;
      }
      archive(project, t.id, by);
      moved.add(t.id);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("archived", moved);
    out.put("skipped", skipped);
    return out;
  }

  /**
   * The U-023/U-027 chain after the epic removal (2026-11-19): the basis parent ({@code u.id ==
   * t.basis}) or a sibling ({@code u.basis == t.basis}).
   */
  static boolean inSameChain(Task a, Task b) {
    if (a == null || b == null || a.id.equals(b.id)) {
      return false;
    }
    return a.id.equals(b.basis)
        || b.id.equals(a.basis)
        || a.basis != null && a.basis.equals(b.basis);
  }

  /**
   * The project's archived tickets ({@code _archive/*.md}), newest-last by file name; read-only
   * view over the same codec. An absent archive directory reads as empty.
   */
  public List<Task> archived(String project) {
    Path dir = root.resolve(TaskStore.sanitizeProject(project)).resolve("_archive");
    if (!Files.isDirectory(dir)) {
      return new ArrayList<>();
    }
    List<Path> files = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
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
        LOG.log(
            Level.WARNING, "skipping unparsable archived ticket " + p + ": " + e.getMessage(), e);
      }
    }
    return out;
  }

  /**
   * T-032: the link graph around one ticket, computed deterministically over the current file set
   * (no cached state). up = the basis chain walked to the entry ticket (a basis pointing nowhere
   * reports {@code missing}); down = the direct derivation children (basis == this id); back = the
   * siblings (same basis, not this id).
   *
   * <p>T-240: the graph is the SCOPE view, so nodes resolve across the live pool AND {@code
   * _archive/} - the chain to the requirement stays a chain where a hop was swept into history (the
   * same live-or-archive rule {@link #linkExists} and task_doctor apply; before this the archived
   * hop read {@code missing:true}, i.e. the same false negative as the old task_get verdict). An
   * archived node carries {@code archived: true}; {@code missing: true} is reserved for an id that
   * resolves NOWHERE, so the two verdicts stay distinguishable. The enumeration verbs that answer
   * "what is on the board" ({@link #list}, {@link #board}, and task_get's RETURNED ticket) stay
   * live-only on purpose.
   */
  public Map<String, Object> links(String project, String id) {
    List<Task> live = list(project, null, null, null);
    java.util.Set<String> liveIds = new java.util.HashSet<>();
    for (Task t : live) {
      liveIds.add(t.id);
    }
    List<Task> all = new ArrayList<>(live);
    all.addAll(archived(project));
    Task self = all.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    if (self == null) {
      throw new NotFound("ticket " + id + " not found");
    }
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    List<Map<String, Object>> up = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    seen.add(self.id);
    Task cur = self;
    while (cur.basis != null && seen.add(cur.basis)) {
      final String basisId = cur.basis;
      Task b = all.stream().filter(t -> t.id.equals(basisId)).findFirst().orElse(null);
      if (b == null) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", cur.basis);
        m.put("missing", true);
        up.add(m);
        break;
      }
      up.add(nodeRef(b, liveIds));
      cur = b;
    }
    List<Map<String, Object>> down = new ArrayList<>();
    for (Task t : all) {
      if (id.equals(t.basis)) {
        down.add(nodeRef(t, liveIds));
      }
    }
    List<Map<String, Object>> back = new ArrayList<>();
    if (self.basis != null) {
      for (Task t : all) {
        if (!t.id.equals(id) && self.basis.equals(t.basis)) {
          back.add(nodeRef(t, liveIds));
        }
      }
    }
    out.put("id", self.id);
    if (!liveIds.contains(self.id)) {
      out.put("archived", true);
    }
    out.put("up", up);
    out.put("down", down);
    out.put("back", back);

    return out;
  }

  private static Map<String, Object> linkRef(Task t) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("id", t.id);
    m.put("title", t.title);
    m.put("status", t.status);
    if (t.stage != null) {
      m.put("stage", t.stage);
    }
    return m;
  }

  /**
   * A graph node as {@link #linkRef} renders it, marked when it came from the archive pool (T-240)
   * so a swept hop reads {@code archived: true} and only an id resolving nowhere reads {@code
   * missing: true}.
   */
  private static Map<String, Object> nodeRef(Task t, java.util.Set<String> liveIds) {
    Map<String, Object> m = linkRef(t);
    if (!liveIds.contains(t.id)) {
      m.put("archived", true);
    }
    return m;
  }

  /**
   * The definition&lt;-&gt;verification traceability matrix (D-001 decision: THREE pairing signals
   * - a role+basis link, the ticket's own V-journey, and test-shaped artifacts; pm requirements
   * count as definitions). The matrix row reports the signals ({@code via}) so the audit can tell a
   * real pair from inflation; a definition with no signal at all stays an orphan.
   */
  public Map<String, Object> traceability(String project) {
    List<Task> tickets = list(project, null, null, null);
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
        if (t.id.equals(other.basis)) {
          links.add(other.id);
        }
      }
      String verifies = null;
      List<String> verifiedBy = new ArrayList<>();
      List<String> via = new ArrayList<>();
      // signal 2: the ticket itself walked BOTH legs (a definition entry
      // then a test-* stage), read from its own history by the helpers
      // below - enteredDefinitionLeg/reachedTestStage parse the stored
      // 'advanced to <stage>' events, which nothing live writes anymore
      // (the advance verb was removed with the U-110 arc, e888abe), and no
      // stage move rewrites the role (role is set at create/update). The
      // row is a verification-role pairing with its own definition history
      // - not an orphan.
      boolean bothLegs = enteredDefinitionLeg(t) && reachedTestStage(t);
      if (Task.VERIFICATION_ROLES.contains(t.role)) {
        verifies =
            t.basis != null && byId.containsKey(t.basis) ? t.basis : (bothLegs ? t.id : null);
        if (verifies == null) {
          orphanVerifications.add(t.id);
        } else {
          via.add(t.id.equals(verifies) ? "journey" : "basis:" + verifies);
        }
      }
      boolean selfVerified = via.contains("journey");
      if (Task.DEFINITION_ROLES.contains(t.role)) {
        for (Task other : tickets) {
          if (t.id.equals(other.basis) && Task.VERIFICATION_ROLES.contains(other.role)) {
            verifiedBy.add(other.id);
            via.add("basis:" + other.id);
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
      row.put("basis", t.basis);
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
   * U-104: the store-backed stage -&gt; dispatch agent override ({@code _meta.json}'s {@code
   * stage_agents}); an empty map means every stage uses its default agent definition (the
   * STAGE-AGENT MAP in the stage-pipeline skill). An unmodifiable copy - configure with {@link
   * #setStageAgent} instead.
   */
  public Map<String, String> stageAgents(String project) {
    return transaction(project, data -> java.util.Map.copyOf(data.stageAgents));
  }

  /**
   * U-104: sets - or with a blank/null {@code agent} CLEARS - the dispatch agent for one V stage:
   * the chat/Board-settable selection without file edits (the ticker's spawn step reads it). The
   * map persists in {@code _meta.json} next to the id counters.
   *
   * @throws Invalid when the stage is not a V stage - rejected loudly, never sanitized
   *     (U-107/U-109)
   */
  public Map<String, String> setStageAgent(String project, String stage, String agent) {
    if (stage == null || !VStages.isValid(stage)) {
      throw new Invalid("stage must be one of " + VStages.STAGES + ", got '" + stage + "'");
    }
    return transaction(
        project,
        data -> {
          if (agent == null || agent.isBlank()) {
            data.stageAgents.remove(stage);
          } else {
            data.stageAgents.put(stage, agent.trim());
          }
          data.metaDirty = true;
          return java.util.Map.copyOf(data.stageAgents);
        });
  }

  /**
   * Persists the H6 invalidation markers: every ticket whose current {@link StageReadiness} verdict
   * is STALE gets one appended unless the exact action is already ANYWHERE in its history - the
   * action {@code "inputs changed: upstream <id> updated <ts>"} names the changed upstream's INPUTS
   * stamp ({@link Task#inputsEffectiveAt()}, D-003), {@code by} is {@code "h6"} - plus an
   * updated_at touch that never moves the ticket's own inputs stamp (the marker is bookkeeping).
   * Idempotent: the whole-history guard makes later ticks append nothing until the cause changes
   * again, even when bookkeeping rows land in between (U-052's AC1). The archive pool rides the
   * verdicts and the cause lookup (U-059). One transaction over the whole project; the verdicts
   * come from one snapshot, so cascading staleness (a marked ticket invalidating its own
   * downstream) is picked up by the next run.
   *
   * @return the number of tickets newly marked (0 when nothing is stale).
   */
  public int recordInvalidations(String project) {
    // T-226 (N1): a READ never materializes the project directory (the invariant every other read
    // entry point guards - get/list/doctor): the readiness tick used to fall through to
    // transaction() -> Files.createDirectories and leave a phantom directory + .lock behind for a
    // project that never existed. Nothing can be marked without a directory, so return before the
    // transaction can create one.
    if (projectDirectoryMissing(project)) {
      return 0;
    }
    // U-059: the archive pool rides the production verdicts - an archived upstream can still
    // satisfy AND still stale its live downstream (read-only view; an absent archive = empty)
    List<Task> archivePool = archived(project);
    return transaction(
        project,
        data -> {
          List<Task> tickets = new ArrayList<>(data.tasks.values());
          Map<String, StageReadiness.Readiness> verdicts =
              StageReadiness.evaluate(tickets, archivePool);
          int marked = 0;
          for (Task t : tickets) {
            StageReadiness.Readiness verdict = verdicts.get(t.id);
            if (verdict == null || verdict.kind() != StageReadiness.Kind.STALE) {
              continue;
            }
            Task upstream = StageReadiness.staleCause(t, tickets, archivePool);
            if (upstream == null) {
              continue;
            }
            // D-003: the marker names the STRUCTURED-input stamp the STALE verdict read (the
            // effective stamp keeps legacy rows' exact old string), and the guard scans the WHOLE
            // history - an interleaved comment between ticks must not re-append the same marker
            // (AC: exactly one per invalidation, no duplicates on later ticks).
            String action =
                "inputs changed: upstream "
                    + upstream.id
                    + " updated "
                    + Task.formatTs(upstream.inputsEffectiveAt());
            boolean alreadyMarked =
                t.history.stream().anyMatch(h -> h != null && action.equals(h.action()));
            if (alreadyMarked) {
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
   * The H6 dispatch-readiness report for the PM agent: one compact row per ticket - {@code id},
   * {@code stage}, {@code kind}, {@code reason} from {@link StageReadiness#evaluate} (tickets
   * without a stage included as NOT_APPLICABLE with a null stage) - ordered by kind severity
   * (STALE, BLOCKED, WAIT_UPSTREAM, RUNNING, READY, NOT_APPLICABLE) then id, so "what's runnable
   * right now" reads top-down. A project whose directory does not exist reads as zero rows and
   * creates NOTHING: readiness is a read (T-226 N1 - it used to materialize the directory through
   * {@link #recordInvalidations}'s transaction).
   */
  public List<Map<String, Object>> readiness(String project) {
    // T-226 (N1): the read-path guard, before anything can touch the transaction engine.
    if (projectDirectoryMissing(project)) {
      return new ArrayList<>();
    }
    // U-052 (D-003): the PRODUCTION readiness tick records the invalidation markers - the ticker's
    // tick answers through this report (task_readiness), so a STALE verdict leaves its 'inputs
    // changed:' history event here, idempotently (the recorder's whole-history guard + freshness
    // make repeated polls append nothing new).
    recordInvalidations(project);
    List<Task> tickets = list(project, null, null, null);
    // U-059: production readiness consults the archive pool (an archived upstream still decides)
    Map<String, StageReadiness.Readiness> verdicts =
        StageReadiness.evaluate(tickets, archived(project));
    tickets.sort(
        Comparator.comparing((Task t) -> READINESS_SEVERITY.indexOf(verdicts.get(t.id).kind()))
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
   * Store self-check (lint): human-readable reports of store damage and inconsistent flag
   * combinations - ticket files the store cannot parse (every other read skips them, so a corrupted
   * file would otherwise vanish silently), tickets that are {@code done} but still carry the
   * blocked flag, {@code file}/{@code path} artifact refs that do not resolve under the repo root
   * (U-053: two corrupted refs sat unnoticed for days), and - U-080/T-242 - done/in-review tickets
   * parked on ANY V stage without any recorded artifact: the definition legs the done-time guard
   * refuses to close bare ("the architecture was not done") AND the test-* legs, which that write
   * guard deliberately exempts (verification evidence is a scan-time concern - T-242), over the
   * live pool first and the archive pool second, each with its own honest heal hint. Glob/ brace/
   * enumerated/prose-like refs are skipped, and so is the whole resolution check when the repo root
   * cannot be determined from the store root's layout - one report line per file or ticket, sorted;
   * empty when the store is consistent. Read-only.
   *
   * <p>T-224 (rubberduck S2/S8/S9/N4) added the five HAND-EDIT blind spots, each with its own
   * greppable message: (1) {@code unknown stage '<x>' (not in VStages)} - the codec reads stage
   * with no validation, so a typo'd stage silently drops the ticket to NOT_APPLICABLE; (2) {@code
   * file stem '<stem>' != frontmatter id '<id>'} - the load keys by frontmatter id only, so a
   * hand-edited id forks identity (one record overwrites the other); (3) {@code stage title-tag
   * drift} - a staged ticket's title must carry its store-owned leading {@code [stage]} tag
   * (T-175); (4) {@code role '<r>' != VStages.roleOf(stage) '<expected>'} unless the override is
   * recorded on the ticket as a non-blank {@code role_override:} frontmatter line; (5) {@code doc
   * artifact ref '<ref>' does not resolve to a live-or-archived ticket} for ticket-id-shaped doc
   * refs.
   *
   * <p>T-226 (T-224 follow-up): checks (2) and (5) scan BOTH pools now - the archive pool is not a
   * write-only graveyard (task_get/link/doc-ref resolution read it), so an id/filename fork or a
   * dangling ticket-id doc ref inside {@code _archive/} is reported too (the stem findings carry an
   * {@code _archive/} prefix; both walks are read-only). The state/V-walk checks (1, 3, 4, the
   * division/join invariants) stay LIVE-pool only on purpose: an archived ticket is a historical
   * record - pre-T-175 titles, retired stages and unfinished legs are expected there, and
   * re-running today's pipeline invariants over history would report every legacy sweep as damage.
   * (T-242's done-evidence row is the FIRST deliberate exception to that live-only rule: a done
   * ticket without its product is damage in EITHER pool - T-243 had to backfill exactly that shape
   * out of the archive (T-190/T-191) - it was never era-expected, and the archived row's message
   * names the hand-edit heal path because the write verbs refuse archived ids (T-240).)
   *
   * <p>T-253 adds the second and third exceptions, in T-242's twin style: {@code unknown priority}
   * and {@code unknown type} scan BOTH pools, because an out-of-enum enum value was never
   * era-expected in either (the codec defaults an absent value to {@code task}/{@code medium}, so
   * every row that ever carried one was written out of band). The write verbs REFUSE an out-of-enum
   * VALUE (T-249 priority, T-253 type), and they refuse archived ids too (T-240), so the two pools
   * heal differently. In the ARCHIVE pool the two refusals stack and no verb can reach such a row,
   * so the archived message names the hand-edit heal as the whole cure. In the LIVE pool the
   * healing write is an in-enum {@code task_update <field>=<an allowed value>}: a verb refuses only
   * the out-of-enum value being written, never an allowed value on a live ticket, so the live
   * message names that working verb instead.
   */
  /**
   * A link target resolves in the live pool or in the archive pool (owner rule 2026-11-19): joins
   * may reattach to kept archive nodes ("if they are joined again") - basis validates at create and
   * update, so dangling references can never enter the store again. (The epic field was removed
   * 2026-11-19: the stage is the structure, the basis chain is the scope.)
   */
  private boolean linkExists(String project, String id, ProjectData data) {
    if (data.tasks.containsKey(id)) {
      return true;
    }
    for (Task a : archived(project)) {
      if (a.id.equals(id)) {
        return true;
      }
    }
    return false;
  }

  public List<String> inconsistencies(String project) {
    if (projectDirectoryMissing(project)) {
      return new ArrayList<>();
    }
    List<Task> tasks = new ArrayList<>();
    List<String> out =
        new ArrayList<>(
            transaction(
                project,
                data -> {
                  tasks.addAll(data.tasks.values());
                  List<String> report = new ArrayList<>(data.unparsable);
                  report.addAll(fileStemIdMismatches(data));
                  return report;
                }));
    for (Task t : tasks) {
      // T-224 (S2): the codec reads stage with NO VStages validation - a hand-edited stage
      // silently drops the ticket to NOT_APPLICABLE in readiness ("ticket has no V stage") and
      // it is never scanned/divided again, while the doctor used to stay ok:true.
      if (t.stage != null && !VStages.isValid(t.stage)) {
        out.add(t.id + ": unknown stage '" + t.stage + "' (not in VStages)");
      }
      if (!Task.VALID_STATUSES.contains(t.status)) {
        out.add(t.id + ": unknown status '" + t.status + "' (not in " + Task.VALID_STATUSES + ")");
      }
      // T-253: the DETECTION half of T-249's class. The codec reads priority/type with no enum
      // validation, and the write verbs REFUSE an out-of-enum VALUE (T-249 priority, T-253 type)
      // and archived ids too (T-240) - so a hand-edited bad row can no longer be WRITTEN through
      // a verb, and this scan (plus its archived twin below) is the only place a stored bad row
      // ever surfaces. The two pools heal differently: in the LIVE pool the healing write is an
      // in-enum task_update <field>=<an allowed value> (a verb refuses only the out-of-enum value
      // being written, never an allowed value on a live ticket); in the ARCHIVE pool no verb can
      // reach such a row, so only a store-file hand edit heals. No era legitimately wrote an
      // out-of-enum enum value, so unlike pre-T-175 titles it is damage, not history.
      if (t.priority != null && !Task.PRIORITY_ORDER.containsKey(t.priority)) {
        out.add(
            t.id
                + ": unknown priority '"
                + t.priority
                + "' (not in "
                + List.of("low", "medium", "high", "critical")
                + ") - heal by task_update priority=<an allowed value> (the stored out-of-enum"
                + " value is replaced with an allowed one), then re-run task_doctor");
      }
      if (t.type != null && !Task.VALID_TYPES.contains(t.type)) {
        out.add(
            t.id
                + ": unknown type '"
                + t.type
                + "' (not in "
                + Task.VALID_TYPES
                + ") - heal by task_update type=<an allowed value> (the stored out-of-enum value"
                + " is replaced with an allowed one), then re-run task_doctor");
      }
      // U-080/T-242: done-evidence over ALL nine stages - the write guard exempts the test-* legs,
      // so this scan (plus the archived twin further down) is the only place a bare closed
      // ticket surfaces. done + in-review = the hand-off contract (artifact BEFORE the in-review
      // flip, AGENTS write discipline).
      if (("done".equals(t.status) || "in-review".equals(t.status))
          && VStages.isValid(t.stage)
          && (t.artifacts == null || t.artifacts.isEmpty())) {
        out.add(
            t.id
                + ": stage="
                + t.stage
                + " ("
                + t.status
                + ") has no recorded artifact (record the stage's product with task_add_artifact)");
      }
      // T-224 (S9): a staged ticket's own stage is validated above; its NAME and its ROLE must
      // agree with that stage.
      if (VStages.isValid(t.stage)) {
        // (S9a) the [stage] tag is store-owned (T-175) but only re-derived at title/stage
        // writes - a hand-edited title leaves the name contradicting the frontmatter (the
        // 2026-11-18 heal needed a manual 137-ticket re-derivation, git 09cb97a).
        String expectedTitle = encodeStageTag(t.title, t.stage);
        // T-225 (T-224 review follow-up): defensive null title in the DONE GATE's shape
        // ((t.artifacts == null || t.artifacts.isEmpty())) - codec orEmpty makes it
        // unreachable today, but a null title would otherwise NPE on expectedTitle.equals().
        if (t.title == null || !expectedTitle.equals(t.title)) {
          out.add(
              t.id
                  + ": stage title-tag drift - title '"
                  + t.title
                  + "' does not carry the leading ["
                  + t.stage
                  + "] tag (expected '"
                  + expectedTitle
                  + "')");
        }
        // (S9b) role drives every role-claim/dispatch, but create/update/doctor never enforced
        // role == VStages.roleOf(stage) - a wrong role means the stage's role-claim never
        // matches and the ticket stays READY forever. An INTENTIONAL override is recorded ON
        // THE TICKET (see roleOverrideRecorded): a non-blank `role_override:` frontmatter line
        // saying why.
        String owningRole = VStages.roleOf(t.stage);
        if (owningRole != null && !owningRole.equals(t.role) && !roleOverrideRecorded(t)) {
          out.add(
              t.id
                  + ": role '"
                  + t.role
                  + "' != VStages.roleOf(stage) '"
                  + owningRole
                  + "' (stage="
                  + t.stage
                  + "; if this override is intentional, record a non-blank role_override: line"
                  + " on the ticket)");
        }
      }
    }
    // ---- V-walk invariants (owner model 2026-11-19, deterministic check) ----
    // Left leg divides >=1 link forward to the NEXT stage (V order); every walked left-leg
    // ticket is joined on the right leg by a test-* ticket whose basis is exactly it; right-leg
    // joins point at LEFT-leg tickets only (1:1 join-back); every basis link resolves in
    // live OR archive. Counts converge as a walk completes: |left| = |right| under these rules.
    // Shipped legacy work is exempt with a recorded "division SATISFIED" comment (SKILL
    // stage-pipeline: division-with-rationale) - never mint duplicate children for shipped work.
    List<Task> pool = new ArrayList<>(tasks);
    List<Task> archivedTasks = archived(project);
    pool.addAll(archivedTasks);
    Map<String, Task> byId = new java.util.HashMap<>();
    Map<String, List<Task>> byBasis = new java.util.HashMap<>();
    for (Task x : pool) {
      byId.put(x.id, x);
      if (x.basis != null) {
        byBasis.computeIfAbsent(x.basis, k -> new ArrayList<>()).add(x);
      }
    }
    for (Task x : pool) {
      if (x.basis != null && !byId.containsKey(x.basis)) {
        out.add(x.id + ": basis " + x.basis + " does not resolve (live or archive)");
      }
    }
    // T-242: the done-evidence row's ARCHIVE twin (same stage set and statuses as the live row
    // above - the split exists only so each message can name its honest heal path). Artifact-less
    // history is damage, not era-typical (unlike pre-T-175 titles - see the javadoc): T-243 had to
    // backfill exactly this shape out of the archive (archived T-190/T-191). The write verbs refuse
    // archived ids (T-240), so task_add_artifact CANNOT heal an archived row - the message points
    // at the documented smallest-hand-edit path instead of prescribing an impossible verb.
    for (Task t : archivedTasks) {
      if (("done".equals(t.status) || "in-review".equals(t.status))
          && VStages.isValid(t.stage)
          && (t.artifacts == null || t.artifacts.isEmpty())) {
        out.add(
            t.id
                + ": stage="
                + t.stage
                + " ("
                + t.status
                + ") has no recorded artifact (ARCHIVED record - write verbs refuse archived ids"
                + " (T-240): heal by the smallest hand edit of the archive file, then re-run"
                + " task_doctor)");
      }
    }
    // T-253: the enum rows' ARCHIVE twin (T-242's pattern, and for the same two reasons): the
    // condition was never era-expected in EITHER pool (no era legitimately wrote an out-of-enum
    // priority/type), and an archived record is out of every write verb's reach twice over - the
    // verbs refuse archived ids (T-240) AND they refuse out-of-enum values (T-249/T-253), so the
    // ONLY heal for a bad archived row is the same smallest hand edit of the archive file. Without
    // this twin a hand-edited bad value in _archive/ would stay invisible forever.
    for (Task t : archivedTasks) {
      if (t.priority != null && !Task.PRIORITY_ORDER.containsKey(t.priority)) {
        out.add(
            t.id
                + ": unknown priority '"
                + t.priority
                + "' (not in "
                + List.of("low", "medium", "high", "critical")
                + ") - ARCHIVED record: write verbs refuse archived ids (T-240) and out-of-enum"
                + " values (T-249), so heal by the smallest hand edit of the archive file, then"
                + " re-run task_doctor");
      }
      if (t.type != null && !Task.VALID_TYPES.contains(t.type)) {
        out.add(
            t.id
                + ": unknown type '"
                + t.type
                + "' (not in "
                + Task.VALID_TYPES
                + ") - ARCHIVED record: write verbs refuse archived ids (T-240) and out-of-enum"
                + " values (T-253), so heal by the smallest hand edit of the archive file, then"
                + " re-run task_doctor");
      }
    }
    // T-224 (N4): doc artifact refs were the one unchecked ref class - file/path kinds resolve
    // against the repo root below, git/url have no offline ticket oracle, but a doc ref NAMING A
    // TICKET that was deleted/renamed stayed silent forever. Only ticket-id-shaped doc refs are
    // validated (refs that look like paths/TEMP logs/prose are not ticket ids), and they resolve
    // in the live pool or the archive pool - the same rule basis links use (linkExists), scoped to
    // this project like every other store link. T-226 (T-224 follow-up): the CHECKS ride the whole
    // pool too - an archived ticket's own doc refs are validated against live+archive as well, so
    // a dangling ticket-id ref inside _archive/ surfaces instead of waiting for a restore nobody
    // performs (archived tickets ARE read: task_get/link/doc-ref resolution, the pool above).
    for (Task t : pool) {
      for (Task.Artifact a : t.artifacts) {
        if (a.kind() == null
            || !"doc".equals(a.kind())
            || a.ref() == null
            || !TICKET_ID_REF.matcher(a.ref()).matches()
            || byId.containsKey(a.ref())) {
          continue;
        }
        out.add(
            t.id
                + ": doc artifact ref '"
                + a.ref()
                + "' does not resolve to a live-or-archived ticket");
      }
    }
    for (Task t : tasks) {
      boolean walked = "done".equals(t.status) || "in-review".equals(t.status);
      if (!walked || !VStages.isValid(t.stage)) {
        continue;
      }
      boolean satisfied =
          t.comments != null
              && t.comments.stream()
                  .anyMatch(
                      c ->
                          c != null
                              && c.text() != null
                              && c.text()
                                  .toLowerCase(java.util.Locale.ROOT)
                                  .contains("division satisfied"));
      List<Task> kids = byBasis.getOrDefault(t.id, new ArrayList<>());
      if (VStages.isVerification(t.stage)) {
        // I2: the right leg joins back 1:1 to a LEFT-leg ticket (shipped legacy exempt)
        if (t.basis == null) {
          if (!satisfied) {
            out.add(
                t.id
                    + ": stage="
                    + t.stage
                    + " ("
                    + t.status
                    + ") has no basis - a right-leg ticket joins back to its left-leg ticket");
          }
        } else {
          Task target = byId.get(t.basis);
          if (target != null
              && VStages.isValid(target.stage)
              && VStages.isVerification(target.stage)
              && !satisfied) {
            out.add(
                t.id
                    + ": stage="
                    + t.stage
                    + " joins back to right-leg ticket "
                    + t.basis
                    + " (stage="
                    + target.stage
                    + ") - the right leg joins LEFT-leg tickets only");
          }
        }
      } else if ("done".equals(t.status)) {
        // I1: >= 1 link forward to the next stage (division happened)
        String next = VStages.next(t.stage);
        boolean fwd = next != null && kids.stream().anyMatch(k -> next.equals(k.stage));
        if (!fwd && !satisfied) {
          out.add(
              t.id
                  + ": stage="
                  + t.stage
                  + " (done) has no link to the next stage ("
                  + next
                  + ") - division not done (create it or record 'division SATISFIED' for shipped legacy work)");
        }
        // I3: joined on the right leg
        boolean joined =
            kids.stream()
                .anyMatch(k -> VStages.isValid(k.stage) && VStages.isVerification(k.stage));
        if (!joined && !satisfied) {
          out.add(
              t.id
                  + ": stage="
                  + t.stage
                  + " (done) is not joined on the right leg (no test-* ticket with basis="
                  + t.id
                  + ") - create the join or record 'division SATISFIED' for shipped legacy work");
        }
      }
    }
    Path repoRoot = repoRootOrNull();
    if (repoRoot != null) {
      for (Task t : tasks) {
        for (Task.Artifact a : t.artifacts) {
          if (a.kind() == null
              || !PATH_ARTIFACT_KINDS.contains(a.kind())
              || !isPlainPathRef(a.ref())) {
            continue;
          }
          if (!artifactRefResolves(repoRoot, a.ref())) {
            out.add(
                t.id
                    + ": artifact "
                    + a.kind()
                    + " ref does not resolve under "
                    + repoRoot
                    + ": "
                    + a.ref());
          }
        }
      }
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  /**
   * One-shot migration from the retired Python pm store document (one JSON doc per project).
   * Imports tickets and per-prefix counters; returns the number of imported tasks.
   *
   * <p>T-242 (AC5, folded in by T-226's verb sweep): the per-ticket overwrite is GUARDED
   * verdict-based - an id whose existing record's verdict is terminal ({@code done}/ {@code
   * not-implemented}) is refused (the same S4 one-way-door class T-225 closed in {@code
   * update}/{@code setPaused} and T-226 in {@code routeReviewDoubt}), and an id that exists only in
   * the archive pool is refused too (importing it would fork the id across both pools - T-240's
   * history-vs-hole). Legitimate migration keeps working: a FRESH id always imports, a live
   * (non-terminal) record re-imports idempotently. Invalid rolls the WHOLE document back (the
   * transaction persists only on success).
   */
  public int importPmJson(String project, String pmJson) {
    JsonElement parsed = JsonParser.parseString(pmJson);
    if (!parsed.isJsonObject()) {
      throw new Invalid("pm export must be a JSON object");
    }
    JsonObject doc = parsed.getAsJsonObject();
    // T-242: the archive ids are resolved BEFORE the transaction (same read pattern readiness()
    // uses) - an absent archive pool simply contributes no ids.
    Set<String> archivedIds = new HashSet<>();
    for (Task archivedTask : archived(project)) {
      archivedIds.add(archivedTask.id);
    }
    return transaction(
        project,
        data -> {
          int count = 0;
          if (doc.has("tickets") && doc.getAsJsonObject("tickets").size() > 0) {
            for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("tickets").entrySet()) {
              Task t = fromPmTicket(e.getValue().getAsJsonObject());
              if (!TaskFileCodec.isValidId(t.id)) {
                throw new Invalid(
                    "ticket '"
                        + e.getKey()
                        + "' has an unusable id '"
                        + t.id
                        + "'; ids double as file names");
              }
              // T-242 (AC5): the guard this verb was missing - data.tasks.put() used to REPLACE
              // any existing record blindly, so importing a pm doc over a done id silently
              // un-terminated it (the S4 class T-225/T-226 closed on update/setPaused/
              // routeReviewDoubt). Verdict-based via T-225's verdictStatus: a chain-blocked done
              // hop is judged by its resume_to verdict; only a TERMINAL verdict refuses - live
              // rows re-import (idempotent refresh, PmImportTest) and fresh ids import (the
              // migration's whole purpose).
              Task prior = data.tasks.get(t.id);
              if (prior != null) {
                String verdict = verdictStatus(prior);
                if (terminalStatus(verdict)) {
                  throw new Invalid(
                      "cannot import over ticket "
                          + t.id
                          + " - its verdict is '"
                          + verdict
                          + "' (T-242: pm-import REPLACES the record, done/not-implemented are"
                          + " one-way - import under a fresh id, or reopen with an explicit verb"
                          + " that records history first)");
                }
              } else if (archivedIds.contains(t.id)) {
                // Same defect class against the archive pool: the live write would fork the id
                // across pools (the archived twin shadows every archive-backed lookup afterwards).
                throw new Invalid(
                    "cannot import over ticket "
                        + t.id
                        + " - it is archived (T-240: history vs hole; pm-import would fork the id"
                        + " across both pools - restore the archive record first, or import under"
                        + " a fresh id)");
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
          if (doc.has("stage_agents")) {
            readStageAgents(doc, data);
            data.metaDirty = true;
          }
          return count;
        });
  }

  // ------------------------------------------------------------------
  // Transaction engine
  // ------------------------------------------------------------------

  /**
   * {@code _meta.json}'s {@code stage_agents} map -> the in-memory dispatch override (U-104); an
   * absent key leaves the map empty (every stage uses its default agent definition).
   */
  private static void readStageAgents(JsonObject doc, ProjectData data) {
    JsonObject map =
        doc.has("stage_agents") && doc.get("stage_agents").isJsonObject()
            ? doc.getAsJsonObject("stage_agents")
            : null;
    if (map == null) {
      return;
    }
    for (Map.Entry<String, JsonElement> e : map.entrySet()) {
      if (e.getValue().isJsonPrimitive()) {
        data.stageAgents.put(e.getKey(), e.getValue().getAsString());
      }
    }
  }

  private static final class ProjectData {
    final Map<String, Task> tasks = new LinkedHashMap<>();
    final Map<String, Integer> seq = new LinkedHashMap<>();

    /** U-104: stage -> dispatch agent override ({@code _meta.json}'s {@code stage_agents}). */
    final Map<String, String> stageAgents = new LinkedHashMap<>();

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
   * Whether the project's directory does not exist on disk yet - read entry points short-circuit on
   * this so they never materialize a directory as a side effect (writes still create it, as they
   * must).
   */
  private boolean projectDirectoryMissing(String project) {
    return !Files.isDirectory(root.resolve(sanitizeProject(project)));
  }

  /**
   * The repository root this store lives in, or null when it cannot be determined. The store does
   * not know its repo: it is by convention rooted at {@code <repo>/.harness/tasks} (the stdio
   * launcher's default and the Eclipse preference's derived root both use exactly that), so the
   * repo root is two levels up the store root's parents - but only when the immediate parent really
   * is a directory named {@code .harness}. The legacy layout {@code <repo>/.opencode/tasks} is
   * honored too (stores predating the T-052 relocation). Any other layout (a temp fixture, a custom
   * root) means the repo root is unknown, and every repo-root-dependent check (task_doctor's
   * artifact resolution) must skip rather than resolve refs against an accidental parent directory.
   */
  private Path repoRootOrNull() {
    Path parent = root.toAbsolutePath().normalize().getParent();
    if (parent == null || parent.getFileName() == null) {
      return null;
    }
    String name = parent.getFileName().toString();
    if (!".harness".equals(name) && !".opencode".equals(name)) {
      return null;
    }
    Path repo = parent.getParent();
    return repo != null && Files.isDirectory(repo) ? repo : null;
  }

  /**
   * Whether an artifact ref looks like a plain path the doctor dares to resolve: no glob or brace
   * characters ({@code { } * ?}), no commas (enumerations) and no spaces (prose). Anything else is
   * left alone - the doctor checks paths, not prose.
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
   * Whether the ref resolves to an existing file under the repo root (absolute refs resolve to
   * themselves). A ref that cannot even form a path (a {@code :} in a Windows-relative ref, a stray
   * URL) counts as unresolvable - that is exactly the corruption the doctor exists to surface.
   */
  private static boolean artifactRefResolves(Path repoRoot, String ref) {
    try {
      return Files.exists(repoRoot.resolve(ref));
    } catch (java.nio.file.InvalidPathException e) {
      return false;
    }
  }

  /**
   * T-224 check 2 (rubberduck S8): the load keys tickets by their frontmatter {@code id} with no
   * filename comparison, so a hand-edited id silently forks identity - the sorted load lets one
   * record overwrite the other, the orphaned ticket becomes NotFound while its file still exists,
   * and doctor used to stay ok:true. Every ticket file's own stem is compared with its own parsed
   * id (a fork can only exist when at least one file's id differs from its own stem), mirroring
   * {@code load}'s file filter exactly (top-level {@code *.md}, never dot/underscore-prefixed, so
   * {@code _meta}-style sidecars and {@code _goal}-style roots stay out); unparsable files are left
   * to the unparsable report they already produce. Runs inside the doctor's transaction; read-only.
   *
   * <p>T-226 (T-224 follow-up): BOTH pools are scanned - the archive pool rides the same walk with
   * an {@code _archive/} report prefix. Archived tickets are not historical write-only records:
   * {@code task_get}/link resolution and the doc-ref check read them (the resolution pool below),
   * so an id/filename fork parked in {@code _archive/} is live damage: the forked record can shadow
   * or overwrite its twin in every archive-backed lookup. The walk is read-only and one directory
   * deeper - cheap enough to run every doctor pass.
   */
  private static List<String> fileStemIdMismatches(ProjectData data) {
    List<String> out = new ArrayList<>();
    out.addAll(fileStemIdMismatchesIn(data.dir, ""));
    out.addAll(fileStemIdMismatchesIn(data.dir.resolve("_archive"), "_archive/"));
    return out;
  }

  /**
   * The stem/id comparison over one directory of ticket files ({@code dir}); each finding is
   * prefixed with {@code reportPrefix} so a fork in the archive pool can be told apart from its
   * live twin. A missing directory (no archive yet) and unreadable listings yield nothing.
   */
  private static List<String> fileStemIdMismatchesIn(Path dir, String reportPrefix) {
    List<String> out = new ArrayList<>();
    List<Path> files = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".md") && !n.startsWith(".") && !n.startsWith("_");
              })
          .forEach(files::add);
    } catch (IOException e) {
      return out; // load() already surfaces listing failures; there is nothing to compare
    }
    for (Path p : files) {
      String name = p.getFileName().toString();
      String stem = name.substring(0, name.length() - ".md".length());
      String id;
      try {
        id = TaskFileCodec.read(Files.readString(p, StandardCharsets.UTF_8)).id;
      } catch (IOException | RuntimeException e) {
        continue; // unparsable: reported through data.unparsable already (live) / the archive
        // loader's WARNING log (archived)
      }
      if (stem.equals(id)) {
        continue;
      }
      out.add(reportPrefix + name + ": file stem '" + stem + "' != frontmatter id '" + id + "'");
    }
    return out;
  }

  /**
   * T-224 check 4's EXPLICIT override mechanism: a ticket whose role deliberately differs from
   * {@link VStages#roleOf} records why ON THE TICKET - a non-blank {@code role_override:}
   * frontmatter line naming the reason (the codec preserves unknown frontmatter keys verbatim, so
   * the marker round-trips every rewrite without a schema or code change). Chosen over a code
   * allow-list because the exemption then lives in the store next to the decision - reviewable in
   * the ticket file itself, no rebuild per override, never hidden in a build artifact. A blank or
   * {@code null} marker keeps the check strict.
   */
  private static boolean roleOverrideRecorded(Task t) {
    String v = t.extraFrontmatter == null ? null : t.extraFrontmatter.get("role_override");
    return v != null && !v.isBlank() && !"null".equals(v.strip());
  }

  private <T> T transaction(String project, Function<ProjectData, T> work) {
    Path dir = root.resolve(sanitizeProject(project));
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIo("cannot create project directory " + dir, e);
    }
    Path lockFile = dir.resolve(".lock");
    ReentrantLock jvmLock =
        JVM_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), k -> new ReentrantLock());
    jvmLock.lock();
    try {
      FileChannel channel =
          FileChannel.open(
              lockFile,
              StandardOpenOption.CREATE,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE);
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
        JsonObject doc =
            JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (doc.has("seq")) {
          for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("seq").entrySet()) {
            data.seq.put(e.getKey(), e.getValue().getAsInt());
          }
        }
        data.counter = doc.has("counter") ? doc.get("counter").getAsInt() : 0;
        readStageAgents(doc, data); // U-104; absent key leaves the map empty
      } catch (IOException | RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "unreadable _meta.json in " + dir + " (" + e.getMessage() + "); recovering",
            e);
      }
    }
    List<Path> files = new ArrayList<>();
    try (var stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
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
        data.unparsable.add(
            p.getFileName()
                + ": unparsable ticket file, invisible to every other"
                + " task_* read ("
                + e.getMessage()
                + ")");
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
      JsonObject stageAgents = new JsonObject();
      data.stageAgents.forEach(stageAgents::addProperty);
      meta.add("stage_agents", stageAgents);
      writeAtomic(data.dir.resolve("_meta.json"), GSON.toJson(meta));
    }
  }

  private static void writeAtomic(Path target, String content) throws IOException {
    Path tmp =
        target.resolveSibling("." + target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
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

  /**
   * The ticket a WRITE verb resolves - live pool only, so an archived ticket can never be mutated
   * through the tools. T-240: the verdict is no longer a false "does not exist" - an archived id
   * names its archive file (the write is refused either way), a genuinely absent id keeps the
   * historical message.
   */
  private Task require(ProjectData data, String project, String id) {
    Task t = data.tasks.get(id);
    if (t == null) {
      throw notFound(data, project, id);
    }
    return t;
  }

  /**
   * The one "no such ticket" verdict (T-240): an id parked in {@code _archive/} is HISTORY, not a
   * hole - it names its archive file, so an archived id and a genuinely absent id stay
   * distinguishable to every agent following a doc reference through the tool surface. The absent
   * id keeps the exact historical message (pinned by {@code
   * TaskStoreSemanticsTest#getMissingFailsWithPmMessage} and {@code TaskToolsDispatchTest}'s T-404
   * shape), and the exception stays {@link NotFound} so every verb's error routing (the tool
   * surface's {@code isError} text) is untouched.
   */
  private NotFound notFound(ProjectData data, String project, String id) {
    if (TaskFileCodec.isValidId(id)
        && Files.isRegularFile(data.dir.resolve("_archive").resolve(id + ".md"))) {
      return new NotFound("ticket " + id + " is archived - see " + archiveRef(data, id));
    }
    return new NotFound("ticket " + id + " not found in project " + project);
  }

  /**
   * Where the archive verdict points: repo-relative (the form docs and agents can follow - {@code
   * .harness/tasks/<project>/_archive/<id>.md}) when the store sits in a known repo layout, the
   * absolute file path otherwise (a temp fixture, a dedicated store repo outside any repo root).
   */
  private String archiveRef(ProjectData data, String id) {
    Path file = data.dir.resolve("_archive").resolve(id + ".md").toAbsolutePath().normalize();
    Path repoRoot = repoRootOrNull();
    if (repoRoot != null) {
      try {
        return repoRoot.relativize(file).toString().replace('\\', '/');
      } catch (IllegalArgumentException differentRoots) {
        // not under the repo root after all - the absolute path still names the file
      }
    }
    return file.toString().replace('\\', '/');
  }

  /**
   * U-067 invariant layer, applied on commit to every changed ticket: resume_to exists ONLY while
   * the ticket is in the blocked state (and a done ticket never keeps stale blocker text -
   * W-006/W-007). Entering the state is a semantic decision (Task.enterBlocked); this only removes
   * what the state forbids.
   */
  private static void normalizeBlockedState(Task t) {
    if (!t.isBlocked()) {
      t.resumeTo = null;
    } else if (t.resumeTo != null
        && (!Task.VALID_STATUSES.contains(t.resumeTo) || "blocked".equals(t.resumeTo))) {
      t.resumeTo = null;
    }
    if ("done".equals(t.status) && t.blocker != null) {
      // the engine outcome is visible in history (the old
      // clearBlockedWhenDone contract, live incident W-006)
      t.blocker = null;
      t.history("unblocked (done)", null);
    }
  }

  /**
   * Mints the next id as {@code <prefix>-NNN}.
   *
   * <p><b>U-109:</b> the FILTERED value is what actually reaches the file name, so the filtered
   * value is what gets validated - here, at the single mint point, not only upstream at the raw
   * guard (U-107). Before this, {@code W-!} filtered to {@code W-} and minted {@code W--001}, and
   * {@code !!} filtered to empty and SILENTLY fell back to {@code T}. <b>Decision (U-109): loud
   * error, never the silent fallback</b> - a caller that passed garbage is told, instead of being
   * quietly re-minted under an unrelated prefix; {@code null} still means the default {@code T}
   * (that is the documented default, not garbage).
   */
  private static String nextId(ProjectData data, String prefixRaw) {
    String prefix = prefixRaw == null ? "T" : prefixRaw.replaceAll("[^A-Za-z0-9_-]", "");
    if (prefix.isEmpty() || prefix.endsWith("-")) {
      throw new Invalid(
          "id_prefix must not be blank or end in '-' (it is minted as <prefix>-NNN; got '"
              + prefixRaw
              + "' which filters to '"
              + prefix
              + "')");
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

  /** T-032: the tool layer sorts {@code task_list(status=ready)} with this order. */
  public static Comparator<Task> prioritized() {
    return Comparator.comparing((Task t) -> -Task.PRIORITY_ORDER.getOrDefault(t.priority, 0))
        .thenComparing(t -> t.createdAt == null ? Instant.EPOCH : t.createdAt)
        .thenComparing(t -> t.id);
  }

  /**
   * Project names become directory names: separators fold to underscores, traversal is rejected.
   */
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

  /**
   * Coerces an update value to a boolean: Boolean, JsonElement primitive, or string
   * ("true"/"false").
   */
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
      throw new Invalid(
          "list items must be non-null strings (a null item would corrupt the file format)");
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
        throw new Invalid(
            field
                + " items must be non-null strings"
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
    // The pm server's backlog statuses map into the owner's no-backlog model
    // (2026-11-20): both product-backlog and sprint-backlog read as ready.
    String pmStatus = orDefault(strOrNull(o, "status"), "ready");
    t.status =
        "product-backlog".equals(pmStatus) || "sprint-backlog".equals(pmStatus)
            ? "ready"
            : pmStatus;
    t.resumeTo =
        o.has("resume_to") && !o.get("resume_to").isJsonNull()
            ? o.get("resume_to").getAsString()
            : null;
    if (o.has("blocked")
        && !o.get("blocked").isJsonNull()
        && o.get("blocked").getAsBoolean()
        && !"blocked".equals(t.status)) {
      // U-067: the pm server still sends the legacy flag - migrate on
      // import exactly like TaskFileCodec does on file read
      t.resumeTo = t.status;
      t.status = "blocked";
    }
    t.blocker = strOrNull(o, "blocker");
    t.storyPoints =
        o.has("story_points") && !o.get("story_points").isJsonNull()
            ? o.get("story_points").getAsInt()
            : 0;
    t.priority = orDefault(strOrNull(o, "priority"), "medium");
    t.role = orDefault(strOrNull(o, "role"), "developer");
    t.assignee = strOrNull(o, "assignee");
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
        t.todos.add(new Task.Todo(text, todo.has("done") && todo.get("done").getAsBoolean()));
      }
    }
    if (o.has("artifacts")) {
      for (JsonElement e : o.getAsJsonArray("artifacts")) {
        JsonObject a = e.getAsJsonObject();
        t.artifacts.add(
            new Task.Artifact(
                strOrNull(a, "kind"),
                strOrNull(a, "ref"),
                orEmpty(strOrNull(a, "note")),
                strOrNull(a, "by"),
                instantOrNull(a, "ts")));
      }
    }
    if (o.has("comments")) {
      for (JsonElement e : o.getAsJsonArray("comments")) {
        JsonObject c = e.getAsJsonObject();
        t.comments.add(
            new Task.Comment(
                instantOrNull(c, "ts"), strOrNull(c, "by"), orEmpty(strOrNull(c, "text"))));
      }
    }
    if (o.has("history")) {
      for (JsonElement e : o.getAsJsonArray("history")) {
        JsonObject h = e.getAsJsonObject();
        t.history.add(
            new Task.HistoryEvent(
                instantOrNull(h, "ts"), strOrNull(h, "action"), strOrNull(h, "by")));
      }
    }
    t.createdAt = instantOrNull(o, "created_at");
    t.updatedAt = instantOrNull(o, "updated_at");
    t.inputsChangedAt = instantOrNull(o, "inputs_changed_at"); // D-003; null on legacy imports
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

  /**
   * IOException wrapped into a runtime exception (store-level failure, not an agent-visible error).
   */
  public static final class UncheckedIo extends RuntimeException {
    UncheckedIo(String message, IOException cause) {
      super(message, cause);
    }
  }
}
