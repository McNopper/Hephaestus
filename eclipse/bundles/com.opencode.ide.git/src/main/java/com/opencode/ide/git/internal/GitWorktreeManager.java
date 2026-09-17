package com.opencode.ide.git.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.opencode.ide.git.FleetGit;
import com.opencode.ide.git.MergeResult;
import com.opencode.ide.git.Worktree;
import com.opencode.ide.git.WorktreeException;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.git.WorktreeStatus;

/**
 * Worktree manager on top of the git CLI. All invocations go through
 * {@code git -C <dir> ...} with UTF-8 output capture and a timeout.
 */
public final class GitWorktreeManager implements WorktreeManager {

    private static final Duration DEFAULT_TIMEOUT = com.opencode.ide.git.GitTuning.COMMAND_TIMEOUT;
    private static final Duration MERGE_TIMEOUT = com.opencode.ide.git.GitTuning.MERGE_TIMEOUT;
    private static final Duration DRAIN_WAIT = com.opencode.ide.git.GitTuning.OUTPUT_DRAIN_WAIT;

    private final String gitCommand;
    private final String gitOrigin;

    public GitWorktreeManager() {
        com.opencode.ide.git.GitLocator.Resolution resolution = com.opencode.ide.git.GitLocator.resolve();
        this.gitCommand = resolution.command().toString();
        this.gitOrigin = resolution.source();
    }

    @Override
    public Worktree create(Path repoRoot, String taskId) {
        // R2: worktree/branch creation mutates the shared repo - serialized
        // by the repo gate so concurrent launches cannot race index.lock
        return com.opencode.ide.git.RepoGate.with(repoRoot, () -> createGuarded(repoRoot, taskId));
    }

    @Override
    public void claimProject(Path repoRoot, String project, String taskId) {
        requireTaskId(taskId);
        com.opencode.ide.git.FleetOwnership.claim(repoRoot, project, taskId, () -> {
            if (Files.exists(FleetGit.worktreePath(repoRoot, taskId))) {
                return true;
            }
            // Headless in-memory engines may use an empty metadata directory.
            if (!Files.exists(FleetGit.fleetRoot(repoRoot).getParent().resolve("HEAD"))) {
                return false;
            }
            GitOutput branch = run(repoRoot, DEFAULT_TIMEOUT, "rev-parse", "--verify", "--quiet",
                    "refs/heads/" + FleetGit.branchFor(taskId));
            if (branch.exitCode() != 0 && branch.exitCode() != 1) {
                throw new WorktreeException("cannot establish project ownership: branch probe failed: " + branch.stderr());
            }
            return branch.exitCode() == 0 || find(repoRoot, taskId).isPresent();
        });
    }

    private Worktree createGuarded(Path repoRoot, String taskId) {
        String branch = FleetGit.branchFor(requireTaskId(taskId));
        Path repo = repo(repoRoot);
        Path worktreePath = fleetRoot(repo).resolve(taskId);
        if (branchExists(repo, branch)) {
            throw new WorktreeException("Branch " + branch + " already exists for task '" + taskId + "'");
        }
        if (Files.exists(worktreePath)) {
            throw new WorktreeException("Worktree path already exists for task '" + taskId + "': " + worktreePath);
        }
        git(repo, "worktree", "add", "-b", branch, worktreePath.toString(), "HEAD");
        return new Worktree(taskId, worktreePath, branch);
    }

    @Override
    public List<Worktree> list(Path repoRoot) {
        Path repo = repo(repoRoot);
        GitOutput out = git(repo, "worktree", "list", "--porcelain");
        List<Worktree> result = new ArrayList<>();
        Path worktreePath = null;
        String ref = null;
        for (String line : out.stdout().split("\\R")) {
            if (line.startsWith("worktree ")) {
                worktreePath = Path.of(line.substring("worktree ".length()).trim()).toAbsolutePath().normalize();
            } else if (line.startsWith("branch ")) {
                ref = line.substring("branch ".length()).trim();
            } else if (line.isBlank()) {
                worktreePath = collect(result, worktreePath, ref);
                ref = null;
            }
        }
        collect(result, worktreePath, ref);
        return List.copyOf(result);
    }

    @Override
    public Optional<Worktree> find(Path repoRoot, String taskId) {
        requireTaskId(taskId);
        return list(repoRoot).stream()
                .filter(w -> w.taskId().equals(taskId))
                .findFirst();
    }

