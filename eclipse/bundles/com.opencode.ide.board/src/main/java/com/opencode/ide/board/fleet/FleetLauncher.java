package com.opencode.ide.board.fleet;

import java.util.Map;

import com.opencode.ide.fleet.dispatch.DispatchScheduler.LaunchAttempt;

/**
 * Seam between the Board view ("Launch task") and the fleet engine. The real
 * implementation will delegate to the {@code TaskFleet} task-driven launcher
 * in {@code com.opencode.ide.fleet}; until that wiring lands, the board runs
 * on {@link DefaultFleetLauncher}.
 */
public interface FleetLauncher {

    /**
     * Launches an agent on the given ticket.
     *
     * @param project  the task-store project id
     * @param ticketId the ticket id (e.g. {@code H2-001})
     * @return the resulting job handle (never {@code null}; a launch failure
     *         comes back as a {@code FAILED} handle with the reason in
     *         {@code detail})
     * @throws com.opencode.ide.fleet.DispatchGuard.AdmissionDeferred when a peer
     *         owns the reservation; automatic callers should re-plan next tick
     */
    FleetJobHandle launch(String project, String ticketId);

    /** Auto dispatch must preserve stale-rework intent; unsupported adapters fail closed. */
    default FleetJobHandle launchAuto(String project, String ticketId, boolean includeStale) {
        throw new UnsupportedOperationException("automatic dispatch not implemented");
    }

    /** Queued adapters must retain this attempt and report pre-execution deferral. */
    default FleetJobHandle launchAuto(String project, String ticketId, boolean includeStale, LaunchAttempt attempt) {
        throw new UnsupportedOperationException("automatic dispatch feedback not implemented");
    }

    /**
     * U-045: the graceful shutdown for maintenance - the same ordered
     * teardown the chat {@code fleet_shutdown} tool runs (park admissions,
     * checkpoint + pause in-flight workers, kill a spawned serve).
     * Adapters without an engine fail closed; the caller surfaces the
     * message.
     *
     * @param project the store project to pause tickets in
     * @param reason  the maintenance reason (recorded on the gate + tickets)
     * @return the shutdown report (maintenance, checkpointed, paused,
     *         in_flight_residue, serve_killed_pid)
     */
    default Map<String, Object> shutdownForMaintenance(String project, String reason) {
        throw new UnsupportedOperationException("graceful shutdown not implemented");
    }
}
