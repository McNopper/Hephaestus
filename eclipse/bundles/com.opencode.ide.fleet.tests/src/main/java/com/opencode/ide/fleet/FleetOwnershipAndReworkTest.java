package com.opencode.ide.fleet;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskFileCodec;
import com.opencode.ide.tasks.TaskStore;

public class FleetOwnershipAndReworkTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void failedWorktreeCannotBeResetOrReusedByCollidingProject() throws Exception {
        Path repo = newRepo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = ticket(store, "a").id;
        assertEquals(id, ticket(store, "b").id);
        FakeClient client = new FakeClient();
        client.failSessionCreation = true;
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, FleetGit.defaultManager()), store);
        assertEquals(FleetJob.State.FAILED, fleet.launch("a", id, repo, Duration.ofSeconds(5)).state());
        Path work = FleetGit.worktreePath(repo, id).resolve("precious.txt");
        Files.writeString(work, "keep me");
        try (FleetControl control = control(store, fleet)) {
            JsonObject args = new JsonObject();
            args.addProperty("project", "b");
            args.addProperty("ticket_id", id);
            var result = new FleetToolProvider(store.root(), control).call("fleet_reset", args);
            assertTrue(result.text(), result.isError());
            assertTrue(result.text().contains("another project"));
            assertThrows(IllegalStateException.class, () -> control.dispatch("b", id, Duration.ofSeconds(5)));
            assertThrows(IllegalStateException.class, () -> fleet.launch("b", id, repo, Duration.ofSeconds(5)));
            assertEquals("keep me", Files.readString(work));
            assertEquals("sprint-backlog", store.get("b", id).status);
            assertFalse(store.get("b", id).blocked);
            args.addProperty("project", "a");
            assertFalse(new FleetToolProvider(store.root(), control).call("fleet_reset", args).isError());
            assertFalse(Files.exists(work));
        }
    }

    @Test
    public void legacyUnknownBranchOrWorktreeIsNeverAdoptedForReset() throws Exception {
        Path repo = newRepo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = ticket(store, "p").id;
        var manager = FleetGit.defaultManager();
        manager.create(repo, id); // deliberately no project binding
        assertThrows(IllegalStateException.class, () -> DispatchGuard.acquire(repo, "p", id));
        assertTrue(manager.find(repo, id).isPresent());
        manager.remove(repo, id, true);
        git(repo, "branch", FleetGit.branchFor(id));
        assertThrows(IllegalStateException.class, () -> DispatchGuard.acquire(repo, "p", id));
        assertFalse(Files.exists(FleetGit.fleetRoot(repo).resolve(id + ".project")));
    }

    @Test(timeout = 15000)
    public void controlAutoDispatchReopensStaleDoneAndRunsFleet() throws Exception {
        Path repo = newRepo();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        String id = stalePair(store);
        FakeClient client = new FakeClient();
        client.replyOnSend = "done";
        client.sessionType = "idle";
        CountDownLatch started = new CountDownLatch(1);
        client.onSessionCreated = () -> {
            try {
                Files.writeString(FleetGit.worktreePath(repo, id).resolve("reworked.txt"), "stale rework");
                started.countDown();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, FleetGit.defaultManager(), () -> { }), store);
        try (FleetControl control = control(store, fleet)) {
            control.startAuto("p", "S-1", 1, 0, true);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            control.stopAuto();
            control.close(); // drain accepted worker
            assertEquals(FleetJob.State.MERGED, fleet.jobs().get(id).state());
            assertEquals("in-review", store.get("p", id).status);
            assertEquals("stale rework", Files.readString(repo.resolve("reworked.txt")));
            assertTrue(store.get("p", id).comments.stream().anyMatch(c -> c.text().startsWith("fleet stale rework:")));
        }
    }

    @Test
    public void staleReworkRequiresOptInLiveReservationAndCurrentUpstream() throws Exception {
        TaskStore store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        String id = stalePair(store);
        Path repo = FleetControl.repoRootOf(store.root());
        TaskFleet fleet = new TaskFleet(new FleetRunner(new FakeClient(), new FakeWorktreeManager()), store);
        try (DispatchGuard guard = DispatchGuard.acquire(repo, "p", id)) {
            assertThrows(IllegalStateException.class, () -> fleet.launchAuto("p", id, repo, Duration.ofSeconds(1), guard, false));
            assertThrows(IllegalStateException.class, () -> fleet.launch("p", id, repo, Duration.ofSeconds(1)));
            store.update("p", store.get("p", id).epic, Map.of("status", "product-backlog"));
            assertThrows(IllegalStateException.class, () -> fleet.launchAuto("p", id, repo, Duration.ofSeconds(1), guard, true));
            assertEquals("done", store.get("p", id).status);
            guard.close();
            assertThrows(IllegalStateException.class, () -> fleet.launchAuto("p", id, repo, Duration.ofSeconds(1), guard, true));
        }
        // Fresh done with satisfied upstream must still be refused.
        store.update("p", store.get("p", id).epic, Map.of("status", "done"));
        store.update("p", id, Map.of("status", "done"));
        try (DispatchGuard guard = DispatchGuard.acquire(repo, "p", id)) {
            assertThrows(IllegalStateException.class, () -> fleet.launchAuto("p", id, repo, Duration.ofSeconds(1), guard, true));
            assertEquals("done", store.get("p", id).status);
        }
    }

    @Test
    public void upstreamEditDuringOwnershipCheckCannotReopenFromAnEarlierSnapshot() throws Exception {
        TaskStore store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        String id = stalePair(store);
        Path repo = FleetControl.repoRootOf(store.root());
        FakeWorktreeManager worktrees = new FakeWorktreeManager();
        String upstream = store.get("p", id).epic;
        worktrees.onClaimProject = () -> store.update("p", upstream, Map.of("status", "product-backlog"));
        TaskFleet fleet = new TaskFleet(new FleetRunner(new FakeClient(), worktrees), store);
        String before = TaskFileCodec.write(store.get("p", id));
        try (DispatchGuard guard = DispatchGuard.acquire(repo, "p", id)) {
            assertThrows(DispatchGuard.AdmissionDeferred.class,
                    () -> fleet.launchAuto("p", id, repo, Duration.ofSeconds(1), guard, true));
        }
        assertEquals(before, TaskFileCodec.write(store.get("p", id)));
        assertTrue(worktrees.createdTaskIds.isEmpty());
    }

    @Test(timeout = 15000)
    public void chatUsesCalibratedLowEstimateRatherThanFlatFiveCents() throws Exception {
        TaskStore store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        for (int i = 0; i < 3; i++) {
            Task sample = ticket(store, "p");
            store.addComment("p", sample.id, "fleet actuals: cost 0.01 USD", "fleet");
            store.update("p", sample.id, Map.of("status", "done"));
        }
        Task ready = ticket(store, "p");
        store.update("p", ready.id, Map.of("stage", "requirements"));
        FakeClient client = new FakeClient();
        client.replyOnSend = "done";
        client.sessionType = "idle";
        CountDownLatch started = new CountDownLatch(1);
        client.onSessionCreated = started::countDown;
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, new FakeWorktreeManager(), () -> { }), store);
        try (FleetControl control = control(store, fleet)) {
            control.startAuto("p", "S-1", 1, 0.045, false);
            assertTrue(".03 actual + .01 calibrated fits; .05 flat would refuse", started.await(5, TimeUnit.SECONDS));
            control.stopAuto();
        }
    }

    private String stalePair(TaskStore store) throws Exception {
        Task upstream = ticket(store, "p");
        Task child = ticket(store, "p");
        upstream.stage = "requirements";
        upstream.status = "done";
        upstream.updatedAt = Instant.parse("2025-02-01T00:00:00Z");
        child.stage = "system";
        child.status = "done";
        child.epic = upstream.id;
        child.updatedAt = Instant.parse("2025-01-01T00:00:00Z");
        Files.writeString(store.root().resolve("p/" + upstream.id + ".md"), TaskFileCodec.write(upstream));
        Files.writeString(store.root().resolve("p/" + child.id + ".md"), TaskFileCodec.write(child));
        assertEquals(StageReadiness.Kind.STALE, StageReadiness.evaluate(store.list("p", null, null, null, null)).get(child.id).kind());
        return child.id;
    }

    @Test(timeout = 20000)
    public void queuedDeferralRetriesWhenOnlyUpstreamReadinessChanges() throws Exception {
        TaskStore store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        String id = stalePair(store);
        String upstream = store.get("p", id).epic;
        String childBefore = TaskFileCodec.write(store.get("p", id));
        FakeClient client = new FakeClient();
        client.replyOnSend = "done";
        client.sessionType = "idle";
        TaskFleet fleet = new TaskFleet(new FleetRunner(client, new FakeWorktreeManager(), () -> { }), store);
        QueuedExecutor queue = new QueuedExecutor();
        try (FleetControl control = new FleetControl(store.root(), ignored -> new FleetControl.Engine() {
            public TaskFleet fleet() { return fleet; }
            public PermissionQueue permissions() { return new PermissionQueue(null); }
            public void close() { }
        }, queue)) {
            control.startAuto("p", "S-1", 1, 0, true);
            Runnable first = queue.take(); // submission accepted, worker not yet run
            store.update("p", upstream, Map.of("status", "product-backlog"));
            first.run(); // prepareAutoDispatch now defers asynchronously
            assertEquals(childBefore, TaskFileCodec.write(store.get("p", id)));
            assertTrue(fleet.jobs().isEmpty());
            assertTrue(DispatchGuard.runningIds(control.repoRoot()).isEmpty());
            store.update("p", upstream, Map.of("status", "done"));
            Runnable retry = queue.take(); // no restart or child edit, next scheduler wave
            assertEquals(childBefore, TaskFileCodec.write(store.get("p", id)));
            control.stopAuto();
            retry.run();
            assertEquals(FleetJob.State.MERGED, fleet.jobs().get(id).state());
            assertEquals("in-review", store.get("p", id).status);
            assertEquals(2, queue.submissions);
        }
    }

    private static final class QueuedExecutor extends java.util.concurrent.AbstractExecutorService {
        private final java.util.concurrent.BlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();
        private volatile boolean shutdown;
        private int submissions;

        @Override public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException();
            }
            submissions++;
            tasks.add(command);
        }

        Runnable take() throws Exception {
            Runnable task = tasks.poll(8, TimeUnit.SECONDS);
            assertNotNull("scheduler must enqueue a runnable worker", task);
            return task;
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown();
            var remaining = new java.util.ArrayList<Runnable>();
            tasks.drainTo(remaining);
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static Task ticket(TaskStore store, String project) {
        Task task = store.create(project, new TaskStore.CreateSpec("work", "", "task", "developer", "high",
                1, List.of(), List.of(), null, "T"));
        store.planSprint(project, "S-1", List.of(task.id), "goal");
        return store.get(project, task.id);
    }

    private static FleetControl control(TaskStore store, TaskFleet fleet) {
        return new FleetControl(store.root(), ignored -> new FleetControl.Engine() {
            public TaskFleet fleet() { return fleet; }
            public PermissionQueue permissions() { return new PermissionQueue(null); }
            public void close() { }
        });
    }

    private Path newRepo() throws Exception {
        Path repo = tmp.newFolder().toPath();
        git(repo, "init");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("seed"), "seed");
        git(repo, "add", "seed");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "seed");
        return repo;
    }

    private static void git(Path repo, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(output, 0, process.waitFor());
    }
}
