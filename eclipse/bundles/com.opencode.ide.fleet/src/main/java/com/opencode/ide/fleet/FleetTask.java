package com.opencode.ide.fleet;

import java.nio.file.Path;

/**
 * One agent task for the fleet: a prompt executed in its own git worktree.
 *
 * @param taskId stable id, used as the worktree/branch name suffix
 * @param title session title shown in opencode (may be {@code null})
 * @param prompt the user prompt executed by the agent
 * @param agent agent name (e.g. {@code build}), or {@code null} for the server default
 * @param model {@code provider/modelId}, or {@code null} for the server default model
 * @param bootstrap optional pre-prompt shell command in the new session (see {@link Bootstrap};
 *     best-effort, never gates the launch), or {@code null} for none
 * @param baseWorktree the main worktree to branch from (the repo root)
 */
public record FleetTask(
    String taskId,
    String title,
    String prompt,
    String agent,
    String model,
    Bootstrap bootstrap,
    Path baseWorktree) {

  /** No-bootstrap convenience: same as passing a {@code null} bootstrap. */
  public FleetTask(
      String taskId, String title, String prompt, String agent, String model, Path baseWorktree) {
    this(taskId, title, prompt, agent, model, null, baseWorktree);
  }
}
