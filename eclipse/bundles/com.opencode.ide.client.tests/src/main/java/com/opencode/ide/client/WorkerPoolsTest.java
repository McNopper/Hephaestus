package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link WorkerPools}: the fixed worker count over the
 * ECLIPSE JOB MANAGER (2026-09-23: "we are not launching every time a new
 * thread - bad design" + "we chose Eclipse because we do not reinvent
 * everything from scratch"). Proves the bound (two chores NEVER run
 * concurrently on one worker), the named-task visibility, and live capacity
 * growth.
 */
public class WorkerPoolsTest {

    @After
    public void restoreDefaults() {
        RuntimeTuning.restoreDefaults();
        WorkerPools.resize(RuntimeTuning.workerThreads());
    }

    @Test
    public void oneWorkerRunsChoresSequentiallyNeverConcurrently() throws Exception {
        WorkerPools.resize(1);
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        Runnable chore = () -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
        };
        Future<?> a = WorkerPools.submit("job-a", chore);
        Future<?> b = WorkerPools.submit("job-b", chore);
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);

        assertEquals("the bound is CONCURRENCY: one worker never runs two chores at once",
                1, peak.get());
    }

    @Test
    public void taskNamesShowWhileTheyRun() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        WorkerPools.submit("job-x", () -> seen.set(Thread.currentThread().getName()))
                .get(5, TimeUnit.SECONDS);

        assertEquals("the chore name shows in the worker while it runs", "job-x", seen.get());
    }

    @Test
    public void resizingGrowsCapacityLive() throws Exception {
        WorkerPools.resize(3);
        CountDownLatch running = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) {
                WorkerPools.submit("hold-" + i, () -> {
                    running.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue("three workers run concurrently after resize(3)",
                    running.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}
