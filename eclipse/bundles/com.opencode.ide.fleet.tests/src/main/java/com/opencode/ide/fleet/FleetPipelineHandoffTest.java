package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * Regression tests for the fleet-vs-pipeline bookkeeping interplay: the
 * post-merge status lift must only touch the fleet's OWN in-progress marking.
 * An agent that already task_advance'd (next stage's product-backlog), set
 * done, or task_send_back'd (blocked) mid-run must NOT be clobbered to
 * in-review — that would fake completion in the next stage's column and
 * pre-arm the advance quality gate.
 */
public class FleetPipelineHandoffTest extends FleetTestHarness {

    private String stagedTicket(String stage) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                "Stage work", "Do the thing.", "task", VStages.roleOf(stage), "high", 3,
                List.of("ac"), List.of(), null, "V");
        var t = store.create(PROJECT, spec, stage);
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    @Test
    public void agentAdvanceDuringRunIsNotClobberedToInReview() {
        String id = stagedTicket("requirements");
        sessionCompletes();
        // the agent advances DURING its run (in its worktree store, which the
        // fake merge lands in the main store) — simulate via the merge hook
        worktrees.onMergeBack = () -> {
            store.update(PROJECT, id, Map.of("status", "in-review"));
            store.advance(PROJECT, id, "executor");
        };

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("the agent's advance must survive the fleet bookkeeping",
                "system", after.stage);
        assertEquals("product-backlog", after.status);
        assertEquals("architect", after.role);
    }

    @Test
    public void agentDoneDuringRunStaysDone() {
        String id = stagedTicket("implementation");
        sessionCompletes();
        worktrees.onMergeBack = () -> store.update(PROJECT, id, Map.of("status", "done"));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("done", store.get(PROJECT, id).status);
    }

    @Test
    public void unmarkedRunIsLiftedToInReviewAsBefore() {
        String id = stagedTicket("design");
        sessionCompletes();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("the fleet's own in-progress marking still lifts to in-review",
                "in-review", store.get(PROJECT, id).status);
    }
}
