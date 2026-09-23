package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * Stage-aware dispatch of {@link TaskFleet} over a real {@link TaskStore}
 * (temporary folder) and the in-memory fakes: a ticket created with a
 * V-pipeline stage launches under the agent its stage's ROLE maps to
 * ({@link RoleAgents}), carries the stage prompt, and is pre-claimed
 * (in-progress, assignee "fleet") before the session exists. Mirrors the
 * {@link TaskFleetTest} fixtures; tickets are created with the store's
 * create-with-stage overload.
 */
public class StagedDispatchTest extends FleetTestHarness {

    /** A staged ticket in the product backlog, role derived from the stage. */
    private String stagedTicket(String stage) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", VStages.roleOf(stage), "high", 3,
                List.of("ac one", "ac two"), List.of(), null, "V"), stage);
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    @Test
    public void designStageDispatchesExecutorWithStagePromptAndPreClaim() {
        String id = stagedTicket("design");
        sessionCompletes();
        List<Task> stateAtSubmit = new ArrayList<>();
        client.onSessionCreated = () -> stateAtSubmit.add(store.get(PROJECT, id));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals(1, client.sentRequests.size());
        assertEquals("developer (design) dispatches to the executor agent",
                "executor", client.sentRequests.get(0).agent());
        String prompt = client.sentRequests.get(0).text();
        assertTrue(prompt.contains("Stage: design"));
        assertTrue(prompt.contains("work in the software-design skill"));
        assertTrue(prompt.contains("PIPELINE PROTOCOL:"));
        // pre-claim happened in the main store BEFORE the session existed
        assertEquals(1, stateAtSubmit.size());
        assertEquals("in-progress", stateAtSubmit.get(0).status);
        assertEquals("fleet", stateAtSubmit.get(0).assignee);
        // final bookkeeping: in-review, still staged, assignee kept
        Task after = store.get(PROJECT, id);
        assertEquals("in-review", after.status);
        assertEquals("fleet", after.assignee);
        assertEquals("launch never moves the stage", "design", after.stage);
        assertFalse("no blocker on the happy path: " + after.blocker, after.blocked);
    }

    @Test
    public void testImplementationStageDispatchesExecutorAndNamesItsSkill() {
        String id = stagedTicket("test-implementation");
        sessionCompletes();
        List<Task> stateAtSubmit = new ArrayList<>();
        client.onSessionCreated = () -> stateAtSubmit.add(store.get(PROJECT, id));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("tester (test-implementation) also dispatches to executor",
                "executor", client.sentRequests.get(0).agent());
        String prompt = client.sentRequests.get(0).text();
        assertTrue(prompt.contains("Stage: test-implementation"));
        assertTrue(prompt.contains("work in the test-software-implementation skill"));
        assertEquals("pre-claim before submit", "in-progress", stateAtSubmit.get(0).status);
        assertEquals("fleet", stateAtSubmit.get(0).assignee);
        assertEquals("in-review", store.get(PROJECT, id).status);
    }

    @Test
    public void architectureStageDispatchesManifestAuthor() {
        String id = stagedTicket("architecture");
        sessionCompletes();
        List<Task> stateAtSubmit = new ArrayList<>();
        client.onSessionCreated = () -> stateAtSubmit.add(store.get(PROJECT, id));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("architect (architecture) dispatches to manifest-author",
                "manifest-author", client.sentRequests.get(0).agent());
        String prompt = client.sentRequests.get(0).text();
        assertTrue(prompt.contains("Stage: architecture"));
        assertTrue(prompt.contains("work in the software-architecture skill"));
        assertEquals("pre-claim before submit", "in-progress", stateAtSubmit.get(0).status);
        assertEquals("fleet", stateAtSubmit.get(0).assignee);
        assertEquals("in-review", store.get(PROJECT, id).status);
    }

    @Test
    public void everyStageDispatchesThroughItsRoleToAnAgent() {
        RoleAgents agents = new RoleAgents();
        sessionCompletes();
        int i = 0;
        for (String stage : VStages.STAGES) {
            String id = stagedTicket(stage);

            FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

            assertEquals(stage + " merges", FleetJob.State.MERGED, job.state());
            assertEquals("one launch per stage so far", i + 1, client.sentRequests.size());
            ChatRequest sent = client.sentRequests.get(i);
            assertEquals(stage + " (role " + VStages.roleOf(stage) + ") dispatches by role",
                    agents.agentFor(VStages.roleOf(stage)), sent.agent());
            assertTrue(stage + " prompt names the stage", sent.text().contains("Stage: " + stage));
            assertTrue(stage + " prompt names its skill",
                    sent.text().contains("work in the " + VStages.skillOf(stage) + " skill"));
            i++;
        }
        assertEquals("all ten stages launched", VStages.STAGES.size(), client.sentRequests.size());
    }
}
