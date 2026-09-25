package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * U-029 stage pass-through + U-031 horizontal routing primitives (the paired
 * unit tests of the pump semantics AGENTS.md promises): {@code passStage}
 * advances exactly like {@code advance} with a recorded rationale and no
 * dispatch, {@code reportHorizontal} moves a blocked ticket to its V-level
 * pair stage, and {@code VStages.pairOf} is symmetric across the V.
 */
public class StageRoutingTest extends StoreTestHarness {

    @Test
    public void passStageAdvancesAndRecordsStageNPassed() {
        String id = staged("design", "product-backlog");

        Task passed = store.passStage("p", id, "no component impact: local change", "agent");

        assertEquals("implementation", passed.stage);
        assertEquals(VStages.roleOf("implementation"), passed.role);
        assertEquals("product-backlog", passed.status);
        assertNull("the assignee is cleared like an advance", passed.assignee);
        String history = passed.toJson().toString();
        assertTrue("the rationale is recorded with the stage number: " + history,
                history.contains("stage 4 passed: no component impact: local change"));
    }

    @Test
    public void passStageIsStateEquivalentToAdvance() {
        Task viaAdvance = store.advance("p", staged("design", "in-review"), "agent");
        Task viaPass = store.passStage("p", staged("design", "product-backlog"), "no impact", "agent");

        assertEquals(viaAdvance.stage, viaPass.stage);
        assertEquals(viaAdvance.role, viaPass.role);
        assertEquals(viaAdvance.status, viaPass.status);
        assertEquals(viaAdvance.assignee, viaPass.assignee);
    }

    @Test
    public void passStageRejectsABlankReason() {
        String id = staged("design", "product-backlog");
        try {
            store.passStage("p", id, "   ", "agent");
            fail("a blank rationale must be rejected");
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()), e.getMessage().contains("reason"));
        }
    }

    @Test
    public void theVTipCanNeverPass() {
        String id = staged(VStages.last(), "product-backlog");
        try {
            store.passStage("p", id, "no impact", "agent");
            fail("the V tip must never pass");
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()), e.getMessage().contains("V tip"));
        }
    }

    @Test
    public void pairOfIsSymmetricAcrossTheV() {
        assertEquals("test-design", VStages.pairOf("design"));
        assertEquals("design", VStages.pairOf("test-design"));
        assertEquals("test-requirements", VStages.pairOf("requirements"));
        assertNull("an unknown stage has no pair", VStages.pairOf("nope"));
    }

    @Test
    public void reportHorizontalMovesToThePairStage() {
        String id = staged("test-design", "product-backlog");

        Task reported = store.reportHorizontal("p", id, "the design contract is wrong", "agent");

        assertEquals("a test-design failure reports to design", "design", reported.stage);
        assertEquals(VStages.roleOf("design"), reported.role);
        assertTrue("the report is unmissable", reported.blocked);
        assertTrue("the blocker names the source stage: " + reported.blocker,
                reported.blocker.startsWith("reported from test-design: "));
        String history = reported.toJson().toString();
        assertTrue("the move is recorded: " + history,
                history.contains("reported to design: the design contract is wrong"));
    }

    @Test
    public void reportHorizontalRejectsABlankReason() {
        String id = staged("test-design", "product-backlog");
        try {
            store.reportHorizontal("p", id, "", "agent");
            fail("a blank report reason must be rejected");
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()), e.getMessage().contains("reason"));
        }
    }
}
