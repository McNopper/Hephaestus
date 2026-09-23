package com.opencode.ide.client;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ONE bounded worker pool (user requirement 2026-09-23: "we can define
 * how many [worker threads] … we are not launching every time a new thread
 * - bad design"). Every background chore - fleet prompt/review runs, view
 * refreshes, ask answers - submits here instead of spawning a thread.
 *
 * <p>The pool is fixed-size ({@link RuntimeTuning#workerThreads()}, live
 * {@link #resize(int)}) with {@code allowCoreThreadTimeOut}: idle workers
 * die after a minute (no background CPU when nothing runs) and appear on
 * demand, always bounded by the configured count. Long fleet runs occupy a
 * worker for their whole duration - so the knob doubles as the
 * max-parallel-work control; keep the dispatcher's concurrency at or below
 * it.</p>
 */
public final class WorkerPools {

    private static final Object LOCK = new Object();
    private static ThreadPoolExecutor pool;

    private WorkerPools() {
    }

    private static ThreadPoolExecutor pool() {
        synchronized (LOCK) {
            if (pool == null) {
                pool = newPool(RuntimeTuning.workerThreads());
            }
            return pool;
        }
    }

    private static ThreadPoolExecutor newPool(int workers) {
        AtomicInteger next = new AtomicInteger();
        // maximum = RuntimeTuning's clamp ceiling so live growth (resize UP)
        // can never throw; the WORKING bound is the core size - the queue is
        // unbounded, so the executor never spawns beyond core: no thread per
        // call, ever. Both numbers are RuntimeTuning knobs (2026-09-23: no
        // magic numbers outside the central tables).
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers, RuntimeTuning.MAX_WORKERS, RuntimeTuning.WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "hephaestus-worker-" + next.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Runs a named chore on a shared worker (the name shows in logs while it runs). */
    public static Future<?> submit(String name, Runnable task) {
        return pool().submit(() -> {
            Thread thread = Thread.currentThread();
            String previous = thread.getName();
            thread.setName(name);
            try {
                task.run();
            } finally {
                thread.setName(previous);
            }
        });
    }

    /** Named worker task with a result. */
    public static <T> Future<T> submit(String name, Callable<T> task) {
        return pool().submit(() -> {
            Thread thread = Thread.currentThread();
            String previous = thread.getName();
            thread.setName(name);
            try {
                return task.call();
            } finally {
                thread.setName(previous);
            }
        });
    }

    /** Live resize: grows spawn immediately for queued work; extras die when idle. */
    public static void resize(int workers) {
        int size = Math.max(1, Math.min(workers, RuntimeTuning.MAX_WORKERS));
        synchronized (LOCK) {
            if (pool == null) {
                pool = newPool(size);
            } else {
                pool.setCorePoolSize(size);
            }
        }
    }

    /** The configured ceiling. */
    public static int workerCount() {
        synchronized (LOCK) {
            return pool == null ? RuntimeTuning.workerThreads() : pool.getCorePoolSize();
        }
    }

    /** {@code "workers 2/8 busy"} for the UI status line. */
    public static String summary() {
        synchronized (LOCK) {
            return pool == null
                    ? "workers 0/" + RuntimeTuning.workerThreads() + " idle"
                    : "workers " + pool.getActiveCount() + "/" + pool.getCorePoolSize() + " busy";
        }
    }
}
