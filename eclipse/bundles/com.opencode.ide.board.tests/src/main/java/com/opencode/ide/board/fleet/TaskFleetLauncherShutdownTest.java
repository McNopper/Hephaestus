package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.fleet.FleetJob;
import com.opencode.ide.fleet.MaintenanceGate;
import com.opencode.ide.fleet.TaskFleet;

/**
 * U-045: the Board's graceful shutdown passthrough
 * ({@link TaskFleetLauncher#shutdownForMaintenance(String, String)}) rides
 * the SAME seam contract as the chat {@code fleet_shutdown} tool: the
 * engine-less path parks admissions via the gate WITHOUT spawning anything
 * (the client supplier must never be consulted), the cached-fleet path
 * hands the fleet to {@link TaskFleet#shutdownForMaintenance} with the
 * board's project, the repo root and the recorded reason/by, and the report
 * carries the FleetControl keys (maintenance / checkpointed / paused /
 * in_flight_residue / serve_killed_pid).
 */
public class TaskFleetLauncherShutdownTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @org.junit.Before
    public void resetProcessWideState() {
        TaskFleetLauncher.resetForTests();
    }

    @org.junit.After
    public void tearDown() {
        TaskFleetLauncher.resetForTests();
    }

    /**
     * Nothing was ever launched here: no fleet is cached, so shutdown must
     * NOT create one (that could spawn a server) - it engages the gate at
     * the repo root directly, the engine-less path FleetControl mirrors,
     * and reports empty teardown lists.
     */
    @Test
    public void engineLessShutdownParksTheGateWithoutSpawning() throws IOException {
        Path repo = repoWithStore("repo1");
        Path storeRoot = repo.resolve(".opencode").resolve("tasks");
        TaskFleetLauncher launcher = new TaskFleetLauncher(
                () -> { throw new AssertionError("shutdown must not consult the client supplier"); },
                () -> { throw new AssertionError("shutdown must not consult the worktrees supplier"); },
                () -> storeRoot);

        Map<String, Object> report = launcher.shutdownForMaintenance("hephaestus", "paving");

        assertTrue("the gate engages at the repo root (where launch reads it)",
                MaintenanceGate.isActive(repo));
        assertEquals("paving", MaintenanceGate.reason(repo));
        assertEquals("paving", report.get("maintenance"));
        assertEquals(List.of(), report.get("checkpointed"));
        assertEquals(List.of(), report.get("paused"));
        assertEquals(List.of(), report.get("in_flight_residue"));
        assertEquals("no serve this session spawned", 0L, report.get("serve_killed_pid"));
    }

    /**
     * With a fleet cached by an earlier launch, shutdown hands THAT fleet
     * to the engine call - right project, right repo root, the recorded
     * reason and the board's {@code by}.
     */
    @Test
    public void cachedFleetShutdownPassesProjectRootReasonAndBy() throws Exception {
        Path repo = repoWithStore("repo2");
        Path storeRoot = repo.resolve(".opencode").resolve("tasks");
        AtomicReference<TaskFleet> launchFleet = captureLaunchFleetOnce();
        TaskFleetLauncher launcher = new TaskFleetLauncher(
                () -> throwingClient("shutdown-capture"), () -> null, () -> storeRoot);
        launcher.launch("hephaestus", "T-001");
        assertTrue("the launch never reached the engine call", launchFleetAwaited(launchFleet));

        AtomicReference<TaskFleet> shutdownFleet = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>();
        TaskFleetLauncher.useEngineShutdownForTests((fleet, project, repoRoot, reason, by) -> {
            shutdownFleet.set(fleet);
            seen.set(project + "|" + repoRoot + "|" + reason + "|" + by);
            return Map.of("checkpointed", List.of(), "paused", List.of(),
                    "in_flight_residue", List.of());
        });

        Map<String, Object> report = launcher.shutdownForMaintenance("hephaestus", "nightly paving");

        assertNotNull("the cached fleet was handed to the engine call", shutdownFleet.get());
        assertSame("shutdown reaches the fleet the launches built (not a rebuild)",
                launchFleet.get(), shutdownFleet.get());
        assertEquals("hephaestus|" + repo + "|nightly paving|" + TaskFleetLauncher.SHUTDOWN_BY,
                seen.get());
        assertEquals("nightly paving", report.get("maintenance"));
    }

    /** A blank reason falls back to the plain "maintenance" wording, like FleetControl. */
    @Test
    public void blankReasonFallsBackToMaintenance() throws IOException {
        Path repo = repoWithStore("repo3");
        Path storeRoot = repo.resolve(".opencode").resolve("tasks");
        TaskFleetLauncher launcher = new TaskFleetLauncher(
                () -> { throw new AssertionError("no fleet may be built"); },
                () -> null,
                () -> storeRoot);

        Map<String, Object> report = launcher.shutdownForMaintenance("hephaestus", " ");

        assertEquals("maintenance", report.get("maintenance"));
        assertEquals("maintenance", MaintenanceGate.reason(repo));
    }

    /** A store root that resolves to no repo still reports - never throws on the null root. */
    @Test
    public void rootlessStoreReportsWithoutEngaging() {
        TaskFleetLauncher launcher = new TaskFleetLauncher(
                () -> { throw new AssertionError("no fleet may be built"); },
                () -> null,
                () -> null);

        Map<String, Object> report = launcher.shutdownForMaintenance("hephaestus", "paving");

        assertEquals("paving", report.get("maintenance"));
        assertEquals(List.of(), report.get("paused"));
        assertEquals("nothing claims a kill without a serve", 0L, report.get("serve_killed_pid"));
    }

    /** A git repo with a store under {@code .opencode/tasks}. */
    private Path repoWithStore(String folder) throws IOException {
        Path repo = tmp.newFolder(folder).toPath();
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(repo.resolve(".opencode").resolve("tasks"));
        return repo;
    }

    /** Captures the fleet the first launch hands to the engine call (settled immediately). */
    private static AtomicReference<TaskFleet> captureLaunchFleetOnce() {
        AtomicReference<TaskFleet> fleet = new AtomicReference<>();
        TaskFleetLauncher.useEngineLaunchForTests(
                (captured, project, taskId, repoRoot, timeout, bootstrap) -> {
                    fleet.set(captured);
                    return new FleetJob(taskId, "sess-1", null, FleetJob.State.MERGED, null);
                });
        return fleet;
    }

    private static boolean launchFleetAwaited(AtomicReference<TaskFleet> fleet) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (fleet.get() != null) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** An {@link OpencodeClient} whose every method throws - fleet creation succeeds, any use fails fast. */
    private static OpencodeClient throwingClient(String marker) {
        return (OpencodeClient) Proxy.newProxyInstance(
                OpencodeClient.class.getClassLoader(),
                new Class<?>[] {OpencodeClient.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException(marker);
                });
    }
}
