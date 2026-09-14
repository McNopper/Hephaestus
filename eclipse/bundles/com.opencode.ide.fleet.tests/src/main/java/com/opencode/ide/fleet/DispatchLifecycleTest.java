package com.opencode.ide.fleet;

import static org.junit.Assert.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.fleet.dispatch.DispatchScheduler;
import com.opencode.ide.tasks.Task;

public class DispatchLifecycleTest {
    @Test(timeout = 10000)
    public void stopDuringSnapshotPreventsAdmissionAndDirectTicks() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        Task ticket = ready("T-1");
        DispatchScheduler scheduler = new DispatchScheduler(AutoDispatch.of(1, 0, false), () -> {
            reading.countDown();
            await(resume);
            return List.of(ticket);
        }, CostOverview::empty, Set::of, id -> launches.incrementAndGet(), null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var tick = executor.submit(scheduler::tick);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            scheduler.stop();
            resume.countDown();
            assertTrue(tick.get(5, TimeUnit.SECONDS).launch().isEmpty());
            assertTrue(scheduler.tick().launch().isEmpty());
            assertEquals(0, launches.get());
        } finally {
            resume.countDown();
            scheduler.stop();
        }
    }

    @Test
    public void oldScheduledCallbackCannotReviveAfterRestartAndRejectedStartIsStopped() {
        AtomicInteger launches = new AtomicInteger();
        DispatchScheduler scheduler = scheduler(List.of(ready("T-1")), id -> launches.incrementAndGet());
        ManualExecutor first = new ManualExecutor();
        ManualExecutor second = new ManualExecutor();
        try {
            scheduler.start(Duration.ofSeconds(1), first);
            scheduler.start(Duration.ofSeconds(1), second);
            first.command.run();
            assertEquals(0, launches.get());
            second.command.run();
            assertEquals(1, launches.get());
            var rejected = new ScheduledThreadPoolExecutor(1);
            rejected.shutdown();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scheduler.start(Duration.ofSeconds(1), rejected));
            assertFalse(scheduler.isRunning());
            assertTrue(scheduler.tick().launch().isEmpty());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    public void failingCandidateDoesNotRetryUnchangedOrStarveOtherWork() {
        Task first = ready("T-1");
        AtomicInteger attempts = new AtomicInteger();
        DispatchScheduler scheduler = scheduler(List.of(first, ready("T-2")), id -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("unavailable");
        });
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        assertEquals(2, attempts.get());
        first.updatedAt = Instant.EPOCH.plusSeconds(1);
        scheduler.tick();
        assertEquals(3, attempts.get());
    }

    @Test
    public void echoHoldConsumesCapacityBeforeSelectingAnotherTicket() {
        AtomicInteger attempts = new AtomicInteger();
        DispatchScheduler scheduler = scheduler(List.of(ready("T-1"), ready("T-2")),
                id -> attempts.incrementAndGet());
        assertEquals(List.of("T-1"), scheduler.tick().launch());
        assertTrue(scheduler.tick().launch().isEmpty());
        assertEquals(1, attempts.get());
    }

    @Test
    public void peerAdmissionDeferralReplansRatherThanSuppressingTheTicket() {
        AtomicInteger attempts = new AtomicInteger();
        DispatchScheduler scheduler = scheduler(List.of(ready("T-1")), id -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DispatchGuard.AdmissionDeferred("peer took last slot");
            }
        });
        assertTrue(scheduler.tick().launch().isEmpty());
        assertEquals(List.of("T-1"), scheduler.tick().launch());
        assertEquals(2, attempts.get());
    }

    @Test(timeout = 10000)
    public void asyncDeferralBeforeCallbackReturnCannotBeOverwritten() {
        AtomicInteger submissions = new AtomicInteger();
        try (var worker = Executors.newSingleThreadExecutor()) {
            DispatchScheduler scheduler = DispatchScheduler.withFeedback(AutoDispatch.of(1, 0, false),
                    () -> List.of(ready("T-1")), () -> List.of(ready("T-1")), CostOverview::empty, Set::of,
                    (id, attempt) -> {
                        if (submissions.incrementAndGet() == 1) {
                            try {
                                worker.submit(attempt::deferred).get(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        }
                    }, null);
            assertTrue(scheduler.tick().launch().isEmpty());
            assertEquals(List.of("T-1"), scheduler.tick().launch());
            assertTrue("successful acceptance still suppresses unchanged child", scheduler.tick().launch().isEmpty());
            assertEquals(2, submissions.get());
        }
    }

    @Test
    public void oldFeedbackCannotClearNewAttemptOrRestartedGeneration() {
        var tokens = new java.util.ArrayList<DispatchScheduler.LaunchAttempt>();
        DispatchScheduler scheduler = DispatchScheduler.withFeedback(AutoDispatch.of(1, 0, false),
                () -> List.of(ready("T-1")), () -> List.of(ready("T-1")), CostOverview::empty, Set::of,
                (id, attempt) -> tokens.add(attempt), null);
        assertEquals(List.of("T-1"), scheduler.tick().launch());
        tokens.getFirst().deferred();
        assertEquals(List.of("T-1"), scheduler.tick().launch());
        tokens.getFirst().deferred();
        assertTrue(scheduler.tick().launch().isEmpty());
        try {
            scheduler.start(Duration.ofSeconds(1), new ManualExecutor());
            assertEquals(List.of("T-1"), scheduler.tick().launch());
            tokens.get(1).deferred();
            assertTrue(scheduler.tick().launch().isEmpty());
            assertEquals(3, tokens.size());
        } finally {
            scheduler.stop();
        }
    }

    @Test(timeout = 10000)
    public void invalidationDoesNotWaitForBlockedCallbackAndStopsRemainingWave() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        DispatchScheduler scheduler = new DispatchScheduler(AutoDispatch.of(2, 0, false),
                () -> List.of(ready("T-1"), ready("T-2")), CostOverview::empty, Set::of, id -> {
                    entered.countDown();
                    await(resume);
                    launches.incrementAndGet();
                }, null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var tick = executor.submit(scheduler::tick);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.requestStop();
            assertFalse(scheduler.isRunning());
            resume.countDown();
            assertEquals(List.of("T-1"), tick.get(5, TimeUnit.SECONDS).launch());
            scheduler.stop();
            assertEquals(1, launches.get());
        } finally {
            resume.countDown();
            scheduler.stop();
        }
    }

    @Test
    public void invalidCostAndOverflowingTokensCannotPoisonAdmission() {
        for (String cost : List.of("NaN", "Infinity", "-1", "1e999")) {
            assertNull(CostOverview.CostRecord.parse("fleet actuals: cost " + cost + " USD").costUsd());
        }
        var record = CostOverview.CostRecord.parse(
                "fleet actuals: cost 0.1 USD, tokens 1 (in 999999999999999999999999 / out 0 / reasoning 0)");
        assertEquals(0.1, record.costUsd(), 0);
        assertNull(record.tokensIn());
        assertThrows(IllegalArgumentException.class, () -> AutoDispatch.of(1, Double.NaN, false));
    }

    @Test
    public void calibratedPlanningRefreshesCostsEveryTick() {
        var samples = new java.util.ArrayList<Task>();
        for (int i = 0; i < 3; i++) {
            Task sample = ready("sample-" + i);
            sample.comments.add(new Task.Comment(Instant.EPOCH, "fleet", "fleet actuals: cost 1 USD"));
            samples.add(sample);
        }
        AtomicInteger launches = new AtomicInteger();
        DispatchScheduler scheduler = new DispatchScheduler(AutoDispatch.of(1, 3.1, false),
                () -> List.of(ready("T-1")), () -> CostOverview.of(samples), Set::of,
                id -> launches.incrementAndGet(), null).withCalibratedCosts();
        assertTrue("3 + calibrated 1 exceeds 3.1, even though flat .05 fits", scheduler.tick().launch().isEmpty());
        samples.forEach(t -> {
            t.comments.clear();
            t.comments.add(new Task.Comment(Instant.EPOCH, "fleet", "fleet actuals: cost 0.01 USD"));
        });
        assertEquals(List.of("T-1"), scheduler.tick().launch());
        assertEquals(1, launches.get());
    }

    private static DispatchScheduler scheduler(List<Task> tasks, java.util.function.Consumer<String> launch) {
        return new DispatchScheduler(AutoDispatch.of(1, 0, false), () -> tasks,
                CostOverview::empty, Set::of, launch, null);
    }

    private static Task ready(String id) {
        Task task = new Task();
        task.id = id;
        task.stage = "requirements";
        task.status = "sprint-backlog";
        task.updatedAt = Instant.EPOCH;
        return task;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class ManualExecutor extends ScheduledThreadPoolExecutor {
        private Runnable command;

        ManualExecutor() { super(1); }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable action, long initial, long period, TimeUnit unit) {
            command = action;
            return null;
        }
    }
}
