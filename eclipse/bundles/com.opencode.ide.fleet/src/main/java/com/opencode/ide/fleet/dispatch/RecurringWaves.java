package com.opencode.ide.fleet.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.opencode.ide.fleet.FleetTuning;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * The U-022 recurring-waves loop — the always-on wave-to-wave pump. One
 * cycle: surface the NEEDS-HUMAN set (blocked tickets no live job is
 * retrying), enforce the cost budget as a hard stop, drain the active wave
 * through an owned {@link DispatchScheduler} (ticked manually — all of its
 * launch-echo/admission hardening applies unchanged), and when the wave has
 * no dispatchable work left, close it and plan the NEXT wave from the
 * prioritized product backlog via {@link WavePlanner} — no human click
 * between waves.
 *
 * <p>Stop vs. park (the escalation semantics): the loop <b>stops cleanly</b>
 * (disables itself, {@link StopReason#BUDGET_EXHAUSTED} or
 * {@link StopReason#NOTHING_PLANNABLE}) when even one more launch would
 * exceed the budget, or when nothing is plannable and no blocked ticket
 * waits anywhere. It <b>parks</b> (stays enabled, keeps ticking, dispatches
 * nothing) while blocked tickets wait — surfaced as NEEDS-HUMAN, or with a
 * live retry still owning them — because clearing a blocker returns the
 * ticket to the backlog's READY set, so the next cycle plans it into a wave
 * and the loop resumes automatically. Accepted workers are never cancelled
 * by a stop.</p>
 *
 * <p>Lifecycle mirrors {@link DispatchScheduler}: {@link #start(Duration)}
 * runs {@link #tick()} immediately then periodically on a single daemon
 * thread ({@code fleet-recurring-waves}); {@link #stop()} is idempotent.
 * SWT-free, pure orchestration over injected seams; nothing here throws —
 * a failing supplier aborts the cycle with a log line, a failing
 * plan/close/launch is logged and retried on the next cycle. The mode is
 * OFF by construction: nothing ticks until {@link #start} is called (the
 * U-022 safety default — enable per project through {@code FleetControl}
 * or the Board).</p>
 */
public final class RecurringWaves implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RecurringWaves.class.getName());

    /** Why a clean stop happened; {@code null} while the loop runs. */
    public enum StopReason {
        /** Even one more launch would exceed the budget — the hard stop. */
        BUDGET_EXHAUSTED,
        /** Nothing is plannable from the backlog and no blocked ticket waits. */
        NOTHING_PLANNABLE,
        /** Disabled by a human/tool ({@link #stop}). */
        STOPPED
    }

    /** One cycle's observable outcome — the tool/board status projection. */
    public record Status(boolean running, String project, String wave, int wavesPlanned,
            StopReason stopReason, double budgetUsd, double spendUsd,
            List<NeedsHuman.Escalation> needsHuman, String lastNotice) {

        public Status {
            needsHuman = needsHuman == null ? List.of() : List.copyOf(needsHuman);
            lastNotice = lastNotice == null ? "" : lastNotice;
        }
    }

    private final TaskStore store;
    private final String project;
    private final AutoDispatch policy;
    private final Supplier<Set<String>> runningIds;
    private final BiConsumer<String, DispatchScheduler.LaunchAttempt> launch;
    private final Consumer<String> notice;
    private final Clock clock;
    /**
     * The manually-ticked drain. Rebuilt by every {@link #start} — a stop
     * poisons the scheduler's stopped flag, and the drain is never started
     * on its own executor, so only a fresh instance un-poisons it.
     */
    private DispatchScheduler drain;

    private final Object lifecycleLock = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long generation;
    private boolean stopped;

    private volatile String activeWave;
    private int wavesPlanned;
    private StopReason stopReason;
    private Instant lastSummaryAt;
    private List<NeedsHuman.Escalation> escalations = List.of();
    private String lastNotice = "";
    private double spendUsd;
    private String spendGround = "";

    /**
     * @param store       the project's task store (the loop plans/closes
     *                    sprints in it — the store is the coordination
     *                    blackboard)
     * @param project     the project name inside the store
     * @param policy      the dispatch policy (concurrency, budget, STALE
     *                    re-runs) shared by drain and planning; the estimate
     *                    is re-calibrated from each cycle's cost snapshot
     * @param initialWave an existing sprint adopted as the first wave
     *                    ({@code null} plans wave 1 from the backlog
     *                    immediately)
     * @param runningIds  ticket ids with a live fleet job; re-read every cycle
     * @param launch      launches one admitted ticket id of the active wave
     *                    (isolated — a throw is logged, never propagated)
     * @param notice      the escalation sink — receives the periodic
     *                    NEEDS-HUMAN summary while tickets wait at the human
     * @param clock       times the summary cadence; {@code null} reads as
     *                    the system clock
     */
    public RecurringWaves(TaskStore store, String project, AutoDispatch policy, String initialWave,
            Supplier<Set<String>> runningIds,
            BiConsumer<String, DispatchScheduler.LaunchAttempt> launch,
            Consumer<String> notice, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.project = Objects.requireNonNull(project, "project");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.runningIds = Objects.requireNonNull(runningIds, "runningIds");
        this.launch = Objects.requireNonNull(launch, "launch");
        this.notice = notice == null ? text -> { } : notice;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.activeWave = initialWave == null || initialWave.isBlank() ? null : initialWave;
        this.drain = newDrain();
    }

    /** A fresh drain over this loop's seams (calibrated, whole-project readiness scope). */
    private DispatchScheduler newDrain() {
        return DispatchScheduler.withFeedback(policy, this::waveTasks,
                () -> store.list(project, null, null, null, null),
                this::costSnapshot, this.runningIds, this.launch, this.clock)
                .withCalibratedCosts();
    }

    /** The sprint-scoped candidates of the CURRENT wave (the drain's guardrail). */
    private List<Task> waveTasks() {
        String wave = activeWave;
        return wave == null ? List.of() : store.list(project, null, null, wave, null);
    }

    private CostOverview costSnapshot() {
        return CostOverview.of(store.list(project, null, null, null, null));
    }

    /**
     * One full cycle, never throws: escalate, budget-check, drain or plan.
     * Safe to call before {@link #start} (tests drive single cycles); a
     * stopped loop returns the frozen status without side effects.
     */
    public synchronized Status tick() {
        long current;
        synchronized (lifecycleLock) {
            if (stopped) {
                return status();
            }
            current = generation;
        }
        tick(current);
        return status();
    }

    private void tick(long current) {
        List<Task> all;
        CostOverview cost;
        Set<String> running;
        try {
            all = store.list(project, null, null, null, null);
            cost = CostOverview.of(all);
            running = runningIds.get();
            running = running == null ? Set.of() : running;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "waves cycle skipped: state supplier failed: " + e.getMessage(), e);
            return;
        }

        // The escalation surface first: blocked tickets with no live retry.
        escalations = NeedsHuman.of(all, running);
        maybeSummarize();

        AutoDispatch effective = policy.withEstimateUsd(AutoDispatch.calibratedEstimate(cost));
        spendUsd = spendSoFar(cost) + running.size() * effective.estimateUsd();
        spendGround = CostOverview.usd(spendUsd) + " spent / "
                + (effective.costBudgetUsd() <= 0 ? "unlimited"
                        : CostOverview.usd(effective.costBudgetUsd()) + " budget");

        // The cost budget is a hard stop: when even ONE more launch would
        // exceed it, nothing can ever be admitted again — stop cleanly.
        if (effective.costBudgetUsd() > 0
                && spendUsd + effective.estimateUsd() > effective.costBudgetUsd()) {
            publishNotice("recurring waves stopping: cost budget "
                    + CostOverview.usd(effective.costBudgetUsd()) + " is a hard stop ("
                    + spendGround + ")");
            stopWith(StopReason.BUDGET_EXHAUSTED);
            return;
        }

        Map<String, StageReadiness.Readiness> verdicts = StageReadiness.evaluate(all);
        String wave = activeWave;
        if (!drained(tasksOf(all, wave), verdicts, effective, running)) {
            drain.tick();
            return;
        }
        if (stoppedOrReplaced(current)) {
            return;
        }

        // The wave has no dispatchable work left (or none exists yet):
        // close it, then plan the next from the prioritized backlog.
        if (wave != null) {
            try {
                store.closeSprint(project, wave);
            } catch (RuntimeException e) {
                // An unknown/closed sprint id must not wedge the loop: drop
                // it and plan fresh (logged once per occurrence).
                LOG.log(Level.WARNING, "closing drained wave " + wave + " failed (dropping it): "
                        + e.getMessage(), e);
            }
            activeWave = null;
            try {
                all = store.list(project, null, null, null, null);
                verdicts = StageReadiness.evaluate(all);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "waves cycle: re-read after close failed: " + e.getMessage(), e);
                return;
            }
        }

        WavePlanner.PlannedWave planned = WavePlanner.of(effective).plan(
                backlogOf(all), verdicts, cost, running);
        if (planned.ticketIds().isEmpty()) {
            if (!anyBlocked(all)) {
                publishNotice("recurring waves stopping: nothing plannable from the backlog ("
                        + spendGround + ")");
                stopWith(StopReason.NOTHING_PLANNABLE);
            }
            // else: park — blocked tickets hold the loop open, whether they
            // already surfaced as NEEDS-HUMAN or a live retry still owns
            // them; a cleared blocker re-enters through the next cycle.
            return;
        }

        wavesPlanned++;
        try {
            Task.Sprint minted = store.planSprint(project, null, planned.ticketIds(),
                    "recurring wave " + wavesPlanned);
            activeWave = minted.id();
        } catch (RuntimeException e) {
            wavesPlanned--;
            LOG.log(Level.WARNING, "planning recurring wave " + (wavesPlanned + 1)
                    + " failed (retried next cycle): " + e.getMessage(), e);
            return;
        }
        if (stoppedOrReplaced(current)) {
            return;
        }
        drain.tick(); // start draining the fresh wave immediately
    }

    /**
     * Whether the wave's dispatchable work is gone: a ticket holds the wave
     * open while a live job runs, its stage work is claimed (in-progress),
     * sits under review (in-review), or its verdict admits it (READY, or
     * STALE with includeStale). Blocked, waiting and finished tickets do
     * not — blocked ones are the human's (NEEDS-HUMAN), waiting ones return
     * to the backlog and re-enter with their upstream, finished ones are
     * done.
     */
    private static boolean drained(List<Task> waveTasks,
            Map<String, StageReadiness.Readiness> verdicts, AutoDispatch policy,
            Set<String> running) {
        for (Task t : waveTasks) {
            if (t == null || t.id == null) {
                continue;
            }
            if (running.contains(t.id)
                    || "in-progress".equals(t.status) || "in-review".equals(t.status)) {
                return false;
            }
            StageReadiness.Readiness verdict = verdicts.get(t.id);
            if (verdict == null) {
                continue;
            }
            if (verdict.kind() == StageReadiness.Kind.READY
                    || (policy.includeStale() && verdict.kind() == StageReadiness.Kind.STALE)) {
                return false;
            }
        }
        return true;
    }

    /** The wave's tickets (sprint field match); a {@code null} wave yields none. */
    private static List<Task> tasksOf(List<Task> all, String wave) {
        List<Task> out = new ArrayList<>();
        if (wave == null) {
            return out;
        }
        for (Task t : all) {
            if (t != null && wave.equals(t.sprint)) {
                out.add(t);
            }
        }
        return out;
    }

    /** The product backlog (product-backlog status; WavePlanner re-sorts defensively). */
    private static List<Task> backlogOf(List<Task> all) {
        List<Task> out = new ArrayList<>();
        for (Task t : all) {
            if (t != null && "product-backlog".equals(t.status)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Whether any ticket of the snapshot carries the blocked flag (at the human, or under a live retry). */
    private static boolean anyBlocked(List<Task> all) {
        for (Task t : all) {
            if (t != null && t.blocked) {
                return true;
            }
        }
        return false;
    }

    /** The recorded project spend; unknown spend reads as 0. */
    private static double spendSoFar(CostOverview cost) {
        return cost.project() != null && cost.project().costUsd() != null
                ? cost.project().costUsd() : 0;
    }

    /** The periodic NEEDS-HUMAN summary — immediately on first appearance, then at most per period. */
    private void maybeSummarize() {
        Instant now = clock.instant();
        if (escalations.isEmpty()) {
            lastSummaryAt = null;
            return;
        }
        if (lastSummaryAt != null
                && Duration.between(lastSummaryAt, now).compareTo(FleetTuning.WAVE_SUMMARY_PERIOD) < 0) {
            return;
        }
        lastSummaryAt = now;
        publishNotice(NeedsHuman.summary(escalations));
    }

    private void publishNotice(String text) {
        lastNotice = text;
        try {
            notice.accept(text);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "waves notice sink failed: " + e.getMessage(), e);
        }
    }

    private void stopWith(StopReason reason) {
        stopReason = reason; // callers hold `this`; status() reads under `this`
        requestStop();
    }

    /** Whether a concurrent stop/replace invalidated this cycle mid-flight. */
    private boolean stoppedOrReplaced(long seen) {
        synchronized (lifecycleLock) {
            return stopped || seen != generation;
        }
    }

    /** The observable state — the {@code fleet_waves_status} projection. */
    public synchronized Status status() {
        return new Status(running, project, activeWave, wavesPlanned, stopReason,
                policy.costBudgetUsd(), spendUsd, escalations, lastNotice);
    }

    /** The sprint the loop currently drains; {@code null} before the first wave. */
    public String activeWave() {
        return activeWave;
    }

    /** @return true while the loop is scheduled (or parking between cycles). */
    public boolean isRunning() {
        return running;
    }

    /**
     * Runs one cycle immediately, then every {@code period}, on a fresh
     * single-thread daemon executor ({@code fleet-recurring-waves});
     * replaces a previous start.
     */
    public void start(Duration period) {
        start(period, com.opencode.ide.client.WorkerPools.timer("fleet-recurring-waves"));
    }

    /**
     * Test seam: schedules the loop on the given executor (ownership
     * transfers — {@link #stop()} shuts it down).
     *
     * @throws IllegalArgumentException when {@code period} is null, zero or negative
     */
    public void start(Duration period, ScheduledExecutorService scheduler) {
        if (period == null || period.isZero() || period.isNegative() || period.toMillis() < 1) {
            throw new IllegalArgumentException("period must be > 0: " + period);
        }
        Objects.requireNonNull(scheduler, "scheduler");
        synchronized (this) {
            synchronized (lifecycleLock) {
                requestStop();
                executor = scheduler;
                running = true;
                stopped = false;
                stopReason = null; // guarded by `this`, which the caller holds
                drain = newDrain(); // the stop above poisoned the old drain
                long current = generation;
                try {
                    scheduler.scheduleAtFixedRate(() -> runCycle(current), 0,
                            period.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    requestStop();
                    throw e;
                }
            }
        }
    }

    /**
     * Stops the loop (accepted workers continue); idempotent. A stop reason
     * already recorded by a clean self-stop is not overwritten.
     */
    public void stop() {
        synchronized (this) {
            if (stopReason == null) {
                stopReason = StopReason.STOPPED;
            }
        }
        requestStop();
    }

    /** Invalidates scheduled work immediately (no join — see {@link DispatchScheduler#requestStop}). */
    public void requestStop() {
        synchronized (lifecycleLock) {
            running = false;
            stopped = true;
            generation++;
            ScheduledExecutorService current = executor;
            executor = null;
            if (current != null) {
                current.shutdown();
            }
        }
        drain.requestStop();
    }

    /** {@link AutoCloseable} alias for {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    /**
     * One scheduled cycle: never runs once stopped, and a throwing cycle is
     * logged, not propagated — the schedule keeps serving.
     */
    private synchronized void runCycle(long current) {
        synchronized (lifecycleLock) {
            if (!running || stopped || current != generation) {
                return;
            }
        }
        try {
            tick(current);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "waves cycle threw (kept serving): " + e.getMessage(), e);
        }
    }
}
