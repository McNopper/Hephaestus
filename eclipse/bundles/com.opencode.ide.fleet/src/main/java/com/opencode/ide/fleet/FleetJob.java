package com.opencode.ide.fleet;

import java.nio.file.Path;

/**
 * The immutable state of a submitted {@link FleetTask} as it moves through the fleet loop (submit,
 * await completion, merge back).
 *
 * @param taskId the task this job belongs to
 * @param sessionId the opencode session running the prompt ({@code null} when no session ever ran:
 *     session creation failed, or the dispatch settled as {@link State#PASSED})
 * @param worktree the task's git worktree; always reported once created, also on FAILED jobs (kept
 *     on disk for post-mortem inspection); {@code null} on a {@link State#PASSED} job
 * @param state lifecycle state
 * @param detail failure message ({@code null} unless {@code state == FAILED})
 */
public record FleetJob(String taskId, String sessionId, Path worktree, State state, String detail) {

  /** Lifecycle of a fleet job. */
  public enum State {
    RUNNING,
    COMPLETED,
    MERGED,
    /**
     * U-058: the dispatch settled by RECORDING A STAGE PASS instead of running a worker - the
     * ticket's current stage carried an active no-impact declaration ({@code StageDeclarations}),
     * so the engine called {@code TaskStore.passStage} and started no session at all: no claim, no
     * worktree, no session, no review, zero charge. A settled success, not a failure - and not a
     * merge (there is nothing to merge).
     */
    PASSED,
    FAILED
  }
}
