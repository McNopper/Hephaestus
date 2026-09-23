package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        AtomicInteger replies = new AtomicInteger();
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            // the late reply appears only once the prompt's OWN user row is
            // the last entry: a fixed probe-count append races the prompt
            // thread (its user row can land after all appends under load,
            // leaving last=user forever) - keying on the actual last entry
            // makes the ordering deterministic in both interleavings
            var messages = client.messagesBySession.get("ses_1");
            var last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && last.isUser() && replies.incrementAndGet() <= 3) {
                client.addEntry("ses_1", "assistant", "late reply " + replies.get());
            }
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents());

        FleetJob job = fleet.launch(PROJECT, id, REPO, Duration.ofSeconds(20));

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("in-review", store.get(PROJECT, id).status);
    }

    /**
     * Busy is NOT observable progress (B-008, live 2026-09-23): a busy
     * session with static messages (a hung tool call, a dead stream) is
     * stopped by the no-progress budget. The busy flag only shields it from
     * the STALL detector (review F2).
     */
    @Test
    public void busySessionTimesOutAndBlocksTheTicket() {
        String id = sprintTicket("pm");
        TaskFleet fleet = fleet();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
        assertTrue(job.detail(), job.detail().contains("without observed progress"));
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
        assertTrue("B-008 AC1: the abort carries a diagnostic snapshot", job.detail().contains("diagnostic:"));
        assertTrue(job.detail(), job.detail().contains("last assistant text"));
        assertTrue(job.detail(), job.detail().contains("last tool call"));
        assertTrue(job.detail(), job.detail().contains("pending request"));
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertTrue(after.blocker, after.blocker.contains("stalled"));
        assertTrue("the snapshot is visible in the blocker", after.blocker.contains("diagnostic:"));
    }

    /**
     * Review F2: a BUSY session with static messages (one long tool call - a
     * reactor build - or one long generation) is WORKING, not stalled: the
     * busy flag resets the stall clock. The no-progress BUDGET still bounds
     * it (busy alone is not progress - see {@link #busySessionTimesOutAndBlocksTheTicket()});
     * {@link #progressResetsTheStallAndBudgetClocks()} pins the progressing
     * case.
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

        // the budget is the backstop for an unanswered ask (F1) - no need to
        // wait the full default window to see it fire
        FleetJob job = fleet.launch(PROJECT, id, REPO, Duration.ofMillis(600));

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
        assertTrue("the abort names the unanswered ask", job.detail().contains("never answered"));
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
                Thread.sleep(800); // one long generation in flight (budget-safe margin)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(100));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
    }

    /**
     * B-008 AC2: observed progress (new messages, streaming growth, tool
     * activity) resets BOTH clocks - a busy but progressing worker is never
     * stall-killed and never budget-killed; it runs until it finishes.
     */
    @Test
    public void progressResetsTheStallAndBudgetClocks() throws Exception {
        String id = sprintTicket("developer");
        client.sessionType = "busy"; // busy the whole time until it completes
        AtomicInteger probes = new AtomicInteger();
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            // a new message arrives on every probe: the worker is progressing
            client.addEntry("ses_1", "assistant", "progress " + probes.incrementAndGet());
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents())
                .withStallTimeout(Duration.ofMillis(100));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<FleetJob> launch = pool.submit(() ->
                    fleet.launch(PROJECT, id, REPO, Duration.ofMillis(300)));
            Thread.sleep(700);

            assertFalse("progress resets the stall and the budget window", launch.isDone());

            client.completeSession("ses_1", "done");
            FleetJob job = launch.get(5, TimeUnit.SECONDS);
            assertEquals(FleetJob.State.MERGED, job.state());
        } finally {
            pool.shutdownNow();
        }
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

    /**
     * F-005 regression (live 2026-09-15): "idle + last assistant reply" is
     * true at every INTER-STEP boundary of a healthy agentic run - the worker
     * texted, the next tool call is being prepared, the session is not in the
     * busy map. A complete-looking probe while the prompt POST is still in
     * flight must NOT complete the job: five concurrent workers were falsely
     * completed ~1 min in this way and failed "worker produced no changes"
     * while still streaming. The launch must still be running when the prompt
     * is mid-flight, and merge only after the POST resolves.
     */
    @Test
    public void interStepBoundaryWhilePromptInFlightIsNotCompletion() throws Exception {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // idle + seeded assistant text = the boundary look
        client.onSessionCreated = () -> client.addEntry("ses_1", "assistant",
                "I have the full picture, checking the remaining files...");
        CountDownLatch promptStarted = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        client.blockOnSend = () -> {
            promptStarted.countDown();
            try {
                releasePrompt.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        client.replyOnSend = "done"; // the real final reply, once the POST may proceed
        TaskFleet fleet = fleet(); // default stall timeout (minutes): sustained-complete cannot fire
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<FleetJob> launch = pool.submit(() -> fleet.launch(PROJECT, id, REPO, TIMEOUT));
            assertTrue("prompt POST is in flight", promptStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(300); // probes see the complete-looking boundary repeatedly
            assertFalse("a complete-looking probe with the prompt in flight must not settle the run",
                    launch.isDone());
            releasePrompt.countDown();
            FleetJob job = launch.get(5, TimeUnit.SECONDS);
            assertEquals(FleetJob.State.MERGED, job.state());
        } finally {
            releasePrompt.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * The property the pure-probe design protected (2026-08-28), now via the
     * v2 turn-end marker (B-008): a FINISHED run whose prompt POST is stuck
     * must still merge - never held hostage by a dead HTTP response, never
     * aborted. The server's {@code idle} turn-end marker proves the turn is
     * over regardless of the HTTP response, so the settle is immediate (the
     * old sustained-window fallback made the live 2026-09-22 success path
     * take ~7 minutes).
     */
    @Test
    public void turnEndMarkerMergesDespiteStuckPost() throws Exception {
        String id = sprintTicket("developer");
        client.sessionType = "idle";
        client.onSessionCreated = () -> {
            client.addEntry("ses_1", "assistant", "done: the work is committed");
            client.addEntry("ses_1", "idle", "turn complete");
        };
        client.blockOnSend = () -> {
            try { // the POST that never resolves within the test's horizon
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(150));

        long start = System.nanoTime();
        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue("merged via the turn-end marker, not the 10s POST (took " + elapsedMs + "ms)",
                elapsedMs < 5_000);
    }

    /**
     * B-008 gap (a) regression: a trailing non-conversational row (the v2
     * {@code idle} turn-end marker) must not mask the final assistant reply -
     * the old "last entry must be an assistant" check never completed on it
     * and the stall watchdog then killed a FINISHED session.
     */
    @Test
    public void trailingTurnEndMarkerDoesNotMaskCompletion() {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // the turn ended server-side
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            var messages = client.messagesBySession.get("ses_1");
            if (messages.size() == 1) { // only the prompt row: the turn just closed
                client.addEntry("ses_1", "assistant", "done: the work is committed");
                client.addEntry("ses_1", "idle", "turn complete");
            }
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents());

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
    }

    /**
     * F-005 at its root (B-008): a still-STREAMING reply (time.completed = 0)
     * is never completion evidence - the run keeps waiting for the stamped
     * reply instead of settling mid-stream.
     */
    @Test
    public void streamingReplyIsNotCompleteUntilStamped() throws Exception {
        String id = sprintTicket("developer");
        client.sessionType = "idle";
        AtomicInteger seeded = new AtomicInteger();
        FleetRunner runner = new FleetRunner(client, worktrees, () -> {
            var messages = client.messagesBySession.get("ses_1");
            if (messages.size() == 1 && seeded.getAndIncrement() == 0) {
                client.addEntry("ses_1", "assistant", "working on it...", 0L); // still streaming
            }
        });
        TaskFleet fleet = new TaskFleet(runner, store, new RoleAgents());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<FleetJob> launch = pool.submit(() -> fleet.launch(PROJECT, id, REPO, TIMEOUT));
            Thread.sleep(300);
            assertFalse("an unstamped (streaming) reply settles nothing", launch.isDone());

            client.addEntry("ses_1", "assistant", "done: the work is committed"); // stamped
            FleetJob job = launch.get(5, TimeUnit.SECONDS);
            assertEquals(FleetJob.State.MERGED, job.state());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * B-008 AC2/AC4: with no PROGRESS anywhere (a prompt POST that never
     * returns and a silent session), the budget stops the run and the detail
     * says it was a no-progress timeout carrying the pending-request state.
     */
    @Test
    public void noProgressRunIsBudgetKilledWithDiagnostics() {
        String id = sprintTicket("developer");
        client.sessionType = "idle";
        client.blockOnSend = () -> {
            try { // the stuck POST: in flight forever, no rows, no growth
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMinutes(5));

        FleetJob job = fleet.launch(PROJECT, id, REPO, Duration.ofMillis(500));

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("timeout"));
        assertTrue(job.detail(), job.detail().contains("without observed progress"));
        assertTrue(job.detail(), job.detail().contains("diagnostic:"));
        assertTrue(job.detail(), job.detail().contains("prompt POST still in flight"));
        assertTrue("the hung session was aborted", client.aborted.contains("ses_1"));
    }

    /**
     * B-008 AC1: the diagnostic names the last tool call (name + status) so
     * a tool-hang is distinguishable from a model-hang and a true hang.
     */
    @Test
    public void abortDetailSnapshotNamesTheLastToolCall() {
        String id = sprintTicket("developer");
        client.sessionType = "idle"; // idle, no reply, messages static
        client.onSessionCreated = () -> {
            client.addEntry("ses_1", "assistant", "Now I run the build.");
            client.addToolEntry("ses_1", "bash", "running");
        };
        TaskFleet fleet = fleet().withStallTimeout(Duration.ofMillis(50));

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue(job.detail(), job.detail().contains("stalled"));
        assertTrue("the snapshot names the tool call: " + job.detail(),
                job.detail().contains("bash [running]"));
        assertTrue(job.detail(), job.detail().contains("Now I run the build."));
    }
}
