package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * {@link TaskFleet} permission wiring (ROADMAP H5 item 1): with a
 * {@link FleetPermissionBridge} connected, a permission ask raised while the
 * launch is in flight lands in the {@link PermissionQueue}, and when the job
 * settles - merged or failed - the session's pending entries are dropped.
 * Uses the same in-memory fakes as the other TaskFleet tests; the ask is
 * delivered from the session-events seam (inside the launch window, standing
 * in for the SSE stream).
 */
public class TaskFleetPermissionsTest {

    private static final Path REPO = Path.of("repo");
    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private FakeWorktreeManager worktrees;
    private PermissionQueue queue;
    private FleetPermissionBridge bridge;
    /** Set by the seam hook to prove the ask WAS queued mid-launch. */
    private String askedDuringLaunch;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
        queue = new PermissionQueue(null);
        bridge = new FleetPermissionBridge(queue);
    }

    private static OpencodeEvent askedEvent(String sessionId, String permissionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        properties.addProperty("id", permissionId);
        properties.addProperty("permission", "bash");
        properties.add("patterns", com.google.gson.JsonParser.parseString("[\"git push\"]"));
        return new OpencodeEvent("permission.asked", properties);
    }

    private String sprintTicket(String role) {
        Task task = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", role, "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(task.id), "goal");
        return task.id;
    }

    private TaskFleet fleet() {
        // the runner's client is wrapped so the session is watched from its
        // creation - BEFORE the prompt call (mirrors TaskFleetLauncher)
        return new TaskFleet(
                new FleetRunner(bridge.watching(client), worktrees, () -> { }),
                store, new RoleAgents(), null, bridge);
    }

    @Test
    public void askDuringTheLaunchIsQueuedAndDroppedWhenMerged() {
        String id = sprintTicket("developer");
        client.replyOnSend = "done";
        client.sessionType = "idle";
        // the ask is delivered while the launch is between session creation
        // and merge - blockOnSend runs on the prompt thread, deterministically
        // before the reply exists (so before the watchdog can complete)
        client.blockOnSend = () -> {
            bridge.onEvent(askedEvent("ses_1", "per_1"));
            askedDuringLaunch = queue.pending().isEmpty()
                    ? "(not queued!)"
                    : queue.pending().get(0).permissionId();
        };

        FleetJob job = fleet().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertEquals("the ask was visible while the job ran", "per_1", askedDuringLaunch);
        assertTrue("job settled: pending entries are dropped", queue.pending().isEmpty());
    }

    @Test
    public void failedLaunchAlsoDropsPendingEntries() {
        String id = sprintTicket("developer");
        client.sessionType = "busy"; // never completes -> watchdog timeout
        client.blockOnSend = () -> bridge.onEvent(askedEvent("ses_1", "per_1"));

        FleetJob job = fleet().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue("failed launch drops the session's pending asks", queue.pending().isEmpty());
    }

    @Test
    public void watchingClientRegistersCreatedSessions() throws Exception {
        // sanity: the wrapped client transparently delegates session creation
        Session session = bridge.watching(client).createSession("t", null);
        assertEquals("ses_1", session.id());
        bridge.onEvent(askedEvent("ses_1", "per_x"));
        assertEquals(1, queue.pendingCount());
        bridge.sessionEnded("ses_1");
        assertTrue(queue.pending().isEmpty());
    }

    @Test
    public void bridgelessFleetStillRuns() {
        String id = sprintTicket("developer");
        client.replyOnSend = "done";
        client.sessionType = "idle";

        FleetJob job = new TaskFleet(new FleetRunner(client, worktrees, () -> { }),
                store, new RoleAgents()).launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        assertTrue(queue.pending().isEmpty());
    }
}
