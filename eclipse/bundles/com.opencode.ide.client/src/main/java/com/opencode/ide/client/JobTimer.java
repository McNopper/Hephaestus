package com.opencode.ide.client;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * A {@link ScheduledExecutorService} facade over the ECLIPSE JOB MANAGER's
 * timer ({@link Job#schedule(long)}) for the periodic loops (auto-dispatch
 * tick, recurring waves). There is no scheduler thread of ours: each tick is
 * a Job and the platform's timer sleeps between ticks - "sleep and wait after
 * each loop" is structural. Periodic work is FIXED-DELAY (the next tick is
 * scheduled after the current one finishes), so a slow tick can never overlap
 * or queue up behind itself.
 */
public final class JobTimer extends AbstractExecutorService implements ScheduledExecutorService {

    private final String name;
    private volatile boolean shutdown;

    public JobTimer(String name) {
        this.name = name;
    }

    @Override
    public void execute(Runnable command) {
        WorkerPools.submit(name, command);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(() -> {
            command.run();
            return null;
        }, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        TimerHandle<V> handle = new TimerHandle<>();
        FutureTask<V> future = new FutureTask<>(callable);
        Job job = new Job(name) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    future.run();
                    return future.isCancelled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
                } finally {
                    handle.bind(future, future.isDone());
                }
            }
        };
        job.setSystem(true);
        handle.bind(job);
        job.schedule(Math.max(0, unit.toMillis(delay)));
        return handle;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable action, long initial, long period, TimeUnit unit) {
        return periodic(action, initial, unit.toMillis(period));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable action, long initial, long delay, TimeUnit unit) {
        return periodic(action, initial, unit.toMillis(delay));
    }

    private ScheduledFuture<?> periodic(Runnable action, long initial, long periodMillis) {
        TimerHandle<Object> handle = new TimerHandle<>();
        scheduleTick(handle, action, Math.max(0, initial), Math.max(1, periodMillis));
        return handle;
    }

    private void scheduleTick(TimerHandle<Object> handle, Runnable action, long delayMillis, long periodMillis) {
        if (shutdown || handle.isCancelled()) {
            handle.done();
            return;
        }
        Job job = new Job(name + " tick") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    if (!shutdown && !handle.isCancelled()) {
                        action.run();
                    }
                } finally {
                    // fixed delay: the platform timer sleeps `periodMillis`
                    // after this tick COMPLETES before the next one runs
                    scheduleTick(handle, action, periodMillis, periodMillis);
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        handle.bind(job);
        job.schedule(delayMillis);
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
        return shutdown;
    }

    /** Minimal handle: cancellation stops the chain, completion ends it. */
    private static final class TimerHandle<V> implements ScheduledFuture<V> {
        private volatile Job job;
        private volatile FutureTask<V> future;
        private volatile boolean finished;
        private volatile boolean cancelled;

        void bind(Job scheduled) {
            this.job = scheduled;
        }

        void bind(FutureTask<V> done, boolean isDone) {
            this.future = done;
            if (isDone) {
                finished = true;
            }
        }

        void done() {
            finished = true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            Job current = job;
            return current == null || current.cancel();
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return finished || cancelled;
        }

        @Override
        public V get() throws InterruptedException, java.util.concurrent.ExecutionException {
            while (!isDone()) {
                Thread.sleep(25); // a polite wait loop: sleep between checks
            }
            FutureTask<V> result = future;
            return result == null ? null : result.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!isDone() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            if (!isDone()) {
                throw new java.util.concurrent.TimeoutException(name());
            }
            FutureTask<V> result = future;
            return result == null ? null : result.get();
        }

        private String name() {
            return "timer task";
        }
    }
}
