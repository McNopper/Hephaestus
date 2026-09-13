package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.ShellResult;
import com.opencode.ide.git.MergeResult;
import com.opencode.ide.git.Worktree;
import com.opencode.ide.git.WorktreeManager;

/**
 * Headless one-task-per-worktree orchestration: creates the task worktree,
 * runs the task's optional {@link Bootstrap} shell command in a
 * directory-scoped opencode session, sends the prompt (on its own daemon
 * thread via {@link #begin} — the TaskFleet watchdog, not the POST timeout,
 * decides completion; the legacy {@code submit}/{@code awaitCompletion} pair
 * remains for interactive callers), and merges the task branch back into the
 * main worktree.
 *
 * <p>Pure Java, no Eclipse/OSGi - the later Fleet view drives this engine.
 * Merge-back is not synchronized internally; callers must serialize it (see
 * {@link WorktreeManager#mergeBack}).</p>
 */
public class FleetRunner {

    private static final Logger LOG = Logger.getLogger(FleetRunner.class.getName());
    private static final long DEFAULT_POLL_MILLIS = FleetTuning.STATUS_POLL_MILLIS;
    private static final int BOOTSTRAP_OUTPUT_LIMIT = 200;

    private final OpencodeClient client;
    private final WorktreeManager worktrees;
    private final Runnable sleeper;
    /**
     * Submitted tasks by id, needed again at merge-back. Concurrent: one
     * runner is shared by every parallel launch thread of a fleet.
     */
    private final Map<String, FleetTask> tasks = new ConcurrentHashMap<>();

    public FleetRunner(OpencodeClient client, WorktreeManager worktrees) {
        this(client, worktrees, FleetRunner::sleepPollInterval);
    }

    /**
     * @param sleeper invoked between completion polls; inject a no-op to make
     *                {@link #awaitCompletion} run instantly in tests
     */
    public FleetRunner(OpencodeClient client, WorktreeManager worktrees, Runnable sleeper) {
        this.client = client;
        this.worktrees = worktrees;
        this.sleeper = sleeper;
    }

    /** One watchdog probe: message count + completion flag + busy flag (2 REST calls). */
    public record Activity(int messages, boolean complete, boolean busy) {
    }

    /**
     * A launch whose blocking prompt POST runs on its OWN daemon thread: the
     * returned job is RUNNING as soon as the session exists, and completion
     * is judged by polling (see {@link TaskFleet}'s watchdog) - a slow or
     * stuck HTTP response can never hold the launch hostage again (the old
     * blocking {@link #submit(FleetTask, Duration)} died with the POST).
     */
    public record Submission(FleetJob job, java.util.concurrent.CompletableFuture<ChatEntry> prompt) {

        /** @return the failure message when the prompt call already failed, else null */
        String promptFailure() {
            if (!prompt.isCompletedExceptionally()) {
                return null;
            }
            try {
                prompt.get();
                return null;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return cause.getMessage() != null ? cause.getMessage() : cause.toString();
            }
        }
    }

    /**
     * Starts a task: creates the worktree and session (and runs the optional
     * bootstrap) synchronously, then sends the prompt asynchronously with the
     * MAXIMUM budget - the watchdog, not the POST timeout, decides when a
     * run is done or stalled. Transport failures before the session exists
     * surface as a FAILED job exactly like the legacy submit.
     */
    public Submission begin(FleetTask task) {
        Worktree worktree = worktrees.create(task.baseWorktree(), task.taskId());
        String sessionId = null;
        try {
            Session session = client.createSession(task.title(), worktree.path());
            final String sid = session.id();
            sessionId = sid;
            runBootstrap(sid, task.bootstrap());
            tasks.put(task.taskId(), task);
            java.util.concurrent.CompletableFuture<ChatEntry> prompt = new java.util.concurrent.CompletableFuture<>();
            Thread t = new Thread(() -> {
                try {
                    prompt.complete(client.sendMessage(
                            chatRequest(sid, task), FleetTuning.MAX_TICKET_BUDGET));
                } catch (Throwable e) {
                    prompt.completeExceptionally(e);
                }
            }, "fleet-prompt-" + task.taskId());
            t.setDaemon(true);
            t.start();
            return new Submission(
                    new FleetJob(task.taskId(), sid, worktree.path(), FleetJob.State.RUNNING, null),
                    prompt);
        } catch (OpencodeException e) {
            return new Submission(
                    new FleetJob(task.taskId(), sessionId, worktree.path(), FleetJob.State.FAILED, e.getMessage()),
                    java.util.concurrent.CompletableFuture.failedFuture(e));
        }
    }

