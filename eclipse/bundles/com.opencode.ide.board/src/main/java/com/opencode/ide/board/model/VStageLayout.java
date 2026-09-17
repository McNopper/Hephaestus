package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.opencode.ide.tasks.VStages;

/**
 * SWT-free geometry of the board's V-model arrangement (U-016): the ten
 * canonical stages placed on a diagonal grid so the definition leg descends
 * from the top left to the bottom-centre tip and the verification leg
 * ascends from the tip to the top right, with the trailing untracked group
 * beside the tip. The Board view renders {@link #grid()} cell by cell
 * ({@code null} cells become spacers); the tests pin the V shape and both
 * leg orders.
 *
 * <p>Everything derives from {@link VStages#STAGES} — the canonical ladder —
 * so the geometry can never drift from it: the pipeline column order of a
 * {@link PipelineSnapshot} (definition leg followed by verification leg)
 * equals the V order, read left/top to right/bottom down the two legs.</p>
 */
public final class VStageLayout {

    /** Grid columns: one per canonical stage (the V spans the full board width). */
    public static final int GRID_COLUMNS = VStages.STAGES.size();

    /** Grid rows: one per V level (both legs carry five stages). */
    public static final int GRID_ROWS = VStages.STAGES.size() / 2;

    /** A cell in the V grid: row 0 is the top, column 0 the left edge. */
    public record Cell(int row, int column) {
    }

    private VStageLayout() {
    }

    /**
     * The V arrangement row by row: {@code grid().get(r).get(c)} is the stage
     * id (or {@link PipelineSnapshot#UNTRACKED}) at that cell, {@code null}
     * for an empty spacer cell. Every row has exactly {@link #GRID_COLUMNS}
     * cells, so the grid maps 1:1 onto an SWT {@code GridLayout} with that
     * many columns.
     */
    public static List<List<String>> grid() {
        String[][] cells = new String[GRID_ROWS][GRID_COLUMNS];
        for (String stage : VStages.STAGES) {
            Cell cell = cellOf(stage);
            cells[cell.row()][cell.column()] = stage;
        }
        Cell untracked = cellOf(PipelineSnapshot.UNTRACKED);
        cells[untracked.row()][untracked.column()] = PipelineSnapshot.UNTRACKED;
        List<List<String>> rows = new ArrayList<>(GRID_ROWS);
        for (String[] row : cells) {
            rows.add(Arrays.asList(row));
        }
        return rows;
    }

    /**
     * The grid cell of a canonical stage or of the trailing
     * {@link PipelineSnapshot#UNTRACKED} group; {@code null} for unknown or
     * {@code null} ids.
     */
    public static Cell cellOf(String stage) {
        if (stage == null) {
            return null;
        }
        if (PipelineSnapshot.UNTRACKED.equals(stage)) {
            // beside the V tip, trailing the pipeline's last stage
            return new Cell(GRID_ROWS - 1, GRID_ROWS + 1);
        }
        int index = VStages.STAGES.indexOf(stage);
        if (index < 0) {
            return null;
        }
        if (index < GRID_ROWS) {
            // definition leg: descends one column per row from the top left
            return new Cell(index, index);
        }
        // verification leg: ascends one column per row towards the top right
        int level = index - GRID_ROWS;
        return new Cell(GRID_ROWS - 1 - level, GRID_ROWS + level);
    }

    /** The definition leg, top to bottom: requirements → implementation. */
    public static List<String> definitionLeg() {
        return List.copyOf(VStages.STAGES.subList(0, GRID_ROWS));
    }

    /** The verification leg, bottom to top: test-implementation → test-requirements. */
    public static List<String> verificationLeg() {
        return List.copyOf(VStages.STAGES.subList(GRID_ROWS, VStages.STAGES.size()));
    }
}
