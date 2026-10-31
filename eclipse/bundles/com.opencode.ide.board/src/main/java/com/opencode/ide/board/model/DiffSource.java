package com.opencode.ide.board.model;

import com.opencode.ide.board.fleet.FleetJobHandle;

/**
 * The SWT-free decision behind the Fleet view's "Open diff" action: which diff source to try for a
 * job. A job that carries a session id has an authoritative server-side diff ({@code GET
 * /session/:id/diff}); otherwise (and as fallback when the server has nothing) the local git
 * task-branch diff is used. Extracted so the choice is testable without SWT.
 */
public final class DiffSource {

  /** Where a diff comes from. */
  public enum Source {
    /** The opencode server's per-session diff via the client (authoritative). */
    SERVER,
    /** {@code git diff HEAD..opencode/<taskId>} in the main worktree. */
    LOCAL_GIT
  }

  private DiffSource() {}

  /**
   * {@link Source#SERVER} when the job carries a non-blank session id, else {@link
   * Source#LOCAL_GIT}.
   */
  public static Source of(FleetJobHandle job) {
    String sessionId = job == null ? null : job.sessionId();
    return sessionId != null && !sessionId.isBlank() ? Source.SERVER : Source.LOCAL_GIT;
  }
}
