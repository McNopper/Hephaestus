package com.opencode.ide.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobGroup;

/**
 * THE one work scheduler - a thin bridge over the ECLIPSE JOB MANAGER (user
 * direction 2026-09-23: "there is a reason why we have chosen Eclipse:
 * because we do not reinvent everything from scratch" ... "either we let
 * things run in opencode ... or we let it run in Eclipse. everything else is
 * reinventing the wheel").
 *
 * <p>Every background task runs as a {@link Job} inside ONE
 * {@link JobGroup} of {@link RuntimeTuning#workerThreads()} threads: the
 * FIXED, configurable worker count over the JobManager's queue. No thread,
 * pool, scheduler or watcher of our own exists anywhere. The Eclipse
 * Progress view shows every job live (the system overview), and
 * {@link #stats()} reports queue depth and load for the status lines.</p>
 *
 * <p>Results are handed back the moment a task completes: {@link #submit}
 * returns a real interruptible {@link Future} (cancel(true) interrupts the
 * running task - the starvation fix's {@code releaseWorker()} depends on
 * it), {@link #submitAsync} a {@code CompletableFuture}. While a task runs,
 * its thread is downshifted one priority class below normal so background
 * work never competes with the desktop (user requirement 2026-09-23), and
 * it yields before handing the thread back.</p>
 *
 * <p>{@link #executor} / {@link #serialExecutor} adapt the same Jobs for call
 * sites wired to an {@link ExecutorService} seam (FleetControl's injected
 * executor, the MCP HTTP server, the old single-thread view executors): the
 * seam keeps its shape, the threads are the JobManager's.</p>
 */
public final class WorkerPools {

    private static final Object LOCK = new Object();
    private static JobGroup group;
    private static int groupSize;

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final AtomicInteger SUBMITTED = new AtomicInteger();
    private static final AtomicInteger COMPLETED = new AtomicInteger();
    private static final AtomicLong RUN_NANOS = new AtomicLong();

    private WorkerPools() {
    }

    private static JobGroup group() {
        synchronized (LOCK) {
            if (group == null) {
                groupSize = RuntimeTuning.workerThreads();
                group = newGroup(groupSize);
            }
            return group;
        }
    }

    private static JobGroup newGroup(int size) {
        // preferredConcurrency == maxThreads: the group is the fixed worker
        // set; overflow JOBS wait in the JobManager queue (visible in the
        // Progress view), never extra threads
        return new JobGroup("hephaestus-workers", size, size);
    }

