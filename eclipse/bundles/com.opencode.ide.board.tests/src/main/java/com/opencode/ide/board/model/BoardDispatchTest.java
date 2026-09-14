package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.FleetLauncher;
import com.opencode.ide.board.fleet.TaskFleetLauncher;
import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.DispatchGuard;
import com.opencode.ide.tasks.TaskStore;

public class BoardDispatchTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void admissionRechecksReadinessAfterEarlierLaunchChangesStore() throws Exception {
        TaskStore store = store();
        String first = ready(store, "first");
        String second = ready(store, "second");
        List<String> calls = new ArrayList<>();
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", automatic((project, id) -> {
            calls.add(id);
            store.setBlocked("p", second, "upstream changed", "test");
            return running(id);
        }), () -> false);
        var scheduler = dispatch.scheduler(() -> AutoDispatch.of(4, 0, true));
        var plan = scheduler.tick();
        assertEquals(List.of(first), plan.launch());
        assertEquals(List.of(first), calls);
        assertTrue(plan.skipped().stream().anyMatch(skip -> skip.id().equals(second)
                && skip.reason().contains("BLOCKED")));
    }

    @Test
    public void admissionRechecksCostAfterEarlierLaunchRecordsSpend() throws Exception {
        TaskStore store = store();
        String first = ready(store, "first");
        String second = ready(store, "second");
        List<String> calls = new ArrayList<>();
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", automatic((project, id) -> {
            calls.add(id);
            store.addComment("p", first, "fleet actuals: cost 10 USD", "fleet");
            return running(id);
        }), () -> false);
        var plan = dispatch.scheduler(() -> AutoDispatch.of(4, 10, true)).tick();
        assertEquals(List.of(first), plan.launch());
        assertEquals(List.of(first), calls);
        assertTrue(plan.skipped().stream().anyMatch(skip -> skip.id().equals(second)
                && skip.reason().contains("cost budget")));
    }

    @Test
    public void schedulerRecalibratesAfterNewCostSamplesArrive() throws Exception {
        TaskStore store = store();
        List<String> calls = new ArrayList<>();
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s",
                automatic((project, id) -> { calls.add(id); return running(id); }), () -> false);
        var scheduler = dispatch.scheduler(() -> AutoDispatch.of(4, 0.045, true));
        assertTrue(scheduler.tick().launch().isEmpty());
        for (int i = 0; i < 3; i++) {
            String sample = ready(store, "sample");
            store.addComment("p", sample, "fleet actuals: cost 0.01 USD", "fleet");
            store.update("p", sample, java.util.Map.of("status", "done"));
        }
        String id = ready(store, "new work");
        assertEquals(".03 actual + .01 calibrated fits; the initial .05 estimate would refuse",
                List.of(id), scheduler.tick().launch());
        assertEquals(List.of(id), calls);
    }

    @Test
    public void peerDeferralRetriesUnchangedInputAndCancellationIsNotSuccess() throws Exception {
        TaskStore store = store();
        String id = ready(store, "ready");
        AtomicBoolean peer = new AtomicBoolean(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", automatic((project, ticket) -> {
            if (peer.getAndSet(false)) {
                throw new DispatchGuard.AdmissionDeferred("peer reservation");
            }
            return running(ticket);
        }), cancelled::get);
        var scheduler = dispatch.scheduler(() -> AutoDispatch.of(4, 0, true));
        assertTrue(scheduler.tick().launch().isEmpty());
        assertEquals(List.of(id), scheduler.tick().launch());
        cancelled.set(true);
        assertThrows(DispatchGuard.AdmissionDeferred.class, () -> dispatch.admit(id, AutoDispatch.of(4, 0, true)));
        scheduler.requestStop();
        assertTrue(scheduler.tick().launch().isEmpty());
        scheduler.stop();
    }

    @Test
    public void movedSprintIsRejectedUnderAdmission() throws Exception {
        TaskStore store = store();
        String id = ready(store, "ready");
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", (project, ticket) -> {
            throw new AssertionError("must not launch a ticket from another sprint");
        }, () -> false);
        store.planSprint("p", "other", List.of(id), "moved");
        assertThrows(DispatchGuard.AdmissionDeferred.class, () -> dispatch.admit(id, AutoDispatch.of(4, 0, true)));
    }

    @Test
    public void cancellationWhileAdmissionIsHeldPreventsQueuedLaunch() throws Exception {
        TaskStore store = store();
        String id = ready(store, "ready");
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch submitted = new CountDownLatch(1);
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", (project, ticket) -> {
            throw new AssertionError("cancelled admission must not launch");
        }, cancelled::get);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = DispatchGuard.exclusive(store.root().getParent().getParent(), () -> {
                var result = worker.submit(() -> {
                    submitted.countDown();
                    return assertThrows(DispatchGuard.AdmissionDeferred.class,
                            () -> dispatch.admit(id, AutoDispatch.of(4, 0, true)));
                });
                try {
                    assertTrue(submitted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                cancelled.set(true);
                return result;
            });
            assertTrue(pending.get(5, TimeUnit.SECONDS).getMessage().contains("stopped"));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private TaskStore store() throws Exception {
        Path repo = tmp.newFolder().toPath();
        Files.createDirectory(repo.resolve(".git"));
        return new TaskStore(repo.resolve(".opencode/tasks"));
    }

    @Test
    public void manualLaunchCancelledWhileWaitingNeverReservesOrSubmitsWork() throws Exception {
        TaskStore store = store();
        Path repo = store.root().getParent().getParent();
        String id = ready(store, "manual");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Runnable> submittedWork = new AtomicReference<>();
        AtomicReference<Thread> launchThread = new AtomicReference<>();
        TaskFleetLauncher launcher = new TaskFleetLauncher(
                () -> { throw new AssertionError("must not acquire client"); },
                () -> null, store::root, () -> null, submittedWork::set);
        BoardDispatch dispatch = new BoardDispatch(store.root(), "p", "s", launcher, cancelled::get);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = DispatchGuard.exclusive(repo, () -> {
                var result = worker.submit(() -> {
                    launchThread.set(Thread.currentThread());
                    return assertThrows(DispatchGuard.AdmissionDeferred.class, () -> dispatch.launch(id));
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // Wait until launch is actually parked behind our admission lock,
                // rather than cancelling before the worker has entered launch().
                while ((launchThread.get() == null || launchThread.get().getState() != Thread.State.WAITING)
                        && !result.isDone() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertTrue("manual launch must be waiting for admission", !result.isDone()
                        && launchThread.get() != null && launchThread.get().getState() == Thread.State.WAITING);
                cancelled.set(true);
                return result;
            });
            assertTrue(pending.get(5, TimeUnit.SECONDS).getMessage().contains("stopped"));
            assertEquals(null, submittedWork.get());
            assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            TaskFleetLauncher.resetForTests();
        }
    }

    private static String ready(TaskStore store, String title) {
        String id = store.create("p", TaskStore.CreateSpec.of(title), "requirements").id;
        store.planSprint("p", "s", List.of(id), "test");
        return id;
    }

    private static FleetJobHandle running(String id) {
        return new FleetJobHandle(id, null, null, FleetJobHandle.State.RUNNING, "queued");
    }

    /** Test adapter explicitly supports auto; production defaults must remain fail closed. */
    private static FleetLauncher automatic(FleetLauncher delegate) {
        return new FleetLauncher() {
            @Override
            public FleetJobHandle launch(String project, String id) {
                return delegate.launch(project, id);
            }

            @Override
            public FleetJobHandle launchAuto(String project, String id, boolean includeStale) {
                assertTrue(includeStale);
                return delegate.launch(project, id);
            }

            @Override
            public FleetJobHandle launchAuto(String project, String id, boolean includeStale,
                    com.opencode.ide.fleet.dispatch.DispatchScheduler.LaunchAttempt attempt) {
                return launchAuto(project, id, includeStale); // this test adapter executes synchronously
            }
        };
    }

    @Test
    public void queuedLaunchKeepsCapturedProjectAndCancellationPreventsNextLaunch() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        BoardDispatch dispatch = new BoardDispatch(store().root(), "selected", "s1",
                (project, id) -> {
                    calls.add(project + "/" + id);
                    return new FleetJobHandle(id, null, null, FleetJobHandle.State.RUNNING, "queued");
                }, cancelled::get);
        dispatch.launch("T1");
        cancelled.set(true);
        assertThrows(IllegalStateException.class, () -> dispatch.launch("T2"));
        assertEquals(List.of("selected/T1"), calls);
    }

    @Test
    public void refusalIsPropagatedSoSchedulerDoesNotReportSuccess() throws Exception {
        BoardDispatch dispatch = new BoardDispatch(store().root(), "p", "s",
                (project, id) -> new FleetJobHandle(id, null, null, FleetJobHandle.State.FAILED, "reserved"),
                () -> false);
        assertTrue(assertThrows(IllegalStateException.class, () -> dispatch.launch("T1"))
                .getMessage().contains("reserved"));
        assertEquals("Launched 1, skipped 1.\nT2 — reserved", BoardDispatch.summary(
                new AutoDispatch.DispatchPlan(List.of("T1"), List.of(new AutoDispatch.Skip("T2", "reserved")))));
    }
}
