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
}
