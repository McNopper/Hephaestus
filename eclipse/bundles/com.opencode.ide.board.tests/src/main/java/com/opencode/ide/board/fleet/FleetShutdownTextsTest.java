package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * U-045: the Board Shutdown button's texts tell the truth. The confirm
 * dialog names the three ordered effects of the graceful path (park
 * admissions, checkpoint + pause in-flight workers, kill a spawned serve),
 * and the report message renders the shutdown report map the engine paths
 * return (maintenance / checkpointed / paused / in_flight_residue /
 * serve_killed_pid) without lying about empty lists.
 */
public class FleetShutdownTextsTest {

    /** The confirm names every ordered effect - the button never does something it did not say. */
    @Test
    public void confirmMessageNamesTheThreeOrderedEffects() {
        String message = FleetShutdownTexts.confirmMessage("hephaestus");

        assertTrue("admissions parked: " + message, message.contains("Admissions are parked"));
        assertTrue("the maintenance gate: " + message, message.contains("maintenance gate"));
        assertTrue("checkpoints in-flight workers: " + message, message.contains("checkpointed"));
        assertTrue("tickets land PAUSED: " + message, message.contains("PAUSED"));
        assertTrue("the spawned serve is killed: " + message, message.contains("spawned"));
        assertTrue("killed: " + message, message.contains("killed"));
        assertTrue("the project is named: " + message, message.contains("hephaestus"));
        assertTrue("bring-up is mentioned: " + message, message.contains("Bring-up"));
    }

    /** A blank project renders without an empty "(project '')" fragment. */
    @Test
    public void confirmMessageToleratesABlankProject() {
        String message = FleetShutdownTexts.confirmMessage(" ");

        assertFalse(message.contains("''"));
        assertFalse(message.contains("null"));
        assertTrue(message.startsWith("Shut the fleet down for maintenance?"));
    }

    /** An empty report says so plainly - every list renders, none silently vanishes. */
    @Test
    public void emptyReportRendersQuietPlaceholders() {
        String message = FleetShutdownTexts.reportMessage(Map.of(
                "maintenance", "maintenance",
                "checkpointed", List.of(),
                "paused", List.of(),
                "in_flight_residue", List.of(),
                "serve_killed_pid", 0L));

        assertTrue(message.startsWith("Shutdown complete."));
        assertTrue("maintenance reason: " + message, message.contains("maintenance: maintenance"));
        assertTrue("checkpointed: " + message, message.contains("checkpointed: (none)"));
        assertTrue("paused: " + message, message.contains("paused: (none)"));
        assertFalse("no residue line when nothing is left in flight: " + message,
                message.contains("still in flight"));
        assertTrue("no spawned serve: " + message, message.contains("none this session had spawned"));
    }

    /** A full report names ids, residue and the killed pid. */
    @Test
    public void fullReportNamesIdsResidueAndPid() {
        String message = FleetShutdownTexts.reportMessage(Map.of(
                "maintenance", "paving the parking lot",
                "checkpointed", List.of("U-001", "U-002"),
                "paused", List.of("U-001", "U-002"),
                "in_flight_residue", List.of("U-003"),
                "serve_killed_pid", 4242L));

        assertTrue(message.contains("maintenance: paving the parking lot"));
        assertTrue("ids are joined: " + message, message.contains("checkpointed: U-001, U-002"));
        assertTrue("residue surfaces: " + message,
                message.contains("still in flight (checkpoint failed): U-003"));
        assertTrue("the killed pid: " + message, message.contains("killed (pid 4242)"));
    }

    /** A null report (engine gave nothing back) still renders a complete sentence. */
    @Test
    public void nullReportRendersThePlainLine() {
        assertEquals("Shutdown complete.", FleetShutdownTexts.reportMessage(null));
    }

    /** The maintenance fallback covers a blank reason without printing "null". */
    @Test
    public void blankMaintenanceReasonFallsBack() {
        String message = FleetShutdownTexts.reportMessage(Map.of("maintenance", " "));

        assertTrue(message.contains("maintenance: maintenance"));
    }
}
