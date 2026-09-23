package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * B-004 regression tests (live-found 2026-09-17): the fleet engine's
 * ownership of its spawned {@code opencode serve}. The incident - a timed-out
 * run leaked its serve, whose locks then broke {@code fleet_reset}, and after
 * the operator killed the orphan the still-running engine kept failing every
 * dispatch with "Cannot reach opencode server" - maps to four guarantees:
 *
 * <ol>
 *   <li>a launch that settles FAILED kills the serve the engine owns when
 *       nothing else is in flight (no orphan survives the job end),</li>
 *   <li>a cached engine whose serve died is probed and respawned BEFORE the
 *       recorded endpoint is reused (a dead serve never blocks later
 *       dispatches),</li>
 *   <li>a healthy MERGED launch keeps the serve for reuse, and a sibling
 *       launch still in flight protects its serve from the recycle,</li>
 *   <li>{@code fleet_reset} kills the leaked serve when idle and retries the
 *       worktree removal, and names the still-live serve pid when it cannot
 *       kill it.</li>
 * </ol>
 *
 * <p>The {@link FleetControl.Engine} seam fakes the serve (an {@code alive}
 * flag + pid) - the "fake process seam" the ticket asks for; no real
 * {@code opencode serve} is spawned here.</p>
 */
public class FleetServeLifecycleTest {

    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;

    /**
     * The fake process seam: an engine whose "spawned serve" is a mutable
     * alive flag with a pid, so tests can kill the serve like an operator
     * would and observe the engine's close (= serve kill).
     */
    private static final class ServeEngine implements FleetControl.Engine {
        final TaskFleet fleet;
        final PermissionQueue queue = new PermissionQueue(null);
        volatile boolean alive = true;
        volatile Long pid;
        volatile boolean closed;

        ServeEngine(TaskStore store, FakeClient client, FakeWorktreeManager worktrees) {
            this.fleet = new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store);
        }

        @Override
        public TaskFleet fleet() {
            return fleet;
        }

        @Override
        public boolean serverAlive() {
            return alive;
        }

        @Override
        public Long serverPid() {
            return alive ? pid : null;
        }

        @Override
        public PermissionQueue permissions() {
            return queue;
        }

