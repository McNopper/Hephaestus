package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * Task-driven front end over the {@link FleetRunner} engine: pre-claims the
 * ticket in the main store (so the claim rides on the task branch), sends a
 * {@link SelfClaimPrompt} to the role's agent ({@link RoleAgents}), awaits
 * completion, merges back (serialized - {@link WorktreeManager#mergeBack}
 * must be externally synchronized), and keeps the store in sync at every
 * step: {@code in-review} plus a git artifact on success, {@code blocked}
 * with a concrete reason on failure (worktree kept for post-mortem).
 *
 * <p>Completion detection is the internal WATCHDOG (2026-09-13 redesign): the
 * prompt POST runs on its own daemon thread while a probe loop
 * (busy/messages/complete) is authoritative — a finished session merges even
 * if the POST response is stuck; BUSY workers and sessions waiting on a
 * permission ask never trip the stall clock (stall = idle-and-silent, aborted
 * at {@link FleetTuning#STALL_TIMEOUT}); a budget timeout aborts the session.</p>
 *
 * <p>On a MERGED job, best-effort telemetry (see {@link FleetTelemetry})
 * records the run's cost/token actuals as a ticket comment. Telemetry needs
 * an {@link OpencodeClient}
 * (optional - the {@link FleetRunner} hides its own); without one it is
 * skipped, and it can never fail or block the launch.</p>
 *
 * <p>Permission requests (unattended sessions asking for human approval, see
 * {@link FleetPermissionBridge}/{@link PermissionQueue}) are collected when
 * the runner's session-created callback is wired to the bridge ({@code new
 * FleetRunner(client, worktrees, bridge::sessionStarted)}) and the bridge is
 * passed to the constructor: the job's session is watched from its creation
 * (before the prompt - the prompt call blocks while an ask is pending), and
 * when the launch ends (merged, failed, aborted) the session's pending
 * requests are dropped again. Without a bridge the fleet runs unchanged.</p>
 *
 * <p>U-021 AUTONOMOUS ACCEPTANCE (opt-in via {@link
 * #withAutonomousAcceptance()}; the production engine wiring enables it):
 * when a MERGED launch leaves the ticket {@code in-review} — the agent
 * finished the stage's work and left the accept gate to the engine — a
 * second, read-only REVIEW session under the reviewer agent judges the
 * acceptance criteria against the recorded artifacts and the merged
 * commit's CI status ({@link ReviewPrompt}), and the engine applies the
 * parsed verdict ({@link ReviewVerdict}): PASS&nbsp;&rarr;&nbsp;done +
 * {@link TaskStore#advance} into the next stage's backlog (the
 * auto-dispatch loop drains it - no human click); FAIL&nbsp;&rarr;&nbsp;
 * {@link TaskStore#sendBack} with the reviewer's reasons (blocked = the
 * human-escalation signal); UNCLEAR (or a failed/unparsable review)
 * &rarr;&nbsp;stays in-review with a comment - the sampled human surface.
 * The review run's actuals land on the ticket like any worker run, so the
 * wave budget absorbs its cost.</p>
 *
 * <p>Pure Java, no Eclipse/OSGi - the later Fleet view drives this.</p>
 */
public final class TaskFleet {

    private static final Logger LOG = Logger.getLogger(TaskFleet.class.getName());
    private static final Duration DEFAULT_TIMEOUT = FleetTuning.DEFAULT_TICKET_BUDGET;
    /**
     * The assignee every fleet engine writes when claiming a ticket — the
     * shared on-disk claim marker. Also matched by the board's peer-row
     * reconstruction (F-004), which rebuilds other engines' jobs from store
     * claims; keep it a literal-free single source.
     */
    public static final String ASSIGNEE = "fleet";
    /**
     * The author of every autonomous-acceptance store write (U-021): the
     * verdict comments and the {@code by} of the advance/send-back history
     * events — and the ROLE key the review session dispatches under
     * ({@link RoleAgents} maps it to the reviewer agent).
     */
    public static final String REVIEWER = "reviewer";
    /** Path-like strings inside acceptance criteria (e.g. {@code src/Foo.java}) - the AC-path gate's expected set. */
    private static final java.util.regex.Pattern AC_PATH =
            java.util.regex.Pattern.compile("[\\w/.-]+\\.\\w{1,5}");

    private final FleetRunner runner;
    private final TaskStore store;
    private final RoleAgents roleAgents;
    private final Supplier<OpencodeClient> telemetryClient;
    private final FleetPermissionBridge permissions;
    private final Map<String, FleetJob> jobsByTask = new ConcurrentHashMap<>();
    /** Tickets with a launch currently running; guards against double launches (one set-add is atomic). */
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** A running session with no new messages for this long is aborted by the watchdog (test seam). */
    private Duration stallTimeout = FleetTuning.STALL_TIMEOUT;
    /**
     * U-021 autonomous acceptance: dispatch the reviewer session when a
     * launch merges and settles the ticket to in-review. Off in the plain
     * constructors so worker-path tests keep the pre-U-021 settle behavior;
     * the production engine wiring (FleetControl.spawnEngine, the Board's
     * TaskFleetLauncher) turns it on.
     */
    private boolean reviewOnSettle;

    /** @param stallTimeout the watchdog's no-progress threshold; returns this for chaining */
    public TaskFleet withStallTimeout(Duration stallTimeout) {
        this.stallTimeout = stallTimeout == null || stallTimeout.isNegative() || stallTimeout.isZero()
                ? FleetTuning.STALL_TIMEOUT
                : stallTimeout;
        return this;
    }

    /**
     * U-021 autonomous acceptance: after a MERGED launch leaves the ticket
     * in-review, dispatch the read-only REVIEW session (reviewer agent) and
     * apply its verdict through the store — PASS: done + advance into the
     * next stage's backlog (the auto-dispatch loop drains it, no human
     * click); FAIL: send-back with the reviewer's reasons (blocked = the
     * human-escalation signal); UNCLEAR: stays in-review with a comment.
     *
     * @return this for chaining
     */
    public TaskFleet withAutonomousAcceptance() {
        this.reviewOnSettle = true;
        return this;
    }

    /** Creates its own {@link FleetRunner} over the given client and worktrees; the client also serves telemetry. */
    public TaskFleet(OpencodeClient client, WorktreeManager worktrees, TaskStore store) {
        this(new FleetRunner(client, worktrees), store, new RoleAgents(), () -> client);
    }

    /** @param runner a pre-configured runner (e.g. a test sleeper); telemetry disabled */
    public TaskFleet(FleetRunner runner, TaskStore store) {
        this(runner, store, new RoleAgents());
    }

    /** @param roleAgents the role -&gt; agent dispatch table to use */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents) {
        this(runner, store, roleAgents, null, null);
    }

    /**
     * @param telemetryClient supplies the client for post-merge telemetry
     *                        (cost actuals); {@code null} or a {@code null}
     *                        supply skips telemetry - see {@link FleetTelemetry}
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            Supplier<OpencodeClient> telemetryClient) {
        this(runner, store, roleAgents, telemetryClient, null);
    }

    /**
     * @param telemetryClient supplies the client for post-merge telemetry
     *                        (cost actuals); {@code null} or a {@code null}
     *                        supply skips telemetry - see {@link FleetTelemetry}
     * @param permissions     collects the job sessions' permission requests
     *                        (pair with the runner's session-created
     *                        callback, {@code bridge::sessionStarted});
     *                        {@code null} = no permission collection
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            Supplier<OpencodeClient> telemetryClient, FleetPermissionBridge permissions) {
        this.runner = runner;
        this.store = store;
        this.roleAgents = roleAgents;
        this.telemetryClient = telemetryClient;
        this.permissions = permissions;
    }

    /** {@link #launch(String, String, Path, Duration)} with the default 30-minute timeout. */
    public FleetJob launch(String project, String taskId, Path baseWorktree) {
        return launch(project, taskId, baseWorktree, DEFAULT_TIMEOUT);
    }

    /** {@link #launch(String, String, Path, Duration, Bootstrap)} without a bootstrap. */
    public FleetJob launch(String project, String taskId, Path baseWorktree, Duration timeout) {
        return launch(project, taskId, baseWorktree, timeout, null);
    }

    /** Auto-launch under a caller-owned reservation. Only this opt-in path may
     * reopen done work, and only while its current upstream verdict is STALE. */
    public FleetJob launchAuto(String project, String taskId, Path baseWorktree, Duration timeout,
            DispatchGuard reservation, boolean includeStale) {
        return launchAuto(project, taskId, baseWorktree, timeout, reservation, includeStale, null);
    }

    /** Auto-launch with the Board's optional bootstrap command. */
    public FleetJob launchAuto(String project, String taskId, Path baseWorktree, Duration timeout,
            DispatchGuard reservation, boolean includeStale, Bootstrap bootstrap) {
        reservation.withOwnership(baseWorktree, project, taskId, () -> {
            runner.claimProject(baseWorktree, project, taskId);
            try {
                store.prepareAutoDispatch(project, taskId, includeStale, ASSIGNEE);
            } catch (TaskStore.Invalid e) {
                throw new DispatchGuard.AdmissionDeferred(e.getMessage());
            }
            return null;
        });
        return launch(project, taskId, baseWorktree, timeout, bootstrap);
    }

    /**
     * Launches one ticket end-to-end: pre-claim, worktree + session + prompt
     * (via the {@link FleetRunner}), await completion, merge back, and store
     * bookkeeping.
     *
     * @param project     the task-store project the ticket lives in
     * @param taskId      the ticket id
     * @param baseWorktree the main worktree to branch from (the repo root)
     * @param timeout     how long to await the agent session
     * @param bootstrap   optional pre-prompt shell command in the new session
     *                    (best-effort, never gates the launch; {@code null} or
     *                    a blank command means none - see {@link Bootstrap})
     * @return the final job: {@code MERGED} on success, or {@code FAILED}
     *         with the ticket blocked (submit failure, timeout, merge
     *         conflict - the worktree is kept for post-mortem in all cases)
     * @throws IllegalStateException if the ticket is blocked, already done, or
     *         already has a fleet job in flight
     */
    public FleetJob launch(String project, String taskId, Path baseWorktree, Duration timeout,
            Bootstrap bootstrap) {
        return launch(project, taskId, baseWorktree, timeout, bootstrap, null);
    }

    /**
     * {@link #launch(String, String, Path, Duration, Bootstrap)} with a
     * per-run model override ({@code provider/modelId[#variant]}), winning
     * over the ticket's {@code model} field for this run only.
     */
    public FleetJob launch(String project, String taskId, Path baseWorktree, Duration timeout,
            Bootstrap bootstrap, String modelOverride) {
        if (!inFlight.add(taskId)) {
            throw new IllegalStateException(
                    "ticket " + taskId + " already has a fleet job in flight (one launch per ticket at a time)");
        }
        try {
            return launchGuarded(project, taskId, baseWorktree, timeout, bootstrap, modelOverride);
        } finally {
            inFlight.remove(taskId);
        }
    }

    /**
     * The launch lifecycle, one named stage per method: validation
     * ({@link #launchableTicket}) &rarr; pre-claim on the main branch
     * ({@link #claimAndCommit}) &rarr; submit + watchdog
     * ({@link #runSession}) &rarr; merge-back + bookkeeping + telemetry +
     * reap ({@link #mergeAndRecord}) &rarr; optional autonomous acceptance
     * ({@link #reviewSettledTicket}, U-021). Every stage failure routes
     * through {@link #blocked} so the ticket never strands as a zombie
     * claim, and the permission watch that started with the session is
     * dropped on EVERY outcome (the {@code finally}).
     */
    private FleetJob launchGuarded(String project, String taskId, Path baseWorktree, Duration timeout,
            Bootstrap bootstrap, String modelOverride) {
        Task ticket = launchableTicket(project, taskId);
        runner.claimProject(baseWorktree, project, taskId);
        FleetJob unclaimed = claimAndCommit(project, taskId, baseWorktree);
        if (unclaimed != null) {
            return unclaimed;
        }
        // cost lever: the ticket's model field, unless this run overrides it
        String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride : ticket.model;
        FleetTask task = new FleetTask(
                ticket.id,
                ticket.title,
                SelfClaimPrompt.forTicket(ticket).project(project).build(),
                roleAgents.agentFor(ticket.role),
                model,
                bootstrap,
                baseWorktree);
        FleetJob job = null;
        try {
            job = runSession(task, timeout);
            if (job.state() != FleetJob.State.COMPLETED) {
                return blocked(job, project, taskId, "fleet: " + job.detail());
            }
            FleetJob merged = mergeAndRecord(project, taskId, job, baseWorktree);
            if (reviewOnSettle && merged.state() == FleetJob.State.MERGED) {
                // U-021: acceptance is the first automated gate — the settle
                // path does not end at in-review, the reviewer session
                // decides it. Fully contained: can never fail the launch.
                reviewSettledTicket(project, taskId, baseWorktree, timeout);
            }
            return merged;
        } catch (RuntimeException e) {
            // R1 total-failure contract: after the pre-claim, NO path may
            // throw past this point - a thrown WorktreeException/UncheckedIo
            // would strand the ticket in-progress and contradict this class's
            // "blocked with a concrete reason on failure" promise
            FleetJob failed = job == null
                    ? new FleetJob(taskId, null, null, FleetJob.State.FAILED, e.getMessage())
                    : withState(job, FleetJob.State.FAILED, e.getMessage());
            LOG.log(Level.WARNING, "fleet launch of ticket " + taskId + " failed unexpectedly", e);
            return blocked(failed, project, taskId, "fleet: " + e.getMessage());
        } finally {
            // The prompt call inside submit blocks while an unattended session
            // waits for a permission answer - watching starts at session creation
            // (the wrapped client), and ends here on EVERY launch outcome.
            if (permissions != null && job != null && job.sessionId() != null) {
                permissions.sessionEnded(job.sessionId());
            }
        }
    }

    /**
     * Stage 0 — validation: fetches the ticket and rejects states the fleet
     * must not touch. A blocked ticket keeps its blocker (the retry
     * contract); a done ticket needs no work. Purely declarative — no store
     * write happens here, so rejection cannot leave partial state.
     *
     * @throws IllegalStateException if the ticket is blocked or already done
     */
    private Task launchableTicket(String project, String taskId) {
        Task ticket = store.get(project, taskId);
        if (ticket.blocked) {
            throw new IllegalStateException(
                    "ticket " + taskId + " is blocked: " + ticket.blocker);
        }
        if ("done".equals(ticket.status)) {
            throw new IllegalStateException("ticket " + taskId + " is already done");
        }
        return ticket;
    }

    /**
     * Stage 1 — claim &amp; commit: pre-claims the ticket in the MAIN store
     * BEFORE the worktree exists and commits that claim, so it is recorded
     * on the branch created next and the later merge-back is never refused
     * over the dirty ticket file (Milestone V finding: the merge failed
     * with zero conflicts). P1-5: from the first claim write onward, EVERY
     * failure lands in blocked()+releaseClaim — a commitMain git failure
     * must not strand the ticket as a zombie in-progress claim.
     *
     * @return {@code null} on success; otherwise the FAILED job with the
     *         ticket already blocked + released
     */
    private FleetJob claimAndCommit(String project, String taskId, Path baseWorktree) {
        try {
            store.update(project, taskId, Map.of(
                    "status", "in-progress",
                    "assignee", ASSIGNEE));
            store.addComment(project, taskId,
                    "launched into worktree opencode/" + taskId + " by the fleet", ASSIGNEE);
            runner.commitMain(baseWorktree, "fleet: pre-claim " + taskId);
            return null;
        } catch (RuntimeException e) {
            FleetJob failed = new FleetJob(taskId, null, null, FleetJob.State.FAILED, e.getMessage());
            LOG.log(Level.WARNING, "fleet pre-claim/commit of ticket " + taskId + " failed", e);
            return blocked(failed, project, taskId, "fleet: pre-claim failed: " + e.getMessage());
        }
    }

    /**
     * Stage 2 — session: begins the runner submission (worktree, branch,
     * session, optional bootstrap, and the self-claim prompt POST on its
     * own daemon thread), then settles it through the {@link #watchdog}
     * until the agent finished, stalled or the budget ran out. Never
     * throws — every failure mode returns as a FAILED job so the caller
     * can block the ticket uniformly.
     *
     * @return the settled job: {@code COMPLETED} when the agent finished
     *         (ready to merge), or {@code FAILED} with the concrete reason
     *         (submit failure, prompt failure, stall, budget timeout)
     */
    private FleetJob runSession(FleetTask task, Duration timeout) {
        String taskId = task.taskId();
        FleetRunner.Submission submission;
        FleetJob job;
        try {
            // the prompt POST runs on its OWN thread with the maximum budget;
            // the watchdog below - not the POST timeout - decides completion
            com.opencode.ide.client.ClientLog.info(
                    "fleet " + taskId + ": submit start (watchdog budget " + timeout + ")");
            submission = runner.begin(task);
            job = submission.job();
            com.opencode.ide.client.ClientLog.info(
                    "fleet " + taskId + ": submit returned state=" + job.state()
                            + (job.detail() == null ? "" : " detail=" + job.detail()));
        } catch (RuntimeException e) {
            // The ticket is already claimed (in-progress/assignee) at this point.
            // A submit that throws (worktree/branch already exists, git failure)
            // must not leave it claimed-but-not-blocked, contradicting this
            // class's "blocked with a concrete reason on failure" contract.
            LOG.log(Level.WARNING, "fleet submit of ticket " + taskId + " failed before the session started", e);
            return new FleetJob(taskId, null, null, FleetJob.State.FAILED, e.getMessage());
        }
        jobsByTask.put(taskId, job);
        if (job.state() == FleetJob.State.FAILED) {
            return job;
        }
        try {
            job = watchdog(submission, timeout);
        } catch (OpencodeException e) {
            job = withState(job, FleetJob.State.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "fleet watchdog of ticket " + taskId + " failed unexpectedly", e);
            job = withState(job, FleetJob.State.FAILED, e.getMessage());
        } finally {
            // the run has settled or been killed: free the prompt worker even
            // when the prompt POST is still blocked (it is interruptible) - a
            // leaked blocked task would starve the bounded pool (2026-09-23)
            submission.releaseWorker();
        }
        com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": await returned state=" + job.state());
        jobsByTask.put(taskId, job);
        return job;
    }

    /**
     * Stage 3 — merge &amp; record: merges the COMPLETED job's branch back
     * into the main worktree and, on success, does the store bookkeeping
     * (lift the fleet's own in-progress marking to in-review only when the
     * agent left it there; add the git branch artifact), best-effort
     * telemetry ({@link #recordTelemetry}) and the merged-worktree reap
     * ({@link #reapMergedWorktree}). A refused merge blocks the ticket
     * with the runner's conflict detail; the worktree is kept for
     * post-mortem.
     *
     * @return the final job: {@code MERGED} on success, or the FAILED job
     *         with the ticket already blocked
     */
    private FleetJob mergeAndRecord(String project, String taskId, FleetJob job, Path baseWorktree) {
        // worker-reliability gate BEFORE the merge: refuses analysis-only
        // runs with an actionable message while main is still clean
        FleetJob refused = enforceAcPaths(project, taskId, job, baseWorktree);
        if (refused != null) {
            return refused;
        }
        // merge-back rides the RepoGate (repo-root-keyed, shared by all
        // engines in this process) - the old per-instance mergeLock is
        // gone: it only serialized THIS engine while the Board and a
        // chat session each built their own
        job = runner.mergeBack(job);
        com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": merge returned state=" + job.state());
        jobsByTask.put(taskId, job);
        if (job.state() != FleetJob.State.MERGED) {
            // runner detail is "merge conflicts: <files>"
            return blocked(job, project, taskId, job.detail());
        }

        Task merged = store.get(project, taskId);
        // Only lift the fleet's OWN in-progress marking. The agent may already
        // have set done, task_advance'd the ticket into the NEXT stage's
        // product-backlog, or task_send_back'd it (blocked) — force-setting
        // in-review here would clobber that, fake completion in the next
        // stage's column, and pre-arm the advance quality gate.
        if ("in-progress".equals(merged.status)) {
            store.update(project, taskId, Map.of("status", "in-review"));
        }
        String ref = com.opencode.ide.git.FleetGit.branchFor(taskId);
        if (merged.artifacts.stream()
                .noneMatch(a -> "git".equals(a.kind()) && ref.equals(a.ref()))) {
            store.addArtifact(project, taskId, "git", ref,
                    "fleet branch merged back by TaskFleet", ASSIGNEE);
        }
        recordTelemetry(project, taskId, job);
        reapMergedWorktree(baseWorktree, taskId);
        com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": launch complete, state=" + job.state());
        return job;
    }

    /**
     * Stage 4 — autonomous acceptance (U-021): when the merged ticket
     * settled to {@code in-review} (the agent finished the stage's work and
     * left the accept gate to the engine), dispatch ONE read-only review
     * session under the reviewer agent ({@link RoleAgents} role
     * {@code reviewer}) — its {@link ReviewPrompt} judges every acceptance
     * criterion against the recorded artifacts and the CI status of the
     * merged commit — settle it through the same watchdog contract as a
     * worker run, record its actuals like any run ({@link
     * #recordReviewActuals}), and apply the parsed verdict ({@link
     * #applyVerdict}). Skipped when the agent already moved the ticket
     * itself (done, advanced, or sent back mid-run): the pipeline has
     * exactly one driver. Fully contained: no review-path failure can throw
     * past this method or fail the already-MERGED launch — the worst case
     * leaves the ticket in-review with a comment (the sampled human
     * surface).
     */
    private void reviewSettledTicket(String project, String taskId, Path baseWorktree, Duration timeout) {
        try {
            Task ticket = store.get(project, taskId);
            if (!"in-review".equals(ticket.status)) {
                return;
            }
            FleetTask reviewTask = new FleetTask(
                    taskId,
                    "Review " + ticket.id + ": " + ticket.title,
                    ReviewPrompt.forTicket(ticket).project(project).build(),
                    roleAgents.agentFor(REVIEWER),
                    null,
                    null,
                    baseWorktree);
            com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": dispatching review session");
            FleetJob reviewJob = settleReview(runner.beginSession(reviewTask), timeout);
            recordReviewActuals(project, taskId, reviewJob);
            if (reviewJob.state() != FleetJob.State.COMPLETED) {
                store.addComment(project, taskId,
                        "review: not performed - " + reviewJob.detail()
                                + "; the ticket waits in in-review for a human accept",
                        REVIEWER);
                com.opencode.ide.client.ClientLog.info("fleet " + taskId
                        + ": review session did not complete: " + reviewJob.detail());
                return;
            }
            applyVerdict(project, taskId, ReviewVerdict.parse(lastAssistantText(reviewJob.sessionId())));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "fleet review of ticket " + taskId
                    + " failed; ticket stays in-review", e);
            try {
                store.addComment(project, taskId,
                        "review: not performed - " + e.getMessage()
                                + "; the ticket waits in in-review for a human accept",
                        REVIEWER);
            } catch (RuntimeException suppressed) {
                LOG.log(Level.WARNING, "recording the failed-review comment on "
                        + taskId + " failed too", suppressed);
            }
        }
    }

    /**
     * Settles the review session through the same {@link #watchdog} contract
     * as a worker run (stall-, budget- and permission-wait aware) and drops
     * the permission watch on every outcome.
     */
    private FleetJob settleReview(FleetRunner.Submission submission, Duration timeout) {
        FleetJob job = submission.job();
        if (job.state() == FleetJob.State.FAILED) {
            if (permissions != null && job.sessionId() != null) {
                permissions.sessionEnded(job.sessionId());
            }
            return job;
        }
        try {
            return watchdog(submission, timeout);
        } catch (OpencodeException e) {
            return withState(job, FleetJob.State.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "fleet review watchdog failed unexpectedly", e);
            return withState(job, FleetJob.State.FAILED, e.getMessage());
        } finally {
            if (permissions != null && job.sessionId() != null) {
                permissions.sessionEnded(job.sessionId());
            }
        }
    }

    /**
     * Applies the parsed review verdict through the store (U-021):
     * <ul>
     *   <li><b>PASS</b> — the ticket moves to {@code done}, then
     *       {@link TaskStore#advance} runs, putting the NEXT stage's ticket
     *       into the wave backlog (the existing auto-dispatch loop drains
     *       it — no human click). At the V tip there is no next stage, and
     *       an unstaged ticket has no pipeline: both stay {@code done}.</li>
     *   <li><b>FAIL</b> — {@link TaskStore#sendBack} with the reviewer's
     *       reasons: the previous stage's backlog, blocked with the reason —
     *       exactly the human-escalation signal. The first stage
     *       ({@code requirements}) and unstaged tickets have nowhere to go
     *       back to and are blocked in place.</li>
     *   <li><b>UNCLEAR</b> — and a reply with no parseable verdict, which is
     *       treated the same way — the ticket stays in-review with the
     *       reviewer's doubt as a comment: the sampled human surface.</li>
     * </ul>
     */
    private void applyVerdict(String project, String taskId, ReviewVerdict verdict) {
        Task ticket = store.get(project, taskId);
        if (verdict == null) {
            store.addComment(project, taskId,
                    "review: UNCLEAR - the review reply carried no parseable verdict"
                            + "; the ticket waits in in-review for a human accept",
                    REVIEWER);
            return;
        }
        switch (verdict.decision()) {
            // exhaustive today (PASS/FAIL) - a future decision must fail loud,
            // never silently leave a verdict unapplied
            default -> throw new IllegalStateException(
                    "unhandled review decision: " + verdict.decision());
            case PASS -> {
                store.addComment(project, taskId,
                        "review: PASS" + reasonSuffix(verdict.reason()), REVIEWER);
                if (!"done".equals(ticket.status)) {
                    store.update(project, taskId, Map.of("status", "done"));
                }
                if (ticket.stage == null) {
                    return; // unstaged ticket: accepted and done, no pipeline to advance
                }
                try {
                    store.advance(project, taskId, REVIEWER);
                } catch (TaskStore.Invalid e) {
                    // the V tip has no next stage: accepted and done, the
                    // pipeline is complete — exactly the expected terminus
                    LOG.fine(() -> "fleet review of " + taskId
                            + " accepted the V-tip stage " + ticket.stage + "; staying done");
                }
            }
            case FAIL -> {
                String reason = verdict.reasonOrDefault();
                store.addComment(project, taskId,
                        "review: FAIL" + reasonSuffix(reason), REVIEWER);
                if (ticket.stage != null && VStages.previous(ticket.stage) != null) {
                    store.sendBack(project, taskId, reason, REVIEWER);
                } else {
                    // requirements (the first stage) or an unstaged ticket:
                    // no previous stage to return to — block in place
                    store.setBlocked(project, taskId, "review failed: " + reason, REVIEWER);
                }
            }
            case UNCLEAR -> store.addComment(project, taskId,
                    "review: UNCLEAR" + reasonSuffix(verdict.reasonOrDefault())
                            + "; the ticket waits in in-review for a human accept",
                    REVIEWER);
        }
    }

    /** {@code ""} for a blank reason, else {@code " - <reason>"} — the verdict comment suffix. */
    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " - " + reason.strip();
    }

    /**
     * The review reply's text (the LAST non-blank assistant message of the
     * review session), or {@code null}; never throws — an unreadable reply
     * parses as no verdict and stays in-review.
     */
    private String lastAssistantText(String sessionId) {
        try {
            List<ChatEntry> messages = runner.messages(sessionId);
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatEntry m = messages.get(i);
                if (m != null && m.info() != null && "assistant".equals(m.info().role())
                        && m.text() != null && !m.text().isBlank()) {
                    return m.text();
                }
            }
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING, "fleet review reply unavailable for session " + sessionId, e);
        }
        return null;
    }

    /**
     * U-021 AC: the review run's actuals land on the ticket like any worker
     * run — the standard {@code fleet actuals:} comment (see
     * {@link FleetTelemetry#actualsComment(List)}) — so the wave's cost
     * overview (CostOverview parses exactly those comments) absorbs the
     * reviewer session's cost against the budget. Best-effort: a telemetry
     * hiccup is logged and never gates the verdict.
     */
    private void recordReviewActuals(String project, String taskId, FleetJob reviewJob) {
        if (reviewJob.sessionId() == null) {
            return;
        }
        try {
            String comment = FleetTelemetry.actualsComment(runner.messages(reviewJob.sessionId()));
            if (comment != null) {
                store.addComment(project, taskId, comment, REVIEWER);
            }
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "fleet review actuals unavailable for ticket " + taskId + "; ignored", e);
        }
    }

    /**
     * Worker-reliability gate (2026-09-14): the executor-tier worker
     * sometimes "completes" with an assistant reply but produces no file
     * changes; the zero-commit guard only catches the fully-empty case.
     * When the ticket's acceptance criteria NAME file paths, the merge
     * additionally requires at least one of those paths among the branch's
     * changed files (committed plus pending) and refuses analysis-only
     * runs with an actionable message BEFORE main is touched. Behavioral
     * criteria without path-like strings skip the gate; an
     * unreadable diff fails verification and preserves the worktree,
     * and an entirely empty diff defers to the zero-commit guard's own
     * message. Matching is exact or path-segment suffix: an AC naming
     * {@code Foo.java} is satisfied by {@code src/Foo.java}.
     *
     * @return {@code null} to proceed to the merge; otherwise the FAILED
     *         job with the ticket already blocked + released (worktree
     *         kept for post-mortem)
     */
    private FleetJob enforceAcPaths(String project, String taskId, FleetJob job, Path baseWorktree) {
        List<String> expected = acPaths(store.get(project, taskId).acceptanceCriteria);
        if (expected.isEmpty()) {
            return null;
        }
        List<String> changed;
        try {
            changed = runner.changedFiles(baseWorktree, taskId);
        } catch (RuntimeException e) {
            String detail = "cannot verify acceptance-criterion paths: " + e.getMessage();
            LOG.log(Level.WARNING, "fleet AC-path probe of ticket " + taskId + " failed", e);
            return blocked(withState(job, FleetJob.State.FAILED, detail), project, taskId, detail);
        }
        if (changed.isEmpty()) {
            return null; // nothing at all: the runner's zero-commit guard owns that refusal
        }
        boolean anyAcPath = changed.stream()
                .anyMatch(file -> expected.stream()
                        .anyMatch(path -> file.equals(path) || file.endsWith("/" + path)));
        if (anyAcPath) {
            return null;
        }
        String detail = "analysis-only run: no acceptance-criterion path in the diff (expected one of: "
                + String.join(", ", expected) + ", got: " + String.join(", ", changed) + ")";
        LOG.log(Level.WARNING, "fleet merge of ticket " + taskId + " refused: " + detail);
        com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": merge refused: " + detail);
        return blocked(withState(job, FleetJob.State.FAILED, detail), project, taskId, detail);
    }

    /** Path-like strings named by the ticket's acceptance criteria, first-seen order, deduplicated. */
    private static List<String> acPaths(List<String> acceptanceCriteria) {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        for (String criterion : acceptanceCriteria) {
            if (criterion == null) {
                continue;
            }
            java.util.regex.Matcher m = AC_PATH.matcher(criterion);
            while (m.find()) {
                paths.add(m.group());
            }
        }
        return List.copyOf(paths);
    }

    /**
     * F-002: a MERGED job's worktree and branch are consumed - reap them so
     * re-dispatches (send-back rework, the next stage) never hit "branch
     * already exists". Best-effort: a failed reap logs and never fails the
     * (already successful) launch. FAILED jobs keep theirs for post-mortem.
     */
    private void reapMergedWorktree(Path baseWorktree, String taskId) {
        try {
            runner.reap(baseWorktree, taskId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "reaping the merged worktree of " + taskId + " failed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * F-002 startup reconciliation: release fleet claims whose work is gone -
     * a ticket in-progress with assignee "fleet" but NO branch/worktree left
     * is crash residue (the engine died mid-run). Each release is a blocked
     * marker with the reason, so the board tells the truth. Best-effort.
     */
    public int reconcileOrphanedClaims(String project) {
        int released = 0;
        Path repoRoot = store.root().getParent().getParent();
        for (Task t : store.list(project, null, "in-progress", null, null)) {
            if (!ASSIGNEE.equals(t.assignee)) {
                continue;
            }
            try {
                released += DispatchGuard.exclusive(repoRoot, () -> {
                    if (inFlight.contains(t.id) || DispatchGuard.runningIds(repoRoot).contains(t.id)) {
                        return 0;
                    }
                    // Claim snapshots can be older than the reservation we waited for.
                    Task current = store.get(project, t.id);
                    if (!"in-progress".equals(current.status) || !ASSIGNEE.equals(current.assignee)) {
                        return 0;
                    }
                    runner.claimProject(repoRoot, project, t.id);
                    if (runner.findWorktree(repoRoot, t.id).isEmpty()) {
                        blocked(new FleetJob(t.id, null, null, FleetJob.State.FAILED,
                                        "reconciled: no worktree/branch for the claim (engine crash residue)"),
                                project, t.id,
                                "reconciled: no worktree/branch for the claim (engine crash residue)");
                        return 1;
                    }
                    return 0;
                });
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "reconciling claim " + t.id + " failed: " + e.getMessage(), e);
            }
        }
        return released;
    }

    /** Snapshot of the tracked jobs by taskId (copy-on-read, never null). */
    public Map<String, FleetJob> jobs() {
        return Map.copyOf(jobsByTask);
    }

    /**
     * The watchdog: polls the session until the turn ends, the budget ends or
     * it STALLS (B-008 redesign, rubberduck F-2/F-3/F-4).
     *
     * <p><b>Completion</b> needs BOTH the {@link
     * com.opencode.ide.client.model.Turns} completion evidence AND the end of
     * the run: the server's {@code idle} turn-end marker (the authoritative v2
     * signal - it fires even while the prompt POST is stuck, so a finished run
     * settles within one probe, not after a sustained window), or the prompt
     * POST resolving. Evidence alone is not enough: it is ALSO true at every
     * INTER-STEP boundary of a multi-message turn (F-005: five concurrent
     * workers were falsely completed ~1 min in and failed "worker produced no
     * changes" while still streaming).</p>
     *
     * <p><b>Budgets</b>: the per-ticket budget ({@code timeout}) is
     * PROGRESS-AWARE - it is a no-progress window, reset ONLY by observed
     * progress: new messages, streaming assistant-text growth, tool activity
     * (B-008 AC: a session showing progress is never budget-killed; busy
     * alone is NOT progress - a busy-but-silent session is hung and is
     * stopped here). A pending permission ask or prompt POST resets the
     * STALL clock only (review F1: an ask is a question for the human, not a
     * stall - but with no PROGRESS the budget eventually stops the run: the
     * watchdog pauses, it does not decide). The absolute wall clock is
     * {@link FleetTuning#HARD_RUN_CAP} (F-4: cost runaway / concurrency-slot
     * starvation backstop). A session with no activity for
     * {@link #stallTimeout} is aborted ({@code POST /session/:id/interrupt})
     * and fails cleanly.</p>
     *
     * <p>Every abort carries a diagnostic snapshot (last assistant text + age,
     * last tool call + age, pending request state) into the FAILED detail -
     * and therefore into the ticket's blocker - so the next triage separates
     * model-hang from tool-hang from true-hang.</p>
     */
    private FleetJob watchdog(FleetRunner.Submission submission, Duration timeout) throws OpencodeException {
        FleetJob job = submission.job();
        long started = System.nanoTime();
        // runtime tuning (2026-09-23): when the caller kept a tuning default,
        // follow the LIVE knob so the idle/budget windows are adjustable while
        // workers run; explicit timeouts (tests, per-ticket overrides) win.
        Duration budget = FleetTuning.DEFAULT_TICKET_BUDGET.equals(timeout)
                ? com.opencode.ide.client.RuntimeTuning.ticketBudget()
                : timeout;
        Duration stall = (stallTimeout == null || FleetTuning.STALL_TIMEOUT.equals(stallTimeout))
                ? com.opencode.ide.client.RuntimeTuning.stallTimeout()
                : stallTimeout;
        long timeoutNanos = budget.toNanos();
        long started0 = System.nanoTime();
        long hardCapNanos = FleetTuning.HARD_RUN_CAP.toNanos();
        long budgetNanos = timeoutNanos;
        long stallNanos = stall.toNanos();
        int lastMessages = -1;
        int lastAssistantLength = -1;
        String lastTool = null;
        long lastAssistantChange = System.nanoTime();
        long lastToolChange = System.nanoTime();
        long lastProgress = System.nanoTime();
        long lastStallReset = System.nanoTime();
        while (true) {
            String promptFailure = submission.promptFailure();
            if (promptFailure != null) {
                return withState(job, FleetJob.State.FAILED, "prompt: " + promptFailure);
            }
            if (System.nanoTime() - started >= hardCapNanos) {
                // the budget below survives progress on purpose - THIS is the
                // absolute backstop (review F6 intent, F-4 renegotiated):
                // nothing runs (and burns tokens) past the hard run cap
                runner.abort(job.sessionId());
                return withState(job, FleetJob.State.FAILED,
                        "timeout after " + FleetTuning.HARD_RUN_CAP
                                + " (absolute run cap, despite progress) awaiting session " + job.sessionId()
                                + " | " + diagnostic(job, submission, null, lastAssistantChange, lastToolChange));
            }
            FleetRunner.Activity activity = null;
            try {
                activity = runner.probe(job.sessionId());
            } catch (OpencodeException | RuntimeException e) {
                // probe failed: keep watching, do NOT reset the progress clock
                LOG.fine(() -> "fleet probe of " + job.sessionId() + " failed: " + e.getMessage());
            }
            boolean permissionWait = permissions != null && permissions.pendingCount() > 0;
            // a PENDING prompt POST means the turn is still in flight server-
            // side: during one long generation the session is NOT in the busy
            // map and no new message ROWS appear (live-proven 2026-09-13: a
            // healthy build-agent stream was stall-killed at exactly 5 min).
            // The provider/server chunk timeout deals with dead streams and
            // unblocks the POST; the budget backstops the rest.
            boolean promptInFlight = !submission.prompt().isDone();
            if (activity != null) {
                boolean progressed = false;
                if (activity.messages() != lastMessages) {
                    lastMessages = activity.messages();
                    progressed = true;
                }
                // FULL text length, not the 300-char display snippet: past 300
                // chars the snippet prefix is stable while the reply keeps
                // streaming (F-4)
                if (activity.assistantTextLength() != lastAssistantLength) {
                    lastAssistantLength = activity.assistantTextLength();
                    lastAssistantChange = System.nanoTime();
                    progressed = true;
                }
                if (!java.util.Objects.equals(activity.lastTool(), lastTool)) {
                    lastTool = activity.lastTool();
                    lastToolChange = System.nanoTime();
                    progressed = true;
                }
                if (progressed) {
                    lastProgress = System.nanoTime();
                    lastStallReset = System.nanoTime(); // progress resets BOTH clocks
                }
                if (activity.complete() && (activity.turnEnded() || !promptInFlight)) {
                    return withState(job, FleetJob.State.COMPLETED, null);
                }
                // BUSY resets the STALL clock only (review F2: one long tool
                // call - a reactor build - is WORKING) - busy alone is NOT
                // observable PROGRESS: a busy session that stays silent is a
                // hung session and the no-progress budget stops it. (U-024's
                // healthy long run was saved by MESSAGE GROWTH, not by the
                // busy flag; B-008 live 2026-09-23: "busy = progress" made a
                // busy-static fixture unkillable and spun a test JVM for 67
                // minutes - rubberduck F-4 exactly.)
                if (activity.busy() || permissionWait || promptInFlight) {
                    lastStallReset = System.nanoTime();
                }
            } else if (promptInFlight || permissionWait) {
                lastStallReset = System.nanoTime();
            }
            long now = System.nanoTime();
            if (now - lastStallReset >= stallNanos) {
                runner.abort(job.sessionId());
                return withState(job, FleetJob.State.FAILED,
                        "stalled: session idle and silent for " + stallTimeout
                                + ", session aborted (the prompt was delivered; the worker hung)"
                                + " | " + diagnostic(job, submission, activity, lastAssistantChange, lastToolChange));
            }
            if (now - lastProgress >= budgetNanos) {
                runner.abort(job.sessionId());
                return withState(job, FleetJob.State.FAILED,
                        "timeout after " + timeout + " without observed progress (ticket budget)"
                                + (permissionWait ? "; a permission ask was never answered" : "")
                                + ", session " + job.sessionId() + " aborted"
                                + " | " + diagnostic(job, submission, activity, lastAssistantChange, lastToolChange));
            }
            runner.pauseBetweenProbes();
        }
    }

    /**
     * B-008 AC1: the abort's diagnostic snapshot - WHAT was the session
     * waiting on (model-hang vs tool-hang vs true-hang). Rendered into the
     * FAILED detail and therefore the ticket's blocker text.
     */
    private static String diagnostic(FleetJob job, FleetRunner.Submission submission,
            FleetRunner.Activity activity, long lastAssistantChange, long lastToolChange) {
        long now = System.nanoTime();
        boolean promptInFlight = submission != null && !submission.prompt().isDone();
        String pending = promptInFlight ? "prompt POST still in flight (turn in flight server-side)"
                : "none";
        String assistant = activity == null ? "unavailable (probe failing)"
                : activity.lastAssistant() == null ? "none seen"
                : "\"" + activity.lastAssistant().replace('\n', ' ') + "\" (changed "
                        + age(now, lastAssistantChange) + " ago)";
        String tool = activity == null ? "unavailable"
                : activity.lastTool() == null ? "none seen"
                : activity.lastTool() + " (changed " + age(now, lastToolChange) + " ago)";
        return "diagnostic: last assistant text " + assistant
                + "; last tool call " + tool
                + "; pending request: " + pending
                + (activity == null ? "" : "; messages=" + activity.messages() + " busy=" + activity.busy());
    }

    private static String age(long nowNanos, long sinceNanos) {
        return java.time.Duration.ofNanos(Math.max(0, nowNanos - sinceNanos)).toString();
    }

    /**
     * B-011: a failed run is LOUD on the ticket - the reason lands as a
     * COMMENT (the history trail a release would otherwise swallow) and as
     * the blocker, and only then is the fleet's own claim released (F-001:
     * never a zombie in-progress claim). Each step is independently guarded:
     * a comment write failure can never skip the release, and a store
     * failure can never hide the reason (it is logged). Callers keep the
     * worktree for post-mortem/rescue; the collision messages name the
     * rescue copy when a re-dispatch meets it (F-6 retry contract).
     */
    private FleetJob blocked(FleetJob job, String project, String taskId, String blocker) {
        try {
            store.addComment(project, taskId, "fleet failed: " + blocker, ASSIGNEE);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "recording the fleet failure comment on " + taskId + " failed", e);
        }
        try {
            store.setBlocked(project, taskId, blocker, ASSIGNEE);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "blocking " + taskId + " failed", e);
        }
        releaseClaim(project, taskId);
        jobsByTask.put(taskId, job);
        return job;
    }

    /**
     * F-001: a failed run must read as FAILED, never RUNNING. The fleet
     * releases its own in-progress claim back to sprint-backlog (the blocked
     * flag + reason stay as the retry contract), so readiness, the board and
     * any future auto-dispatcher see the truth instead of a zombie claim.
     */
    private void releaseClaim(String project, String taskId) {
        try {
            Task t = store.get(project, taskId);
            if (t != null && "in-progress".equals(t.status) && ASSIGNEE.equals(t.assignee)) {
                Map<String, Object> release = new java.util.HashMap<>();
                release.put("status", "sprint-backlog");
                release.put("assignee", null);
                store.update(project, taskId, release);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "releasing the fleet claim on " + taskId + " failed", e);
        }
    }

    /**
     * Best-effort telemetry on a MERGED job: the run's cost/token actuals as
     * a ticket comment. Each item is individually caught and logged -
     * telemetry can never fail the launch or block the ticket. Skipped
     * entirely when no telemetry client is wired (the {@link FleetRunner}
     * hides its own client).
     */
    private void recordTelemetry(String project, String taskId, FleetJob job) {
        if (telemetryClient == null || job.sessionId() == null) {
            LOG.fine(() -> "fleet telemetry skipped for ticket " + taskId + " (no telemetry client or session)");
            return;
        }
        OpencodeClient client;
        try {
            client = telemetryClient.get();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING,
                    "fleet telemetry client unavailable for ticket " + taskId + "; skipped", e);
            return;
        }
        if (client == null) {
            LOG.fine(() -> "fleet telemetry skipped for ticket " + taskId + " (no telemetry client)");
            return;
        }
        recordActualsComment(client, project, taskId, job.sessionId());
    }

    private void recordActualsComment(OpencodeClient client, String project, String taskId,
            String sessionId) {
        try {
            String comment = FleetTelemetry.actualsComment(client.getMessages(sessionId));
            if (comment != null) {
                store.addComment(project, taskId, comment, ASSIGNEE);
            }
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "fleet telemetry: cost actuals unavailable for ticket " + taskId + "; ignored", e);
        }
    }

    private static FleetJob withState(FleetJob job, FleetJob.State state, String detail) {
        return new FleetJob(job.taskId(), job.sessionId(), job.worktree(), state, detail);
    }
}
