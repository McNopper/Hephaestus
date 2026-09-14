package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.board.model.FleetJobsModel;
import com.opencode.ide.board.model.BoardDispatch;
import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.DispatchGuard;
import com.opencode.ide.fleet.FleetRunner;
import com.opencode.ide.fleet.TaskFleet;
import com.opencode.ide.fleet.FleetJob;
import com.opencode.ide.fleet.Bootstrap;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.TaskFileCodec;

public class TaskFleetLauncherAdmissionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void reset() {
        TaskFleetLauncher.resetForTests();
        FleetJobsModel.getDefault().remove("T1");
    }

    @Test
    public void rejectedSubmissionReleasesReservationAndPublishesFailure() throws Exception {
        Path repo = repo();
        TaskFleetLauncher launcher = rejectingLauncher(repo);
        FleetJobHandle result = launcher.launch("p", "T1");
        assertTrue(result.failed());
        assertTrue(result.detail().contains("cannot schedule launch"));
        assertFalse(DispatchGuard.runningIds(repo).contains("T1"));
        assertEquals(FleetJobHandle.State.FAILED, FleetJobsModel.getDefault().jobs().stream()
                .filter(job -> job.taskId().equals("T1")).findFirst().orElseThrow().state());
        try (DispatchGuard reacquired = DispatchGuard.acquire(repo, "T1")) {
            assertTrue(DispatchGuard.runningIds(repo).contains("T1"));
        }
    }

    @Test
    public void peerReservationPropagatesRetryableDeferralWithoutReleasingPeer() throws Exception {
        Path repo = repo();
        try (DispatchGuard peer = DispatchGuard.acquire(repo, "T1")) {
            assertThrows(DispatchGuard.AdmissionDeferred.class, () -> rejectingLauncher(repo).launch("p", "T1"));
            assertTrue(DispatchGuard.runningIds(repo).contains("T1"));
        }
    }

    @Test
    public void persistentProjectBindingSurvivesRejectedSubmission() throws Exception {
        Path repo = repo();
        assertTrue(rejectingLauncher(repo).launch("owner", "T1").failed());
        var collision = rejectingLauncher(repo).launch("other", "T1");
        assertTrue(collision.failed());
        assertTrue(collision.detail(), collision.detail().contains("another project"));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
    }

    @Test
    public void autoWorkerReopensOnlyStaleDoneWithOptInAndLiveReservation() throws Exception {
        Path repo = repo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskFleetLauncher launcher = isolatedLauncher(store, queued);

        launcher.launch("p", id);
        queued.get().run();
        assertEquals("done", store.get("p", id).status);
        launcher.launchAuto("p", id, false);
        queued.get().run();
        assertEquals("done", store.get("p", id).status);

        launcher.launchAuto("p", id, true);
        assertTrue(DispatchGuard.runningIds(repo).contains(id));
        queued.get().run();
        // The fake fails at commit, AFTER real ownership and stale-rework transition.
        assertTrue(store.get("p", id).comments.stream().anyMatch(c -> c.text().startsWith("fleet stale rework:")));
        assertFalse("done".equals(store.get("p", id).status));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
    }

    @Test
    public void queuedAutoWorkerRechecksUpstreamAndRejectsFreshDone() throws Exception {
        Path repo = repo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskFleetLauncher launcher = isolatedLauncher(store, queued);
        launcher.launchAuto("p", id, true);
        store.update("p", store.get("p", id).epic, Map.of("status", "product-backlog"));
        queued.get().run();
        assertEquals("done", store.get("p", id).status);
        store.update("p", store.get("p", id).epic, Map.of("status", "done"));
        store.update("p", id, Map.of("status", "done"));
        launcher.launch("p", id);
        queued.get().run();
        launcher.launchAuto("p", id, true);
        queued.get().run();
        assertEquals("done", store.get("p", id).status);
        assertFalse(store.get("p", id).comments.stream().anyMatch(c -> c.text().startsWith("fleet stale rework:")));
    }

    @Test
    public void queuedUpstreamOnlyDeferralReleasesSchedulerHoldForUnchangedChild() throws Exception {
        Path repo = repo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        String upstream = store.get("p", id).epic;
        store.update("p", upstream, Map.of("sprint", "upstream-sprint"));
        Path childFile = store.root().resolve("p/" + id + ".md");
        String childBefore = Files.readString(childFile);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        var scheduler = new BoardDispatch(store.root(), "p", "s", isolatedLauncher(store, queued), () -> false)
                .scheduler(() -> AutoDispatch.of(1, 0, true));
        assertEquals(List.of(id), scheduler.tick().launch());
        Runnable first = queued.get();
        store.update("p", upstream, Map.of("status", "product-backlog"));
        first.run(); // deferred by real TaskFleet preparation, after the scheduler accepted submission
        assertEquals(childBefore, Files.readString(childFile));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        assertTrue(scheduler.tick().launch().isEmpty()); // upstream still not ready
        store.update("p", upstream, Map.of("status", "done"));
        assertEquals(childBefore, Files.readString(childFile));
        assertEquals("upstream-only recovery must release the accepted revision and echo hold",
                List.of(id), scheduler.tick().launch());
        queued.get().run();
        assertTrue(store.get("p", id).comments.stream().anyMatch(c -> c.text().startsWith("fleet stale rework:")));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        scheduler.stop();
    }

    @Test
    public void ordinaryQueuedFailureDoesNotReleaseAcceptedRevision() throws Exception {
        Path repo = repo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskFleetLauncher launcher = new TaskFleetLauncher(() -> null, () -> null, store::root,
                () -> null, queued::set, root -> { throw new IllegalStateException("engine unavailable"); });
        var scheduler = new BoardDispatch(store.root(), "p", "s", launcher, () -> false)
                .scheduler(() -> AutoDispatch.of(1, 0, true));
        assertEquals(List.of(id), scheduler.tick().launch());
        queued.get().run();
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        assertEquals("done", store.get("p", id).status);
        assertTrue("ordinary failure must not retry unchanged work", scheduler.tick().launch().isEmpty());
        scheduler.stop();
    }

    @Test
    public void queuedAutoWorkerRejectsLostReservationWithoutReopeningDone() throws Exception {
        Path repo = repo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        isolatedLauncher(store, queued).launchAuto("p", id, true);
        Files.delete(FleetGit.fleetRoot(repo).resolve(id + ".dispatch"));
        queued.get().run();
        assertEquals("done", store.get("p", id).status);
        assertTrue(FleetJobsModel.getDefault().jobs().stream().anyMatch(job -> job.taskId().equals(id)
                && job.failed() && job.detail().contains("reservation")));
    }

    @Test
    public void extendedEngineSeamCarriesBootstrapStaleFlagAndOwnedGuard() throws Exception {
        Path repo = repo();
        Bootstrap bootstrap = Bootstrap.of("build", "prepare");
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Bootstrap> seen = new AtomicReference<>();
        TaskFleetLauncher.useEngineLaunchForTests(new TaskFleetLauncher.EngineLaunch() {
            @Override
            public FleetJob launch(TaskFleet fleet, String project, String id, Path root,
                    Duration timeout, Bootstrap supplied) {
                throw new AssertionError("auto must not use manual engine call");
            }

            @Override
            public FleetJob launchAuto(TaskFleet fleet, String project, String id, Path root,
                    Duration timeout, DispatchGuard guard, boolean includeStale, Bootstrap supplied) {
                assertTrue(includeStale);
                guard.withOwnership(root, project, id, () -> { seen.set(supplied); return null; });
                return new FleetJob(id, null, null, FleetJob.State.MERGED, "test");
            }
        });
        TaskFleetLauncher launcher = new TaskFleetLauncher(() -> null, () -> null,
                () -> repo.resolve(".opencode/tasks"), () -> bootstrap, queued::set, root -> null);
        launcher.launchAuto("p", "T1", true);
        queued.get().run();
        assertEquals(bootstrap, seen.get());
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
    }

    private static TaskFleetLauncher isolatedLauncher(TaskStore store, AtomicReference<Runnable> queued) {
        OpencodeClient client = (OpencodeClient) Proxy.newProxyInstance(OpencodeClient.class.getClassLoader(),
                new Class<?>[] { OpencodeClient.class }, (proxy, method, args) -> {
                    throw new AssertionError("no server call expected: " + method.getName());
                });
        WorktreeManager worktrees = (WorktreeManager) Proxy.newProxyInstance(WorktreeManager.class.getClassLoader(),
                new Class<?>[] { WorktreeManager.class }, (proxy, method, args) -> {
                    if (method.getName().equals("claimProject")) {
                        // Faithfully retain persistent project ownership, even though git work is faked.
                        FleetGit.defaultManager().claimProject((Path) args[0], (String) args[1], (String) args[2]);
                        return null;
                    }
                    if (method.getName().equals("commitAll")) {
                        throw new IllegalStateException("test stops before git/session work");
                    }
                    throw new AssertionError("unexpected worktree operation: " + method.getName());
                });
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, worktrees), store);
        return new TaskFleetLauncher(() -> client, () -> worktrees, store::root, () -> null,
                queued::set, root -> fleet);
    }

    private static String stalePair(TaskStore store) throws Exception {
        var upstream = store.create("p", TaskStore.CreateSpec.of("upstream"), "requirements");
        var child = store.create("p", TaskStore.CreateSpec.of("child"), "system");
        store.planSprint("p", "s", List.of(upstream.id, child.id), "test");
        upstream = store.get("p", upstream.id);
        child = store.get("p", child.id);
        upstream.status = "done";
        upstream.updatedAt = Instant.parse("2025-02-01T00:00:00Z");
        child.status = "done";
        child.epic = upstream.id;
        child.updatedAt = Instant.parse("2025-01-01T00:00:00Z");
        Files.writeString(store.root().resolve("p/" + upstream.id + ".md"), TaskFileCodec.write(upstream));
        Files.writeString(store.root().resolve("p/" + child.id + ".md"), TaskFileCodec.write(child));
        return child.id;
    }

    private Path repo() throws Exception {
        Path repo = tmp.newFolder().toPath();
        Files.createDirectory(repo.resolve(".git"));
        return repo;
    }

    private static TaskFleetLauncher rejectingLauncher(Path repo) {
        return new TaskFleetLauncher(() -> { throw new AssertionError("client must not be acquired"); },
                () -> null, () -> repo.resolve(".opencode/tasks"), () -> null,
                task -> { throw new RejectedExecutionException("executor stopped"); });
    }
}
