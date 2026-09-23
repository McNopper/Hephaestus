package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The advance quality gate as a matrix: only in-review/done pass (every other
 * status is rejected naming the status), the V tip and unstaged legacy
 * tickets are rejected, the assignee is cleared while the blocked flag stays
 * as-is (V-001 semantics: "blocked stays as-is" — the flag is orthogonal to
 * the pipeline), and the surrounding Scrum operations (claim, planSprint)
 * keep their own semantics without touching the stage.
 */
public class AdvanceGateTest extends StoreTestHarness {

    @Test
    public void advanceIsRejectedFromUnfinishedStatusesNamingTheStatus() {
        for (String bad : List.of("product-backlog", "sprint-backlog", "in-progress")) {
            String id = staged("design", bad);
            try {
                store.advance("p", id, null);
                fail("expected Invalid from status '" + bad + "'");
            } catch (TaskStore.Invalid expected) {
                assertTrue("message names the offending status: " + expected.getMessage(),
                        expected.getMessage().contains("'" + bad + "'"));
                assertTrue(expected.getMessage().contains("in-review"));
                assertTrue(expected.getMessage().contains("done"));
            }
            Task unchanged = store.get("p", id);
            assertEquals("a rejected advance changes nothing (stage)", "design", unchanged.stage);
            assertEquals(bad, unchanged.status);
            assertEquals("worker", unchanged.assignee);
        }
    }

    @Test
    public void advanceIsAllowedFromInReviewAndDone() {
        for (String finished : List.of("in-review", "done")) {
            String id = staged("requirements", finished);
            Task advanced = store.advance("p", id, "pm");
            assertEquals("system", advanced.stage);
            assertEquals("architect", advanced.role);
            assertEquals("product-backlog", advanced.status);
        }
    }

    @Test
    public void advanceWithoutStageIsRejectedAlsoAfterExplicitlyClearingIt() {
        Task t = store.create("p", TaskStore.CreateSpec.of("legacy"));
        store.update("p", t.id, Map.of("status", "in-review"));
        try {
            store.advance("p", t.id, null);
            fail("expected Invalid for an unstaged ticket");
        } catch (TaskStore.Invalid expected) {
            assertEquals("ticket has no stage; set one first", expected.getMessage());
        }
        store.update("p", t.id, Map.of("stage", "system"));
        assertEquals("system", store.get("p", t.id).stage);
        store.update("p", t.id, Collections.singletonMap("stage", null));
        try {
            store.advance("p", t.id, null);
            fail("expected Invalid after nulling the stage");
        } catch (TaskStore.Invalid expected) {
            assertEquals("ticket has no stage; set one first", expected.getMessage());
        }
    }

    @Test
    public void tipAdvanceIsRejectedAndLeavesTheTicketAlone() {
        String id = staged("test-requirements", "done");
        try {
            store.advance("p", id, null);
            fail("expected Invalid at the V tip");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("test-requirements"));
            assertTrue(expected.getMessage().contains("no next stage"));
        }
        Task after = store.get("p", id);
        assertEquals("test-requirements", after.stage);
        assertEquals("done", after.status);
        assertEquals("worker", after.assignee);
    }

    @Test
    public void advanceClearsTheAssigneeButKeepsTheBlockedFlagAsIs() {
        String id = staged("system", "in-review");
        store.sendBack("p", id, "spec contradicts itself", "architect");
        Task sentBack = store.get("p", id);
        assertEquals("requirements", sentBack.stage);
        assertTrue(sentBack.blocked);
        store.update("p", id, Map.of("status", "in-review", "assignee", "pm-agent"));

        Task advanced = store.advance("p", id, "pm");
        assertEquals("system", advanced.stage);
        assertEquals("architect", advanced.role);
        assertNull("the assignee is cleared", advanced.assignee);
        // V-001 semantics: "blocked stays as-is" on advance — the flag is
        // orthogonal to the pipeline and only clearBlocked lowers it.
        assertTrue("the blocked flag intentionally persists across an advance", advanced.blocked);
        assertEquals("sent back from system: spec contradicts itself", advanced.blocker);
        assertTrue("and it persisted to the file", store.get("p", id).blocked);
    }

    @Test
    public void claimOnStagedTicketsWorksAfterSprintPlanningAndLeavesTheStageAlone() {
        String id = store.create("p", TaskStore.CreateSpec.of("staged work"), "design").id;
        assertNull("a stage's product-backlog is not claimable (claim needs sprint-backlog)",
                store.claim("p", "developer", null, "dev-1"));
        store.planSprint("p", null, List.of(id), "ship it");
        Task claimed = store.claim("p", "developer", null, "dev-1");
        assertNotNull(claimed);
        assertEquals(id, claimed.id);
        assertEquals("in-progress", claimed.status);
        assertEquals("dev-1", claimed.assignee);
        assertEquals("claim semantics are untouched by the stage", "design", claimed.stage);
        assertEquals("developer", claimed.role);
    }

    @Test
    public void aBlockedStagedTicketIsNotClaimableUntilTheFlagClears() {
        String id = store.create("p", TaskStore.CreateSpec.of("blocked work"), "design").id;
        store.planSprint("p", null, List.of(id), "ship it");
        store.setBlocked("p", id, "waiting on an upstream decision", null);
        assertNull("blocked tickets are skipped by claim", store.claim("p", "developer", null, "dev-1"));
        store.clearBlocked("p", id, null);
        Task claimed = store.claim("p", "developer", null, "dev-1");
        assertNotNull(claimed);
        assertEquals("design", claimed.stage);
    }

    @Test
    public void planSprintForceSetsSprintBacklogWithoutTouchingTheStage() {
        String id = staged("architecture", "in-review");
        Task.Sprint sprint = store.planSprint("p", "S-07", List.of(id), "the sprint");
        assertEquals("S-07", sprint.id());
        Task t = store.get("p", id);
        assertEquals("plan force-sets sprint-backlog even from in-review", "sprint-backlog", t.status);
        assertEquals("S-07", t.sprint);
        assertEquals("the stage is untouched by sprint planning", "architecture", t.stage);
        assertEquals("developer", t.role);
        assertEquals("worker", t.assignee);
    }
}
