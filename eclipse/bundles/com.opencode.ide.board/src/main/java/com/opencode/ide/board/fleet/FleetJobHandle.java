package com.opencode.ide.board.fleet;

/**
 * The board's view of a fleet job: a SWT-free row value. Mirrors the fleet
 * engine's {@code FleetJob} shape (taskId, sessionId, worktree, state,
 * detail) without coupling the board to the engine bundle.
 *
 * <p>F-004: rows reconstructed from the shared on-disk truth for jobs
 * launched by a PEER engine (a chat session's fleet server) carry
 * {@code external = true}; the Fleet view decorates them grey and offers
 * view-only actions (no abort, no take-over).</p>
 */
public record FleetJobHandle(String taskId, String sessionId, String worktree, State state, String detail,
        boolean external) {

    /** Lifecycle of a fleet job (mirrors the engine's states). */
    public enum State {
        RUNNING, COMPLETED, MERGED, FAILED
    }

    /** The historical five-argument shape: a row of THIS engine's own jobs. */
    public FleetJobHandle(String taskId, String sessionId, String worktree, State state, String detail) {
        this(taskId, sessionId, worktree, state, detail, false);
    }

    public boolean failed() {
        return state == State.FAILED;
    }
}
