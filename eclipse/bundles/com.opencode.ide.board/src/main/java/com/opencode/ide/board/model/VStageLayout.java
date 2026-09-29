package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.opencode.ide.tasks.VStages;

/**
 * SWT-free geometry of the board's V-model arrangement (U-016, orientation
 * reworked on user direction 2026-09-18): <em>two vertical arms side by
 * side</em> — the LEFT column is the V's left arm, the definition leg
 * reading top&#x2192;bottom (requirements at the top, implementation at the
 * vertex), and the RIGHT column is the right arm, the verification leg
 * reading bottom&#x2192;top (test-implementation at the vertex,
 * test-requirements at the top). Each height level pairs a definition
 * stage with its verification stage (requirements | test-requirements,
 * &#x2026;, implementation | test-implementation), which is exactly the
 * classic V-diagram orientation; the flow snakes down the left arm, turns
 * at the vertex, and climbs the right arm. The trailing untracked group
 * parks in the spare third column cell beside the vertex.
 *
 * <p>The reading order of the snake equals {@link VStages#STAGES} — the
 * canonical ladder — so the geometry can never drift from it: stage number
 * {@code n} (1-based, for the numbered column headers) is simply the index
 * in that list plus one. The Board view renders {@link #grid()} cell by
 * cell ({@code null} cells become spacers); the tests pin the shape, the
 * level pairing, and both leg orders.</p>
 */
public final class VStageLayout {

    /** Grid columns: the two arms. The untracked group is NOT part of the V - the view renders it in its own row below, separated and hideable. */
    public static final int GRID_COLUMNS = 2;

    /** Grid rows: one per V level (both arms carry five stages). */
    public static final int GRID_ROWS = VStages.STAGES.size() / 2;

    /** A cell in the layout grid: row 0 is the top, column 0 the left arm. */
    public record Cell(int row, int column) {
    }

    private VStageLayout() {
    }

    /**
     * The two-arm arrangement row by row: {@code grid().get(r).get(c)} is
     * the stage id at that cell, {@code null} for an empty spacer cell
     * (none today - both arms are complete - but spacers stay supported).
     * Every row has exactly {@link #GRID_COLUMNS} cells, so the grid maps
     * 1:1 onto an SWT {@code GridLayout} with that many columns. The
     * untracked group is deliberately absent (see class doc).
     */
    public static List<List<String>> grid() {
        String[][] cells = new String[GRID_ROWS][GRID_COLUMNS];
        for (String stage : VStages.STAGES) {
            Cell cell = cellOf(stage);
            cells[cell.row()][cell.column()] = stage;
        }
        List<List<String>> rows = new ArrayList<>(GRID_ROWS);
        for (String[] row : cells) {
            rows.add(Arrays.asList(row));
        }
        return rows;
    }

    /**
     * The grid cell of a canonical stage; {@code null} for unknown or
     * {@code null} ids - and for {@link PipelineSnapshot#UNTRACKED}: the
     * untracked group is not part of the V geometry (own row, separator,
     * hideable).
     *
     * <p>Definition stage {@code i} (0-based, requirements first) sits in
     * the left arm at row {@code i}. Its verification pair sits in the
     * right arm at the SAME level.</p>
     */
    public static Cell cellOf(String stage) {
        if (stage == null || PipelineSnapshot.UNTRACKED.equals(stage)) {
            return null;
        }
        int index = VStages.STAGES.indexOf(stage);
        if (index < 0) {
            return null;
        }
        int half = VStages.STAGES.size() / 2;
        if (index < half) {
            // left arm: definition leg, top to bottom
            return new Cell(index, 0);
        }
        // right arm: verification leg, placed at its definition pair's level
        return new Cell(half - 1 - (index - half), 1);
    }

    /**
     * The 1-based reading number of a canonical stage (its position along
     * the V: 1 = requirements &#x2026; 5 = implementation (the vertex),
     * 6 = test-implementation &#x2026; 10 = test-requirements); 0 for the
     * untracked group and unknown ids. The numbered headers use this so the
     * two-arm layout still reads unambiguously as one sequence.
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

    /** One chevron connector along the V reading order (U-028 FR-006..FR-010). */
    public record Connector(String from, String to, String glyph) {
    }

    /**
     * U-028 FR-006..FR-010: the NINE directional connectors along the
     * canonical reading order 1 -&gt; 10 - DOWN the definition leg (1-&gt;2
     * ... 4-&gt;5), the 5-&gt;6 VERTEX TURN across the bottom level, and UP
     * the verification leg (6-&gt;7 ... 9-&gt;10). Pure decoration derived
     * from {@link VStages#STAGES}, so the chain can never drift from the
     * ladder; it renders on an empty board just the same.
     */
    public static List<Connector> connectors() {
        List<Connector> out = new ArrayList<>(VStages.STAGES.size() - 1);
        for (int i = 0; i + 1 < VStages.STAGES.size(); i++) {
            String from = VStages.STAGES.get(i);
            String to = VStages.STAGES.get(i + 1);
            out.add(new Connector(from, to, connectorGlyph(from)));
        }
        return List.copyOf(out);
    }

    /**
     * The connector LEAVING a stage: {@code ↓} down the definition leg,
     * {@code →} the vertex turn, {@code ↑} up the verification leg;
     * {@code null} at the V tip (nothing follows stage 10), for unknown ids
     * and for the untracked group. Escaped literals on purpose - the source
     * stays pure ASCII.
     */
    public static String connectorGlyph(String stage) {
        if (stage == null) {
            return null;
        }
        int index = VStages.STAGES.indexOf(stage);
        if (index < 0 || index + 1 >= VStages.STAGES.size()) {
            return null;
        }
        int half = VStages.STAGES.size() / 2;
        if (index < half - 1) {
            return "\u2193";
        }
        if (index == half - 1) {
            return "\u2192";
        }
        return "\u2191";
    }
}
