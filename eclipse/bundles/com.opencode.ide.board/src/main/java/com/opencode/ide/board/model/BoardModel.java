package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.CostOverview;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * SWT-free kanban model over the Markdown {@link TaskStore}: one project, one
 * selected sprint, refreshed on demand (the view drives it from
 * {@link TaskStoreWatcher} events). All reads are tolerant of a missing or
 * unreadable store — a snapshot with an {@code error} comes back instead of an
 * exception.
 *
 * <p>Two layouts: {@link BoardMode#FLAT} (the five status columns) and
 * {@link BoardMode#PIPELINE} (the ten V-model stage columns plus a trailing
 * untracked group — tickets land in their {@link TicketRow#effectiveStage()},
 * legacy tickets by role fallback). The {@code blockedOnly} filter applies in
 * both modes.</p>
 */
public final class BoardModel {

    /** Pseudo-sprint id for tickets whose {@code sprint} field is null. */
    public static final String BACKLOG = CostOverview.BACKLOG;

    /** The single definition of the fallback project name (view + model share it). */
    public static final String DEFAULT_PROJECT = "hephaestus";

    /** The board layout: flat status kanban or V-model pipeline. */
    public enum BoardMode { FLAT, PIPELINE }

    private TaskStore store;
    private String project;
    private String sprint = BACKLOG;
    private BoardMode mode = BoardMode.FLAT;
    private boolean blockedOnly;
    /**
     * Stage visibility filter: {@code null} = all visible (default); otherwise
     * only tickets whose {@link TicketRow#effectiveStage()} is in the set are
     * shown. May contain {@link PipelineSnapshot#UNTRACKED} to include the
     * untracked group. An empty/null set is normalized to no filtering.
     * Applies in BOTH modes (pipeline: hides stage columns; flat: hides rows).
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

    /** The selected sprint id, or {@link #BACKLOG}. */
    public String sprint() {
        return sprint;
    }

    public void setSprint(String sprint) {
        this.sprint = sprint == null || sprint.isBlank() ? BACKLOG : sprint;
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
     * The stage visibility filter ({@code null} = all stages visible). The
     * returned set is a copy; mutating it has no effect.
     */
    public java.util.Set<String> stageFilter() {
        return stageFilter == null ? null : java.util.Set.copyOf(stageFilter);
    }

    /**
     * Restricts the board to the given effective stages. {@code null} or an
     * empty set clears the filter (everything visible). The special id
     * {@link PipelineSnapshot#UNTRACKED} includes stage-less tickets.
     */
    public void setStageFilter(java.util.Set<String> stages) {
        this.stageFilter = (stages == null || stages.isEmpty()) ? null : java.util.Set.copyOf(stages);
    }

    /** @return true when the given effective stage passes the stage filter. */
    private boolean stageVisible(String effectiveStage) {
        if (stageFilter == null) {
            return true;
        }
        if (effectiveStage == null || !VStages.isValid(effectiveStage)) {
            return stageFilter.contains(PipelineSnapshot.UNTRACKED);
        }
        return stageFilter.contains(effectiveStage);
    }

    /**
     * Reads the board for the selected sprint in the current {@link #mode()}.
     * {@link #BACKLOG} selects the tickets with a {@code null} sprint field
     * (client-side filter over the unfiltered board); a named sprint selects
     * exactly that sprint's tickets.
     */
    public BoardSnapshot refresh() {
        Path dir = projectDir();
        if (!Files.isDirectory(dir)) {
            return BoardSnapshot.empty("Task store not found: " + dir);
        }
        try {
            Map<String, List<Task>> board = BACKLOG.equals(sprint)
                    ? unassignedBoard()
                    : store.board(project, sprint);
            Map<String, List<TicketRow>> columns = new LinkedHashMap<>();
            List<TicketRow> allRows = new ArrayList<>();
            int total = 0;
            int blocked = 0;
            for (String status : Task.VALID_STATUSES) {
                List<TicketRow> rows = new ArrayList<>();
                for (Task t : board.getOrDefault(status, List.of())) {
                    TicketRow row = TicketRow.from(t);
                    if (row == null || (blockedOnly && !row.blocked())
                            || !stageVisible(row.effectiveStage())) {
                        continue;
                    }
                    rows.add(row);
                    allRows.add(row);
                    total++;
                    if (row.blocked()) {
                        blocked++;
                    }
                }
                columns.put(status, List.copyOf(rows));
            }
            String goal = BACKLOG.equals(sprint) ? "" : sprintGoals(dir).getOrDefault(sprint, "");
            PipelineSnapshot pipeline = mode == BoardMode.PIPELINE ? pipelineOf(allRows) : null;
            return new BoardSnapshot(columns, goal, total, blocked, null, pipeline);
        } catch (RuntimeException e) {
            return BoardSnapshot.empty("Task store unreadable: " + e.getMessage());
        }
    }

    /**
     * Groups rows into the ten canonical V stages (always all ten, in
     * {@link VStages#STAGES} order) plus the trailing untracked group for
     * tickets whose effective stage is {@code null} or not a canonical stage.
     */
    private PipelineSnapshot pipelineOf(List<TicketRow> rows) {
        Map<String, List<TicketRow>> byStage = new LinkedHashMap<>();
        for (String stage : VStages.STAGES) {
            byStage.put(stage, new ArrayList<>());
        }
        List<TicketRow> untracked = new ArrayList<>();
        for (TicketRow row : rows) {
            String stage = row.effectiveStage();
            List<TicketRow> target = stage == null ? null : byStage.get(stage);
            if (target == null) {
                untracked.add(row);
            } else {
                target.add(row);
            }
        }
        List<StageColumn> columns = new ArrayList<>();
        for (String stage : VStages.STAGES) {
            if (stageFilter == null || stageFilter.contains(stage)) {
                columns.add(stageColumn(stage, byStage.get(stage)));
            }
        }
        if (stageFilter == null || stageFilter.contains(PipelineSnapshot.UNTRACKED)) {
            columns.add(stageColumn(PipelineSnapshot.UNTRACKED, untracked));
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
     * Advances the ticket one V stage via the store (allowed from
     * in-review/done only).
     *
     * @return {@code null} on success, a human-readable failure message otherwise.
     */
    public String advance(String id) {
        try {
            store.advance(project, id, "board");
            return null;
        } catch (RuntimeException e) {
            return failure("advance", id, e);
        }
    }

    /**
     * Sends the ticket one V stage back (blocked with the given reason) via
     * the store.
     *
     * @return {@code null} on success, a human-readable failure message otherwise.
     */
    public String sendBack(String id, String reason) {
        try {
            store.sendBack(project, id, reason, "board");
            return null;
        } catch (RuntimeException e) {
            return failure("send back", id, e);
        }
    }

    private static String failure(String what, String id, RuntimeException e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "Cannot " + what + " " + id + ": " + detail;
    }

    /**
     * The selectable sprint ids: distinct non-null sprints from the tickets,
     * plus sprints seen in {@code _meta.json}, sorted — with {@link #BACKLOG}
     * appended last.
     */
    public List<String> sprints() {
        TreeSet<String> ids = new TreeSet<>();
        Path dir = projectDir();
        if (Files.isDirectory(dir)) {
            try {
                for (Task t : store.list(project, null, null, null, null)) {
                    if (t.sprint != null && !t.sprint.isBlank()) {
                        ids.add(t.sprint);
                    }
                }
            } catch (RuntimeException ignored) {
                // unreadable store: fall through to whatever _meta.json knows
            }
            ids.addAll(sprintGoals(dir).keySet());
        }
        List<String> out = new ArrayList<>(ids);
        out.add(BACKLOG);
        return out;
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
     * Every ticket of the project as full {@link Task}s — the snapshot basis
     * {@link com.opencode.ide.tasks.StageReadiness#evaluate(List)} expects
     * (upstream evidence may live in any ticket of the project). Tolerant of
     * a missing/unreadable store: an empty list instead of an exception.
     */
    public List<Task> projectTasks() {
        if (!Files.isDirectory(projectDir())) {
            return List.of();
        }
        try {
            return List.copyOf(store.list(project, null, null, null, null));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The selected sprint's tickets as full {@link Task}s — {@link #BACKLOG}
     * selects the unassigned ones, exactly like {@link #refresh()}.
     */
    public List<Task> sprintTasks() {
        List<Task> out = new ArrayList<>();
        for (Task t : projectTasks()) {
            if (BACKLOG.equals(sprint) ? t.sprint == null : sprint.equals(t.sprint)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Aggregates the fleet cost actuals comments over ALL tickets of the
     * project (per ticket, per sprint, project-wide — see
     * {@link CostOverview}). Tolerant of a missing/unreadable store: an empty
     * overview comes back instead of an exception.
     */
    public CostOverview costOverview() {
        if (!Files.isDirectory(projectDir())) {
            return CostOverview.empty();
        }
        try {
            return CostOverview.of(store.list(project, null, null, null, null));
        } catch (RuntimeException e) {
            return CostOverview.empty();
        }
    }

    private Map<String, List<Task>> unassignedBoard() {
        Map<String, List<Task>> out = new LinkedHashMap<>();
        for (String status : Task.VALID_STATUSES) {
            out.put(status, new ArrayList<>());
        }
        for (Task t : store.list(project, null, null, null, null)) {
            if (t.sprint == null) {
                List<Task> column = out.get(t.status);
                if (column != null) {
                    column.add(t);
                }
            }
        }
        return out;
    }

    private Path projectDir() {
        return store.root().resolve(TaskStore.sanitizeProject(project));
    }

    /**
     * Read-only parse of {@code _meta.json}'s {@code sprints} map
     * (id → goal). Tolerant: any missing/corrupt sidecar yields an empty map.
     */
    private static Map<String, String> sprintGoals(Path projectDir) {
        Map<String, String> goals = new LinkedHashMap<>();
        Path meta = projectDir.resolve("_meta.json");
        if (!Files.isRegularFile(meta)) {
            return goals;
        }
        try {
            JsonObject doc = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            if (doc.has("sprints") && doc.get("sprints").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject("sprints").entrySet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject sprint = e.getValue().getAsJsonObject();
                    String goal = sprint.has("goal") && !sprint.get("goal").isJsonNull()
                            ? sprint.get("goal").getAsString() : "";
                    goals.put(e.getKey(), goal);
                }
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // unreadable sidecar: goals simply stay empty
        }
        return goals;
    }
}
