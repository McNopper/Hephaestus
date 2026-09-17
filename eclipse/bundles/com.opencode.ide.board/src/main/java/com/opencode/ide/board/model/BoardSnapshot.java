package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;

/**
 * Immutable result of {@link BoardModel#refresh()}: the five kanban columns
 * (all statuses always present, in store order), the selected sprint's goal,
 * totals, an error/notice string ({@code null} when the board loaded fine -
 * e.g. "task store not found at ..." when the store root is missing), and
 * the per-ticket {@link StageReadiness} dispatch verdicts (U-018: the board
 * surfaces what should run NOW - ready counts on the fleet controls, stale/
 * waiting chips on the cards; keyed by ticket id, empty when unknown).
 *
 * <p>In {@link BoardModel.BoardMode#PIPELINE} the snapshot additionally
 * carries a {@link PipelineSnapshot} (all ten V stages plus the trailing
 * untracked group); in {@link BoardModel.BoardMode#FLAT} {@link #pipeline()}
 * is {@code null} and the flat columns are the authoritative grouping.</p>
 */
public record BoardSnapshot(Map<String, List<TicketRow>> columns, String sprintGoal,
        int total, int blockedCount, String error, PipelineSnapshot pipeline,
        Map<String, StageReadiness.Readiness> readiness,
        Map<String, List<TicketRow>> epicLanes) {

    /** FLAT-mode shape: no pipeline grouping. */
    public BoardSnapshot(Map<String, List<TicketRow>> columns, String sprintGoal,
            int total, int blockedCount, String error) {
        this(columns, sprintGoal, total, blockedCount, error, null, Map.of(), Map.of());
    }

    public BoardSnapshot(Map<String, List<TicketRow>> columns, String sprintGoal,
            int total, int blockedCount, String error, PipelineSnapshot pipeline) {
        this(columns, sprintGoal, total, blockedCount, error, pipeline, Map.of(), Map.of());
    }

    public BoardSnapshot(Map<String, List<TicketRow>> columns, String sprintGoal,
            int total, int blockedCount, String error, PipelineSnapshot pipeline,
            Map<String, StageReadiness.Readiness> readiness) {
        this(columns, sprintGoal, total, blockedCount, error, pipeline, readiness, Map.of());
    }

    /** An all-empty board carrying an error/notice message. */
    public static BoardSnapshot empty(String error) {
        Map<String, List<TicketRow>> columns = new LinkedHashMap<>();
        for (String status : Task.VALID_STATUSES) {
            columns.put(status, new ArrayList<>());
        }
        return new BoardSnapshot(columns, "", 0, 0, error);
    }

    /** One column's rows; never {@code null}, missing statuses read as empty. */
    public List<TicketRow> column(String status) {
        List<TicketRow> rows = columns.get(status);
        return rows == null ? List.of() : rows;
    }

    /** @return how many sprint tickets the readiness verdict calls READY (dispatchable now). */
    public long readyCount() {
        return count(StageReadiness.Kind.READY);
    }

    /** @return how many sprint tickets the readiness verdict calls STALE (upstream changed - re-run needed). */
    public long staleCount() {
        return count(StageReadiness.Kind.STALE);
    }

    private long count(StageReadiness.Kind kind) {
        return readiness.values().stream().filter(r -> r != null && r.kind() == kind).count();
    }

    /** The verdict for a ticket id; {@code null} when unknown. */
    public StageReadiness.Readiness readinessOf(String id) {
        return id == null ? null : readiness.get(id);
    }
}
