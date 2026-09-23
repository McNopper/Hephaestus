package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;

/**
 * V-model pipeline semantics over the real store: advance is the quality-gated
 * hand-forward (only from in-review/done; lands in the next stage's product
 * backlog with the next stage's role), sendBack is the unmissable feedback
 * loop (blocked + "sent back from ...: reason"), stages persist through the
 * file, and legacy unstaged tickets are rejected, never guessed.
 */
public class VPipelineTest extends StoreTestHarness {

    @Test
    public void advanceMovesStageRoleStatusAndClearsAssignee() {
        String id = staged("requirements", "in-review");
        Task advanced = store.advance("p", id, "pm");
        assertEquals("system", advanced.stage);
        assertEquals("architect", advanced.role);
        assertEquals("product-backlog", advanced.status);
        assertNull(advanced.assignee);
        assertFalse(advanced.blocked);
        Task.HistoryEvent last = advanced.history.get(advanced.history.size() - 1);
        assertEquals("advanced to system", last.action());
        assertEquals("pm", last.by());
        // and it persisted
        Task reread = store.get("p", id);
        assertEquals("system", reread.stage);
        assertEquals("architect", reread.role);
    }

    @Test
    public void advanceFromDoneIsAlsoAllowed() {
        String id = staged("design", "done");
        Task advanced = store.advance("p", id, null);
        assertEquals("implementation", advanced.stage);
        assertEquals("developer", advanced.role);
    }

    @Test
    public void advanceKeepsTheBlockedFlagAsIs() {
        String id = staged("requirements", "in-review");
        store.setBlocked("p", id, "waiting on legal", null);
        Task advanced = store.advance("p", id, "pm");
        assertTrue("the blocked flag survives an advance untouched", advanced.blocked);
        assertEquals("waiting on legal", advanced.blocker);
    }

    @Test
    public void advanceFromInProgressIsRejectedNamingTheStatus() {
        String id = staged("requirements", "in-progress");
        try {
            store.advance("p", id, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("in-progress"));
            assertTrue(expected.getMessage().contains("in-review"));
        }
        assertEquals("a rejected advance changes nothing", "requirements", store.get("p", id).stage);
        assertEquals("in-progress", store.get("p", id).status);
    }

    @Test
    public void advanceWithoutStageIsRejectedForLegacyTickets() {
        Task legacy = store.create("p", TaskStore.CreateSpec.of("old"));
        store.update("p", legacy.id, Map.of("status", "in-review"));
        try {
            store.advance("p", legacy.id, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertEquals("ticket has no stage; set one first", expected.getMessage());
        }
    }

    @Test
    public void advanceAtVTipIsRejected() {
        String id = staged("test-requirements", "done");
        try {
            store.advance("p", id, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("test-requirements"));
        }
    }

    @Test
    public void sendBackMovesToPreviousStageBlockedWithReason() {
        String id = staged("design", "in-review");
        Task back = store.sendBack("p", id, "interface unclear", "architect");
        assertEquals("architecture", back.stage);
        assertEquals("architect", back.role);
        assertEquals("product-backlog", back.status);
        assertNull(back.assignee);
        assertTrue(back.blocked);
        assertEquals("sent back from design: interface unclear", back.blocker);
        Task.HistoryEvent last = back.history.get(back.history.size() - 1);
        assertEquals("sent back to architecture: interface unclear", last.action());
        assertEquals("architect", last.by());
    }

    @Test
    public void sendBackCrossesFromVerificationLegToDefinitionLeg() {
        String id = staged("test-implementation", "in-review");
        Task back = store.sendBack("p", id, "unit tests fail", "tester");
        assertEquals("implementation", back.stage);
        assertEquals("developer", back.role);
    }

    @Test
    public void sendBackAtRequirementsIsRejected() {
        String id = staged("requirements", "in-review");
        try {
            store.sendBack("p", id, "no earlier stage exists", null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("requirements"));
        }
    }

    @Test
    public void sendBackRequiresANonBlankReason() {
        String id = staged("system", "in-review");
        for (String bad : new String[] {null, "", "   "}) {
            try {
                store.sendBack("p", id, bad, null);
                fail("expected Invalid for reason '" + bad + "'");
            } catch (TaskStore.Invalid expected) {
                assertTrue(expected.getMessage().contains("reason"));
            }
        }
        assertEquals("a rejected send back changes nothing", "system", store.get("p", id).stage);
    }

    @Test
    public void sendBackWithoutStageIsRejectedForLegacyTickets() {
        Task legacy = store.create("p", TaskStore.CreateSpec.of("old"));
        try {
            store.sendBack("p", legacy.id, "why", null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertEquals("ticket has no stage; set one first", expected.getMessage());
        }
    }

    @Test
    public void clearBlockedAfterSendBackResolvesTheHandBack() {
        String id = staged("system", "in-review");
        store.sendBack("p", id, "conflicting goals", "architect");
        Task cleared = store.clearBlocked("p", id, "pm");
        assertFalse(cleared.blocked);
        assertNull(cleared.blocker);
        assertEquals("the stage stays where the ticket was sent back to", "requirements", cleared.stage);
    }

    @Test
    public void stageRoundTripsThroughTheFile() {
        Task t = store.create("p", TaskStore.CreateSpec.of("file stage"), "requirements");
        store.update("p", t.id, Map.of("stage", "design"));
        assertEquals("design", new TaskStore(store.root()).get("p", t.id).stage);
        // an advance survives a reopen as well
        store.update("p", t.id, Map.of("status", "in-review"));
        store.advance("p", t.id, null);
        assertEquals("implementation", new TaskStore(store.root()).get("p", t.id).stage);
    }

    @Test
    public void updateStageValidatesAndNullClears() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        try {
            store.update("p", t.id, Map.of("stage", "waterfall"));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("stage"));
        }
        assertNull("a rejected update wrote nothing", store.get("p", t.id).stage);
        store.update("p", t.id, Map.of("stage", "architecture"));
        assertEquals("architecture", store.get("p", t.id).stage);
        store.update("p", t.id, Collections.singletonMap("stage", null));
        assertNull("explicit null clears the stage", store.get("p", t.id).stage);
    }

    @Test
    public void createWithStageSetsAndValidates() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"), "test-design");
        assertEquals("test-design", store.get("p", t.id).stage);
        try {
            store.create("p", TaskStore.CreateSpec.of("b"), "unicorn");
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("stage"));
        }
    }

    @Test
    public void fullLadderWalkAdvancesRequirementsToTheVTip() {
        String id = staged("requirements", "in-review");
        for (String stage : VStages.STAGES.subList(1, VStages.STAGES.size())) {
            store.update("p", id, Map.of("status", "in-review"));
            Task t = store.advance("p", id, "walker");
            assertEquals(stage, t.stage);
            assertEquals(VStages.roleOf(stage), t.role);
            assertEquals("product-backlog", t.status);
        }
        store.update("p", id, Map.of("status", "done"));
        try {
            store.advance("p", id, "walker");
            fail("expected Invalid at the V tip");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("test-requirements"));
        }
    }
}
