package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.VStages;

/**
 * Pins the two-arm V-model layout (U-016, orientation reworked 2026-09-18
 * on user direction): the LEFT column carries the definition leg top to
 * bottom, the RIGHT column carries the verification leg level-paired with
 * it (test-requirements at the top, test-implementation at the vertex),
 * the untracked group sits beside the vertex, and the snake reading order
 * equals {@link VStages#STAGES}.
 */
public class VStageLayoutTest {

    private static final List<String> DEFINITION = List.of(
            "requirements", "system", "architecture", "design", "implementation");
    private static final List<String> VERIFICATION = List.of(
            "test-implementation", "test-design", "test-architecture", "test-system", "test-requirements");

    @Test
    public void gridHasFiveRowsOfTwoCells() {
        List<List<String>> grid = VStageLayout.grid();
        assertEquals(5, grid.size());
        for (List<String> row : grid) {
            assertEquals(2, row.size());
        }
    }

    @Test
    public void leftColumnIsTheDefinitionLegTopToBottom() {
        for (int i = 0; i < 5; i++) {
            assertEquals(DEFINITION.get(i), VStageLayout.grid().get(i).get(0));
        }
    }

    @Test
    public void rightColumnIsTheVerificationLegPairedByLevel() {
        List<List<String>> grid = VStageLayout.grid();
        // top level pairs requirements with test-requirements; the vertex
        // level pairs implementation with test-implementation
        assertEquals("test-requirements", grid.get(0).get(1));
        assertEquals("test-system", grid.get(1).get(1));
        assertEquals("test-architecture", grid.get(2).get(1));
        assertEquals("test-design", grid.get(3).get(1));
        assertEquals("test-implementation", grid.get(4).get(1));
    }

    @Test
    public void eachLevelPairsADefinitionStageWithItsVerificationStage() {
        List<List<String>> grid = VStageLayout.grid();
        for (int i = 0; i < DEFINITION.size(); i++) {
            assertEquals("level " + i, DEFINITION.get(i), grid.get(i).get(0));
            assertEquals("level " + i, VERIFICATION.get(VERIFICATION.size() - 1 - i), grid.get(i).get(1));
        }
    }

    @Test
    public void untrackedIsNotPartOfTheVGeometry() {
        assertNull(VStageLayout.cellOf(PipelineSnapshot.UNTRACKED));
        boolean anyUntracked = VStageLayout.grid().stream()
                .flatMap(List::stream)
                .anyMatch(PipelineSnapshot.UNTRACKED::equals);
        assertFalse("untracked renders in its own row, not the V grid", anyUntracked);
    }

    @Test
    public void cellOfPlacesTheCorners() {
        assertEquals(new VStageLayout.Cell(0, 0), VStageLayout.cellOf("requirements"));
        assertEquals(new VStageLayout.Cell(4, 0), VStageLayout.cellOf("implementation"));
        assertEquals(new VStageLayout.Cell(4, 1), VStageLayout.cellOf("test-implementation"));
        assertEquals(new VStageLayout.Cell(0, 1), VStageLayout.cellOf("test-requirements"));
        assertNull(VStageLayout.cellOf(null));
        assertNull(VStageLayout.cellOf("no-such-stage"));
    }

    @Test
    public void stageNumbersFollowTheVOrder() {
        int expected = 1;
        for (String stage : VStages.STAGES) {
            assertEquals(stage, expected++, VStageLayout.stageNumber(stage));
        }
        assertEquals(0, VStageLayout.stageNumber(PipelineSnapshot.UNTRACKED));
        assertEquals(0, VStageLayout.stageNumber("no-such-stage"));
    }

    @Test
    public void everyStageAppearsExactlyOnce() {
        List<String> seen = new ArrayList<>();
        for (List<String> row : VStageLayout.grid()) {
            for (String cell : row) {
                if (cell != null) {
                    seen.add(cell);
                }
            }
        }
        // the V grid carries exactly the ten canonical stages; untracked
        // is not part of the geometry (own row in the view)
        assertEquals(VStages.STAGES.size(), seen.size());
        for (String stage : VStages.STAGES) {
            assertEquals("stage " + stage + " once", 1, seen.stream().filter(s -> s.equals(stage)).count());
        }
    }

    @Test
    public void legsMatchTheCanonicalLadder() {
        assertEquals(VStages.STAGES.subList(0, 5), VStageLayout.definitionLeg());
        assertEquals(VStages.STAGES.subList(5, 10), VStageLayout.verificationLeg());
        assertEquals(VERIFICATION, VStageLayout.verificationLeg());
    }
}
