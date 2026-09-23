package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.fleet.dispatch.DispatchScheduler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for the SWT-free {@link DispatchScheduler}: ticks launch
 * READY tickets per the policy, the sprint supplier is the scope
 * guardrail (only its ids are ever launched), running jobs count against
 * the cap, a launched-then-running ticket is never relaunched, the launch
 * hold shields against a lagging running set (and expires), failing
 * launches and failing suppliers never kill the loop, null suppliers
 * yield an empty plan, and the start/stop lifecycle works on an injected
 * executor (no sleeping).
 */
public class DispatchSchedulerTest {

    private static final AutoDispatch POLICY = AutoDispatch.of(4, 0, true);

    /** Controllable clock (UTC, start at EPOCH) so the launch hold is deterministic. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.EPOCH;

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Captures the periodic command instead of scheduling it; tests run it
     * by hand. Every non-lifecycle method is unsupported — the scheduler
     * only uses scheduleAtFixedRate/shutdown (the unused future is null).
     */
    private static final class ManualExecutor implements ScheduledExecutorService {

        final List<Runnable> ticks = new CopyOnWriteArrayList<>();
        volatile long recordedPeriodMillis = -1;
        volatile boolean shutdown;

        void runOneTick() {
            ticks.get(0).run();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("shutdown");
            }
            recordedPeriodMillis = unit.toMillis(period);
            ticks.add(command);
            return null;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <T> ScheduledFuture<T> schedule(Callable<T> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks,
                long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    /** Mutable data/action seams recording everything the scheduler does. */
    private static final class Seams {

        final MutableClock clock = new MutableClock();
        final List<String> launches = new CopyOnWriteArrayList<>();
        List<Task> sprint = List.of();
        CostOverview cost = CostOverview.empty();
        Set<String> running = Set.of();
        boolean failSprintSupplier;
        Consumer<String> onLaunch = id -> {
        };

        DispatchScheduler scheduler(AutoDispatch policy) {
            return new DispatchScheduler(policy, () -> {
                if (failSprintSupplier) {
                    throw new IllegalStateException("store gone");
                }
                return sprint;
            }, () -> cost, () -> running, id -> {
                launches.add(id);
                onLaunch.accept(id);
            }, clock);
        }
    }

    /** A sprint-backlog ticket at 'requirements' (no upstream) — READY. */
    private static Task ready(String id) {
        Task t = new Task();
        t.id = id;
        t.stage = "requirements";
        t.status = "sprint-backlog";
        t.createdAt = Instant.EPOCH;
        t.updatedAt = Instant.EPOCH;
        return t;
    }

    /** A sprint-backlog ticket at 'design' without upstream evidence — WAIT_UPSTREAM. */
    private static Task waiting(String id) {
        Task t = ready(id);
        t.stage = "design";
        return t;
    }

    /** A blocked sprint-backlog ticket — BLOCKED. */
    private static Task blocked(String id) {
        Task t = ready(id);
        t.blocked = true;
        t.blocker = "no server";
        return t;
    }

    private static String reasonOf(AutoDispatch.DispatchPlan plan, String id) {
        return plan.skipped().stream()
                .filter(skip -> id.equals(skip.id()))
                .map(AutoDispatch.Skip::reason)
                .findFirst()
                .orElse("");
    }

    // ------------------------------------------------------------------
    // tick behavior
    // ------------------------------------------------------------------

    @Test
    public void tickLaunchesReadyTicketsPerPolicy() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-2"), ready("T-1"), waiting("T-3"), blocked("T-4"));
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        AutoDispatch.DispatchPlan plan = scheduler.tick();
        assertEquals("only READY launches, ids in natural order", List.of("T-1", "T-2"), plan.launch());
        assertEquals(List.of("T-1", "T-2"), seams.launches);
        assertEquals(2, plan.skipped().size());
        assertTrue(reasonOf(plan, "T-3"), reasonOf(plan, "T-3").contains("WAIT_UPSTREAM"));
        assertTrue(reasonOf(plan, "T-4"), reasonOf(plan, "T-4").contains("BLOCKED"));
    }

    @Test
    public void onlySprintSupplierTasksAreEverLaunched() {
        Seams seams = new Seams();
        // T-out exists in the project but is out of the sprint: the supplier
        // never hands it over, so no tick can ever admit it
        seams.sprint = List.of(ready("T-in"));
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        for (int i = 0; i < 3; i++) {
            scheduler.tick();
        }
        assertTrue("the sprint scope is the supplier's contract",
                seams.launches.stream().allMatch("T-in"::equals));
        assertEquals("the launch hold keeps even the in-sprint id from re-launching",
                List.of("T-in"), seams.launches);
    }

    @Test
    public void runningJobsCountAgainstTheCap() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"), ready("T-2"));
        seams.running = Set.of("T-9");
        DispatchScheduler scheduler = seams.scheduler(AutoDispatch.of(2, 0, true));
        AutoDispatch.DispatchPlan plan = scheduler.tick();
        assertEquals("one of two slots already taken by a job outside the sprint",
                List.of("T-1"), plan.launch());
        assertEquals(List.of("T-1"), seams.launches);
    }

