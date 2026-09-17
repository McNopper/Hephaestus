package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.VStages;

/**
 * Pins the boustrophedon V-model layout (U-016, redesigned after the
 * rubberduck review 2026-09-17): two rows, five stage pairs plus the spare
 * untracked cell, each definition stage directly above its verification
 * pair, snake reading order equal to {@link VStages#STAGES}.
 */
public class VStageLayoutTest {

    private static final List<String> DEFINITION = List.of(
            "requirements", "system", "architecture", "design", "implementation");
    private static final List<String> VERIFICATION = List.of(
            "test-implementation", "test-design", "test-architecture", "test-system", "test-requirements");

    @Test
    public void gridHasTwoRowsOfSixCells() {
        List<List<String>> grid = VStageLayout.grid();
        assertEquals(2, grid.size());
        assertEquals(6, grid.get(0).size());
        assertEquals(6, grid.get(1).size());
    }

    @Test
    public void topRowIsTheDefinitionLegLeftToRight() {
        assertEquals(DEFINITION, VStageLayout.grid().get(0).subList(0, 5));
        assertNull(VStageLayout.grid().get(0).get(5));
    }

    @Test
    public void bottomRowMirrorsThePairsUnderTheirDefinitionStages() {
        List<String> bottom = VStageLayout.grid().get(1);
        // left to right: test-requirements … test-implementation, then untracked
        assertEquals("test-requirements", bottom.get(0));
        assertEquals("test-system", bottom.get(1));
        assertEquals("test-architecture", bottom.get(2));
        assertEquals("test-design", bottom.get(3));
        assertEquals("test-implementation", bottom.get(4));
        assertEquals(PipelineSnapshot.UNTRACKED, bottom.get(5));
    }

    @Test
    public void eachVerificationStageSitsDirectlyBelowItsDefinitionPair() {
        List<List<String>> grid = VStageLayout.grid();
        for (int i = 0; i < DEFINITION.size(); i++) {
            String definition = grid.get(0).get(i);
            String verification = grid.get(1).get(i);
            assertEquals("pair " + i, DEFINITION.get(i), definition);
            assertEquals("pair " + i, VERIFICATION.get(VERIFICATION.size() - 1 - i), verification);
        }
    }

    @Test
    public void everyStageAppearsExactlyOnceAndSpacersAreNull() {
        List<String> seen = new ArrayList<>();
        int spacers = 0;
        for (List<String> row : VStageLayout.grid()) {
            for (String cell : row) {
                if (cell == null) {
                    spacers++;
                } else {
                    seen.add(cell);
                }
            }
        }
        assertEquals(VStages.STAGES.size() + 1, seen.size());
        assertEquals(1, spacers);
        for (String stage : VStages.STAGES) {
            assertEquals("stage " + stage + " once", 1, seen.stream().filter(s -> s.equals(stage)).count());
        }
    }

    @Test
    public void cellOfPlacesTheCorners() {
        assertEquals(new VStageLayout.Cell(0, 0), VStageLayout.cellOf("requirements"));
        assertEquals(new VStageLayout.Cell(0, 4), VStageLayout.cellOf("implementation"));
        assertEquals(new VStageLayout.Cell(1, 4), VStageLayout.cellOf("test-implementation"));
        assertEquals(new VStageLayout.Cell(1, 0), VStageLayout.cellOf("test-requirements"));
        assertEquals(new VStageLayout.Cell(1, 5), VStageLayout.cellOf(PipelineSnapshot.UNTRACKED));
        assertNull(VStageLayout.cellOf(null));
        assertNull(VStageLayout.cellOf("no-such-stage"));
    }

    @Test
    public void stageNumbersFollowTheSnakeOrder() {
        int expected = 1;
        for (String stage : VStages.STAGES) {
            assertEquals(stage, expected++, VStageLayout.stageNumber(stage));
        }
        assertEquals(0, VStageLayout.stageNumber(PipelineSnapshot.UNTRACKED));
        assertEquals(0, VStageLayout.stageNumber("no-such-stage"));
    }

    @Test
    public void legsMatchTheCanonicalLadder() {
        assertEquals(VStages.STAGES.subList(0, 5), VStageLayout.definitionLeg());
        assertEquals(VStages.STAGES.subList(5, 10), VStageLayout.verificationLeg());
        assertEquals(VERIFICATION, VStageLayout.verificationLeg());
    }
}
