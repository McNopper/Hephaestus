package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * {@link TaskFleet}'s WATCHDOG completion (2026-08-28 redesign): the prompt
 * POST runs on its own thread and completion is judged by probing (busy flag
 * + last assistant reply) - so a finished session merges even if the POST
 * response is stuck, a prompt failure surfaces fast, and a session with no
 * new messages for the stall threshold is aborted and fails cleanly instead
 * of burning the whole budget. Reuses the in-memory fakes and TaskFleetTest
 * fixtures.
 */
public class TaskFleetWatchdogTest {

    private static final Path REPO = Path.of("repo");
    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private FakeWorktreeManager worktrees;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
    }

    private String sprintTicket(String role) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", role, "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    private TaskFleet fleet() {
        return new TaskFleet(new FleetRunner(client, worktrees, () -> { }),
                store, new RoleAgents());
    }

    @Test
    public void probeCompletionDrivesMergeAndBookkeeping() {
        String id = sprintTicket("developer");
        sessionCompletes();
        TaskFleet fleet = fleet();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task after = store.get(PROJECT, id);
        assertEquals("in-review", after.status);
        assertFalse("no blocker on success", after.blocked);
        assertTrue(after.artifacts.stream().anyMatch(a ->
                "git".equals(a.kind()) && ("opencode/" + id).equals(a.ref())));
        assertEquals(List.of(id), worktrees.mergedTaskIds);
    }

    @Test
    public void idleWithoutAssistantReplyWaitsUntilTheReplyAppears() {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // idle status, but no assistant reply yet
        AtomicInteger probes = new AtomicInteger();
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            if (probes.incrementAndGet() == 1) {
                client.completeSession("ses_1", "done");
            }
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents());

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("in-review", store.get(PROJECT, id).status);
    }

    @Test
    public void busySessionTimesOutAndBlocksTheTicket() {
        String id = sprintTicket("pm");
        TaskFleet fleet = fleet();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("timeout"));
        assertTrue("no merge must be attempted", worktrees.mergedTaskIds.isEmpty());
    }

    @Test
    public void promptFailureBlocksTheTicket() {
        String id = sprintTicket("developer");
        client.blockOnSend = () -> {
            throw new IllegalStateException("event stream broken");
        };
        TaskFleet fleet = fleet();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("event stream broken"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("event stream broken"));
    }

    /**
     * The watchdog's reason to exist: a session that is IDLE and streams
     * nothing new for the stall threshold is ABORTED and fails within the
     * threshold - not after the whole budget. BUSY resets the clock (see
     * {@link #busyWorkerIsNeverStallKilled()}), and so does a pending
     * permission ask (see {@link #permissionWaitIsNotAStall()}).
     */
    @Test
    public void idleAndSilentSessionIsAbortedAndBlocksTheTicket() {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // idle, no assistant reply, messages static
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(50));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("stalled"));
        assertTrue("the hung session was aborted", client.aborted.contains("ses_1"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("stalled"));
    }

    /**
     * Review F2: a BUSY session with static messages (one long tool call - a
     * reactor build - or one long generation) is WORKING, not stalled: the
     * busy flag resets the stall clock and the run may live to its budget.
     */
    @Test
    public void busyWorkerIsNeverStallKilled() {
        String id = sprintTicket("developer");
        client.sessionType = "busy"; // busy, messages static (no new rows)
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(100));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
    }

    /**
     * Review F1: a session WAITING on a permission ask (an ask is a question
     * for the human, not worker silence) must not be stall-killed - the run
     * dies only if nobody ever answers, at the budget.
     */
    @Test
    public void permissionWaitIsNotAStall() {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // idle and silent - would stall in seconds...
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        client.blockOnSend = () -> queue.offer(new com.opencode.ide.client.activity.PermissionRequest(
                "ses_1", "per_1", "bash", List.of("git push"), "git push",
                com.opencode.ide.client.activity.PermissionRequest.Status.PENDING));
        TaskFleet fleet = new TaskFleet(
                new FleetRunner(client, worktrees, () -> { }, bridge::sessionStarted),
                store, new RoleAgents(), null, bridge)
                .withStallTimeout(Duration.ofMillis(100)); // ...but the pending ask pauses the clock

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
    }

    /**
     * Live-proven 2026-09-13 (W-005, third retry): during ONE long generation
     * the session is absent from the busy map and no new message rows appear -
     * the stall clock must pause while the prompt POST is still pending (the
     * turn is in flight server-side). The budget, not the stall threshold, is
     * the backstop for a POST that never returns.
     */
    @Test
    public void inFlightGenerationIsNeverStallKilled() {
        String id = sprintTicket("developer");
        sessionCompletes(); // idle + assistant reply once the generation ends
        client.blockOnSend = () -> {
            try {
                Thread.sleep(4_000); // one long generation in flight
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(100));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
    }

    @Test
    public void progressResetsTheStallClock() {
        String id = sprintTicket("developer");
        client.sessionType = "busy"; // busy the whole time, never completes
        AtomicInteger probes = new AtomicInteger();
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            // a new message arrives on every probe: the worker is progressing
            client.addEntry("ses_1", "assistant", "progress " + probes.incrementAndGet());
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents())
                .withStallTimeout(Duration.ofMillis(200));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals("busy but progressing workers are never stall-killed -"
                + " they run to the budget", FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
    }

    @Test
    public void defaultClientConstructorKeepsPollingCompletion() {
        String id = sprintTicket("developer");
        sessionCompletes();
        TaskFleet fleet = new TaskFleet(client, worktrees, store);

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("in-review", store.get(PROJECT, id).status);
    }
}
