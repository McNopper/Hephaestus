package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
 * at {@link FleetTuning#STALL_TIMEOUT}); a budget timeout aborts the session.
 * The {@link SessionEvents} constructor seam is retained for compatibility
 * but no longer participates in completion.</p>
 *
 * <p>On a MERGED job, best-effort telemetry (see {@link FleetTelemetry})
 * records the run's cost/token actuals as a ticket comment and merges new
 * session todos into the ticket. Telemetry needs an {@link OpencodeClient}
 * (optional - the {@link FleetRunner} hides its own); without one it is
 * skipped, and it can never fail or block the launch.</p>
 *
 * <p>Permission requests (unattended sessions asking for human approval, see
 * {@link FleetPermissionBridge}/{@link PermissionQueue}) are collected when
 * the runner's client is wrapped with
 * {@link FleetPermissionBridge#watching(OpencodeClient)} and the bridge is
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

    private final FleetRunner runner;
    private final TaskStore store;
    private final RoleAgents roleAgents;
    private final SessionEvents events;
    private final Supplier<OpencodeClient> telemetryClient;
    private final FleetPermissionBridge permissions;
    private final ReentrantLock mergeLock = new ReentrantLock();
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
        this(new FleetRunner(client, worktrees), store, new RoleAgents(), null, () -> client);
    }

    /** @param runner a pre-configured runner (e.g. a test sleeper); telemetry disabled */
    public TaskFleet(FleetRunner runner, TaskStore store) {
        this(runner, store, new RoleAgents());
    }

    /** @param roleAgents the role -&gt; agent dispatch table to use */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents) {
        this(runner, store, roleAgents, null);
    }

    /**
     * @param events completion detection; {@code null} = the runner's own
     *               status polling (see {@link FleetRunner#awaitCompletion})
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            SessionEvents events) {
        this(runner, store, roleAgents, events, null);
    }

    /**
     * @param telemetryClient supplies the client for post-merge telemetry
     *                        (cost actuals + session todos); {@code null} or
     *                        a {@code null} supply skips telemetry - see
     *                        {@link FleetTelemetry}
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            SessionEvents events, Supplier<OpencodeClient> telemetryClient) {
        this(runner, store, roleAgents, events, telemetryClient, null);
    }

    /**
     * @param telemetryClient supplies the client for post-merge telemetry
     *                        (cost actuals + session todos); {@code null} or
     *                        a {@code null} supply skips telemetry - see
     *                        {@link FleetTelemetry}
     * @param permissions     collects the job sessions' permission requests
     *                        (pair with
     *                        {@link FleetPermissionBridge#watching(OpencodeClient)}
     *                        around the runner's client); {@code null} = no
     *                        permission collection
     */
    public TaskFleet(FleetRunner runner, TaskStore store, RoleAgents roleAgents,
            SessionEvents events, Supplier<OpencodeClient> telemetryClient,
            FleetPermissionBridge permissions) {
        this.runner = runner;
        this.store = store;
        this.roleAgents = roleAgents;
        this.events = events;
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

    private FleetJob launchGuarded(String project, String taskId, Path baseWorktree, Duration timeout,
            Bootstrap bootstrap) {
        Task ticket = store.get(project, taskId);
        if (ticket.blocked) {
            throw new IllegalStateException(
                    "ticket " + taskId + " is blocked: " + ticket.blocker);
        }
        if ("done".equals(ticket.status)) {
            throw new IllegalStateException("ticket " + taskId + " is already done");
        }

        // Pre-claim in the MAIN store BEFORE the worktree exists and COMMIT
        // it, so the claim is recorded on the branch created next and the
        // later merge-back is never refused over the dirty ticket file
        // (Milestone V finding: the merge failed with zero conflicts).
        store.update(project, taskId, Map.of(
                "status", "in-progress",
                "assignee", ASSIGNEE));
        store.addComment(project, taskId,
                "launched into worktree opencode/" + taskId + " by the fleet", ASSIGNEE);
        runner.commitMain(baseWorktree, "fleet: pre-claim " + taskId);

        FleetTask task = new FleetTask(
                ticket.id,
                ticket.title,
                SelfClaimPrompt.forTicket(ticket).project(project).build(),
                roleAgents.agentFor(ticket.role),
                null,
                bootstrap,
                baseWorktree);

        FleetJob job;
        FleetRunner.Submission submission;
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
            FleetJob failed = new FleetJob(taskId, null, null, FleetJob.State.FAILED, e.getMessage());
            LOG.log(Level.WARNING, "fleet submit of ticket " + taskId + " failed before the session started", e);
            return blocked(failed, project, taskId, "fleet: " + e.getMessage());
        }
        jobsByTask.put(taskId, job);
        // The prompt call inside submit blocks while an unattended session
        // waits for a permission answer - watching starts at session creation
        // (the wrapped client), and ends here on EVERY launch outcome.
        String permissionSession = job.sessionId();
        try {
            if (job.state() == FleetJob.State.FAILED) {
                return blocked(job, project, taskId, "fleet: " + job.detail());
            }

            try {
                job = watchdog(submission, timeout);
            } catch (OpencodeException e) {
                job = withState(job, FleetJob.State.FAILED, e.getMessage());
            }
            com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": await returned state=" + job.state());
            jobsByTask.put(taskId, job);
            if (job.state() != FleetJob.State.COMPLETED) {
                return blocked(job, project, taskId, "fleet: " + job.detail());
            }

            mergeLock.lock();
            try {
                job = runner.mergeBack(job);
            } finally {
                mergeLock.unlock();
            }
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
            com.opencode.ide.client.ClientLog.info("fleet " + taskId + ": launch complete, state=" + job.state());
            return job;
        } finally {
            if (permissions != null && permissionSession != null) {
                permissions.sessionEnded(permissionSession);
            }
        }
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
            }
            boolean permissionWait = permissions != null && permissions.pendingCount() > 0;
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
                if (activity.busy() || permissionWait) {
                    lastProgress = System.nanoTime();
                }
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
        jobsByTask.put(taskId, job);
        return job;
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
