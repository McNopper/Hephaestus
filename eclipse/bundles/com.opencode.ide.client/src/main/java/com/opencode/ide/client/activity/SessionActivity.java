package com.opencode.ide.client.activity;

import java.util.List;

/**
 * Derived live activity of one session: whether the agent is running / thinking and which tool
 * invocations are in flight or just finished.
 */
public record SessionActivity(
    String sessionId, boolean running, boolean thinking, List<ToolActivity> activity) {

  public SessionActivity {
    activity = activity == null ? List.of() : List.copyOf(activity);
  }
}
