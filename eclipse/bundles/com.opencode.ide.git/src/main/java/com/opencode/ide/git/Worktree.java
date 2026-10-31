package com.opencode.ide.git;

import java.nio.file.Path;

/** A git worktree owned by an agent task: branch plus on-disk checkout. */
public record Worktree(String taskId, Path path, String branch) {}
