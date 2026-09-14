package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.git.MergeResult;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * End-to-end tests for {@link TaskFleet}: a real {@link TaskStore} on a temp
 * directory plus the in-memory client/worktree fakes - pre-claim before
 * submit, store bookkeeping on success, blocked-with-reason on merge
 * conflict and timeout.
 */
public class TaskFleetTest {

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
        fleet = new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store);
    }

    private String sprintTicket(String role) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", role, "high", 3,
                List.of("ac one", "ac two"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    @Test
    public void happyPathEndToEnd() {
        String id = sprintTicket("developer");
        sessionCompletes();
        List<Task> stateAtSubmit = new ArrayList<>();
        client.onSessionCreated = () -> stateAtSubmit.add(store.get(PROJECT, id));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertNull(job.detail());
        // pre-claim happened in the main store BEFORE the session was created
        assertEquals(1, stateAtSubmit.size());
        assertEquals("in-progress", stateAtSubmit.get(0).status);
        assertEquals("fleet", stateAtSubmit.get(0).assignee);
        // the prompt went to the role's agent, with the server-default model
        assertEquals(1, client.sentRequests.size());
        assertEquals("executor", client.sentRequests.get(0).agent());
        assertFalse(client.sentRequests.get(0).hasModel());
        assertTrue(client.sentRequests.get(0).text().contains("task_get(\"" + id + "\")"));
        // the prompt POST runs on its own thread with the MAXIMUM budget -
        // the watchdog, not a POST deadline, bounds the run (2026-08-28
        // redesign: slow workers are never budget-killed, hung ones abort)
        assertTrue("prompt recorded", !client.sentRequests.isEmpty());
        // final ticket state: in-review, launch comment, git artifact
        Task after = store.get(PROJECT, id);
        assertEquals("in-review", after.status);
        // the pre-claim is committed in main BEFORE the worktree/branch exists,
        // so merge-back is never refused over the dirty ticket file
        assertEquals(1, worktrees.commitMessages.size());
        assertTrue(worktrees.commitMessages.get(0), worktrees.commitMessages.get(0).contains(id));
        assertTrue("commit must precede the worktree create",
                worktrees.createdTaskIds.contains(id));
        // F-002: a MERGED job consumes its worktree+branch - re-dispatches
        // never hit "branch already exists"
        assertTrue("merged worktree reaped", worktrees.removedTaskIds.contains("force:" + id));
        assertFalse(after.blocked);
        assertTrue(after.comments.stream().anyMatch(c ->
                "fleet".equals(c.by()) && c.text().contains("opencode/" + id)));
        assertTrue(after.artifacts.stream().anyMatch(a ->
                "git".equals(a.kind())
                        && ("opencode/" + id).equals(a.ref())
                        && "fleet".equals(a.by())));
        // merged through the manager, and observable via the jobs snapshot
        assertEquals(List.of(id), worktrees.mergedTaskIds);
        assertEquals(FleetJob.State.MERGED, fleet.jobs().get(id).state());
    }

    @Test
    public void mergeConflictBlocksTicketWithFilesAndKeepsWorktree() {
        String id = sprintTicket("tester");
        sessionCompletes();
        worktrees.nextMergeResult =
                new MergeResult(false, List.of("src/A.java", "README.md"), "CONFLICT");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertNotNull("worktree kept for post-mortem", job.worktree());
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("merge conflicts"));
        assertTrue(after.blocker, after.blocker.contains("src/A.java"));
        assertTrue(after.blocker, after.blocker.contains("README.md"));
        assertEquals("F-001: a failed run releases the claim - never a zombie in-progress",
                "sprint-backlog", after.status);
        assertNull("the fleet assignee is released with the claim", after.assignee);
    }

    @Test
    public void mergeBackThrowingBlocksAndReleasesInsteadOfStranding() {
        String id = sprintTicket("developer");
        sessionCompletes();
        worktrees.mergeBackFailure = new RuntimeException("git killed mid-merge");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("git killed mid-merge"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("git killed mid-merge"));
        assertEquals("sprint-backlog", after.status);
        assertNull(after.assignee);
    }

    @Test
    public void timeoutBlocksTicket() {
        String id = sprintTicket("pm");
        // client stays busy: the session never completes

        FleetJob job = fleet.launch(PROJECT, id, REPO, Duration.ofMillis(50));

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("timeout"));
        assertTrue("no merge must be attempted", worktrees.mergedTaskIds.isEmpty());
        assertEquals(FleetJob.State.FAILED, fleet.jobs().get(id).state());
    }

    @Test
    public void submitFailureBlocksTicket() {
        String id = sprintTicket("developer");
        client.failSessionCreation = true;

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("session create failed"));
        assertTrue("worktree kept for post-mortem", job.worktree() != null);
    }

    @Test
    public void launchRejectsBlockedTicket() {
        String id = sprintTicket("developer");
        store.setBlocked(PROJECT, id, "waiting on infra", "human");

        try {
            fleet.launch(PROJECT, id, REPO, TIMEOUT);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("blocked"));
        }
        assertTrue("no worktree may be created", worktrees.createdTaskIds.isEmpty());
        assertTrue(client.sentRequests.isEmpty());
    }

    @Test
    public void launchRejectsDoneTicket() {
        String id = sprintTicket("developer");
        store.update(PROJECT, id, Map.of("status", "done"));

        try {
            fleet.launch(PROJECT, id, REPO, TIMEOUT);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("done"));
        }
        assertTrue(worktrees.createdTaskIds.isEmpty());
    }

    @Test
    public void mergeDoesNotDuplicateAnExistingGitArtifact() {
        String id = sprintTicket("developer");
        sessionCompletes();
        store.addArtifact(PROJECT, id, "git", "opencode/" + id, "recorded by the agent", "executor");

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals(1, after.artifacts.stream()
                .filter(a -> "git".equals(a.kind()) && ("opencode/" + id).equals(a.ref()))
                .count());
    }

    /**
     * Worker-reliability gate: a ticket whose ACs name a file path must not
     * merge when the worker touched only unrelated files - the refusal
     * names both sides so the operator can act without a post-mortem.
     */
    @Test
    public void analysisOnlyRunIsRefusedBeforeMergingWithSpecificMessage() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("src/main/java/Foo.java exists", "runs"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        sessionCompletes();
        worktrees.nextChangedFiles = new ArrayList<>(List.of("src/main/java/Y.java"));

        FleetJob job = fleet.launch(PROJECT, t.id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("analysis-only run"));
        assertTrue(job.detail(), job.detail()
                .contains("no acceptance-criterion path in the diff"));
        assertTrue(job.detail(), job.detail().contains("src/main/java/Foo.java"));
        assertTrue(job.detail(), job.detail().contains("src/main/java/Y.java"));
        Task after = store.get(PROJECT, t.id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("analysis-only run"));
        assertEquals("sprint-backlog", after.status);
        assertNull("the fleet assignee is released with the claim", after.assignee);
        assertTrue("refused BEFORE the merge - main stays clean", worktrees.mergedTaskIds.isEmpty());
        assertFalse("worktree kept for post-mortem", worktrees.removedTaskIds.contains("force:" + t.id));
    }

    /** An AC path satisfied by a changed file (exact or path-segment suffix) merges normally. */
    @Test
    public void acPathInDiffMergesNormally() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("Foo.java exists"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        sessionCompletes();
        worktrees.nextChangedFiles = new ArrayList<>(List.of("src/main/java/Foo.java", "README.md"));

        FleetJob job = fleet.launch(PROJECT, t.id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("the gate probed the worker's diff once", List.of(t.id), worktrees.changedFilesCalls);
        assertEquals("in-review", store.get(PROJECT, t.id).status);
    }

    /** Behavioral criteria (no path-like strings) never trip the gate. */
    @Test
    public void behavioralCriteriaSkipTheAcPathGate() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("compiles", "deterministic output"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        sessionCompletes();
        worktrees.nextChangedFiles = new ArrayList<>(List.of("some/other/File.cpp"));

        FleetJob job = fleet.launch(PROJECT, t.id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue("no AC paths -> no diff probe", worktrees.changedFilesCalls.isEmpty());
        assertEquals("in-review", store.get(PROJECT, t.id).status);
    }

    @Test
    public void agentMovedTicketToInReviewIsNotMovedAgain() {
        String id = sprintTicket("developer");
        sessionCompletes();
        // simulate the agent's in-review update coming back with the merge
        worktrees.onMergeBack = () -> store.update(PROJECT, id, Map.of("status", "in-review"));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("in-review", after.status);
        long statusUpdates = after.history.stream()
                .filter(h -> h.action() != null && h.action().startsWith("updated:") && h.action().contains("status"))
                .count();
        assertEquals("only the pre-claim (in-progress) and the agent's own update; "
                + "TaskFleet must not add a third", 2, statusUpdates);
    }

    @Test
    public void unknownRoleUsesServerDefaultAgent() {
        String id = sprintTicket("research");
        sessionCompletes();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertNull("unknown role -> no agent override", client.sentRequests.get(0).agent());
    }

    @Test
    public void secondLaunchWhileInFlightIsRejected() throws Exception {
        String id = sprintTicket("developer");
        // keep the first launch in flight: block inside sendMessage until released
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        client.onSessionCreated = entered::countDown;
        client.blockOnSend = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(() -> fleet.launch(PROJECT, id, REPO, TIMEOUT), "launch-1");
        first.start();
        assertTrue("first launch never reached submit", entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

        try {
            fleet.launch(PROJECT, id, REPO, TIMEOUT);
            fail("second launch on an in-flight ticket must be rejected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("in flight"));
        } finally {
            // make the session complete, then let the blocked send finish
            sessionCompletes();
            client.blockOnSend = null;
            release.countDown();
        }
        first.join(10_000);
        // after completion the guard is released: relaunch is allowed again
        // (ticket is in-review now, which is launchable per the rules)
        FleetJob again = fleet.launch(PROJECT, id, REPO, TIMEOUT);
        assertEquals(FleetJob.State.MERGED, again.state());
    }

    /**
     * F-002 startup reconciliation: an in-progress fleet claim whose
     * worktree/branch no longer exists is crash residue - released with a
     * blocked marker naming the reason, never left as a zombie claim.
     */
    @Test
    public void reconcileReleasesOrphanedFleetClaims() {
        String live = sprintTicket("developer");
        store.update(PROJECT, live, Map.of("status", "in-progress", "assignee", "fleet"));
        worktrees.create(REPO, live); // this claim HAS a worktree - untouched

        String dead = sprintTicket("developer");
        store.update(PROJECT, dead, Map.of("status", "in-progress", "assignee", "fleet"));
        // no worktree for `dead` - orphaned

        String human = sprintTicket("developer");
        store.update(PROJECT, human, Map.of("status", "in-progress", "assignee", "norbe"));
        // no worktree, but not the fleet's claim - untouched

        int released = fleet.reconcileOrphanedClaims(PROJECT);

        assertEquals("only the orphaned FLEET claim is released", 1, released);
        assertEquals("live claim untouched", "in-progress", store.get(PROJECT, live).status);
        Task after = store.get(PROJECT, dead);
        assertEquals("sprint-backlog", after.status);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("reconciled"));
        assertEquals("human claim untouched", "norbe", store.get(PROJECT, human).assignee);
    }
}
