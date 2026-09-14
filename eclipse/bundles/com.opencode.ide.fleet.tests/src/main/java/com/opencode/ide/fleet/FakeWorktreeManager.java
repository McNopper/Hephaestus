package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.opencode.ide.git.MergeResult;
import com.opencode.ide.git.Worktree;
import com.opencode.ide.git.WorktreeManager;
import com.opencode.ide.git.WorktreeStatus;

/**
 * In-memory fake of {@link WorktreeManager} for the fleet tests (no git).
 * Shared by {@link FleetRunnerTest} and {@link TaskFleetTest}; set
 * {@link #onMergeBack} to mutate the world as a real merge would.
 */
final class FakeWorktreeManager implements WorktreeManager {
    private final java.util.Map<String, String> projects = new java.util.concurrent.ConcurrentHashMap<>();
    Runnable onClaimProject;

    @Override
    public void claimProject(Path repoRoot, String project, String taskId) {
        String previous = projects.putIfAbsent(taskId, project);
        if (previous != null && !previous.equals(project)) {
            throw new IllegalStateException("fleet slot belongs to another project");
        }
        if (onClaimProject != null) {
            onClaimProject.run();
        }
    }

    final List<String> createdTaskIds = new ArrayList<>();
    final List<String> mergedTaskIds = new ArrayList<>();
    final List<Path> mergedRepoRoots = new ArrayList<>();
    final List<String> commitMessages = new ArrayList<>();
    MergeResult nextMergeResult = new MergeResult(true, List.of(), "merged");

    /** When set, {@link #mergeBack} throws - proving the total-failure contract (R1). */
    RuntimeException mergeBackFailure;

    /** Optional hook, invoked inside {@link #mergeBack} before the result is returned. */
    Runnable onMergeBack;

    @Override
    public void commitAll(Path repoRoot, String pathSpec, String message) {
        commitMessages.add(message + " @" + pathSpec);
    }

    @Override
    public Worktree create(Path repoRoot, String taskId) {
        createdTaskIds.add(taskId);
        liveWorktrees.add(taskId);
        return new Worktree(taskId, repoRoot.resolve(".git/opencode-fleet").resolve(taskId),
                "opencode/" + taskId);
    }

    @Override
    public MergeResult mergeBack(Path repoRoot, String taskId) {
        if (mergeBackFailure != null) {
            throw mergeBackFailure;
        }
        if (onMergeBack != null) {
            onMergeBack.run();
        }
        mergedTaskIds.add(taskId);
        mergedRepoRoots.add(repoRoot);
        return nextMergeResult;
    }

    /** Live worktrees = created minus removed (createdTaskIds keeps history). */
    private final List<String> liveWorktrees = new ArrayList<>();

    @Override
    public List<Worktree> list(Path repoRoot) {
        return liveWorktrees.stream()
                .map(id -> new Worktree(id,
                        repoRoot.resolve(".git/opencode-fleet").resolve(id), "opencode/" + id))
                .toList();
    }

    @Override
    public Optional<Worktree> find(Path repoRoot, String taskId) {
        return list(repoRoot).stream()
                .filter(w -> w.taskId().equals(taskId))
                .findFirst();
    }

    final List<String> removedTaskIds = new ArrayList<>();

    /** Files {@link #changedFiles} reports (the worker's diff); empty default = no evidence, the AC-path gate defers. */
    List<String> nextChangedFiles = new ArrayList<>();
    RuntimeException changedFilesFailure;
    final List<String> changedFilesCalls = new ArrayList<>();

    @Override
    public List<String> changedFiles(Path repoRoot, String taskId) {
        if (changedFilesFailure != null) {
            throw changedFilesFailure;
        }
        changedFilesCalls.add(taskId);
        return List.copyOf(nextChangedFiles);
    }

    @Override
    public void remove(Path repoRoot, String taskId, boolean force) {
        removedTaskIds.add((force ? "force:" : "") + taskId);
        // createdTaskIds keeps its history: tests assert create-vs-commit
        // ordering AFTER the reap consumed the worktree
        liveWorktrees.remove(taskId);
    }

    @Override
    public WorktreeStatus status(Path repoRoot, String taskId) {
        throw new UnsupportedOperationException();
    }
}
