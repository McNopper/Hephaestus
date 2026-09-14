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
import com.opencode.ide.client.model.SessionTodo;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

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
 * records the run's cost/token actuals as a ticket comment and merges new
 * session todos into the ticket. Telemetry needs an {@link OpencodeClient}
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
 * <p>Pure Java, no Eclipse/OSGi - the later Fleet view drives this.</p>
 */
public final class TaskFleet {

    private static final Logger LOG = Logger.getLogger(TaskFleet.class.getName());
    private static final Duration DEFAULT_TIMEOUT = FleetTuning.DEFAULT_TICKET_BUDGET;
    private static final String ASSIGNEE = "fleet";
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

    /** @param stallTimeout the watchdog's no-progress threshold; returns this for chaining */
    public TaskFleet withStallTimeout(Duration stallTimeout) {
        this.stallTimeout = stallTimeout == null || stallTimeout.isNegative() || stallTimeout.isZero()
                ? FleetTuning.STALL_TIMEOUT
                : stallTimeout;
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
     *                        (cost actuals + session todos); {@code null} or
     *                        a {@code null} supply skips telemetry - see
     *                        {@link FleetTelemetry}
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            Supplier<OpencodeClient> telemetryClient) {
        this(runner, store, roleAgents, telemetryClient, null);
    }

    /**
     * @param telemetryClient supplies the client for post-merge telemetry
     *                        (cost actuals + session todos); {@code null} or
     *                        a {@code null} supply skips telemetry - see
     *                        {@link FleetTelemetry}
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
        if (!inFlight.add(taskId)) {
            throw new IllegalStateException(
                    "ticket " + taskId + " already has a fleet job in flight (one launch per ticket at a time)");
        }
        try {
            return launchGuarded(project, taskId, baseWorktree, timeout, bootstrap);
        } finally {
            inFlight.remove(taskId);
        }
    }

    /**
     * The launch lifecycle, one named stage per method: validation
     * ({@link #launchableTicket}) &rarr; pre-claim on the main branch
     * ({@link #claimAndCommit}) &rarr; submit + watchdog
     * ({@link #runSession}) &rarr; merge-back + bookkeeping + telemetry +
     * reap ({@link #mergeAndRecord}). Every stage failure routes through
     * {@link #blocked} so the ticket never strands as a zombie claim, and
     * the permission watch that started with the session is dropped on
     * EVERY outcome (the {@code finally}).
     */
    private FleetJob launchGuarded(String project, String taskId, Path baseWorktree, Duration timeout,
            Bootstrap bootstrap) {
        Task ticket = launchableTicket(project, taskId);
        runner.claimProject(baseWorktree, project, taskId);
        FleetJob unclaimed = claimAndCommit(project, taskId, baseWorktree);
        if (unclaimed != null) {
            return unclaimed;
        }
        FleetTask task = new FleetTask(
                ticket.id,
                ticket.title,
                SelfClaimPrompt.forTicket(ticket).project(project).build(),
                roleAgents.agentFor(ticket.role),
                null,
                bootstrap,
                baseWorktree);
        FleetJob job = null;
        try {
            job = runSession(task, timeout);
            if (job.state() != FleetJob.State.COMPLETED) {
                return blocked(job, project, taskId, "fleet: " + job.detail());
            }
            return mergeAndRecord(project, taskId, job, baseWorktree);
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
     * The watchdog: polls the session until it completes, the budget ends or
     * it STALLS. Completion is judged purely by probing (busy flag + last
     * assistant reply) - the prompt POST's own fate is irrelevant except for
     * immediate failures, so a stuck HTTP response can never hold a finished
     * run hostage. A session with no new messages for {@link #stallTimeout}
     * is aborted ({@code POST /session/:id/abort}) and fails cleanly instead
     * of burning the whole budget on a hang. Slow-but-progressing workers are
     * never killed by a guessed wall clock.
     */
    private FleetJob watchdog(FleetRunner.Submission submission, Duration timeout) throws OpencodeException {
        FleetJob job = submission.job();
        long deadline = System.nanoTime() + timeout.toNanos();
        long stallNanos = stallTimeout.toNanos();
        int lastMessages = -1;
        long lastProgress = System.nanoTime();
        while (true) {
            String promptFailure = submission.promptFailure();
            if (promptFailure != null) {
                return withState(job, FleetJob.State.FAILED, "prompt: " + promptFailure);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                // don't leave the session running (and burning tokens) past
                // the budget the dispatcher granted (review F6)
                runner.abort(job.sessionId());
                return withState(job, FleetJob.State.FAILED,
                        "timeout after " + timeout + " awaiting session " + job.sessionId());
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
                if (activity.messages() != lastMessages) {
                    lastMessages = activity.messages();
                    lastProgress = System.nanoTime();
                }
                if (activity.complete()) {
                    return withState(job, FleetJob.State.COMPLETED, null);
                }
                // BUSY resets the stall clock: a single long tool call (a
                // reactor build, npm install) or one long generation emits no
                // new message rows while legitimately working - busy-and-
                // silent runs to the budget, only idle-and-silent is a hang
                // (review F2). Same for a session WAITING on a permission
                // answer: an ask is a question for the human, not a stall
                // (review F1) - the run dies only if nobody ever answers.
                if (activity.busy() || permissionWait || promptInFlight) {
                    lastProgress = System.nanoTime();
                }
            } else if (promptInFlight || permissionWait) {
                lastProgress = System.nanoTime();
            }
            if (System.nanoTime() - lastProgress >= stallNanos) {
                runner.abort(job.sessionId());
                return withState(job, FleetJob.State.FAILED,
                        "stalled: session idle and silent for " + stallTimeout
                                + ", session aborted (the prompt was delivered; the worker hung)");
            }
            runner.pauseBetweenProbes();
        }
    }

    private FleetJob blocked(FleetJob job, String project, String taskId, String blocker) {
        store.setBlocked(project, taskId, blocker, ASSIGNEE);
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
     * a ticket comment plus new session todos merged into the ticket. Each
     * item is individually caught and logged - telemetry can never fail the
     * launch or block the ticket. Skipped entirely when no telemetry client
     * is wired (the {@link FleetRunner} hides its own client).
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
        mergeSessionTodos(client, project, taskId, job.sessionId());
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

    private void mergeSessionTodos(OpencodeClient client, String project, String taskId,
            String sessionId) {
        try {
            List<SessionTodo> sessionTodos = client.getSessionTodos(sessionId);
            Task ticket = store.get(project, taskId);
            for (Task.Todo todo : FleetTelemetry.todosToMerge(sessionTodos, ticket.todos)) {
                try {
                    store.addTodo(project, taskId, todo.text(), todo.done(), ASSIGNEE);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "fleet telemetry: skipping todo '" + todo.text()
                            + "' for ticket " + taskId, e);
                }
            }
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "fleet telemetry: session todos unavailable for ticket " + taskId + "; ignored", e);
        }
    }

    private static FleetJob withState(FleetJob job, FleetJob.State state, String detail) {
        return new FleetJob(job.taskId(), job.sessionId(), job.worktree(), state, detail);
    }
}
