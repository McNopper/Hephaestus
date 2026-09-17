package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * U-021 autonomous acceptance, engine-level: a launch (with
 * {@link TaskFleet#withAutonomousAcceptance()}) that merges and settles a
 * ticket to in-review dispatches a second, read-only REVIEW session under
 * the reviewer agent; the FAKE reviewer path is the fake client's reply to
 * that session ({@code VERDICT: ...}), and the tests assert the engine
 * applies it through the store: PASS → done + advance into the next
 * stage's wave backlog, FAIL → send-back blocked with the reviewer's
 * reasons (the human-escalation signal), UNCLEAR → stays in-review with a
 * comment. The review run's actuals land on the ticket like any run, so
 * the wave budget absorbs the reviewer's cost.
 */
public class AutonomousAcceptanceTest {

    private static final Path REPO = Path.of("repo");
    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private FakeWorktreeManager worktrees;
    private TaskFleet fleet;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
        fleet = new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store)
                .withAutonomousAcceptance();
    }

    private String stagedTicket(String stage) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                "Stage work", "Do the thing.", "task", VStages.roleOf(stage), "high", 3,
                List.of("ac one", "ac two"), List.of(), null, "V");
        var t = store.create(PROJECT, spec, stage);
        return t.id;
    }

    /**
     * The worker session completes plainly ("done"); the merge hook then
     * swaps the reply so the NEXT send — the review session — answers with
     * the given verdict text (the fake reviewer path).
     */
    private void workerCompletesAndReviewReplies(String verdictReply) {
        client.replyOnSend = "done";
        client.sessionType = "idle";
        worktrees.onMergeBack = () -> client.replyOnSend = verdictReply;
    }

    @Test
    public void acceptedReviewMarksDoneAndAdvancesToTheNextStageBacklog() {
        String id = stagedTicket("implementation");
        workerCompletesAndReviewReplies(
                "criteria met, gate green.\nVERDICT: PASS - all criteria verified, gate green");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("worker prompt, then the review prompt", 2, client.sentRequests.size());
        ChatRequest review = client.sentRequests.get(1);
        assertEquals("the review session dispatches under the reviewer agent",
                "reviewer", review.agent());
        assertTrue("the review prompt judges the acceptance criteria",
                review.text().contains("Acceptance criteria:"));
        assertTrue(review.text().contains("1. ac one"));
        assertTrue("the review prompt names the verdict protocol",
                review.text().contains("VERDICT: PASS"));

        Task after = store.get(PROJECT, id);
        assertEquals("PASS -> advance() put the next stage's ticket into the wave backlog",
                "test-implementation", after.stage);
        assertEquals("tester", after.role);
        assertEquals("product-backlog", after.status);
        assertNull("assignee cleared for the next stage's claim", after.assignee);
        assertFalse(after.blocked);
        assertNotNull("the advance is the reviewer's (from the done the verdict set)",
                after.history.stream()
                        .filter(h -> "advanced to test-implementation".equals(h.action())
                                && "reviewer".equals(h.by()))
                        .findAny().orElse(null));
        assertTrue(after.comments.stream()
                .anyMatch(c -> "reviewer".equals(c.by()) && c.text().startsWith("review: PASS")));
    }

    @Test
    public void failedReviewSendsTheTicketBackBlockedWithTheReasons() {
        String id = stagedTicket("implementation");
        workerCompletesAndReviewReplies(
                "criterion 1 is not met and the gate is red.\nVERDICT: FAIL - criterion 1 unmet: no engine tests; gate red");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("FAIL -> task_send_back to the previous stage", "design", after.stage);
        assertEquals("developer", after.role);
        assertEquals("product-backlog", after.status);
        assertTrue("blocked is the human-escalation signal", after.blocked);
        assertTrue(after.blocker.startsWith("sent back from implementation: "));
        assertTrue("the reviewer's reasons ride on the blocker",
                after.blocker.contains("criterion 1 unmet"));
        assertNotNull(after.history.stream()
                .filter(h -> h.action() != null && h.action().startsWith("sent back to design")
                        && "reviewer".equals(h.by()))
                        .findAny().orElse(null));
        assertTrue(after.comments.stream()
                .anyMatch(c -> "reviewer".equals(c.by()) && c.text().startsWith("review: FAIL")
                        && c.text().contains("criterion 1 unmet")));
    }

    @Test
    public void unclearReviewLeavesTheTicketInReviewWithAComment() {
        String id = stagedTicket("design");
        workerCompletesAndReviewReplies(
                "cannot reach the verification gate.\nVERDICT: UNCLEAR - verification gate unreachable");

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertEquals("doubt stays in-review — the sampled human surface", "in-review", after.status);
        assertEquals("design", after.stage);
        assertFalse(after.blocked);
        assertTrue(after.comments.stream()
                .anyMatch(c -> "reviewer".equals(c.by()) && c.text().startsWith("review: UNCLEAR")
                        && c.text().contains("verification gate unreachable")));
    }

    @Test
    public void acceptedVTipStaysDoneThereIsNoNextStage() {
        String id = stagedTicket(VStages.last());
        workerCompletesAndReviewReplies("acceptance verified.\nVERDICT: PASS - acceptance verified");

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertEquals("the V tip has no task_advance: PASS ends in done", "done", after.status);
        assertEquals(VStages.last(), after.stage);
        assertFalse(after.blocked);
    }

    @Test
    public void reviewReplyWithoutAVerdictLineStaysInReview() {
        String id = stagedTicket("implementation");
        workerCompletesAndReviewReplies("looks fine to me"); // no VERDICT line

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertEquals("never auto-advance on a malformed review", "in-review", after.status);
        assertEquals("implementation", after.stage);
        assertTrue(after.comments.stream()
                .anyMatch(c -> c.text().contains("no parseable verdict")));
    }

    @Test
    public void reviewActualsLandOnTheTicketLikeAnyRun() {
        String id = stagedTicket("implementation");
        client.replyOnSend = "done";
        client.sessionType = "idle";
        worktrees.onMergeBack = () -> {
            client.replyOnSend = "VERDICT: PASS - all good";
            client.replyAgent = "reviewer";
            client.replyCost = 0.0123;
        };

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertTrue("the review run's cost lands as a fleet actuals comment "
                + "(CostOverview counts it against the wave budget)",
                after.comments.stream()
                        .anyMatch(c -> c.text().startsWith("fleet actuals:")
                                && c.text().contains("cost 0.0123 USD")
                                && c.text().contains("agent reviewer")));
    }

    @Test
    public void agentSelfAdvancedTicketGetsNoSecondDriver() {
        String id = stagedTicket("requirements");
        client.replyOnSend = "done";
        client.sessionType = "idle";
        // the WORKER advanced the ticket during its run (in its worktree
        // store, which the fake merge lands in the main store)
        worktrees.onMergeBack = () -> {
            store.update(PROJECT, id, Map.of("status", "in-review"));
            store.advance(PROJECT, id, "executor");
        };

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("no review session: the agent already drove the pipeline",
                1, client.sentRequests.size());
        assertEquals("system", store.get(PROJECT, id).stage);
    }

    @Test
    public void reviewSessionRunsReadOnlyInTheMergedMainWorktree() {
        String id = stagedTicket("implementation");
        workerCompletesAndReviewReplies("VERDICT: PASS - ok");

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals("the worker gets the task worktree, the reviewer the merged main worktree",
                REPO, client.sessionDirectories.get(1));
        assertTrue(client.sessionDirectories.get(0).endsWith(id));
        assertEquals("no second worktree/branch for a read-only review",
                List.of(id), worktrees.createdTaskIds);
    }
}