    /** Runs a named task on a shared worker (the name shows while it runs). */
    public static Future<?> submit(String name, Runnable task) {
        return submit(name, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Named worker task with a result. The returned {@link Future} is a real
     * interruptible handle: {@code cancel(true)} interrupts the running task.
     */
    public static <T> Future<T> submit(String name, Callable<T> task) {
        return submitTo(group(), name, task);
    }

    /** The "pass back the result as soon as done" form. */
    public static <T> CompletableFuture<T> submitAsync(String name, Callable<T> task) {
        CompletableFuture<T> done = new CompletableFuture<>();
        submit(name, () -> {
            try {
                done.complete(task.call());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
            return null;
        });
        return done;
    }

    private static <T> Future<T> submitTo(JobGroup target, String name, Callable<T> task) {
        String label = name + " #" + NEXT.incrementAndGet();
        FutureTask<T> future = new FutureTask<>(polite(name, task));
        Job job = new Job(label) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                long started = System.nanoTime();
                try {
                    future.run();
                    return future.isCancelled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } finally {
                    COMPLETED.incrementAndGet();
                    RUN_NANOS.addAndGet(System.nanoTime() - started);
                }
            }
        };
        if (target != null) {
            job.setJobGroup(target);
        }
        job.schedule();
        SUBMITTED.incrementAndGet();
        return future;
    }

    /**
     * The polite wrapper: the worker thread is downshifted below normal while
     * the task runs (restored afterwards - JobManager threads are shared with
     * the platform) and yields once before handing the thread back.
     */
    private static <T> Callable<T> polite(String name, Callable<T> task) {
        return () -> {
            Thread thread = Thread.currentThread();
            String previousName = thread.getName();
            int previousPriority = thread.getPriority();
            thread.setName(name);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, previousPriority - 2));
            try {
                return task.call();
            } finally {
                thread.setPriority(previousPriority);
                thread.setName(previousName);
                Thread.yield();
            }
        };
    }

    /**
     * An {@link ExecutorService} facade over the shared group, for call sites
     * wired to an executor (FleetControl's seam, the MCP HTTP server). The
     * seam keeps its shape; the threads are the JobManager's.
     */
    public static ExecutorService executor(String name) {
        return new JobExecutor(name, group());
    }

    /** The old {@code newSingleThreadExecutor} semantics (never overlap), without a thread. */
    public static ExecutorService serialExecutor(String name) {
        return new JobExecutor(name, newGroup(1));
    }

    /**
     * The I/O lane: UNGROUPED jobs on the JobManager's own pool, so pipe and
     * stream readers START IMMEDIATELY and never queue behind long work.
     * (2026-09-23 lesson: git drains behind fleet prompts on the shared group
     * read as empty output - "output drain lost" - and the store sync aborted
     * with NOT_A_REPO. I/O plumbing must never wait for a work slot.)
     */
    public static ExecutorService ioExecutor(String name) {
        return new JobExecutor(name, null);
    }

    /** The periodic-loop form: the ECLIPSE JOB MANAGER's timer, not a scheduler thread. */
    public static ScheduledExecutorService timer(String name) {
        return new JobTimer(name);
    }

    /** Minimal adapter - {@link AbstractExecutorService} supplies the rest. */
    private static final class JobExecutor extends AbstractExecutorService {
        private final String name;
        private final JobGroup target;
        private final List<Future<?>> live = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean shutdown;

        JobExecutor(String name, JobGroup target) {
            this.name = name;
            this.target = target;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException(name + " is shut down");
            }
            live.add(submitTo(target, name, () -> {
                command.run();
                return null;
            }));
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            synchronized (live) {
                for (Future<?> future : live) {
                    future.cancel(true);
                }
                live.clear();
            }
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            synchronized (live) {
                live.removeIf(Future::isDone);
                return shutdown && live.isEmpty();
            }
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!isTerminated() && System.nanoTime() < deadline) {
                Thread.sleep(25); // a polite wait loop: sleep between checks, never spin
            }
            return isTerminated();
        }
    }

    /**
     * Live resize. The JobGroup's thread count is fixed at construction, so a
     * resize installs a NEW group; work already running drains in the old one.
     */
    public static void resize(int workers) {
        int size = Math.max(1, Math.min(workers, RuntimeTuning.MAX_WORKERS));
        synchronized (LOCK) {
            groupSize = size;
            group = newGroup(size);
        }
    }

    /** The configured worker count. */
    public static int workerCount() {
        synchronized (LOCK) {
            return group == null ? RuntimeTuning.workerThreads() : groupSize;
        }
    }

    /** Queue depth and load feedback (the deep overview is the Progress view). */
    public record Stats(int running, int queued, int maxThreads, long completed, long avgMillis) {
        public String summary() {
            return "workers " + running + "/" + maxThreads + " busy | queue " + queued
                    + " | avg " + avgMillis + "ms";
        }
    }

    /** Snapshot for the status lines: queue depth + per-worker load. */
    public static Stats stats() {
        JobGroup current;
        int max;
        synchronized (LOCK) {
            current = group;
            max = current == null ? RuntimeTuning.workerThreads() : groupSize;
        }
        // getActiveJobs() = not-done jobs of the group (running + queued)
        int inFlight = current == null ? 0 : current.getActiveJobs().size();
        int running = Math.min(inFlight, max);
        int queued = inFlight - running;
        long completed = COMPLETED.get();
        long avg = completed == 0 ? 0
                : TimeUnit.NANOSECONDS.toMillis(RUN_NANOS.get() / completed);
        return new Stats(running, queued, max, completed, avg);
    }

    /** {@code "workers 2/8 busy | queue 3"} for the UI status line. */
    public static String summary() {
        return stats().summary();
    }
}
