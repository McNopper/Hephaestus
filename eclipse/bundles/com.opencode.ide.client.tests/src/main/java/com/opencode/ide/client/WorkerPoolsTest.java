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
 * Unit tests for {@link WorkerPools}: one bounded shared pool instead of a
 * thread per task (2026-09-23 requirement: "we are not launching every time
 * a new thread - bad design"). Proves the bound (two chores share one
 * worker), the named-task visibility, and live capacity growth.
 */
public class WorkerPoolsTest {

    @After
    public void restoreDefaults() {
        RuntimeTuning.restoreDefaults();
        WorkerPools.resize(RuntimeTuning.workerThreads());
    }

    @Test
    public void choresShareOneBoundedWorkerInsteadOfSpawningThreads() throws Exception {
        WorkerPools.resize(1);
        AtomicReference<Thread> first = new AtomicReference<>();
        AtomicReference<Thread> second = new AtomicReference<>();
        Future<?> a = WorkerPools.submit("job-a", () -> first.set(Thread.currentThread()));
        Future<?> b = WorkerPools.submit("job-b", () -> second.set(Thread.currentThread()));
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);

        assertTrue("both chores ran on the SAME pooled worker, not one thread each",
                first.get() == second.get());
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
