package com.opencode.ide.git;

/** Snapshot of a task's branch/worktree state. */
public record WorktreeStatus(boolean exists, int dirtyFiles, String head) {}