    @Override
    public void remove(Path repoRoot, String taskId, boolean force) {
        // R2: worktree removal + branch deletion mutate shared repo state
        com.opencode.ide.git.RepoGate.with(repoRoot, () -> removeGuarded(repoRoot, taskId, force));
    }

    private void removeGuarded(Path repoRoot, String taskId, boolean force) {
        requireTaskId(taskId);
        Path repo = repo(repoRoot);
        Worktree wt = find(repo, taskId).orElse(null);
        if (wt == null) {
            String branch = FleetGit.branchFor(taskId);
            if (branchExists(repo, branch)) {
                git(repo, "branch", force ? "-D" : "-d", branch);
            }
            // B-004: a previous `git worktree remove --force` that failed on
            // locked files already deleted the ADMIN registration while the
            // DIRECTORY survived (live-proven: git removes the admin before
            // unlinking the tree) - so the worktree is invisible to find()
            // from now on, yet the residue blocks the next `worktree add`
            // ("Worktree path already exists"). Consume the residue here.
            if (force) {
                deleteTree(FleetGit.worktreePath(repoRoot, taskId));
            }
            return;
        }
        List<String> removeArgs = new ArrayList<>(List.of("worktree", "remove"));
        if (force) {
            removeArgs.add("--force");
        }
        removeArgs.add(wt.path().toString());
        try {
            git(repo, removeArgs.toArray(new String[0]));
        } catch (WorktreeException removeFailed) {
            // B-004 live-found 2026-09-17: a leaked process holding files in
            // the worktree (a spawned opencode serve's watchers, a bash-tool
            // child with its CWD there) makes `git worktree remove --force`
            // fail - on Windows with "Permission denied" (exit 255) - and
            // the thrown error used to abort the whole reset BEFORE the
            // branch cleanup, so the next dispatch hit "Branch
            // opencode/<taskId> already exists". Recover instead: delete the
            // tree ourselves (locked files survive, everything else goes),
            // prune the stale registration, and only then give up - with the
            // original git error and the live-process explanation.
            if (!force) {
                throw removeFailed;
            }
            if (deleteTree(wt.path())) {
                // directory gone: drop the now-dangling registration so the
                // branch deletion below is not refused over a dead worktree
                run(repo, DEFAULT_TIMEOUT, "worktree", "prune");
            } else {
                try {
                    git(repo, removeArgs.toArray(new String[0])); // one retry: some locks release between attempts
                } catch (WorktreeException retryFailed) {
                    throw new WorktreeException("git worktree remove failed for " + wt.path()
                            + " - a live process holds files inside the worktree (stop it and retry): "
                            + removeFailed.getMessage());
                }
            }
        }
        git(repo, "branch", force ? "-D" : "-d", wt.branch());
    }

