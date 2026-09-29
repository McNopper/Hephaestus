package com.opencode.ide.board.model;

import java.util.List;

/**
 * Immutable PIPELINE-mode grouping of a board: one {@link StageColumn} for
 * <b>every</b> canonical V stage — always all ten, in
 * {@link com.opencode.ide.tasks.VStages#STAGES} order (the V order: the
 * definition leg followed by the verification leg; the view renders every
 * column on the {@link VStageLayout} diagonal, empty ones included — U-016)
 * — plus a trailing {@link #UNTRACKED} group for tickets with no stage and
 * no role-derived stage (unknown roles and the like).
 */
public record PipelineSnapshot(List<StageColumn> columns) {

    /** Stage key of the trailing untracked group. */
    public static final String UNTRACKED = "(untracked)";

    /**
     * One stage's column; never {@code null} — an unknown stage reads as an
     * empty column so callers never throw on absent data.
     */
    public StageColumn column(String stage) {
        for (StageColumn column : columns) {
            if (column.stage().equals(stage)) {
                return column;
            }
        }
        return new StageColumn(stage, List.of(), 0, 0);
    }

    /**
     * U-028 FR-001/FR-004: WIP is the plain count of tickets with status
     * {@code in-progress} - a COUNT only. There is NO WIP-limit concept: no
     * warning, no gating, no setting, no tool. Recomputed per snapshot.
     */
    public static int wipCount(List<TicketRow> rows) {
        int wip = 0;
        for (TicketRow row : rows) {
            if ("in-progress".equals(row.status())) {
                wip++;
            }
        }
        return wip;
    }

    /** U-028 FR-002/FR-005: the board-wide WIP count across every column. */
    public int wipTotal() {
        int total = 0;
        for (StageColumn column : columns) {
            total += wipCount(column.rows());
        }
        return total;
    }
}
