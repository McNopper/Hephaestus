package com.opencode.ide.fleet;

/**
 * Optional per-launch bootstrap of a {@link FleetTask}: one shell command (e.g. {@code npm
 * install}) executed in the freshly created session via {@link
 * com.opencode.ide.client.OpencodeClient#runShell} BEFORE the main prompt is sent (see {@link
 * FleetRunner#submit}). Best-effort - a failure or error status is logged and the launch proceeds
 * with the prompt; it is a convenience, never a gate. {@code null} means no bootstrap.
 *
 * @param agent agent context for the shell call, or {@code null} for the server default
 * @param command the shell command to run
 */
public record Bootstrap(String agent, String command) {

  /**
   * Normalizing factory: a {@code null} or blank command means no bootstrap ({@code null} is
   * returned), and a blank agent is the server default.
   */
  public static Bootstrap of(String agent, String command) {
    if (command == null || command.isBlank()) {
      return null;
    }
    return new Bootstrap(agent == null || agent.isBlank() ? null : agent, command);
  }
}
