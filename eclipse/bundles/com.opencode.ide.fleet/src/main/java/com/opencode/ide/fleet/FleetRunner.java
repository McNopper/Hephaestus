package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
     * Session-created callback seam: invoked with each new session's id
     * right after {@code createSession} returns and BEFORE the bootstrap
     * and prompt are sent. It exists because permission watching (see
     * {@link FleetPermissionBridge#sessionStarted}) needs the session id
     * before the first prompt: the prompt POST blocks until the agent's
     * final reply, and an unattended session that asks for permission waits
     * mid-run inside that very call - so the session must be watched from
     * the moment it is created, not after the launch returns. May be null.
     */
    private final Consumer<String> onSessionCreated;
    /**
     * Submitted tasks by id, needed again at merge-back. Concurrent: one
     * runner is shared by every parallel launch thread of a fleet.
     */
    private final Map<String, FleetTask> tasks = new ConcurrentHashMap<>();

    public FleetRunner(OpencodeClient client, WorktreeManager worktrees) {
        this(client, worktrees, FleetRunner::sleepPollInterval, null);
    }

    /**
     * @param sleeper invoked between completion polls; inject a no-op to make
     *                {@link #awaitCompletion} run instantly in tests
     */
    public FleetRunner(OpencodeClient client, WorktreeManager worktrees, Runnable sleeper) {
        this(client, worktrees, sleeper, null);
    }

    /**
     * @param onSessionCreated invoked with each new session's id before the
     *                         first prompt is sent (permission watching -
     *                         see the field comment); may be null
     */
    public FleetRunner(OpencodeClient client, WorktreeManager worktrees, Consumer<String> onSessionCreated) {
        this(client, worktrees, FleetRunner::sleepPollInterval, onSessionCreated);
    }

    /**
     * @param sleeper          invoked between completion polls; inject a no-op
     *                         to make {@link #awaitCompletion} run instantly
     *                         in tests
     * @param onSessionCreated invoked with each new session's id before the
     *                         first prompt is sent (permission watching -
     *                         see the field comment); may be null
     */
    public FleetRunner(OpencodeClient client, WorktreeManager worktrees, Runnable sleeper,
            Consumer<String> onSessionCreated) {
        this.client = client;
        this.worktrees = worktrees;
        this.sleeper = sleeper;
        this.onSessionCreated = onSessionCreated;
    }

    /**
     * One watchdog probe: message count, completion evidence, the busy flag
     * and the B-008 diagnostic carriers - the last assistant text (display
     * snippet + FULL length as the streaming-growth progress signal; the
     * 300-char snippet prefix stops changing while text keeps growing), the
     * last tool call {@code name [status]}, and whether the server stamped
     * this turn's end.
     */
    public record Activity(int messages, boolean complete, boolean busy, String lastAssistant,
            int assistantTextLength, String lastTool, boolean turnEnded) {

        /** Legacy shape (seams/tests): no diagnostics. */
        public Activity(int messages, boolean complete, boolean busy, String lastAssistant) {
            this(messages, complete, busy, lastAssistant, -1, null, false);
        }
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
            // before anything else touches the session: permission watching
            // needs the id before the first prompt (see onSessionCreated)
            if (onSessionCreated != null && sid != null) {
                onSessionCreated.accept(sid);
            }
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
     * Starts a worktree-FREE session (U-021 autonomous acceptance: the
     * read-only review pass over a merged ticket): creates the session
     * directly in {@code task.baseWorktree()} — the merged main worktree;
     * a reviewer has nothing to branch and nothing to merge — then sends
     * the prompt on its own daemon thread exactly like {@link #begin}, so
     * the {@link TaskFleet} watchdog, stall/budget handling and permission
     * watching apply unchanged. The returned job is never merged back:
     * {@link #mergeBack} refuses it ("unknown task") on purpose.
     */
    public Submission beginSession(FleetTask task) {
        String sessionId = null;
        try {
            Session session = client.createSession(task.title(), task.baseWorktree());
            final String sid = session.id();
            sessionId = sid;
            // before anything else touches the session: permission watching
            // needs the id before the first prompt (see onSessionCreated)
            if (onSessionCreated != null && sid != null) {
                onSessionCreated.accept(sid);
            }
            java.util.concurrent.CompletableFuture<ChatEntry> prompt = new java.util.concurrent.CompletableFuture<>();
            Thread t = new Thread(() -> {
                try {
                    prompt.complete(client.sendMessage(
                            chatRequest(sid, task), FleetTuning.MAX_TICKET_BUDGET));
                } catch (Throwable e) {
                    prompt.completeExceptionally(e);
                }
            }, "fleet-review-" + task.taskId());
            t.setDaemon(true);
            t.start();
            return new Submission(
                    new FleetJob(task.taskId(), sid, task.baseWorktree(), FleetJob.State.RUNNING, null),
                    prompt);
        } catch (OpencodeException e) {
            return new Submission(
                    new FleetJob(task.taskId(), sessionId, task.baseWorktree(),
                            FleetJob.State.FAILED, e.getMessage()),
                    java.util.concurrent.CompletableFuture.failedFuture(e));
        }
    }

    /**
     * One watchdog probe of a running session: message count (progress
     * signal), the completion evidence ({@link com.opencode.ide.client.model.Turns}:
     * the turn-end marker, or the finished-reply evidence - non-conversational
     * marker/shell rows never mask the reply and a still-streaming step is no
     * evidence), the busy flag (a session present as non-idle in the busy-only
     * status map), and the diagnostic carriers (see {@link Activity}).
     */
    public Activity probe(String sessionId) throws OpencodeException {
        SessionStatus status = client.getSessionStatus().get(sessionId);
        boolean busy = status != null && !"idle".equals(status.type());
        List<ChatEntry> messages = client.getMessages(sessionId);
        boolean turnEnded = com.opencode.ide.client.model.Turns.turnEnded(messages);
        boolean complete = !busy && (turnEnded
                || com.opencode.ide.client.model.Turns.replyEvidence(messages) != null);
        String lastAssistant = null;
        int assistantTextLength = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatEntry m = messages.get(i);
            if (m != null && m.info() != null && "assistant".equals(m.info().role()) && !m.text().isBlank()) {
                String text = m.text().strip();
                assistantTextLength = m.text().length();
                lastAssistant = text.length() <= 300 ? text : text.substring(0, 300) + "…";
                break;
            }
        }
        String lastTool = null;
        for (ChatEntry m : messages) {
            if (m == null) {
                continue;
            }
            for (com.opencode.ide.client.model.ChatPart part : m.parts()) {
                if (part != null && part.isTool()) {
                    lastTool = (part.tool() == null ? "tool" : part.tool()) + " [" + part.stateName() + "]";
                }
            }
        }
        return new Activity(messages.size(), complete, busy, lastAssistant,
                assistantTextLength, lastTool, turnEnded);
    }

    /** Best-effort abort of a session; tolerance for already-idle is the client's. */
    public void abort(String sessionId) {
        try {
            client.abortSession(sessionId);
        } catch (OpencodeException | RuntimeException e) {
            LOG.log(Level.WARNING, "aborting session " + sessionId + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * The session's full message history — the review verdict and the review
     * run's actuals both come from the last assistant reply; delegates to
     * the client.
     */
    public List<ChatEntry> messages(String sessionId) throws OpencodeException {
        return client.getMessages(sessionId);
    }

    /**
     * F-002: consumes a MERGED job's worktree and branch. Best-effort by
     * contract; callers log failures without failing the launch.
     */
    public void reap(Path repoRoot, String taskId) {
        worktrees.remove(repoRoot, taskId, true);
    }

    /** F-002 seam: locates the task's worktree, if any (reconciliation). */
    public void claimProject(Path repoRoot, String project, String taskId) {
        worktrees.claimProject(repoRoot, project, taskId);
    }

    public java.util.Optional<Worktree> findWorktree(Path repoRoot, String taskId) {
        return worktrees.find(repoRoot, taskId);
    }

    /**
     * Names of the files the task branch changed relative to the main
     * worktree's HEAD (committed branch changes plus uncommitted worktree
     * edits) - the evidence {@link TaskFleet}'s acceptance-criterion path
     * enforcement checks against the ticket before merging. Read-only.
     */
    public List<String> changedFiles(Path repoRoot, String taskId) {
        return worktrees.changedFiles(repoRoot, taskId);
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
            if (onSessionCreated != null && sessionId != null) {
                onSessionCreated.accept(sessionId);
            }
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
     * Completion check (the {@link com.opencode.ide.client.model.Turns}
     * contract): the session reports {@code idle} and the turn is over - the
     * server's {@code idle} turn-end marker, or the finished-reply evidence
     * (trailing conversational entry is an assistant reply with text, its
     * step stamped {@code time.completed}, no tool call/shell run in flight).
     * As everywhere: the status map lists busy sessions only (v2's
     * {@code /session/active} by definition), so an ABSENT session is idle;
     * only a present non-idle entry means busy.
     */
    public boolean isComplete(FleetJob job) throws OpencodeException {
        SessionStatus status = client.getSessionStatus().get(job.sessionId());
        if (status != null && !"idle".equals(status.type())) {
            return false;
        }
        List<ChatEntry> messages = client.getMessages(job.sessionId());
        return com.opencode.ide.client.model.Turns.turnEnded(messages)
                || com.opencode.ide.client.model.Turns.replyEvidence(messages) != null;
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
            // provider/modelId[#variant] - the variant splits off after '#'
            String variant = null;
            int hash = model.indexOf('#');
            if (hash >= 0) {
                variant = model.substring(hash + 1);
                model = model.substring(0, hash);
            }
            String provider = model.substring(0, model.indexOf('/'));
            String modelId = model.substring(model.indexOf('/') + 1);
            if (!provider.isBlank() && !modelId.isBlank()) {
                request = request.withModel(provider, modelId);
                if (variant != null && !variant.isBlank()) {
                    request = request.withVariant(variant);
                }
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
