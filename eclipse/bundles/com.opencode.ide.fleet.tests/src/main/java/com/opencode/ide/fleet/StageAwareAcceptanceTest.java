package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.git.MergeResult;
import com.opencode.ide.tasks.Task;

/**
 * B-007 engine-level checkpoint behavior (AC-002, AC-004): the merge gate
 * and the settle check consult the per-stage evidence matrix
 * ({@link com.opencode.ide.tasks.StageEvidence}, FR-005) - the U-026
 * definition-leg case passes untouched, store-side evidence passes the
 * settle check (FR-006), heuristic refusals route to the originator retry
 * on the definition leg (FR-011) and keep the established blocked contract
 * everywhere else (analysis-only implementation runs remain refused).
 */
public class StageAwareAcceptanceTest extends FleetTestHarness {

    /** FR-008 / AC-002 regression: the live U-026 false rejection, end to end. */
    @Test
    public void aDefinitionLegTicketAndDocDiffMergesUntouched() {
        String id = stagedTicket("requirements", "see e.g. docs/requirements/feature.md");
        worktrees.nextChangedFiles = List.of(
                ".opencode/tasks/hephaestus/" + id + ".md", "docs/requirements/feature.md");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertFalse("the definition-leg output is never blocked", after.isBlocked());
        assertFalse("no analysis-only refusal for doc/store paths",
                after.comments.stream().anyMatch(c -> c.text().contains("analysis-only")));
    }

    /** FR-006 / AC-002: a store-side-only definition run survives the settle check. */
    @Test
    public void storeSideStageEvidencePassesTheSettleCheck() {
        String id = stagedTicket("requirements", "deliver docs/requirements/feature.md");
        worktrees.nextMergeResult = new MergeResult(false, List.of(),
                "worker produced no changes (no commits on opencode/" + id
                        + " and no pending worktree edits) - the task may genuinely need no changes,"
                        + " or the worker wrote outside its worktree (check the main checkout),"
                        + " or the deliverable arrived only as chat text");
        // the worker delivered through the task_* tools mid-run (the U-026/B-005 shape)
        client.blockOnSend = () -> store.addComment(PROJECT, id, "requirements written to the store", "executor");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue("the settle-check allowance travelled to the merge seam",
                worktrees.mergeAllowEmptyFlags.contains(Boolean.TRUE));
        assertFalse(store.get(PROJECT, id).isBlocked());
    }

    /** FR-006 negative: no store evidence at all keeps the settle refusal - but FR-011 routes it. */
    @Test
    public void anEmptyDefinitionRunRoutesTheRefusalToTheOriginatorRetry() {
        String id = stagedTicket("requirements", "deliver docs/requirements/feature.md");
        worktrees.nextMergeResult = new MergeResult(false, List.of(),
                "worker produced no changes (no commits on opencode/" + id
                        + " and no pending worktree edits) - the task may genuinely need no changes,"
                        + " or the worker wrote outside its worktree (check the main checkout),"
                        + " or the deliverable arrived only as chat text");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue("the settle-check allowance was correctly NOT granted",
                worktrees.mergeAllowEmptyFlags.contains(Boolean.FALSE));
        Task after = store.get(PROJECT, id);
        assertFalse("FR-011: a heuristic refusal is not the blocked first outcome", after.isBlocked());
        assertEquals("the originator retry runs in the same stage", "requirements", after.stage);
        assertEquals("product-backlog", after.status);
        assertTrue("the refusal routes through the doubt path (retry 1/1)",
                after.history.stream().anyMatch(h -> h.action().startsWith("review doubt retry (1/1)")));
    }

    /** AC-005 regression pin: analysis-only IMPLEMENTATION runs remain refused. */
    @Test
    public void analysisOnlyImplementationRunsAreStillRefused() {
        String id = stagedTicket("implementation", "src/Foo.java must change");
        worktrees.nextChangedFiles = List.of("docs/some-notes.md");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        Task after = store.get(PROJECT, id);
        assertTrue("the established contract: blocked with the reason", after.isBlocked());
        assertTrue(after.blocker, after.blocker.startsWith("analysis-only run:"));
        assertTrue("the refusal never merges", worktrees.mergedTaskIds.isEmpty());
    }

    /** FR-011 scope: the same heuristic refusal blocks off the definition leg (unchanged contract). */
    @Test
    public void heuristicRefusalsOffTheDefinitionLegStillBlock() {
        String id = stagedTicket("implementation", "src/Foo.java must change");
        worktrees.nextMergeResult = new MergeResult(false, List.of(),
                "worker produced no changes (no commits on opencode/" + id
                        + " and no pending worktree edits) - the task may genuinely need no changes,"
                        + " or the worker wrote outside its worktree (check the main checkout),"
                        + " or the deliverable arrived only as chat text");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        Task after = store.get(PROJECT, id);
        assertTrue(after.isBlocked());
    }
}
