package com.opencode.ide.client.activity;

/**
 * One tool invocation as observed in a {@code message.part.updated} event: tool name, the file it
 * touches (nullable — not every tool works on a file), and its lifecycle state.
 */
public record ToolActivity(String tool, String file, State state) {

  /** Coarse tool lifecycle; a missing state on the wire reads as RUNNING. */
  public enum State {
    RUNNING,
    COMPLETED,
    ERROR
  }
}
