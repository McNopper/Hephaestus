package com.opencode.ide.board.fleet;

/**
 * Placeholder launcher: every launch fails with a clear "pending" detail. Replaced by the real
 * {@code TaskFleet} wiring in a later ticket.
 */
public final class DefaultFleetLauncher implements FleetLauncher {

  static final String PENDING_DETAIL = "FleetRunner v2 wiring pending — integrate TaskFleet";

  @Override
  public FleetJobHandle launch(String project, String ticketId) {
    return new FleetJobHandle(ticketId, null, null, FleetJobHandle.State.FAILED, PENDING_DETAIL);
  }
}
