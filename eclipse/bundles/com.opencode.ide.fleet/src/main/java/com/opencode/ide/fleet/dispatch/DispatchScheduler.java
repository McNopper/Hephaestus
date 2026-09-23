package com.opencode.ide.fleet.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;

/**
 * The H6 background dispatch loop (the self-draining scheduler): ticks
 * {@link AutoDispatch} over one sprint until it drains. SWT-free, pure
 * orchestration over injected seams — the sprint scope is the
 * {@code sprintTasks} supplier's contract (the caller hands in an
 * ALREADY sprint-scoped list; the scheduler never sees out-of-sprint
 * tasks), and every seam is re-read per tick so tickets added to the
 * sprint drain automatically. Nothing here ever throws: a failing
 * supplier aborts the tick with an empty plan, a failing launch is
 * logged and skipped (the remaining launches of the tick still run),
 * and the loop keeps serving.
 *
 * <p>Launch-echo guard: the real launcher publishes its RUNNING rows
 * synchronously, so the running set catches up immediately in the normal
 * case — but a lagging {@code runningTaskIds} supplier would re-admit a
 * just-launched ticket on the next tick. Every launch attempt is
 * remembered by ticket revision, preventing repeated attempts on unchanged
 * input. Accepted launches also consume capacity for {@link #LAUNCH_HOLD}
 * while the running snapshot catches up. Peer admission deferrals re-plan
 * on the next tick. The returned plan reports what actually went
 * out: held-back ids join the policy's skips with their own reason.</p>
 *
 * <p>Lifecycle: {@link #start(Duration)} runs {@link #tick()} immediately
 * and then periodically on a single daemon thread ({@code fleet-auto-dispatch});
 * {@link #stop()} is idempotent, prevents further ticks and shuts the
 * executor down (also for an injected one — ownership transfers on
 * start). Direct {@link #tick()} calls work before start, but are disabled
 * after stop until restarted. Stop waits for an already-entered launch
 * callback; it does not cancel accepted workers.</p>
 */
public final class DispatchScheduler {

    /** How long a launched ticket is held back from re-admission even when the running set has not caught up. */
    public static final Duration LAUNCH_HOLD = Duration.ofMinutes(2);

    private static final Logger LOG = Logger.getLogger(DispatchScheduler.class.getName());

    private final AutoDispatch policy;
    private final Supplier<List<Task>> sprintTasks;
    private final Supplier<List<Task>> readinessTasks;
    private final Supplier<CostOverview> cost;
    private final Supplier<Set<String>> runningTaskIds;
    private final BiConsumer<String, LaunchAttempt> launch;
    private final Clock clock;
    private final Map<String, String> failedRevision = new ConcurrentHashMap<>();
    private final Map<String, LaunchAttempt> attempts = new ConcurrentHashMap<>();

    /** Feedback for exactly one submission. Pass this token to the queued worker
     * and call deferred() if readiness/admission revalidation prevents execution.
     * Safe before the launch callback returns, after restart, and on repeated calls.
     * A token never alters another attempt. Ordinary run failures must not defer. */
    public static final class LaunchAttempt {
        private final String revision;
        private final Instant submittedAt;
        private volatile boolean deferred;

        private LaunchAttempt(String revision, Instant submittedAt) {
            this.revision = revision;
            this.submittedAt = submittedAt;
        }

        public void deferred() {
            deferred = true;
        }
    }

    /** Async launch contract: normal return accepts the submission; the token
     * permits a later pre-execution deferral to release its revision/echo hold. */
    public static DispatchScheduler withFeedback(AutoDispatch policy, Supplier<List<Task>> sprintTasks,
            Supplier<List<Task>> readinessTasks, Supplier<CostOverview> cost,
            Supplier<Set<String>> runningTaskIds, BiConsumer<String, LaunchAttempt> launch, Clock clock) {
        return new DispatchScheduler(policy, sprintTasks, readinessTasks, cost, runningTaskIds, clock, launch);
    }

    /** Guards lifecycle and admission callbacks; data suppliers run unlocked. */
    private final Object lifecycleLock = new Object();
    private final Object admissionLock = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long generation;
    private boolean stopped;
    private boolean calibrateCosts;

    /** Opt in before starting; recalibrate from each tick's cost snapshot. */
    public DispatchScheduler withCalibratedCosts() {
        calibrateCosts = true;
        return this;
    }

