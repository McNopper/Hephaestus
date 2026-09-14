package com.opencode.ide.git;

import java.util.function.Supplier;

/**
 * Cross-process serializer for fleet mutations of shared repository state —
 * worktree/branch creation, the pre-claim commit, merge-back and store sync.
 *
 * <p>Why it exists: git does not queue on {@code .git/index.lock} — it fails
 * fast. Without a shared gate, two concurrent fleet launches (or a launch and
 * a store sync) intermittently lost that race and surfaced as blocked
 * tickets; worse, a per-instance Java lock (like the old TaskFleet.mergeLock)
 * only serialized ONE engine while the Board and a chat session each built
 * their own. The gate uses the canonical common git directory, so linked
 * worktrees and path aliases share a permanent OS lock file. Nested calls
 * on the owning thread are reentrant. Callers must acquire admission before
 * this gate, never in the opposite order.</p>
 *
 * <p>Pure Java, no Eclipse/OSGi. The lock map never evicts: one entry per
 * distinct repository per process lifetime is bounded by usage.</p>
 */
public final class RepoGate {

    private RepoGate() {
    }

    /** Runs {@code action} while holding the repo's gate (value form). */
    public static <T> T with(java.nio.file.Path repoRoot, Supplier<T> action) {
        return FileGate.with(FleetGit.fleetRoot(repoRoot).resolve("repository.lock"), action);
    }

    /** Runs {@code action} while holding the repo's gate (void form). */
    public static void with(java.nio.file.Path repoRoot, Runnable action) {
        with(repoRoot, () -> {
            action.run();
            return null;
        });
    }
}
