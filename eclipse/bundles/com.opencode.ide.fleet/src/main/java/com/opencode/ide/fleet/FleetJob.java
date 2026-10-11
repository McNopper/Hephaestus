package com.opencode.ide.fleet;

import java.nio.file.Path;

/**
 * The immutable state of a submitted {@link FleetTask} as it moves through the fleet loop (submit,
 * await completion, merge back).
 *
 * @param taskId the task this job belongs to
 * @param sessionId the opencode session running the prompt ({@code null} when session creation
 *     failed)
 * @param worktree the task's git worktree; always reported once created, also on FAILED jobs (kept
 *     on disk for post-mortem inspection)
 * @param state lifecycle state
 * @param detail failure message ({@code null} unless {@code state == FAILED})
 */
public record FleetJob(String taskId, String sessionId, Path worktree, State state, String detail) {

  /** Lifecycle of a fleet job. */
  public enum State {
    RUNNING,
    COMPLETED,
    MERGED,
    FAILED
  }
}
