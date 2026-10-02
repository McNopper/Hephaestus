package com.opencode.ide.client;

import java.time.Duration;

/**
 * Runtime-tunable worker timings (user requirement 2026-09-23: "when the
 * shells and agents are running … we can adjust the sleep and idle time
 * somewhere, otherwise the system gets not usable").
 *
 * <p>Three knobs, read LIVE at each use so adjustments apply to running
 * workers without a restart:</p>
 * <ul>
 *   <li>{@link #pollMillis()} — the sleep between worker polls (the fleet
 *       probe tick and the UI refresh cadence);</li>
 *   <li>{@link #stallTimeout()} — the idle/silent window after which the
 *       watchdog aborts a hung session (the "PT5M" knob);</li>
 *   <li>{@link #ticketBudget()} — the no-progress window after which a run
 *       is stopped (the "PT30M" knob).</li>
 * </ul>
 * Explicit per-call overrides (tests, {@code withStallTimeout}, an explicit
 * launch timeout) always win over these defaults. Thread-safe; values are
 * validated and clamped, never zero/negative.
 */
public final class RuntimeTuning {

    /** Mirrors {@code ClientTuning.REPLY_POLL_INTERVAL}-era cadence (1s probe tick). */
    private static final long DEFAULT_POLL_MILLIS = 1_000;
    /** Compile-time baseline for {@link #stallTimeout()} ({@code FleetTuning.STALL_TIMEOUT} with no env override). */
    private static final Duration BASE_STALL_TIMEOUT = Duration.ofMinutes(5);
    /** Compile-time baseline for {@link #ticketBudget()} ({@code FleetTuning.DEFAULT_TICKET_BUDGET} with no env override). */
    private static final Duration BASE_TICKET_BUDGET = Duration.ofMinutes(30);
    /** The shared {@link WorkerPools} size - the "how many worker threads" knob. */
    private static final int DEFAULT_WORKER_THREADS = 8;
    /** Hard ceiling for {@link #workerThreads()} - also the pool's hard max. */
    public static final int MAX_WORKERS = 64;
    /** Idle workers die after this long (WorkerPools keeps no dead weight). */
    public static final long WORKER_IDLE_SECONDS = 60;

    private static final Object LOCK = new Object();
    private static long pollMillis = DEFAULT_POLL_MILLIS;
    private static Duration stallTimeout = BASE_STALL_TIMEOUT;
    private static Duration ticketBudget = BASE_TICKET_BUDGET;
    private static int workerThreads = DEFAULT_WORKER_THREADS;
    /** B-015: the default {@link #restoreDefaults()} returns to (env-seeded by the fleet engine). */
    private static Duration defaultStallTimeout = BASE_STALL_TIMEOUT;
    /** B-015: the default {@link #restoreDefaults()} returns to (env-seeded by the fleet engine). */
    private static Duration defaultTicketBudget = BASE_TICKET_BUDGET;
    /** B-015: an explicit {@link #setStallTimeout} ran since the last restore - a later seed must not clobber it. */
    private static boolean stallOverridden;
    /** B-015: an explicit {@link #setTicketBudget} ran since the last restore - a later seed must not clobber it. */
    private static boolean budgetOverridden;

    private RuntimeTuning() {
    }

    /** Sleep between worker polls; clamped to 100ms..10min. */
    public static long pollMillis() {
        synchronized (LOCK) {
            return pollMillis;
        }
    }

    public static void setPollMillis(long millis) {
        synchronized (LOCK) {
            pollMillis = Math.max(100, Math.min(millis, 600_000));
        }
    }

    /** The idle/silent abort window; clamped to 5s..2h. */
    public static Duration stallTimeout() {
        synchronized (LOCK) {
            return stallTimeout;
        }
    }

    public static void setStallTimeout(Duration duration) {
        if (duration == null) {
            return;
        }
        synchronized (LOCK) {
            stallOverridden = true;
            stallTimeout = clamp(duration, Duration.ofSeconds(5), Duration.ofHours(2));
        }
    }

    /** The no-progress run budget; clamped to 30s..24h. */
    public static Duration ticketBudget() {
        synchronized (LOCK) {
            return ticketBudget;
        }
    }

    /** The shared worker-pool size (WorkerPools); clamped to 1..{@link #MAX_WORKERS}. */
    public static int workerThreads() {
        synchronized (LOCK) {
            return workerThreads;
        }
    }

    public static void setWorkerThreads(int workers) {
        synchronized (LOCK) {
            workerThreads = Math.max(1, Math.min(workers, MAX_WORKERS));
        }
    }

    public static void setTicketBudget(Duration duration) {
        if (duration == null) {
            return;
        }
        synchronized (LOCK) {
            budgetOverridden = true;
            ticketBudget = clamp(duration, Duration.ofSeconds(30), Duration.ofHours(24));
        }
    }

    /**
     * B-015: seeds the DEFAULT {@link #stallTimeout()} from the engine's
     * env-parsed knob table ({@code FleetTuning.STALL_TIMEOUT}, env
     * {@code FLEET_STALL_TIMEOUT_MS}) - the env knob sets the default, the
     * tuning dialog still overrides at runtime (a seed never clobbers an
     * explicit {@link #setStallTimeout} value). The value must be positive
     * and is taken as-is (the engine validates its env knobs itself; the
     * setter's window clamp stays a dialog concern).
     */
    public static void seedStallTimeout(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        synchronized (LOCK) {
            defaultStallTimeout = duration;
            if (!stallOverridden) {
                stallTimeout = duration;
            }
        }
    }

    /**
     * B-015: seeds the DEFAULT {@link #ticketBudget()} from the engine's
     * env-parsed knob table ({@code FleetTuning.DEFAULT_TICKET_BUDGET}, env
     * {@code FLEET_TICKET_BUDGET_MS}) - see {@link #seedStallTimeout}.
     */
    public static void seedTicketBudget(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        synchronized (LOCK) {
            defaultTicketBudget = duration;
            if (!budgetOverridden) {
                ticketBudget = duration;
            }
        }
    }

    /** Back to the seeded defaults (the FleetTuning-mirroring baselines when no env override seeded them). */
    public static void restoreDefaults() {
        synchronized (LOCK) {
            pollMillis = DEFAULT_POLL_MILLIS;
            stallTimeout = defaultStallTimeout;
            ticketBudget = defaultTicketBudget;
            workerThreads = DEFAULT_WORKER_THREADS;
            stallOverridden = false;
            budgetOverridden = false;
        }
    }

    /**
     * B-015: test isolation - drops any seed and returns every value to the
     * compile-time baselines (what the world looked like before any fleet
     * engine loaded). Production code never calls this.
     */
    public static void clearSeededDefaults() {
        synchronized (LOCK) {
            defaultStallTimeout = BASE_STALL_TIMEOUT;
            defaultTicketBudget = BASE_TICKET_BUDGET;
            stallTimeout = BASE_STALL_TIMEOUT;
            ticketBudget = BASE_TICKET_BUDGET;
            stallOverridden = false;
            budgetOverridden = false;
        }
    }

    /** One-line summary for the UI status line. */
    public static String summary() {
        return "poll " + pollMillis() + "ms | idle " + stallTimeout() + " | budget " + ticketBudget()
                + " | workers " + workerThreads();
    }

    private static Duration clamp(Duration value, Duration min, Duration max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }
}