    @Test
    public void launchedThenRunningTicketIsNotRelaunched() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"));
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.tick();
        assertEquals(List.of("T-1"), seams.launches);
        seams.running = Set.of("T-1");
        seams.clock.advance(DispatchScheduler.LAUNCH_HOLD.plusSeconds(1)); // hold expired: the running set must carry the exclusion
        AutoDispatch.DispatchPlan second = scheduler.tick();
        assertEquals(List.of(), second.launch());
        assertTrue(reasonOf(second, "T-1"), reasonOf(second, "T-1").contains("waiting for ticket input"));
        assertEquals(List.of("T-1"), seams.launches);
    }

    @Test
    public void launchHoldShieldsAgainstLaggingRunningSet() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"));
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.tick();
        AutoDispatch.DispatchPlan second = scheduler.tick(); // clock unmoved: within the hold
        assertEquals(List.of(), second.launch());
        assertTrue(reasonOf(second, "T-1"), reasonOf(second, "T-1").contains("waiting for ticket input"));
        assertEquals(List.of("T-1"), seams.launches);
    }

    @Test
    public void expiredHoldRequiresChangedTicketInputBeforeRelaunch() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"));
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.tick();
        seams.clock.advance(DispatchScheduler.LAUNCH_HOLD.plus(Duration.ofSeconds(1)));
        scheduler.tick();
        assertEquals("unchanged inputs must not produce repeated launches", List.of("T-1"), seams.launches);
        seams.sprint.get(0).updatedAt = Instant.EPOCH.plusSeconds(60);
        scheduler.tick();
        assertEquals("new ticket input is eligible once the reservation hold expires",
                List.of("T-1", "T-1"), seams.launches);
    }

    @Test
    public void failingLaunchDoesNotKillTheTickOrLaterTicks() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"), ready("T-2"));
        seams.onLaunch = id -> {
            if ("T-1".equals(id)) {
                throw new IllegalStateException("boom");
            }
        };
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        AutoDispatch.DispatchPlan plan = scheduler.tick();
        assertEquals("only accepted launches are reported", List.of("T-2"), plan.launch());
        assertTrue(reasonOf(plan, "T-1").contains("boom"));
        assertEquals("T-2 launches even though T-1's launch threw", List.of("T-1", "T-2"), seams.launches);
        seams.sprint = List.of(ready("T-1"), ready("T-2"), ready("T-3"));
        seams.running = Set.of("T-1", "T-2");
        AutoDispatch.DispatchPlan next = scheduler.tick();
        assertEquals("the loop keeps serving after a failed launch", List.of("T-3"), next.launch());
    }

    @Test
    public void failingSupplierYieldsEmptyPlanAndNextTickProceeds() {
        Seams seams = new Seams();
        seams.failSprintSupplier = true;
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        assertEquals(new AutoDispatch.DispatchPlan(List.of(), List.of()), scheduler.tick());
        assertTrue(seams.launches.isEmpty());
        seams.failSprintSupplier = false;
        seams.sprint = List.of(ready("T-1"));
        assertEquals(List.of("T-1"), scheduler.tick().launch());
    }

    @Test
    public void nullSuppliersYieldAnEmptyPlan() {
        Seams seams = new Seams();
        DispatchScheduler scheduler = new DispatchScheduler(POLICY, () -> null, () -> null,
                () -> null, seams.launches::add, null);
        assertEquals(new AutoDispatch.DispatchPlan(List.of(), List.of()), scheduler.tick());
        assertTrue(seams.launches.isEmpty());
    }

    // ------------------------------------------------------------------
    // start/stop lifecycle
    // ------------------------------------------------------------------

    @Test
    public void startSchedulesRecurringTicksOnTheInjectedExecutor() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"));
        ManualExecutor executor = new ManualExecutor();
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.start(Duration.ofSeconds(30), executor);
        assertTrue(scheduler.isRunning());
        assertEquals(30_000L, executor.recordedPeriodMillis);
        executor.runOneTick();
        assertEquals(List.of("T-1"), seams.launches);
        scheduler.stop();
    }

    @Test
    public void stopPreventsFurtherTicksAndIsIdempotent() {
        Seams seams = new Seams();
        seams.sprint = List.of(ready("T-1"));
        ManualExecutor executor = new ManualExecutor();
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.start(Duration.ofSeconds(30), executor);
        scheduler.stop();
        assertFalse(scheduler.isRunning());
        assertTrue(executor.shutdown);
        executor.runOneTick(); // a late/queued tick must not launch anything
        assertTrue(seams.launches.isEmpty());
        scheduler.stop(); // idempotent
        assertFalse(scheduler.isRunning());
    }

    @Test
    public void startRejectsNonPositivePeriods() {
        DispatchScheduler scheduler = new Seams().scheduler(POLICY);
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.start(Duration.ZERO, new ManualExecutor()));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.start(Duration.ofSeconds(-1), new ManualExecutor()));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.start(null, new ManualExecutor()));
    }

    @Test
    public void restartReplacesThePreviousLoop() {
        Seams seams = new Seams();
        ManualExecutor first = new ManualExecutor();
        ManualExecutor second = new ManualExecutor();
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.start(Duration.ofSeconds(30), first);
        scheduler.start(Duration.ofSeconds(5), second);
        assertTrue("ownership transferred: the previous executor is shut down", first.shutdown);
        assertFalse(second.shutdown);
        assertEquals(5_000L, second.recordedPeriodMillis);
        assertTrue(scheduler.isRunning());
        scheduler.stop();
    }

    @Test
    public void defaultExecutorStartAndStopSmoke() {
        Seams seams = new Seams();
        DispatchScheduler scheduler = seams.scheduler(POLICY);
        scheduler.start(Duration.ofMillis(10));
        assertTrue(scheduler.isRunning());
        scheduler.stop();
        assertFalse(scheduler.isRunning());
    }
}