    /**
     * B-004: best-effort recursive delete of a worktree directory - the
     * recovery path when {@code git worktree remove --force} cannot (a
     * process holds files inside). Locked files survive; everything else
     * goes. Never throws.
     *
     * @return whether the directory is gone afterwards
     */
    private static boolean deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // locked by a live process - leave it
                }
            });
        } catch (IOException ignored) {
            // the tree could not be walked at all - report "still there"
        }
        return !Files.exists(root);
    }

    @Override
    public MergeResult mergeBack(Path repoRoot, String taskId) {
        // R2: the merge mutates the shared main tree - serialized by the repo
        // gate (this ALSO closes the cross-engine hole the old per-instance
        // mergeLock left: Board and chat engines share this gate)
        return com.opencode.ide.git.RepoGate.with(repoRoot, () -> mergeBackGuarded(repoRoot, taskId));
    }

    private MergeResult mergeBackGuarded(Path repoRoot, String taskId) {
        requireTaskId(taskId);
        Path repo = repo(repoRoot);
        if (mergeInProgress(repo)) {
            throw new WorktreeException("repository already has a merge in progress; recover it before fleet merge-back");
        }
        Worktree task = find(repo, taskId)
                .orElseThrow(() -> new WorktreeException("No fleet worktree for task '" + taskId + "'"));
        String branch = task.branch();
        Path worktree = task.path();
        // Milestone V finding #8: a worker that finishes WITHOUT committing
        // leaves a zero-commit branch, and `git merge` then exits 0 "already
        // up to date" - a MERGED + in-review + git artifact for NOTHING. So:
        // (a) pending worktree changes are auto-committed on the branch (the
        // worktree is the unit of work), then (b) a branch with no commits
        // beyond the merge base fails the merge explicitly.
        GitOutput pending = git(worktree, "status", "--porcelain");
        if (!pending.stdout().isBlank()) {
            git(worktree, "add", "-A");
            GitOutput workerCommit = run(worktree, DEFAULT_TIMEOUT, "commit", "-m",
                    "fleet: worker changes (auto-committed at merge-back)");
            if (workerCommit.exitCode() != 0) {
                throw new WorktreeException("auto-committing worker changes in " + worktree
                        + " failed (exit " + workerCommit.exitCode() + "): "
                        + workerCommit.stderr().trim());
            }
        }
        GitOutput ahead = run(repo, DEFAULT_TIMEOUT, "rev-list", "--count", "HEAD.." + branch);
        if (ahead.exitCode() == 0 && "0".equals(ahead.stdout().trim())) {
            return new MergeResult(false, List.of(),
                    "worker produced no changes (no commits on " + branch
                            + " and no pending worktree edits)");
        }
        // peer tolerance (review F5): a store write that landed between the
        // pre-claim and now (a PM comment, another ticket's telemetry) would
        // leave main dirty and git would refuse the merge outright - commit
        // the STORE SUBTREE only, then merge
        try {
            Path storeDir = repo.resolve(com.opencode.ide.git.FleetGit.STORE_PATH);
            if (Files.isDirectory(storeDir)) {
                commitAllGuarded(repo, FleetGit.STORE_PATH, "fleet: store bookkeeping before merge of " + taskId);
            }
        } catch (WorktreeException ignored) {
            // nothing staged ("nothing to commit") or a benign commit race -
            // the merge itself is the gate that matters
        }
        GitOutput merge = run(repo, MERGE_TIMEOUT, "merge", branch);
        String output = (merge.stdout() + merge.stderr()).trim();
        if (merge.exitCode() == 0) {
            return new MergeResult(true, List.of(), output);
        }
        List<String> conflicted = List.of();
        if (mergeInProgress(repo)) {
            GitOutput conflicts = run(repo, DEFAULT_TIMEOUT, "diff", "--name-only", "--diff-filter=U");
            conflicted = Arrays.stream(conflicts.stdout().split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            GitOutput abort = run(repo, DEFAULT_TIMEOUT, "merge", "--abort");
            if (abort.exitCode() != 0) {
                throw new WorktreeException("git merge --abort failed (exit " + abort.exitCode() + "): "
                        + abort.stderr().trim());
            }
        }
        return new MergeResult(false, conflicted, output);
    }

    @Override
    public WorktreeStatus status(Path repoRoot, String taskId) {
        String branch = FleetGit.branchFor(requireTaskId(taskId));
        Path repo = repo(repoRoot);
        if (!branchExists(repo, branch)) {
            return new WorktreeStatus(false, 0, "");
        }
        Path worktreePath = fleetRoot(repo).resolve(taskId);
        if (!Files.isDirectory(worktreePath)) {
            return new WorktreeStatus(false, 0, "");
        }
        GitOutput status = git(worktreePath, "status", "--porcelain");
        int dirty = (int) Arrays.stream(status.stdout().split("\\R"))
                .filter(s -> !s.isBlank())
                .count();
        GitOutput head = git(worktreePath, "rev-parse", "--short", "HEAD");
        return new WorktreeStatus(true, dirty, head.stdout().trim());
    }

    /**
     * Read-only (no repo gate): committed changes via the three-dot diff
     * (fork point to branch tip - main-side commits after the fork never
     * pollute the worker's file list) plus pending worktree edits from
     * {@code status --porcelain}.
     */
    @Override
    public List<String> changedFiles(Path repoRoot, String taskId) {
        requireTaskId(taskId);
        Path repo = repo(repoRoot);
        Worktree task = find(repo, taskId)
                .orElseThrow(() -> new WorktreeException("No fleet worktree for task '" + taskId + "'"));
        GitOutput committed = run(repo, DEFAULT_TIMEOUT, "diff", "--name-only", "HEAD..." + task.branch());
        if (committed.exitCode() != 0) {
            throw new WorktreeException("git diff HEAD..." + task.branch() + " failed (exit "
                    + committed.exitCode() + "): " + committed.stderr().trim());
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>(Arrays.stream(committed.stdout().split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        GitOutput pending = git(task.path(), "status", "--porcelain");
        for (String line : pending.stdout().split("\\R")) {
            String path = porcelainPath(line);
            if (path != null) {
                names.add(path);
            }
        }
        return List.copyOf(names);
    }

    /** The path of one {@code status --porcelain} line (post side of a rename; null when not parseable). */
    private static String porcelainPath(String line) {
        if (line == null || line.length() <= 3) {
            return null;
        }
        String path = line.substring(3).trim();
        int arrow = path.indexOf(" -> ");
        if (arrow >= 0) {
            path = path.substring(arrow + 4);
        }
        return path.isEmpty() ? null : path;
    }

    /**
     * Collects one porcelain stanza. The fleet marker is the branch ref
     * ({@code refs/heads/opencode/<taskId>}), never the path: git reports
     * worktree paths in its own canonical form, which can differ from the
     * caller's spelling of the same directory (8.3 short names on Windows,
     * symlinks), and path matching silently drops worktrees on such machines.
     *
     * @return null to reset the stanza accumulator
     */
    @Override
    public void commitAll(Path repoRoot, String pathSpec, String message) {
        // R2: main-tree commits ride the repo gate
        com.opencode.ide.git.RepoGate.with(repoRoot, () -> commitAllGuarded(repoRoot, pathSpec, message));
    }

    private void commitAllGuarded(Path repoRoot, String pathSpec, String message) {
        Path repo = repo(repoRoot);
        git(repo, "add", "-A", "--", pathSpec);
        GitOutput staged = git(repo, "diff", "--cached", "--name-only", "--", pathSpec);
        if (staged.stdout().isBlank()) {
            return;
        }
        GitOutput commit = run(repo, DEFAULT_TIMEOUT, "commit", "--only", "-m", message, "--", pathSpec);
        if (commit.exitCode() != 0
                && !(commit.stdout() + commit.stderr()).contains("nothing to commit")) {
            throw new WorktreeException("git commit failed (exit " + commit.exitCode() + "): "
                    + commit.stderr().trim());
        }
    }

    private static Path collect(List<Worktree> into, Path worktreePath, String ref) {
        var taskId = FleetGit.taskIdOfRef(ref);
        if (worktreePath != null && taskId.isPresent()) {
            into.add(new Worktree(taskId.get(), worktreePath, FleetGit.branchFor(taskId.get())));
        }
        return null;
    }

    private static Path repo(Path repoRoot) {
        return repoRoot.toAbsolutePath().normalize();
    }

    private static Path fleetRoot(Path repo) {
        return FleetGit.fleetRoot(repo);
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]+")) {
            throw new WorktreeException("invalid taskId: '" + taskId + "'");
        }
        return taskId;
    }

    private boolean branchExists(Path repo, String branch) {
        return run(repo, DEFAULT_TIMEOUT, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch)
                .exitCode() == 0;
    }

    private boolean mergeInProgress(Path repo) {
        return run(repo, DEFAULT_TIMEOUT, "rev-parse", "-q", "--verify", "MERGE_HEAD")
                .exitCode() == 0;
    }

    private record GitOutput(int exitCode, String stdout, String stderr) {
    }

    private GitOutput git(Path directory, String... args) {
        GitOutput out = run(directory, DEFAULT_TIMEOUT, args);
        if (out.exitCode() != 0) {
            throw new WorktreeException("git " + String.join(" ", args) + " failed (exit " + out.exitCode()
                    + "): " + out.stderr().trim());
        }
        return out;
    }

    private GitOutput run(Path directory, Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitCommand);
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new WorktreeException("Failed to start git (resolved via " + gitOrigin + ": " + gitCommand
                    + "): " + e.getMessage(), e);
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readUtf8(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            GitProcesses.terminate(process);
            Thread.currentThread().interrupt();
            throw new WorktreeException("Interrupted while waiting for git " + Arrays.toString(args), e);
        }
        if (!finished) {
            GitProcesses.terminate(process);
            throw new WorktreeException("git " + Arrays.toString(args) + " timed out after " + timeout);
        }
        return new GitOutput(process.exitValue(), join(stdout), join(stderr));
    }

    private static String join(CompletableFuture<String> future) {
        try {
            return future.get(DRAIN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readUtf8(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
