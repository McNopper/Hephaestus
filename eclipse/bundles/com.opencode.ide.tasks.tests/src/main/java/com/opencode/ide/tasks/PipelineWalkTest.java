package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The full-lifecycle E2E of the V-model pipeline: one ticket staged at
 * requirements walks DOWN the whole V to the tip (every advance gated through
 * in-review, landing in the next stage's product-backlog with the next
 * stage's role and a cleared assignee) and back UP via the sendBack chain
 * (every hop blocked with the "sent back from ..." blocker, unblocked per
 * hop). The stage round-trips through the file at checkpoints along the way,
 * read via a fresh store instance on the same directory.
 */
public class PipelineWalkTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;
    private TaskStore store;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    /** A fresh store on the same directory sees what the walk wrote to disk. */
    private Task reopened(String id) {
        return new TaskStore(root).get("p", id);
    }

    private String walkToTip(String id) {
        for (String next : VStages.STAGES.subList(1, VStages.STAGES.size())) {
            store.update("p", id, Map.of("status", "in-review"));
            store.advance("p", id, "walker");
        }
        return store.get("p", id).stage;
    }

    @Test
    public void walkingDownTheVAdvancesThroughEveryStageClearingAssignees() {
        Task t = store.create("p", TaskStore.CreateSpec.of("walk me down the V"), "requirements");
        String id = t.id;
        assertEquals("requirements", t.stage);
        assertEquals("create keeps the spec's role; the stage owns the role only from the first advance on",
                "developer", t.role);
        assertEquals("checkpoint 1: the created stage is on disk", "requirements", reopened(id).stage);

        boolean checkedMidpoint = false;
        for (String next : VStages.STAGES.subList(1, VStages.STAGES.size())) {
            store.update("p", id, Map.of("status", "in-review", "assignee", "worker-" + next));
            Task advanced = store.advance("p", id, "walker");
            assertEquals(next, advanced.stage);
            assertEquals(VStages.roleOf(next), advanced.role);
            assertEquals("the next stage's backlog is fed by the previous stage",
                    "product-backlog", advanced.status);
            assertNull("each hand-forward clears the assignee", advanced.assignee);
            assertFalse(advanced.isBlocked());
            Task.HistoryEvent last = advanced.history.get(advanced.history.size() - 1);
            assertEquals("advanced to " + next, last.action());
            assertEquals("walker", last.by());
            assertEquals(next, store.get("p", id).stage);
            if (!checkedMidpoint && "implementation".equals(next)) {
                checkedMidpoint = true;
                assertEquals("checkpoint 2: the stage survives a reopen mid-walk",
                        "implementation", reopened(id).stage);
            }
        }
        assertEquals("checkpoint 3: the V tip round-trips through the file",
                "test-requirements", reopened(id).stage);
    }

    @Test
    public void advanceAtTheVTipIsRejectedAndChangesNothing() {
        String id = store.create("p", TaskStore.CreateSpec.of("tip walker"), "requirements").id;
        assertEquals("test-requirements", walkToTip(id));
        store.update("p", id, Map.of("status", "done"));
        try {
            store.advance("p", id, null);
            fail("expected Invalid at the V tip");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("test-requirements"));
            assertTrue(expected.getMessage().contains("no next stage"));
        }
        Task after = store.get("p", id);
        assertEquals("a rejected advance changes nothing", "test-requirements", after.stage);
        assertEquals("done", after.status);
        assertEquals("test-requirements", reopened(id).stage);
    }

    @Test
    public void sendBackChainWalksBackToRequirementsBlockedThenClearedPerHop() {
        String id = store.create("p", TaskStore.CreateSpec.of("walk me back up"), "requirements").id;
        walkToTip(id);

        boolean checkedMidpoint = false;
        for (int i = VStages.STAGES.size() - 1; i > 0; i--) {
            String from = VStages.STAGES.get(i);
            String prev = VStages.STAGES.get(i - 1);
            store.update("p", id, Map.of("assignee", "worker-" + from));
            Task back = store.sendBack("p", id, "hop " + from, "reviewer");
            assertEquals(prev, back.stage);
            assertEquals(VStages.roleOf(prev), back.role);
            assertEquals("blocked", back.status);
            assertEquals("product-backlog", back.resumeTo);
            assertNull("the hand-back clears the assignee", back.assignee);
            assertTrue("every hop enters the blocked state", back.isBlocked());
            assertEquals("sent back from " + from + ": hop " + from, back.blocker);
            Task.HistoryEvent last = back.history.get(back.history.size() - 1);
            assertEquals("sent back to " + prev + ": hop " + from, last.action());
            assertEquals("reviewer", last.by());

            Task cleared = store.clearBlocked("p", id, prev + "-owner");
            assertFalse("the flag clears without moving the ticket", cleared.isBlocked());
            assertNull(cleared.blocker);
            assertEquals(prev, cleared.stage);
            assertEquals(VStages.roleOf(prev), cleared.role);

            if (!checkedMidpoint && "implementation".equals(prev)) {
                checkedMidpoint = true;
                assertEquals("mid-chain checkpoint: the sent-back stage is on disk",
                        "implementation", reopened(id).stage);
            }
        }
        Task fin = store.get("p", id);
        assertEquals("requirements", fin.stage);
        assertEquals("pm", fin.role);
        assertEquals("product-backlog", fin.status);
        assertFalse("the chain ends unblocked", fin.isBlocked());
        assertNull(fin.blocker);
        assertEquals("requirements", reopened(id).stage);
    }

    @Test
    public void sendBackIsNotStatusGatedItWorksFromEveryStatus() {
        for (String status : Task.VALID_STATUSES) {
            String id = store.create("p", TaskStore.CreateSpec.of("from " + status), "system").id;
            store.update("p", id, Map.of("status", status));
            Task back = store.sendBack("p", id, "rework", null);
            assertEquals("sendBack is allowed from any status (the implementation has no status gate)",
                    "requirements", back.stage);
            assertEquals("blocked", back.status);
            assertEquals("product-backlog", back.resumeTo);
            assertTrue(back.isBlocked());
        }
    }
}
