package com.opencode.ide.board.fleet;

import java.util.List;
import java.util.Map;

/**
 * U-045 the Board Shutdown button's texts. The confirm dialog names exactly
 * what the graceful path does — the SAME ordered teardown the chat
 * {@code fleet_shutdown} tool runs (park admissions, checkpoint + pause
 * in-flight workers, kill a spawned serve) — and the report message renders
 * the shutdown report map those paths return (keys {@code maintenance},
 * {@code checkpointed}, {@code paused}, {@code in_flight_residue},
 * {@code serve_killed_pid}). Pure text building: SWT stays in the view.
 */
public final class FleetShutdownTexts {

    private FleetShutdownTexts() {
    }

    /**
     * The confirm dialog message: names the three ordered effects and the
     * bring-up path, so the button never does something it did not say.
     *
     * @param project the store project the board would pause tickets in
     *                (blank = the board has no project context)
     */
    public static String confirmMessage(String project) {
        return "Shut the fleet down for maintenance"
                + (project == null || project.isBlank() ? "" : " (project '" + project + "')") + "?\n\n"
                + "1. Admissions are parked — the maintenance gate engages and\n"
                + "    this board's Auto / Waves loops stop; every dispatch path\n"
                + "    refuses with a clear 'maintenance' reason.\n\n"
                + "2. Every in-flight worker is checkpointed — its worktree WIP is\n"
                + "    committed to the task branch and the ticket is PAUSED\n"
                + "    (visible, never blocked, never NEEDS-HUMAN).\n\n"
                + "3. A serve spawned by this session is killed — no orphan\n"
                + "    survives the shutdown (an attached shared service keeps\n"
                + "    running).\n\n"
                + "Bring-up clears the gate; resume is a plain status update per\n"
                + "paused ticket.";
    }

    /**
     * The post-shutdown report message: what was parked, who was
     * checkpointed and paused, what residue stayed in flight, and whether a
     * spawned serve was killed.
     */
    public static String reportMessage(Map<String, Object> report) {
        StringBuilder text = new StringBuilder("Shutdown complete.");
        if (report == null) {
            return text.toString();
        }
        text.append("\nmaintenance: ").append(string(report.get("maintenance"), "maintenance"));
        text.append("\ncheckpointed: ").append(ids(report.get("checkpointed")));
        text.append("\npaused: ").append(ids(report.get("paused")));
        Object residue = report.get("in_flight_residue");
        if (residue instanceof List<?> list && !list.isEmpty()) {
            text.append("\nstill in flight (checkpoint failed): ").append(ids(residue));
        }
        text.append("\nspawned serve: ").append(serveLine(report.get("serve_killed_pid")));
        return text.toString();
    }

    /** "(none)" for absent/empty lists, comma-joined ids otherwise. */
    private static String ids(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (Object id : list) {
                if (joined.length() > 0) {
                    joined.append(", ");
                }
                joined.append(id);
            }
            return joined.toString();
        }
        return "(none)";
    }

    /** "killed (pid n)" when a live serve was killed, the quiet line otherwise. */
    private static String serveLine(Object pid) {
        return pid instanceof Number number && number.longValue() != 0
                ? "killed (pid " + number.longValue() + ")" : "none this session had spawned";
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