        @Override
        public void close() {
            closed = true; // the serve kill
            alive = false;
        }
    }

    /** Each factory call spawns one engine over the shared client; recorded in spawn order. */
    private final List<ServeEngine> engines = new CopyOnWriteArrayList<>();
    private FakeClient client;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        client = new FakeClient();
    }

    private FleetControl control() {
        return new FleetControl(store.root(), root -> {
            ServeEngine engine = new ServeEngine(store, client, new FakeWorktreeManager());
            engines.add(engine);
            return engine;
        });
    }

    private String sprintTicket() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        // generous: git spawns cost 10-100x on a machine without the Defender
        // exclusions (T-006) - see the storeClean note in FleetControlTest
        // (2026-09-23: the "flake" was plain git latency, not a race)
        long deadline = System.currentTimeMillis() + FleetTestHarness.EVENT_WAIT.toMillis();
        while (!condition.getAsBoolean()) {
            assertTrue(what + " did not happen in time", System.currentTimeMillis() < deadline);
            Thread.sleep(50);
        }
    }

    // ---- AC 1: a failed launch kills the serve it owns ----

    /**
     * The timed-out-run scenario: a launch settling FAILED (here a session
     * create failure - the fastest deterministic failure) recycles the engine
     * when idle, so the spawned serve is killed instead of surviving the job
     * as an orphan; the NEXT dispatch lazily spawns a fresh serve.
     */
    @Test(timeout = 30_000)
    public void failedLaunchKillsTheSpawnedServeAndTheNextDispatchRespawns() throws Exception {
        client.failSessionCreation = true; // the run fails instantly
        try (FleetControl control = control()) {
            String failed = sprintTicket();
            control.dispatch(PROJECT, failed, TIMEOUT);

            await("the failed ticket is blocked and released",
                    () -> store.get(PROJECT, failed).blocked
                            && "sprint-backlog".equals(store.get(PROJECT, failed).status));
            await("the engine (and with it the serve) is recycled after the failure",
                    () -> !control.engineStarted());

            assertEquals("exactly one engine so far", 1, engines.size());
            assertTrue("the failed run's engine was closed - its serve is dead, no orphan",
                    engines.get(0).closed);
            assertNull("no serve pid while no engine exists", control.engineServePid());

            // the recovery path: the next dispatch spawns a FRESH serve and merges
            client.failSessionCreation = false;
            sessionCompletes();
            String retried = sprintTicket();
            control.dispatch(PROJECT, retried, TIMEOUT);
            await("the re-dispatch merges on the respawned engine", () -> {
                FleetJob job = control.jobs().get(retried);
                return job != null && job.state() == FleetJob.State.MERGED;
            });
            assertEquals("a second engine (fresh serve) was spawned", 2, engines.size());
            assertFalse("the fresh engine is alive", engines.get(1).closed);
        }
    }

    // ---- AC 2: a dead recorded endpoint is probed before reuse ----

    /**
     * The stale-endpoint scenario: the engine's serve dies between dispatches
     * (here: an operator kills it). Reusing the recorded endpoint would fail
     * EVERY later dispatch with "Cannot reach opencode server"; the control
     * probes the serve before reuse, closes the dead engine and spawns a
     * fresh one - the later dispatch merges instead of failing.
     */
    @Test(timeout = 30_000)
    public void deadRecordedServeIsProbedAndRespawnedBeforeReuse() throws Exception {
        sessionCompletes();
        try (FleetControl control = control()) {
            String first = sprintTicket();
            control.dispatch(PROJECT, first, TIMEOUT);
            await("first dispatch merges", () -> {
                FleetJob job = control.jobs().get(first);
                return job != null && job.state() == FleetJob.State.MERGED;
            });
            engines.get(0).pid = 62090L;
            assertEquals("the live serve's pid is exposed for diagnostics",
                    Long.valueOf(62090L), control.engineServePid());

            engines.get(0).alive = false; // the orphan gets killed out from under the engine
            assertNull("a dead serve has no pid", control.engineServePid());

            String second = sprintTicket();
            control.dispatch(PROJECT, second, TIMEOUT);
            await("the post-kill dispatch merges on the respawned engine", () -> {
                FleetJob job = control.jobs().get(second);
                return job != null && job.state() == FleetJob.State.MERGED;
            });
            assertEquals("the dead engine was replaced, not reused", 2, engines.size());
            assertTrue("the dead engine was closed (its event stream too)", engines.get(0).closed);
            assertFalse("the replacement engine stays alive", engines.get(1).closed);
        }
    }

    // ---- guards: healthy runs must NOT recycle the serve ----

    /** A MERGED launch keeps the serve alive - healthy reuse across jobs, no respawn churn. */
    @Test(timeout = 30_000)
    public void mergedLaunchsKeepTheServeForHealthyReuse() throws Exception {
        sessionCompletes();
        try (FleetControl control = control()) {
            String first = sprintTicket();
            control.dispatch(PROJECT, first, TIMEOUT);
            await("first dispatch merges", () -> {
                FleetJob job = control.jobs().get(first);
                return job != null && job.state() == FleetJob.State.MERGED;
            });

            String second = sprintTicket();
            control.dispatch(PROJECT, second, TIMEOUT);
            await("second dispatch merges", () -> {
                FleetJob job = control.jobs().get(second);
                return job != null && job.state() == FleetJob.State.MERGED;
            });

            assertEquals("both dispatches shared ONE engine", 1, engines.size());
            assertFalse("a merged launch never kills the serve", engines.get(0).closed);
            assertTrue(control.engineStarted());
        }
    }

    /**
     * The sibling guard: ticket A fails while ticket B is still mid-launch -
     * killing the serve would kill B's session too, so the recycle is
     * skipped and the engine survives until B settles.
     */
    @Test(timeout = 30_000)
    public void failedLaunchWithASiblingStillRunningKeepsTheServe() throws Exception {
        sessionCompletes();
        CountDownLatch siblingSessionCreated = new CountDownLatch(1);
        client.onSessionCreated = siblingSessionCreated::countDown;
        CountDownLatch releaseSiblingMerge = new CountDownLatch(1);
        FakeWorktreeManager siblingWorktrees = new FakeWorktreeManager();
        siblingWorktrees.onMergeBack = () -> {
            try {
                assertTrue(releaseSiblingMerge.await(FleetTestHarness.LATCH_WAIT.toSeconds(), TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        FleetControl control = new FleetControl(store.root(), root -> {
            ServeEngine engine = new ServeEngine(store, client, siblingWorktrees);
            engines.add(engine);
            return engine;
        });
        try {
            String sibling = sprintTicket();
            control.dispatch(PROJECT, sibling, TIMEOUT);
            assertTrue("sibling session started",
                    siblingSessionCreated.await(FleetTestHarness.LATCH_WAIT.toSeconds(), TimeUnit.SECONDS));

            client.failSessionCreation = true; // the sibling's session already exists
            String failed = sprintTicket();
            control.dispatch(PROJECT, failed, TIMEOUT);
            await("the failed ticket settled", () -> {
                FleetJob job = control.jobs().get(failed);
                return job != null && job.state() == FleetJob.State.FAILED;
            });
            await("the failed run's bookkeeping landed",
                    () -> store.get(PROJECT, failed).blocked);

            assertEquals("no respawn while the sibling runs", 1, engines.size());
            assertFalse("the sibling's serve was NOT killed by the sibling failure",
                    engines.get(0).closed);
            assertTrue(control.engineStarted());

            releaseSiblingMerge.countDown();
            await("the sibling merges after the block releases", () -> {
                FleetJob job = control.jobs().get(sibling);
                return job != null && job.state() == FleetJob.State.MERGED;
            });
            assertFalse("a merged sibling keeps the engine", engines.get(0).closed);
        } finally {
            releaseSiblingMerge.countDown();
            control.close();
        }
    }

    // ---- AC 3: fleet_reset kills the leaked serve, retries, names pids ----

    /**
     * Worktree manager whose {@code remove} fails the first N calls like
     * {@code git worktree remove --force} does while a leaked process holds
     * the worktree files (Windows: "Permission denied", exit 255).
     */
    private static final class LockedWorktrees implements WorktreeManager {
        private final FakeWorktreeManager delegate = new FakeWorktreeManager();
        private final AtomicInteger calls = new AtomicInteger();
        volatile int failCalls;

        @Override
        public void claimProject(Path repoRoot, String project, String taskId) {
            delegate.claimProject(repoRoot, project, taskId);
        }

        @Override
        public com.opencode.ide.git.Worktree create(Path repoRoot, String taskId) {
            return delegate.create(repoRoot, taskId);
        }

        @Override
        public List<com.opencode.ide.git.Worktree> list(Path repoRoot) {
            return delegate.list(repoRoot);
        }

        @Override
        public java.util.Optional<com.opencode.ide.git.Worktree> find(Path repoRoot, String taskId) {
            return delegate.find(repoRoot, taskId);
        }

        @Override
        public void remove(Path repoRoot, String taskId, boolean force) {
            if (calls.incrementAndGet() <= failCalls) {
                throw new IllegalStateException("git worktree remove --force " + taskId
                        + " failed (exit 255): fatal: unable to unlink '" + taskId
                        + "/file.txt': Permission denied");
            }
            delegate.remove(repoRoot, taskId, force);
        }

        @Override
        public com.opencode.ide.git.MergeResult mergeBack(Path repoRoot, String taskId) {
            return delegate.mergeBack(repoRoot, taskId);
        }

        @Override
        public com.opencode.ide.git.WorktreeStatus status(Path repoRoot, String taskId) {
            return delegate.status(repoRoot, taskId);
        }

        @Override
        public void commitAll(Path repoRoot, String pathSpec, String message) {
            delegate.commitAll(repoRoot, pathSpec, message);
        }
    }

    private static com.google.gson.JsonObject resetArgs(String ticketId) {
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("project", PROJECT);
        args.addProperty("ticket_id", ticketId);
        return args;
    }

    /**
     * The reset recovery: the removal fails while the (fake) leaked serve
     * holds files - reset kills the engine's serve, lets the handles
     * release, retries the removal and completes, reporting the killed pid.
     */
    @Test(timeout = 30_000)
    public void resetKillsTheLeakedServeThenRemovesTheWorktree() throws Exception {
        String id = sprintTicket();
        store.setBlocked(PROJECT, id, "fleet: timeout after PT5S awaiting session ses_1", "fleet");
        LockedWorktrees locked = new LockedWorktrees();
        locked.failCalls = 1; // the first remove fails; the retry (post-kill) succeeds
        try (FleetControl control = control()) {
            control.engine(); // spawn the engine with its "serve"
            engines.get(0).pid = 62090L;
            FleetToolProvider provider = new FleetToolProvider(store.root(), control, locked);

            var result = provider.call("fleet_reset", resetArgs(id));

            assertFalse(result.text(), result.isError());
            assertTrue("the report names the killed serve pid: " + result.text(),
                    result.text().contains("pid 62090"));
            assertTrue("the removal was retried to success: " + result.text(),
                    result.text().contains("worktree+branch removed"));
            assertTrue("the leaked serve was killed", engines.get(0).closed);
            Task after = store.get(PROJECT, id);
            assertFalse("blocker cleared", after.blocked);
            assertEquals("sprint-backlog", after.status);
        }
    }

    /**
     * The naming branch: removal keeps failing AND the serve cannot be killed
     * (a sibling launch is in flight) - the error names the live serve pid as
     * the likely file holder instead of a bare git message.
     */
    @Test(timeout = 30_000)
    public void resetNamesTheLiveServePidWhenItCannotBeKilled() throws Exception {
        String residue = sprintTicket();
        store.setBlocked(PROJECT, residue, "fleet: timeout after PT5S awaiting session ses_1", "fleet");
        LockedWorktrees locked = new LockedWorktrees();
        locked.failCalls = Integer.MAX_VALUE; // removal never succeeds
        sessionCompletes();
        CountDownLatch siblingSessionCreated = new CountDownLatch(1);
        client.onSessionCreated = siblingSessionCreated::countDown;
        CountDownLatch releaseSibling = new CountDownLatch(1);
        client.blockOnSend = () -> {
            try {
                assertTrue(releaseSibling.await(FleetTestHarness.LATCH_WAIT.toSeconds(), TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (FleetControl control = control()) {
            String sibling = sprintTicket();
            control.dispatch(PROJECT, sibling, TIMEOUT); // keeps the engine busy
            assertTrue(siblingSessionCreated.await(FleetTestHarness.LATCH_WAIT.toSeconds(), TimeUnit.SECONDS));
            engines.get(0).pid = 62090L;
            FleetToolProvider provider = new FleetToolProvider(store.root(), control, locked);

            var result = provider.call("fleet_reset", resetArgs(residue));

            assertTrue(result.text(), result.isError());
            assertTrue("the error names the live serve pid: " + result.text(),
                    result.text().contains("pid 62090"));
            assertTrue("the error says why it was not killed: " + result.text(),
                    result.text().contains("in flight"));
            assertFalse("the busy engine was not killed", engines.get(0).closed);

            releaseSibling.countDown();
            await("the sibling settles after release", () -> {
                FleetJob job = control.jobs().get(sibling);
                return job != null && job.state() != FleetJob.State.RUNNING;
            });
        } finally {
            releaseSibling.countDown();
        }
    }

    /** The seam contract itself: no engine, no pid; the method never spawns. */
    @Test
    public void servePidQueriesNeverSpawnAnEngine() {
        try (FleetControl control = new FleetControl(store.root(), root -> {
            throw new AssertionError("pid queries must never spawn the engine");
        })) {
            assertNull(control.engineServePid());
            assertNull("recycle with no engine is a no-op", control.recycleEngineIfIdle("test"));
            assertFalse(control.engineStarted());
        }
    }
}