    /**
     * Readiness-scope-less convenience: readiness is evaluated over the
     * sprint list itself. Only correct when every ticket proves its upstream
     * through its own advance history — an epic parent outside the sprint is
     * invisible and its children read as WAIT_UPSTREAM forever. Prefer
     * {@link #DispatchScheduler(AutoDispatch, Supplier, Supplier, Supplier, Supplier, Consumer, Clock)}.
     */
    public DispatchScheduler(AutoDispatch policy, Supplier<List<Task>> sprintTasks,
            Supplier<CostOverview> cost, Supplier<Set<String>> runningTaskIds,
            Consumer<String> launch, Clock clock) {
        this(policy, sprintTasks, sprintTasks, cost, runningTaskIds, launch, clock);
    }

    /**
     * @param policy          the dispatch policy ticked each round
     * @param sprintTasks     the CURRENT sprint's tickets (already
     *                        sprint-scoped by the caller — the guardrail:
     *                        only these are ever admitted); re-read every tick
     * @param readinessTasks  the tickets readiness is evaluated over — the
     *                        WHOLE project, because
     *                        {@link StageReadiness#evaluate(List)} resolves
     *                        upstream evidence through the epic chain and an
     *                        epic parent usually sits outside the sprint;
     *                        re-read every tick
     * @param cost            the cost overview the budget counts; re-read
     *                        every tick
     * @param runningTaskIds  ticket ids with a live fleet job; re-read
     *                        every tick
     * @param launch          launches one admitted ticket id (isolated —
     *                        a throw is logged, never propagated)
     * @param clock           times the launch-echo hold;
     *                        {@code null} reads as the system clock
     */
    public DispatchScheduler(AutoDispatch policy, Supplier<List<Task>> sprintTasks,
            Supplier<List<Task>> readinessTasks, Supplier<CostOverview> cost,
            Supplier<Set<String>> runningTaskIds, Consumer<String> launch, Clock clock) {
        this(policy, sprintTasks, readinessTasks, cost, runningTaskIds, clock, adapt(launch));
    }

    private static BiConsumer<String, LaunchAttempt> adapt(Consumer<String> launch) {
        Objects.requireNonNull(launch, "launch");
        return (id, attempt) -> launch.accept(id);
    }

