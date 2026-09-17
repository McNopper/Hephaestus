package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.VStages;

/**
 * U-016: the V-model arrangement of the ten canonical stages — the
 * definition leg descends the left side to the bottom-centre tip, the
 * verification leg ascends to the top right, and the trailing untracked
 * group sits beside the tip. These tests pin the exact grid the Board view
 * renders (a flat single row of ten is NOT the arrangement) and both leg
 * orders.
 */
public class VStageLayoutTest {

    @Test
    public void definitionLegDescendsRequirementsToImplementation() {
        assertEquals(List.of("requirements", "system", "architecture", "design", "implementation"),
                VStageLayout.definitionLeg());
    }

    @Test
    public void verificationLegAscendsTestImplementationToTestRequirements() {
        assertEquals(List.of("test-implementation", "test-design", "test-architecture",
                "test-system", "test-requirements"), VStageLayout.verificationLeg());
    }

    @Test
    public void vOrderIsTheCanonicalStageOrder() {
        List<String> v = new ArrayList<>(VStageLayout.definitionLeg());
        v.addAll(VStageLayout.verificationLeg());
        assertEquals("V order = definition leg + verification leg = the canonical ladder",
                VStages.STAGES, v);
    }

    @Test
    public void definitionLegStepsOneColumnRightPerRowDown() {
        List<String> leg = VStageLayout.definitionLeg();
        for (int i = 0; i < leg.size(); i++) {
            VStageLayout.Cell cell = VStageLayout.cellOf(leg.get(i));
            assertEquals("row of " + leg.get(i), i, cell.row());
            assertEquals("column of " + leg.get(i), i, cell.column());
        }
    }

    @Test
    public void verificationLegStepsOneColumnLeftPerRowUp() {
        List<String> leg = VStageLayout.verificationLeg();
        for (int level = 0; level < leg.size(); level++) {
            VStageLayout.Cell cell = VStageLayout.cellOf(leg.get(level));
            assertEquals("row of " + leg.get(level), leg.size() - 1 - level, cell.row());
            assertEquals("column of " + leg.get(level), leg.size() + level, cell.column());
        }
    }

    @Test
    public void gridIsARectangleCarryingAllStagesPlusUntracked() {
        List<List<String>> grid = VStageLayout.grid();
        assertEquals(VStageLayout.GRID_ROWS, grid.size());
        int stages = 0;
        int untracked = 0;
        for (List<String> row : grid) {
            assertEquals("every grid row fills the column count (SWT GridLayout needs that)",
                    VStageLayout.GRID_COLUMNS, row.size());
            for (String cell : row) {
                if (cell == null) {
                    continue;
                }
                if (PipelineSnapshot.UNTRACKED.equals(cell)) {
                    untracked++;
                } else {
                    stages++;
                }
            }
        }
        assertEquals(VStages.STAGES.size(), stages);
        assertEquals(1, untracked);
    }

    @Test
    public void gridCornersFormTheV() {
        List<List<String>> grid = VStageLayout.grid();
        // top row: the two ends of the V
        assertEquals("requirements", grid.get(0).get(0));
        assertEquals("test-requirements", grid.get(0).get(VStageLayout.GRID_COLUMNS - 1));
        // bottom row: the V tip and the trailing untracked group beside it
        int bottom = VStageLayout.GRID_ROWS - 1;
        assertEquals("implementation", grid.get(bottom).get(VStageLayout.GRID_ROWS - 1));
        assertEquals("test-implementation", grid.get(bottom).get(VStageLayout.GRID_ROWS));
        assertEquals(PipelineSnapshot.UNTRACKED, grid.get(bottom).get(VStageLayout.GRID_ROWS + 1));
    }

    @Test
    public void legsAreDisjointAndCoverTheLadder() {
        List<String> both = new ArrayList<>(VStageLayout.definitionLeg());
        both.addAll(VStageLayout.verificationLeg());
        assertEquals(VStages.STAGES.size(), VStageLayout.definitionLeg().size()
                + VStageLayout.verificationLeg().size());
        assertEquals(VStages.STAGES, both);
    }

    @Test
    public void unknownStageHasNoCell() {
        assertNull(VStageLayout.cellOf("not-a-stage"));
        assertNull(VStageLayout.cellOf(null));
    }
}
