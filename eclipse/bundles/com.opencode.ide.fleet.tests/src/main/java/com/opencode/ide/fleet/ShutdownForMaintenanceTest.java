package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * U-045/U-038 engine-side contract at the {@link FleetControl} seam: the ONE
 * graceful shutdown action parks admissions (the maintenance gate engages -
 * every later launch refuses), stops the auto/waves loops, kills the spawned
 * {@code opencode serve} it owns and REPORTS what stopped. Bring-up is the
 * gate clear plus a plain status update per paused ticket.
 */
public class ShutdownForMaintenanceTest extends FleetTestHarness {

    private static final class RecordingEngine implements FleetControl.Engine {
        final TaskFleet fleet;
        boolean closed;
        boolean alive = true;

        RecordingEngine(TaskFleet fleet) {
            this.fleet = fleet;
        }

        @Override
        public TaskFleet fleet() {
            return fleet;
        }

        @Override
        public boolean serverAlive() {
            return alive;
        }

        @Override
        public Long serverPid() {
            return 4242L;
        }

        @Override
        public PermissionQueue permissions() {
            return new PermissionQueue(null);
        }

        @Override
        public void close() {
            closed = true;
            alive = false;
        }
    }

    @Test
    public void oneActionParksAdmissionsKillsTheServeAndReports() {
        RecordingEngine engine = new RecordingEngine(fleet);
        FleetControl control = new FleetControl(store.root(), root -> engine,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        control.engine(); // the engine is up: its serve is running

        Map<String, Object> report = control.shutdownForMaintenance(PROJECT, "test maintenance");

        assertEquals("the reason is recorded", "test maintenance", report.get("maintenance"));
        assertEquals("the spawned serve is killed (no orphan survives)",
                4242L, report.get("serve_killed_pid"));
        assertTrue("the engine closed with its serve", engine.closed);
        assertTrue("the loops are stopped", !((Boolean) control.autoStatus().get("running")));
        assertNotNull("the maintenance gate engaged", MaintenanceGate.reason(control.repoRoot()));
    }

    @Test
    public void afterShutdownEveryLaunchRefusesWithTheMaintenanceReason() {
        RecordingEngine engine = new RecordingEngine(fleet);
        FleetControl control = new FleetControl(store.root(), root -> engine,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        control.engine();
        String id = stagedTicket("implementation");
        control.shutdownForMaintenance(PROJECT, "paving the parking lot");

        try {
            fleet.launch(PROJECT, id, control.repoRoot(), TIMEOUT);
            throw new AssertionError("expected the maintenance refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().startsWith("maintenance: paving the parking lot"));
        }
    }

    @Test
    public void shutdownWithoutAnEngineStillParksAdmissions() {
        RecordingEngine engine = new RecordingEngine(fleet);
        FleetControl control = new FleetControl(store.root(), root -> engine,
                java.util.concurrent.Executors.newSingleThreadExecutor());

        Map<String, Object> report = control.shutdownForMaintenance("", "nightly paving");

        assertEquals("nightly paving", report.get("maintenance"));
        assertNotNull("the gate engages even with no engine", MaintenanceGate.reason(control.repoRoot()));
    }
}
