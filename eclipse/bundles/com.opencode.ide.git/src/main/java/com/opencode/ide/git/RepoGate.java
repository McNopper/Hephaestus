package com.opencode.ide.git;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * R2 (architecture review 2026-09-13): ONE process-wide, repo-root-keyed
 * serializer for every mutation of a repository's main working tree —
 * worktree/branch creation, the pre-claim commit, merge-back and store sync.
 *
 * <p>Why it exists: git does not queue on {@code .git/index.lock} — it fails
 * fast. Without a shared gate, two concurrent fleet launches (or a launch and
 * a store sync) intermittently lost that race and surfaced as blocked
 * tickets; worse, a per-instance Java lock (like the old TaskFleet.mergeLock)
 * only serialized ONE engine while the Board and a chat session each built
 * their own. The gate is keyed by the normalized absolute repo root — the
 * same key for every engine in this process — and holds no other lock inside
 * (deadlock-free by construction).</p>
 *
 * <p>Pure Java, no Eclipse/OSGi. The lock map never evicts: one entry per
 * distinct repository per process lifetime is bounded by usage.</p>
 */
public final class RepoGate {

    private static final Map<java.nio.file.Path, ReentrantLock> GATES = new ConcurrentHashMap<>();

    private RepoGate() {
    }

    /** Runs {@code action} while holding the repo's gate (value form). */
    public static <T> T with(java.nio.file.Path repoRoot, Supplier<T> action) {
        ReentrantLock lock = gate(repoRoot);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code action} while holding the repo's gate (void form). */
    public static void with(java.nio.file.Path repoRoot, Runnable action) {
        ReentrantLock lock = gate(repoRoot);
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock gate(java.nio.file.Path repoRoot) {
        return GATES.computeIfAbsent(
                repoRoot.toAbsolutePath().normalize(), key -> new ReentrantLock());
    }
}
