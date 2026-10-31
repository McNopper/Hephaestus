package com.opencode.ide.git;

import java.util.List;

/** Outcome of merging a task branch back into the main worktree's branch. */
public record MergeResult(boolean merged, List<String> conflictedFiles, String output) {

  public MergeResult {
    conflictedFiles = conflictedFiles == null ? List.of() : List.copyOf(conflictedFiles);
  }
}
