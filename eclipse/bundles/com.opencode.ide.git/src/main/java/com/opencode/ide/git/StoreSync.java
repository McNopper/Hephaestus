package com.opencode.ide.git;

import java.nio.file.Path;

import com.opencode.ide.git.internal.GitStore;

/**
 * The task store's pull→commit→push discipline for distributed fleets: N
 * machines share the git-versioned store repo (pull, claim, push; a push
 * conflict just means sync and re-claim). The board calls these primitives;
 * they never throw — failures surface as {@link Outcome} plus a git stderr
 * tail in the log.
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class StoreSync {

    private StoreSync() {
    }

    /** Result of a {@link #sync} or {@link #recover} run. */
    public enum Outcome {
        /** Nothing to commit and nothing unpushed — local and remote agree. */
        UP_TO_DATE,
        /** Local work was committed and/or pushed to the remote. */
        PUSHED,
        /** {@code git pull --rebase} hit a conflict; the rebase is left in progress for manual resolution ({@link #recover}). */
        PULL_CONFLICT,
        /** {@code git push} was rejected (non-fast-forward) — typically another machine pushed first; sync again. */
        PUSH_REJECTED,
        /** {@code root} is not a git working copy (or git cannot confirm it is one). */
        NOT_A_REPO,
        /** Any other git failure; the git stderr tail is in the log. */
        FAILED
    }

    /**
     * Runs the sync discipline on the store working copy at {@code root}:
     * {@code git add -A -- .} — <em>scoped to the store subtree</em> (a
     * pathspec-less {@code add -A} stages the entire repository regardless of
     * cwd, which would sweep host-repo WIP into a store commit; review
     * F1/F4) — then {@code git commit -m <message>} — skipped when nothing
     * is staged — then {@code git pull --rebase} (the tree is clean by then,
     * so no stash games), then {@code git push}.
     * Committing before the pull keeps the conflict surface well-defined: a
     * conflicted pull is always a plain rebase conflict, and local edits are
     * never left in a stash when a later step fails.
     *
     * <ul>
     * <li>{@link Outcome#PULL_CONFLICT} — the pull-rebase hit a conflict. The
     * rebase is left in progress and nothing is pushed; abort it with
     * {@link #recover}, resolve manually, then sync again.</li>
     * <li>{@link Outcome#PUSH_REJECTED} — the push was non-fast-forward;
     * syncing again re-pulls and retries.</li>
     * <li>{@link Outcome#FAILED} — any other git failure, including a pull on
     * a branch with no upstream; already-made local commits are kept (nothing
     * is lost — the next successful sync publishes them). The git stderr tail
     * is in the log.</li>
     * </ul>
     *
     * <p>{@link Outcome#UP_TO_DATE} means nothing was staged <em>and</em>
     * nothing was unpushed; {@link Outcome#PUSHED} means a commit and/or push
     * happened. A {@code null}/blank message falls back to a fixed default
     * commit message.</p>
     */
    public static Outcome sync(Path root, String message) {
        return GitStore.sync(root, message);
    }

    /**
     * Aborts a rebase left in progress by a {@link Outcome#PULL_CONFLICT}
     * ({@code git rebase --abort}), returning the working copy to its branch
     * at the pre-pull state — the conflicted pull is never auto-resolved.
     *
     * <ul>
     * <li>{@link Outcome#FAILED} — no rebase is in progress anymore (the
     * normal result; it means "nothing was synced", not "the abort failed").
     * Resolve leftover differences manually, then call {@link #sync} again.</li>
     * <li>{@link Outcome#PULL_CONFLICT} — a rebase is still in progress (the
     * abort failed; intervene with git manually).</li>
     * <li>{@link Outcome#NOT_A_REPO} — {@code root} is not a git working
     * copy.</li>
     * </ul>
     */
    public static Outcome recover(Path root) {
        return GitStore.recover(root);
    }
}
