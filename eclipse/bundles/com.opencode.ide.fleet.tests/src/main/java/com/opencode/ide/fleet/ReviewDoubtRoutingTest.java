package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * B-007 FR-009..FR-012 at the engine level (AC-003): reviewer doubt
 * round-trips to the originator - one retry per stage visit - and only a
 * recursing doubt blocks; an unstaged ticket keeps the U-021 human surface.
 * Complements {@link com.opencode.ide.tasks.ReviewDoubtTest} (the store
 * mechanics) and {@link AutonomousAcceptanceTest} (PASS/FAIL routing).
 */
public class ReviewDoubtRoutingTest extends FleetTestHarness {

    @Override
    protected TaskFleet build(TaskFleet fleet) {
        return fleet.withAutonomousAcceptance();
    }

    @Test
    public void unclearReviewRoutesTheDoubtToTheOriginatorForOneRetry() {
        String id = stagedTicket("system");
        workerCompletesAndReviewReplies(
                "I cannot determine this.\nVERDICT: UNCLEAR - missing evidence for criterion 2");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertFalse("FR-009/011: doubt never blocks as the first outcome", after.blocked);
        assertEquals("the originator retries in its own stage", "system", after.stage);
        assertEquals("product-backlog", after.status);
        assertTrue("the retry is recorded (FR-012)",
                after.history.stream().anyMatch(h -> h.action()
                        .equals("review doubt retry (1/1) for stage system: missing evidence for criterion 2")));
    }

    @Test
    public void aRecurringDoubtBlocksAfterTheRetryIsConsumed() {
        String id = stagedTicket("system");
        workerCompletesAndReviewReplies(
                "still unclear.\nVERDICT: UNCLEAR - missing evidence for criterion 2");
        fleet.launch(PROJECT, id, REPO, TIMEOUT);
        assertFalse(store.get(PROJECT, id).blocked);

        workerCompletesAndReviewReplies(
                "still unclear.\nVERDICT: UNCLEAR - missing evidence for criterion 2");
        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertTrue("FR-010: blocked only after the one retry is consumed", after.blocked);
        assertTrue(after.blocker, after.blocker.startsWith("review doubt unresolved after 1 originator retries"));
    }

    @Test
    public void aStageTransitionResetsTheDoubtBudget() {
        String id = stagedTicket("system");
        workerCompletesAndReviewReplies(
                "unclear.\nVERDICT: UNCLEAR - doubt one");
        fleet.launch(PROJECT, id, REPO, TIMEOUT);
        store.update(PROJECT, id, Map.of("status", "in-review"));
        store.advance(PROJECT, id, TaskFleet.REVIEWER);

        workerCompletesAndReviewReplies(
                "unclear.\nVERDICT: UNCLEAR - doubt at the next stage");
        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        Task after = store.get(PROJECT, id);
        assertFalse("a new stage visit gets a fresh retry budget", after.blocked);
        assertEquals("architecture", after.stage);
        assertTrue(after.history.stream().anyMatch(h -> h.action()
                .equals("review doubt retry (1/1) for stage architecture: doubt at the next stage")));
    }

    @Test
    public void anUnstagedTicketKeepsTheHumanSurface() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec("Unstaged work", "d", "task",
                "developer", "high", 2, List.of("ac"), List.of(), null, "R"));
        workerCompletesAndReviewReplies(
                "unclear.\nVERDICT: UNCLEAR - no stage to retry in");

        FleetJob job = fleet.launch(PROJECT, t.id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, t.id);
        assertEquals("U-021 behavior stands: waits in in-review", "in-review", after.status);
        assertFalse(after.blocked);
        assertTrue(after.comments.stream()
                .anyMatch(c -> "reviewer".equals(c.by()) && c.text().contains("review: UNCLEAR")));
    }

    /** The reviewer must see the matrix row (NFR-CONSIST-001, Q-004). */
    @Test
    public void theReviewPromptQuotesTheStageEvidenceMatrixRow() {
        String id = stagedTicket("design");
        workerCompletesAndReviewReplies("VERDICT: PASS - fine");

        fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertNotNull(client.sentRequests);
        String review = client.sentRequests.get(1).text();
        assertTrue("the judging model sees the same contract the gates enforce",
                review.contains("Expected evidence for this stage (B-007 matrix):"));
        assertTrue(review.contains("definition-leg run (stage design)"));
    }
}
