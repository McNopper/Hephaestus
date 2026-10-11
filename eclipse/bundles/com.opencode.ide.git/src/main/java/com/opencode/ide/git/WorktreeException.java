package com.opencode.ide.git;

/**
 * Thrown when git plumbing for fleet worktrees fails (missing git, non-zero exit, timeout,
 * conflicting state).
 */
public class WorktreeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public WorktreeException(String message) {
    super(message);
  }

  public WorktreeException(String message, Throwable cause) {
    super(message, cause);
  }
}
