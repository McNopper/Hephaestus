package com.opencode.ide.git;

import java.nio.file.Path;
import java.util.Optional;

import com.opencode.ide.git.internal.GitWorktreeManager;

/**
 * The fleet's git naming conventions, in one place: the branch and worktree
 * location every task gets. The git bundle owns these strings — callers (the
 * fleet engine, the board's launcher/diff, views showing worktree locations)
 * must never re-spell them.
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class FleetGit {

    private static final String BRANCH_PREFIX = "opencode/";

    /** The task store's conventional location inside the repo - the ONLY path fleet git discipline may commit. */
    public static final String STORE_PATH = ".opencode/tasks";
    private static final String BRANCH_REF_PREFIX = "refs/heads/" + BRANCH_PREFIX;

    private FleetGit() {
    }

    /** The fleet branch for a task: {@code opencode/<taskId>}. */
    public static String branchFor(String taskId) {
        return BRANCH_PREFIX + taskId;
    }

    /**
     * Inverse of {@link #branchFor} over a porcelain ref line: a full ref
     * {@code refs/heads/opencode/<taskId>} yields the task id, everything
     * else (other branches, {@code detached}) is empty. The branch — not the
     * worktree path — is the stable fleet marker: git reports worktree paths
     * in its own canonical form, which can differ from the caller's spelling
     * of the same directory (8.3 short names, symlinks), so path matching
     * loses worktrees on such machines.
     */
    public static Optional<String> taskIdOfRef(String ref) {
        if (ref == null || !ref.startsWith(BRANCH_REF_PREFIX)) {
            return Optional.empty();
        }
        String taskId = ref.substring(BRANCH_REF_PREFIX.length()).trim();
        return taskId.isEmpty() || taskId.contains("/") || taskId.contains("\\")
                ? Optional.empty()
                : Optional.of(taskId);
    }

    /** The fleet worktree root: {@code <repoRoot>/.git/opencode-fleet}. */
    public static Path fleetRoot(Path repoRoot) {
        repoRoot = repoRoot.toAbsolutePath().normalize();
        Path git = repoRoot.resolve(".git");
        try {
            if (java.nio.file.Files.isRegularFile(git)) {
                String pointer = java.nio.file.Files.readString(git).trim();
                if (!pointer.startsWith("gitdir:")) {
                    throw new IllegalStateException("invalid git directory pointer: " + git);
                }
                git = repoRoot.resolve(pointer.substring("gitdir:".length()).trim()).normalize();
                Path common = git.resolve("commondir");
                if (java.nio.file.Files.isRegularFile(common)) {
                    git = git.resolve(java.nio.file.Files.readString(common).trim()).normalize();
                }
            }
            // Canonicalize existing metadata so aliases share JVM locks as well
            // as the same OS lock. Non-repository test roots remain supported.
            if (java.nio.file.Files.exists(git)) {
                git = git.toRealPath();
            }
            return git.resolve("opencode-fleet");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot resolve fleet directory in " + repoRoot, e);
        }
    }

    /** The task's worktree location: {@code <repoRoot>/.git/opencode-fleet/<taskId>}. */
    public static Path worktreePath(Path repoRoot, String taskId) {
        return fleetRoot(repoRoot).resolve(taskId);
    }

    /** The default {@link WorktreeManager} implementation (git CLI backed). */
    public static WorktreeManager defaultManager() {
        return new GitWorktreeManager();
    }
}