    /**
     * One watchdog probe of a running session: message count (progress
     * signal), the completion flag (idle + last message is an assistant reply
     * with text - the same contract as {@link #isComplete}) and the busy flag
     * (a session present as non-idle in the busy-only status map).
     */
    public Activity probe(String sessionId) throws OpencodeException {
        SessionStatus status = client.getSessionStatus().get(sessionId);
        boolean busy = status != null && !"idle".equals(status.type());
        List<ChatEntry> messages = client.getMessages(sessionId);
        ChatEntry last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        boolean complete = !busy && last != null && last.info() != null
                && "assistant".equals(last.info().role()) && !last.text().isBlank();
        return new Activity(messages.size(), complete, busy);
    }

    /** Best-effort abort of a session; tolerance for already-idle is the client's. */
    public void abort(String sessionId) {
        try {
            client.abortSession(sessionId);
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING, "aborting session " + sessionId + " failed: " + e.getMessage(), e);
        }
    }

    /** The pause between watchdog probes; the runner's test sleeper seam. */
    void pauseBetweenProbes() {
        sleeper.run();
    }

    /**
     * Delegates to {@link WorktreeManager#commitAll}: commits the fleet's own
     * main-worktree bookkeeping (the pre-claim), SCOPED to the task-store
     * subtree so the task branch starts from it and merge-back is never
     * refused over the dirty ticket file - and so nothing else in the repo is
     * ever swept into a fleet commit.
     */
    public void commitMain(Path repoRoot, String message) {
        worktrees.commitAll(repoRoot, com.opencode.ide.git.FleetGit.STORE_PATH, message);
    }

    /**
     * Creates the task worktree ({@code opencode/<taskId>} via the
     * {@link WorktreeManager}), opens a session scoped to it, runs the task's
     * optional {@link Bootstrap} command, and sends the prompt. On client
     * failure after worktree creation the returned job is {@code FAILED}; the
     * worktree is deliberately kept (see {@link FleetJob#worktree}) for
     * post-mortem inspection. A FAILED job still carries the session id when
     * the session was already created, so the caller can wind that session
     * down (permission watches, telemetry) instead of leaking it.
     */
    public FleetJob submit(FleetTask task) {
        return submit(task, FleetTuning.INTERACTIVE_PROMPT_TIMEOUT);
    }

    /**
     * @param promptTimeout the ticket's whole run budget for the blocking
     *                      prompt POST - the agent may stream for many minutes
     *                      before the final reply, and a short fixed cap
     *                      aborts healthy runs (Milestone V finding)
     */
    public FleetJob submit(FleetTask task, java.time.Duration promptTimeout) {
        Worktree worktree = worktrees.create(task.baseWorktree(), task.taskId());
        String sessionId = null;
        try {
            Session session = client.createSession(task.title(), worktree.path());
            sessionId = session.id();
            runBootstrap(sessionId, task.bootstrap());
            client.sendMessage(chatRequest(sessionId, task), promptTimeout);
            tasks.put(task.taskId(), task);
            return new FleetJob(task.taskId(), sessionId, worktree.path(), FleetJob.State.RUNNING, null);
        } catch (OpencodeException e) {
            return new FleetJob(task.taskId(), sessionId, worktree.path(), FleetJob.State.FAILED, e.getMessage());
        }
    }

    /**
     * Best-effort pre-prompt bootstrap: runs the task's optional shell
     * command in the new session ({@link OpencodeClient#runShell} - the call
     * blocks until the process exits, possibly for minutes, on this launch
     * thread like the prompt itself). A transport failure or an error status
     * is logged and the launch proceeds with the prompt - the bootstrap is a
     * convenience (e.g. {@code npm install}), never a gate. Never throws; a
     * {@code null} or blank command is no bootstrap at all.
     */
    private void runBootstrap(String sessionId, Bootstrap bootstrap) {
        if (bootstrap == null || bootstrap.command() == null || bootstrap.command().isBlank()) {
            return;
        }
        try {
            ShellResult result = client.runShell(sessionId, bootstrap.agent(), bootstrap.command());
            String status = result == null || result.status() == null ? "unknown" : result.status();
            String output = summarize(result == null ? null : result.output());
            if (status.toLowerCase(Locale.ROOT).contains("error")) {
                LOG.log(Level.WARNING, "fleet bootstrap '" + bootstrap.command() + "' in session "
                        + sessionId + " reported status " + status + "; proceeding with the prompt. output: "
                        + output);
            } else {
                LOG.fine(() -> "fleet bootstrap '" + bootstrap.command() + "' in session " + sessionId
                        + " finished with status " + status + ", output: " + output);
            }
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING, "fleet bootstrap '" + bootstrap.command() + "' in session "
                    + sessionId + " failed; proceeding with the prompt", e);
        }
    }

    /** Flattens and truncates a bootstrap output for the log summary. */
    private static String summarize(String output) {
        String flat = output == null ? "" : output.strip().replace('\n', ' ');
        return flat.length() <= BOOTSTRAP_OUTPUT_LIMIT ? flat
                : flat.substring(0, BOOTSTRAP_OUTPUT_LIMIT) + "...";
    }

    /**
     * Completion check: the session reports {@code idle} and its last message
     * is an assistant reply with non-empty text. As everywhere: since
     * opencode 1.18.23 {@code /session/status} lists busy sessions only, so an
     * ABSENT session is idle (requiring an explicit idle entry never completed
     * - Milestone V); only a present non-idle entry means busy.
     */
    public boolean isComplete(FleetJob job) throws OpencodeException {
        SessionStatus status = client.getSessionStatus().get(job.sessionId());
        if (status != null && !"idle".equals(status.type())) {
            return false;
        }
        List<ChatEntry> messages = client.getMessages(job.sessionId());
        if (messages.isEmpty()) {
            return false;
        }
        ChatEntry last = messages.get(messages.size() - 1);
        return last.info() != null
                && "assistant".equals(last.info().role())
                && !last.text().isBlank();
    }

    /**
     * Polls {@link #isComplete} until true or the timeout elapses.
     *
     * @return the job as {@code COMPLETED}, or {@code FAILED} with a timeout
     *         detail
     */
    public FleetJob awaitCompletion(FleetJob job, Duration timeout) throws OpencodeException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!isComplete(job)) {
            if (System.nanoTime() - deadline >= 0) {
                return withState(job, FleetJob.State.FAILED,
                        "timeout after " + timeout + " awaiting session " + job.sessionId());
            }
            sleeper.run();
        }
        return withState(job, FleetJob.State.COMPLETED, null);
    }

    /**
     * Merges a {@code COMPLETED} job's task branch back into the main
     * worktree.
     *
     * @return the job as {@code MERGED}, or {@code FAILED} with the conflicted
     *         files in the detail (the merge itself is aborted cleanly by the
     *         {@link WorktreeManager})
     */
    public FleetJob mergeBack(FleetJob job) {
        if (job.state() != FleetJob.State.COMPLETED) {
            throw new IllegalStateException("mergeBack requires a COMPLETED job, got " + job.state());
        }
        FleetTask task = tasks.get(job.taskId());
        if (task == null) {
            throw new IllegalStateException("unknown task " + job.taskId());
        }
        MergeResult result = worktrees.mergeBack(task.baseWorktree(), job.taskId());
        tasks.remove(job.taskId());
        if (result.merged()) {
            return withState(job, FleetJob.State.MERGED, null);
        }
        if (!result.conflictedFiles().isEmpty()) {
            return withState(job, FleetJob.State.FAILED,
                    "merge conflicts: " + String.join(", ", result.conflictedFiles()));
        }
        // no conflicts and still not merged: the guard's refusal (e.g.
        // "worker produced no changes") - carry its message, don't mislabel
        return withState(job, FleetJob.State.FAILED, result.output());
    }

    private static ChatRequest chatRequest(String sessionId, FleetTask task) {
        ChatRequest request = ChatRequest.of(sessionId, task.prompt());
        if (task.agent() != null && !task.agent().isBlank()) {
            request = request.withAgent(task.agent());
        }
        String model = task.model();
        if (model != null && model.contains("/")) {
            String provider = model.substring(0, model.indexOf('/'));
            String modelId = model.substring(model.indexOf('/') + 1);
            if (!provider.isBlank() && !modelId.isBlank()) {
                request = request.withModel(provider, modelId);
            }
        }
        return request;
    }

    private static FleetJob withState(FleetJob job, FleetJob.State state, String detail) {
        return new FleetJob(job.taskId(), job.sessionId(), job.worktree(), state, detail);
    }

    private static void sleepPollInterval() {
        try {
            Thread.sleep(DEFAULT_POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
