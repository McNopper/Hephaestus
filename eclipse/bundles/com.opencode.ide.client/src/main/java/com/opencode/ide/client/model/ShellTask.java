package com.opencode.ide.client.model;

/**
 * One shell task from {@code GET /api/shell} (v2's Shell tab surface, U-045/v2 parity).
 *
 * <p>Wire shape (v2 {@code Shell.Info}): {@code {"id":"sh…",
 * "status":"running"|"exited"|"timeout"|"killed", "command", "cwd", "shell", "file", "pid", "exit",
 * "metadata", "time":{started, completed?}}}. Only the fields the harness surfaces are mapped;
 * {@code metadata} carries the owner binding (T-179: {@code metadata.sessionID} nests a task under
 * its session in the server browser).
 */
public record ShellTask(
    String id,
    String status,
    String command,
    String cwd,
    Integer exit,
    Long pid,
    Time time,
    Meta metadata) {

  /** Epoch millis. */
  public record Time(Long started, Long completed) {}

  /** v2 {@code metadata}: the owning session plus the background flag. */
  public record Meta(String sessionID, Boolean background) {}

  /** Compatibility constructor for pre-T-179 fixtures (no owner binding). */
  public ShellTask(
      String id, String status, String command, String cwd, Integer exit, Long pid, Time time) {
    this(id, status, command, cwd, exit, pid, time, null);
  }

  /**
   * @return true while the task is still running (wire status {@code running}).
   */
  public boolean isRunning() {
    return "running".equals(status);
  }

  /**
   * @return the owning session id from {@code metadata.sessionID}, or {@code null}.
   */
  public String sessionId() {
    return metadata == null ? null : metadata.sessionID();
  }
}
