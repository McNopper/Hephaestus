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

    /** A v2 ask with distinct permission-request and source-tool identities. */
    private static OpencodeEvent askedEvent(String sessionId, String permissionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("id", permissionId);
        properties.addProperty("sessionID", sessionId);
        properties.addProperty("action", "shell");
        properties.add("resources", com.google.gson.JsonParser.parseString("[\"git push\"]"));
        properties.add("save", com.google.gson.JsonParser.parseString("[]"));
        JsonObject source = new JsonObject();
        source.addProperty("type", "tool");
        source.addProperty("messageID", "msg_1");
        source.addProperty("id", "call_1");
        properties.add("source", source);
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
        // the runner's session-created callback watches the session from its
        // creation - BEFORE the prompt call (mirrors TaskFleetLauncher)
        return new TaskFleet(
                new FleetRunner(client, worktrees, () -> { }, bridge::sessionStarted),
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
        // idle and without progress -> the budget stops the run (B-008: a
        // continuously busy session would never be budget-killed)
        client.sessionType = "idle";
        client.blockOnSend = () -> bridge.onEvent(askedEvent("ses_1", "per_1"));

        FleetJob job = fleet().launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.FAILED, job.state());
        assertTrue("failed launch drops the session's pending asks", queue.pending().isEmpty());
    }

    @Test
    public void runnerCallbackRegistersTheSessionBeforeThePromptIsSent() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        client.blockOnSend = () -> order.add("prompt");
        FleetRunner runner = new FleetRunner(client, worktrees, () -> { }, id -> {
            bridge.sessionStarted(id);
            order.add("watched:" + id);
        });

        runner.begin(new FleetTask("t1", "Fix the widget", "do the thing", null, null, REPO))
                .prompt().get();

        assertEquals("the session is watched before the prompt POST",
                List.of("watched:ses_1", "prompt"), order);
        bridge.onEvent(askedEvent("ses_1", "per_x"));
        assertEquals("the watched session's asks are queued", 1, queue.pendingCount());
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
