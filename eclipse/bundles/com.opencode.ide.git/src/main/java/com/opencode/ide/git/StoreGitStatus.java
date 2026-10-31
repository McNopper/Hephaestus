package com.opencode.ide.git;

import com.opencode.ide.git.internal.GitStore;
import java.nio.file.Path;

/**
 * Read-only snapshot of a git working copy — the task store — from a single {@code git status
 * --porcelain=v1 -b} invocation: current branch, divergence from its upstream, and the number of
 * locally changed files. Built for the board header; callers wanting to change the store must use
 * {@link StoreSync}.
 *
 * <p>Unknown state — {@code root} is not a repository, git is missing, or the git call fails — is
 * {@link #NONE}: {@code branch == null}, {@code detached == false}; query it with {@link
 * #exists()}. {@link #load} never throws. A detached HEAD (including a rebase left in progress by
 * {@link StoreSync}) has {@code detached == true}, {@code branch == null} and still {@link
 * #exists() == true}.
 *
 * <p>Pure Java, no Eclipse/OSGi.
 */
public record StoreGitStatus(String branch, int ahead, int behind, int changed, boolean detached) {

  /** The unknown state (not a repo / git unusable): {@code exists() == false}. */
  public static final StoreGitStatus NONE = new StoreGitStatus(null, 0, 0, 0, false);

  private static final String SEPARATOR = " · ";

  /** Loads the status of the working copy at {@code root}; never throws. */
  public static StoreGitStatus load(Path root) {
    return GitStore.status(root);
  }

  /**
   * Whether git reported a known state (on a branch or detached); {@code false} for {@link #NONE}.
   */
  public boolean exists() {
    return branch != null || detached;
  }

  /**
   * One-line summary with zero parts omitted, e.g. {@code main · ahead 2 · 3 changed}; {@code
   * detached} replaces the branch name; empty string when the state is unknown. Deterministic.
   */
  public String summary() {
    if (!exists()) {
      return "";
    }
    StringBuilder text = new StringBuilder(detached ? "detached" : branch);
    if (ahead > 0) {
      text.append(SEPARATOR).append("ahead ").append(ahead);
    }
    if (behind > 0) {
      text.append(SEPARATOR).append("behind ").append(behind);
    }
    if (changed > 0) {
      text.append(SEPARATOR).append(changed).append(" changed");
    }
    return text.toString();
  }
}
