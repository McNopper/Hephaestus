package com.opencode.ide.client.model;

/**
 * A session's {@code /goal} state as returned by the {@code opencode.goal} plugin RPC ({@code POST
 * /api/rpc/opencode.goal/get} → {@code {output: Goal.State|null}}) and published on the event
 * stream as {@code rpc.opencode.goal.changed} ({@code {sessionID, goal}}) — T-149, wire shape
 * probed live 2026-11-16 against the local 2.3.0 service.
 *
 * <p>Mirrors {@code Goal.State} in the opencode schema: {@code status} is {@code active}, {@code
 * paused} or {@code complete}; {@code reason} explains the last transition ("Paused by the user");
 * {@code rounds}/{@code maxRounds} count the goal loop's continuations. A server without the goal
 * plugin answers an error on the RPC - callers treat that as "no goal" (the TUI does the same:
 * {@code packages/tui/src/feature-plugins/prompt/goal.tsx}).
 */
public record SessionGoal(
    String id,
    String objective,
    String status,
    String reason,
    Integer rounds,
    Integer maxRounds,
    String boot,
    Long updatedAt) {

  public static final String STATUS_ACTIVE = "active";
  public static final String STATUS_PAUSED = "paused";
  public static final String STATUS_COMPLETE = "complete";

  /**
   * The TUI's chip label ({@code packages/tui/src/feature-plugins/prompt/goal.tsx:56-59}: {@code
   * paused -> "goal paused"}, {@code complete -> "goal met"}, anything else {@code "goal"}).
   */
  public String label() {
    if (STATUS_PAUSED.equals(status)) {
      return "goal paused";
    }
    if (STATUS_COMPLETE.equals(status)) {
      return "goal met";
    }
    return "goal";
  }

  /**
   * The TUI dialog's status word ({@code goal.tsx:160-166}: {@code Active} / {@code Paused} /
   * {@code Met}).
   */
  public String statusWord() {
    if (STATUS_PAUSED.equals(status)) {
      return "Paused";
    }
    if (STATUS_COMPLETE.equals(status)) {
      return "Met";
    }
    return "Active";
  }
}
