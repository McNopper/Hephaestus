package com.opencode.ide.board.model;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SWT-free kanban model over the Markdown {@link TaskStore}: one project, refreshed on demand (the
 * view drives it from {@link TaskStoreWatcher} events). All reads are tolerant of a missing or
 * unreadable store — a snapshot with an {@code error} comes back instead of an exception.
 *
 * <p>Two layouts: {@link BoardMode#FLAT} (the seven status columns (paused and blocked included))
 * and {@link BoardMode#PIPELINE} (the ten V-model stage columns - tickets land in their {@link
 * TicketRow#effectiveStage()}, legacy tickets by role fallback; there is NO stage-less group, owner
 * directive 2026-11-19). The {@code blockedOnly} filter applies in both modes.
 */
public final class BoardModel {

  /** The single definition of the fallback project name (view + model share it). */
  public static final String DEFAULT_PROJECT = "hephaestus";

  /** The board layout: flat status kanban or V-model pipeline. */
  public enum BoardMode {
    FLAT,
    PIPELINE
  }

  private TaskStore store;
  private String project;

  /** U-018: free-text filter over id/title (case-insensitive substring, empty = no filter). */
  private String textFilter = "";

  /** U-018: hidden statuses (per-status visibility; empty = all visible). */
  private java.util.Set<String> hiddenStatuses = java.util.Set.of();

  private BoardMode mode = BoardMode.FLAT;
  private boolean blockedOnly;

  /**
   * True when only bug tickets are shown (U-005's triage filter: bugs first). Applies in both
   * modes, like {@link #blockedOnly}.
   */
  private boolean bugsOnly;

  /**
   * Stage visibility filter: {@code null} = all visible (default); otherwise only tickets whose
   * {@link TicketRow#effectiveStage()} is in the set are shown. An empty/null set is normalized to
   * no filtering. Applies in BOTH modes (pipeline: hides stage columns; flat: hides rows).
   */
  private java.util.Set<String> stageFilter;

  public BoardModel(Path root, String project) {
    this.store = new TaskStore(root);
    this.project = project == null || project.isBlank() ? DEFAULT_PROJECT : project;
  }

  /** The store root currently in use. */
  public Path root() {
    return store.root();
  }

  /** Points the model at another store root (replaces the internal store). */
  public void setRoot(Path root) {
    this.store = new TaskStore(root);
  }

  /** The project (subdirectory of the store root) currently shown. */
  public String project() {
    return project;
  }

  public void setProject(String project) {
    this.project = project == null || project.isBlank() ? DEFAULT_PROJECT : project;
  }

  /** The board layout; {@link BoardMode#FLAT} unless switched. */
  public BoardMode mode() {
    return mode;
  }

  public void setMode(BoardMode mode) {
    this.mode = mode == null ? BoardMode.FLAT : mode;
  }

  /** True when only blocked tickets are shown (applies to both modes). */
  public boolean blockedOnly() {
    return blockedOnly;
  }

  public void setBlockedOnly(boolean blockedOnly) {
    this.blockedOnly = blockedOnly;
  }

  /**
   * U-018: the free-text filter over id and title (case-insensitive substring). Empty/null clears
   * it. Applies to every layout.
   */
  public void setTextFilter(String text) {
    this.textFilter = text == null ? "" : text.trim();
  }

  public String textFilter() {
    return textFilter;
  }

  /**
   * U-018: hides tickets of the given STATUSES everywhere (flat column drops out, V cards vanish).
   * {@code null} or empty = all visible.
   */
  public void setStatusFilter(java.util.Set<String> hidden) {
    this.hiddenStatuses =
        hidden == null || hidden.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(hidden);
  }

  public java.util.Set<String> hiddenStatuses() {
    return hiddenStatuses;
  }

  /**
   * @return true when the row's id or title matches the text filter.
   */
  private boolean textVisible(TicketRow row) {
    if (textFilter.isEmpty()) {
      return true;
    }
    String needle = textFilter.toLowerCase();
    return (row.id() != null && row.id().toLowerCase().contains(needle))
        || (row.title() != null && row.title().toLowerCase().contains(needle));
  }

  /** True when only bug tickets are shown (applies to both modes). */
  public boolean bugsOnly() {
    return bugsOnly;
  }

  public void setBugsOnly(boolean bugsOnly) {
    this.bugsOnly = bugsOnly;
  }

  /**
   * The stage visibility filter ({@code null} = all stages visible). The returned set is a copy;
   * mutating it has no effect.
   */
  public java.util.Set<String> stageFilter() {
    return stageFilter == null ? null : java.util.Set.copyOf(stageFilter);
  }

  /**
   * Restricts the board to the given effective stages. {@code null} or an empty set clears the
   * filter (everything visible).
   */
  public void setStageFilter(java.util.Set<String> stages) {
    this.stageFilter = (stages == null || stages.isEmpty()) ? null : java.util.Set.copyOf(stages);
  }

  /**
   * @return true when the given effective stage passes the stage filter.
   */
  private boolean stageVisible(String effectiveStage) {
    if (stageFilter == null) {
      return true;
    }
    if (effectiveStage == null || !VStages.isValid(effectiveStage)) {
      // no stage = no place on the V (the stage-less group is retired): a stage
      // filter hides it; without a filter the early return above already showed it
      return false;
    }
    return stageFilter.contains(effectiveStage);
  }

  /**
   * Reads the board in the current {@link #mode()} - ALL tickets (owner model 2026-11-20: no
   * backlogs, no sprint scope; the filters carry the scope).
   */
  public BoardSnapshot refresh() {
    Path dir = projectDir();
    if (!Files.isDirectory(dir)) {
      return BoardSnapshot.empty("Task store not found: " + dir);
    }
    try {
      // U-020 follow-up (user direction 2026-09-18): the board shows
      // ALL tickets - scope comes from the filters (blocked/bugs/stages/
      // text); the owner's no-backlog model (2026-11-20) removed the last
      // sprint remnant.
      Map<String, List<Task>> board = store.board(project);
      // U-018: dispatch-readiness verdicts over the WHOLE sprint task
      // set (upstream stages need the unfiltered
      // tickets), computed once per refresh and carried in the snapshot
      List<Task> sprintTasks = new ArrayList<>();
      for (List<Task> tasks : board.values()) {
        sprintTasks.addAll(tasks);
      }
      Map<String, com.opencode.ide.tasks.StageReadiness.Readiness> readiness =
          com.opencode.ide.tasks.StageReadiness.evaluate(sprintTasks, store.archived(project));
      // U-026: per-ticket stage journeys parsed from the SAME store
      // read (the tasks above carry their history) - O(history size),
      // no extra I/O (NFR-PERF-001), a read-only projection (FR-007)
      Map<String, StageJourney> journeys = new LinkedHashMap<>();
      for (Task t : sprintTasks) {
        journeys.put(t.id, StageJourney.of(t));
      }
      Map<String, List<TicketRow>> columns = new LinkedHashMap<>();
      List<TicketRow> allRows = new ArrayList<>();
      int total = 0;
      int blocked = 0;
      for (String status : Task.VALID_STATUSES) {
        List<TicketRow> rows = new ArrayList<>();
        for (Task t : board.getOrDefault(status, List.of())) {
          TicketRow row = TicketRow.from(t);
          if (row == null
              || hiddenStatuses.contains(status)
              || (blockedOnly && !row.displayBlocked())
              || (bugsOnly && !row.isBug())
              || !stageVisible(row.effectiveStage())
              || !textVisible(row)) {
            continue;
          }
          rows.add(row);
          allRows.add(row);
          total++;
          if (row.blocked()) {
            blocked++;
          }
        }
        rows.sort(WITHIN_COLUMN);
        columns.put(status, List.copyOf(rows));
      }
      PipelineSnapshot pipeline = mode == BoardMode.PIPELINE ? pipelineOf(allRows) : null;
      return new BoardSnapshot(columns, total, blocked, null, pipeline, readiness, journeys);
    } catch (RuntimeException e) {
      return BoardSnapshot.empty("Task store unreadable: " + e.getMessage());
    }
  }

  /**
   * Groups rows into the ten canonical V stages (always all ten, in {@link VStages#STAGES} order).
   * A row whose effective stage is null or non-canonical has NO column: the stage-less group is
   * retired (owner directive 2026-11-19 - a ticket is born in a stage), flat mode still lists such
   * rows, the V does not.
   */
  private PipelineSnapshot pipelineOf(List<TicketRow> rows) {
    Map<String, List<TicketRow>> byStage = new LinkedHashMap<>();
    for (String stage : VStages.STAGES) {
      byStage.put(stage, new ArrayList<>());
    }
    for (TicketRow row : rows) {
      String stage = row.effectiveStage();
      List<TicketRow> target = stage == null ? null : byStage.get(stage);
      if (target != null) {
        target.add(row);
      }
    }
    List<StageColumn> columns = new ArrayList<>();
    for (String stage : VStages.STAGES) {
      if (stageFilter == null || stageFilter.contains(stage)) {
        byStage.get(stage).sort(WITHIN_COLUMN);
        columns.add(stageColumn(stage, byStage.get(stage)));
      }
    }
    return new PipelineSnapshot(List.copyOf(columns));
  }

  private static StageColumn stageColumn(String stage, List<TicketRow> rows) {
    int blocked = 0;
    int points = 0;
    for (TicketRow row : rows) {
      if (row.blocked()) {
        blocked++;
      }
      points += row.points();
    }
    return new StageColumn(stage, List.copyOf(rows), blocked, points);
  }

  /**
   * Moves a ticket to another status column (drag-and-drop on the flat kanban): a plain {@code
   * status} update via the store, same path the status tools use.
   *
   * @return {@code null} on success, a human-readable failure message otherwise.
   */
  /** O-002: scaffold a fresh task-store project (no hand-editing of .harness/tasks). */
  public void newProject(String name) {
    store.newProject(name, "board");
  }

  /** O-002: clear THIS project's tickets + wave history (other projects untouched). */
  public java.util.Map<String, Object> resetProject() {
    return store.resetProject(project, "board");
  }

  public String setStatus(String id, String status) {
    try {
      store.update(project, id, Map.of("status", status));
      return null;
    } catch (RuntimeException e) {
      return failure("move", id, e);
    }
  }

  /**
   * Moves a ticket to an arbitrary V stage (drag-and-drop on the V-model layout). A forward (or
   * same-depth) move is a plain {@code stage} update. A backward move carries the send-back
   * contract: the reason becomes the blocker text, and - like the store's one-step {@link
   * #sendBack(String, String)} - the ticket returns to ready unassigned.
   *
   * @param targetStage the stage id to move to ({@code null} is refused - a ticket is born in a
   *     stage, owner directive 2026-11-19)
   * @param reason required (non-blank) when the move goes backward, ignored otherwise
   * @return {@code null} on success, a human-readable failure message otherwise.
   */
  public String setStage(String id, String targetStage, String reason) {
    if (targetStage == null) {
      return "Cannot move " + id + " to no stage: a ticket is born in a stage";
    }
    TicketRow row = row(id);
    // null-safe like TicketRow.stageDepth (U-026): a stage-less row has no
    // depth, so the move reads as a forward jump rather than NPE-ing
    int from =
        row == null || row.effectiveStage() == null
            ? -1
            : VStages.STAGES.indexOf(row.effectiveStage());
    // no nullcheck here: the entry guard above already refused a null targetStage,
    // so it is non-null from here (and indexOf still yields -1 for an unknown stage)
    int to = VStages.STAGES.indexOf(targetStage);
    boolean backward = row != null && from >= 0 && to >= 0 && to < from;
    if (backward && (reason == null || reason.isBlank())) {
      return "Moving "
          + id
          + " back from '"
          + row.effectiveStage()
          + "' to '"
          + targetStage
          + "' needs a reason";
    }
    try {
      Map<String, Object> changes = new HashMap<>();
      changes.put("stage", targetStage);
      if (backward) {
        changes.put("status", "ready");
        changes.put("assignee", null);
        changes.put("blocked", true);
        changes.put("blocker", "sent back from " + row.effectiveStage() + ": " + reason.trim());
      }
      store.update(project, id, changes);
      return null;
    } catch (RuntimeException e) {
      return failure("move stage", id, e);
    }
  }

  /**
   * The row for {@code id} from a plain store read (drag source lookup); {@code null} when gone.
   */
  public TicketRow row(String id) {
    if (id == null) {
      return null;
    }
    try {
      return TicketRow.from(store.get(project, id));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Within-column card order: priority rank first, then V depth, then id — stable and SWT-free. */
  private static final java.util.Comparator<TicketRow> WITHIN_COLUMN =
      java.util.Comparator.comparingInt(TicketRow::priorityRank)
          .thenComparingInt(TicketRow::stageDepth)
          .thenComparing(TicketRow::id, java.util.Comparator.nullsLast(String::compareTo));

  /**
   * U-025: archives a done ticket (moves it to {@code _archive/}, out of the active
   * board/readiness/dispatch; the record keeps history and artifacts). The Archive row lists what
   * lives there.
   */
  public String archive(String id) {
    try {
      store.archive(project, id, "board");
      return null;
    } catch (RuntimeException e) {
      return failure("archive", id, e);
    }
  }

  /** The project's archived tickets as display rows (newest last). */
  public List<TicketRow> archivedRows() {
    try {
      return store.archived(project).stream()
          .map(TicketRow::from)
          .filter(java.util.Objects::nonNull)
          .toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  /** The archive size for the collapsed toggle label (cheap: file count). */
  public int archiveCount() {
    try {
      return store.archived(project).size();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static String failure(String what, String id, RuntimeException e) {
    String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return "Cannot " + what + " " + id + ": " + detail;
  }

  /** Loads the full {@link Task} behind a row id, or {@code null} when gone/unreadable. */
  public Task loadTask(String id) {
    try {
      return store.get(project, id);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Every ticket of the project as full {@link Task}s - the snapshot basis {@link
   * com.opencode.ide.tasks.StageReadiness#evaluate(List, List)} expects (upstream evidence may live
   * in any ticket of the project, live or archived - the archive pool rides alongside). Tolerant of
   * a missing/unreadable store: an empty list instead of an exception.
   */
  public List<Task> projectTasks() {
    if (!Files.isDirectory(projectDir())) {
      return List.of();
    }
    try {
      return List.copyOf(store.list(project, null, null, null));
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private Map<String, List<Task>> unassignedBoard() {
    Map<String, List<Task>> out = new LinkedHashMap<>();
    for (String status : Task.VALID_STATUSES) {
      out.put(status, new ArrayList<>());
    }
    for (Task t : store.list(project, null, null, null)) {
      List<Task> column = out.get(t.status);
      if (column != null) {
        column.add(t);
      }
    }
    return out;
  }

  private Path projectDir() {
    return store.root().resolve(TaskStore.sanitizeProject(project));
  }
}