    private DispatchScheduler(AutoDispatch policy, Supplier<List<Task>> sprintTasks,
            Supplier<List<Task>> readinessTasks, Supplier<CostOverview> cost,
            Supplier<Set<String>> runningTaskIds, Clock clock, BiConsumer<String, LaunchAttempt> launch) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sprintTasks = Objects.requireNonNull(sprintTasks, "sprintTasks");
        this.readinessTasks = Objects.requireNonNull(readinessTasks, "readinessTasks");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.runningTaskIds = Objects.requireNonNull(runningTaskIds, "runningTaskIds");
        this.launch = Objects.requireNonNull(launch, "launch");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * One dispatch wave, never throws: evaluates readiness over the
     * readiness-scope supplier's tickets (the whole project), delegates to
     * {@link AutoDispatch#plan(List, Map, CostOverview, Set)} with the
     * sprint-scoped candidates and the current running set, holds back
     * recently launched ids, then launches the rest through the consumer.
     *
     * @return the plan as executed — {@code launch} lists what really
     *         went out this tick, {@code skipped} everyone else with a
     *         reason
     */
    public synchronized AutoDispatch.DispatchPlan tick() {
        long current;
        synchronized (lifecycleLock) {
            if (stopped) {
                return new AutoDispatch.DispatchPlan(List.of(), List.of());
            }
            current = generation;
        }
        return tick(current);
    }

    private AutoDispatch.DispatchPlan tick(long current) {
        List<Task> sprint;
        List<Task> scope;
        CostOverview overview;
        Set<String> runningIds;
        try {
            sprint = sprintTasks.get();
            scope = readinessTasks.get();
            overview = cost.get();
            runningIds = runningTaskIds.get();
            sprint = sprint == null ? List.of() : sprint;
            scope = scope == null ? List.of() : scope;
            runningIds = runningIds == null ? Set.of() : runningIds;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "dispatch tick skipped: data supplier failed: " + e.getMessage(), e);
            return new AutoDispatch.DispatchPlan(List.of(), List.of());
        }
        Instant now = clock.instant();
        // Echo-held launches still consume slots/cost. Failed attempts do not,
        // but cannot starve later candidates or retry forever on unchanged input.
        Set<String> occupied = new java.util.HashSet<>(runningIds);
        attempts.forEach((id, attempt) -> {
            if (!attempt.deferred && Duration.between(attempt.submittedAt, now).compareTo(LAUNCH_HOLD) < 0) {
                occupied.add(id);
            }
        });
        List<Task> candidates = new ArrayList<>();
        Map<String, String> revisions = new java.util.HashMap<>();
        List<AutoDispatch.Skip> skipped = new ArrayList<>();
        for (Task task : sprint) {
            if (task == null || task.id == null) {
                continue;
            }
            String revision = revision(task);
            revisions.put(task.id, revision);
            LaunchAttempt previous = attempts.get(task.id);
            if (previous != null && !previous.deferred && revision.equals(previous.revision)) {
                skipped.add(new AutoDispatch.Skip(task.id, "launch accepted; waiting for ticket input to change"));
            } else if (revision.equals(failedRevision.get(task.id))) {
                skipped.add(new AutoDispatch.Skip(task.id, "launch failed; waiting for ticket input to change or auto restart"));
            } else {
                candidates.add(task);
            }
        }
        failedRevision.keySet().retainAll(revisions.keySet());
        attempts.keySet().retainAll(revisions.keySet());
        AutoDispatch.DispatchPlan planned =
                (calibrateCosts ? policy.withEstimateUsd(AutoDispatch.calibratedEstimate(overview)) : policy)
                        .plan(candidates, StageReadiness.evaluate(scope), overview, occupied);
        skipped.addAll(planned.skipped());
        List<String> accepted = new ArrayList<>();
        for (String id : planned.launch()) {
            synchronized (admissionLock) {
                synchronized (lifecycleLock) {
                    if (stopped || current != generation) {
                        skipped.add(new AutoDispatch.Skip(id, "auto-dispatch stopped or replaced"));
                        continue;
                    }
                }
                // Publish before invoking user code. Feedback only changes this
                // token, so an early deferral cannot be overwritten on return.
                LaunchAttempt attempt = new LaunchAttempt(revisions.get(id), now);
                attempts.put(id, attempt);
                try {
                    launch.accept(id, attempt);
                    failedRevision.remove(id);
                    if (attempt.deferred) {
                        skipped.add(new AutoDispatch.Skip(id, "submission deferred before execution"));
                    } else {
                        accepted.add(id);
                    }
                } catch (com.opencode.ide.fleet.DispatchGuard.AdmissionDeferred e) {
                    attempt.deferred();
                    skipped.add(new AutoDispatch.Skip(id, "admission deferred: " + e.getMessage()));
                } catch (RuntimeException e) {
                    attempts.remove(id, attempt);
                    failedRevision.put(id, revisions.get(id));
                    skipped.add(new AutoDispatch.Skip(id, "launch failed: " + e.getMessage()));
                    LOG.log(Level.WARNING, "dispatch launch of " + id + " failed: " + e.getMessage(), e);
                }
            }
        }
        return new AutoDispatch.DispatchPlan(List.copyOf(accepted), List.copyOf(skipped));
    }

    /** @return true while the background loop is scheduled. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Runs one tick immediately, then every {@code period}, on a fresh
     * single-thread daemon executor ({@code fleet-auto-dispatch}); replaces a
     * previous start.
     */
    public void start(Duration period) {
        start(period, com.opencode.ide.client.WorkerPools.timer("fleet-auto-dispatch"));
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
        synchronized (admissionLock) {
            synchronized (lifecycleLock) {
                requestStop();
                executor = scheduler;
                running = true;
                stopped = false;
                failedRevision.clear();
                attempts.clear();
                long current = generation;
                try {
                    scheduler.scheduleAtFixedRate(() -> runTick(current), 0, period.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    requestStop();
                    throw e;
                }
            }
        }
    }

    /** Stops the loop; idempotent. Prevents further ticks and shuts the executor down. */
    public void stop() {
        requestStop();
        synchronized (admissionLock) {
            // Barrier for callbacks that entered before invalidation.
        }
    }

    /** Invalidates scheduled work without waiting for a callback blocked in an
     * external admission lock. The caller must fence that callback's admission
     * itself (FleetControl uses its generation under its lifecycle monitor). */
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
    }

    /**
     * One scheduled tick: never launches once stopped, and a throwing tick
     * is logged, not propagated — the schedule keeps serving.
     */
    private synchronized void runTick(long current) {
        synchronized (lifecycleLock) {
            if (!running || stopped || current != generation) {
                return;
            }
        }
        try {
            tick(current);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "dispatch tick threw (kept serving): " + e.getMessage(), e);
        }
    }

    private static String revision(Task task) {
        return task.updatedAt + "|" + task.status + "|" + task.stage + "|" + task.sprint
                + "|" + task.blocked + "|" + task.history;
    }

}
