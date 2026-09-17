package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.opencode.ide.tasks.VStages;

/**
 * SWT-free geometry of the board's V-model arrangement (U-016): the ten
 * canonical stages on a <em>two-row boustrophedon</em> grid — the definition
 * leg reads left&#x2192;right on the top row, the verification leg continues
 * right&#x2192;left on the bottom row, so the flow snakes through all ten
 * stages and each definition stage sits <em>directly above</em> its
 * verification pair (requirements/test-requirements &#x2026;
 * implementation/test-implementation). That vertical pairing encodes the
 * V-model's verification mapping better than a literal diagonal V would,
 * tiles a rectangle without dead space, and keeps the board at five columns
 * per row instead of ten — no guaranteed horizontal scrolling. The trailing
 * untracked group parks in the spare bottom-right cell.
 *
 * <p>The reading order of the snake equals {@link VStages#STAGES} — the
 * canonical ladder — so the geometry can never drift from it: stage number
 * {@code n} (1-based, for the numbered column headers) is simply the index
 * in that list plus one. The Board view renders {@link #grid()} cell by
 * cell ({@code null} cells become spacers); the tests pin the shape, the
 * pairing, and both leg orders.</p>
 */
public final class VStageLayout {

    /** Grid columns: five stage pairs plus one spare cell for the untracked group. */
    public static final int GRID_COLUMNS = VStages.STAGES.size() / 2 + 1;

    /** Grid rows: two — definition leg on top, verification leg below. */
    public static final int GRID_ROWS = 2;

    /** A cell in the layout grid: row 0 is the top, column 0 the left edge. */
    public record Cell(int row, int column) {
    }

    private VStageLayout() {
    }

    /**
     * The boustrophedon arrangement row by row: {@code grid().get(r).get(c)}
     * is the stage id (or {@link PipelineSnapshot#UNTRACKED}) at that cell,
     * {@code null} for an empty spacer cell. Every row has exactly
     * {@link #GRID_COLUMNS} cells, so the grid maps 1:1 onto an SWT
     * {@code GridLayout} with that many columns.
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
     *
     * <p>Definition stage {@code i} (0-based, requirements first) sits on the
     * top row in column {@code i}. Its verification pair sits directly below
     * in the same column — which, read along the bottom row's right&#x2192;left
     * flow, is position {@code 5 - i} in the snake. The untracked group takes
     * the spare sixth cell of the bottom row, after the snake's tail.</p>
     */
    public static Cell cellOf(String stage) {
        if (stage == null) {
            return null;
        }
        if (PipelineSnapshot.UNTRACKED.equals(stage)) {
            return new Cell(1, GRID_COLUMNS - 1);
        }
        int index = VStages.STAGES.indexOf(stage);
        if (index < 0) {
            return null;
        }
        int half = VStages.STAGES.size() / 2;
        if (index < half) {
            // definition leg: top row, left to right
            return new Cell(0, index);
        }
        // verification leg: bottom row, placed under its definition pair
        // (test-implementation below implementation, …, test-requirements
        // below requirements)
        return new Cell(1, half - 1 - (index - half));
    }

    /**
     * The 1-based reading number of a canonical stage (its position along
     * the snake: 1 = requirements &#x2026; 5 = implementation (the turn),
     * 6 = test-implementation &#x2026; 10 = test-requirements); 0 for the
     * untracked group and unknown ids. The numbered headers use this so the
     * two-row layout still reads unambiguously as one sequence.
     */
    public static int stageNumber(String stage) {
        int index = VStages.STAGES.indexOf(stage);
        return index < 0 ? 0 : index + 1;
    }

    /** The definition leg, top to bottom: requirements → implementation. */
    public static List<String> definitionLeg() {
        return List.copyOf(VStages.STAGES.subList(0, VStages.STAGES.size() / 2));
    }

    /** The verification leg, bottom to top: test-implementation → test-requirements. */
    public static List<String> verificationLeg() {
        return List.copyOf(VStages.STAGES.subList(VStages.STAGES.size() / 2, VStages.STAGES.size()));
    }
}
