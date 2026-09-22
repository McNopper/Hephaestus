package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.client.activity.SessionObserver;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.ParamError;

/**
 * Tests for the {@code fleet_*} tool pack over the chat-first control plane:
 * a {@link FleetToolProvider} wired to a {@link FleetControl} whose engine is
 * a real {@link TaskFleet} on the in-memory client/worktree fakes (the
 * {@link TaskFleetTest} combination), so dispatch → jobs → bookkeeping runs
 * end-to-end without a server spawn. The engine's {@link PermissionQueue}
 * sits behind a recording fake responder, covering the chat permission path
 * (list asks, answer them, malformed arguments).
 */
public class FleetToolProviderTest {

    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private RecordingResponder responder;
    private PermissionQueue enginePermissions;
    private FleetToolProvider provider;

    /** Recording fake of the engine queue's answer endpoint. */
    private static final class RecordingResponder implements PermissionQueue.PermissionResponder {

        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public boolean respond(String sessionId, String permissionId, String response, boolean remember) {
            calls.add(sessionId + "|" + permissionId + "|" + response + "|" + remember);
            return true;
        }
    }

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve(".opencode/tasks"));
        client = new FakeClient();
        responder = new RecordingResponder();
        enginePermissions = new PermissionQueue(responder);
        FleetControl control = new FleetControl(store.root(), root -> new FleetControl.Engine() {
            private final TaskFleet fleet = new TaskFleet(
                    new FleetRunner(client, new FakeWorktreeManager(), () -> { }), store);

            @Override
            public TaskFleet fleet() {
                return fleet;
            }

            @Override
            public PermissionQueue permissions() {
                return enginePermissions;
            }

            @Override
            public SessionObservation observe(String ticketId, String sessionId) {
                String sid = sessionId;
                if (sid == null) {
                    FleetJob job = fleet.jobs().get(ticketId);
                    if (job == null || job.sessionId() == null) {
                        return null;
                    }
                    sid = job.sessionId();
                }
                return SessionObserver.observe(client, sid, null);
            }

            @Override
            public void close() {
                // nothing to release in the fake engine
            }
        });
        control.engine();
        provider = new FleetToolProvider(store.root(), control);
    }

    private static PermissionRequest asked(String permissionId) {
        return new PermissionRequest("ses_1", permissionId, "bash",
                List.of("git push"), "git push origin main", PermissionRequest.Status.PENDING);
    }

    @org.junit.After
    public void closeProvider() {
        provider.close();
    }

    @Test
    public void resetRefusesAnotherEnginesReservationWithoutChangingTicket() {
        String id = sprintTicket();
        store.setBlocked(PROJECT, id, "failed run", "test");
        Path repo = FleetControl.repoRootOf(store.root());
        try (DispatchGuard guard = DispatchGuard.acquire(repo, id)) {
            McpToolResult result = provider.call("fleet_reset", args("project", PROJECT, "ticket_id", id));
            assertTrue(result.text(), result.isError());
            assertTrue(store.get(PROJECT, id).blocked);
            assertTrue(DispatchGuard.runningIds(repo).contains(id));
        }
    }

    @Test
    public void autoControlsReportScopeAndStopWithoutDispatchingBlockedTickets() {
        String id = sprintTicket();
        store.setBlocked(PROJECT, id, "wait", "test");
        assertOk(provider.call("fleet_auto_start", args("project", PROJECT, "sprint", "S-01")));
        JsonObject status = JsonParser.parseString(provider.call("fleet_auto_status", null).text()).getAsJsonObject();
        assertTrue(status.get("running").getAsBoolean());
        assertEquals("S-01", status.get("sprint").getAsString());
        assertOk(provider.call("fleet_auto_stop", null));
        assertFalse(JsonParser.parseString(provider.call("fleet_auto_status", null).text())
                .getAsJsonObject().get("running").getAsBoolean());
        assertTrue(store.get(PROJECT, id).blocked);
    }

    /** A requirements-stage ticket in the product backlog (the waves loop's fuel). */
    private String backlogTicket(String title) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                title, "", "task", "developer", "high", 2, List.of("ac one"), List.of(), null, "T"));
        store.update(PROJECT, t.id, java.util.Map.of("stage", "requirements"));
        return t.id;
    }

    private JsonObject wavesStatus() {
        return JsonParser.parseString(provider.call("fleet_waves_status", null).text()).getAsJsonObject();
    }

    @Test
    public void wavesStatusDefaultsToDisabled() {
        JsonObject status = wavesStatus();
        assertFalse(status.get("enabled").getAsBoolean());
        assertFalse(status.get("running").getAsBoolean());
        assertTrue("the disabled state names the opt-in",
                status.get("hint").getAsString().contains("OFF by default"));
    }

    @Test
    public void wavesStartRejectsMissingProjectAndEmptyProjects() {
        assertThrows(ParamError.class, () -> provider.call("fleet_waves_start", new JsonObject()));
        McpToolResult unknown = provider.call("fleet_waves_start", args("project", "nope"));
        assertTrue(unknown.text(), unknown.isError());
        assertTrue(unknown.text(), unknown.text().contains("no tickets in project nope"));
    }

    @Test
    public void wavesStartPlansTheFirstWaveAndStopsCleanly() throws Exception {
        sessionCompletes();
        String id = backlogTicket("recurring work");
        assertOk(provider.call("fleet_waves_start", args("project", PROJECT)));

        JsonObject status = wavesStatus();
        assertTrue(status.get("enabled").getAsBoolean());
        assertTrue(status.get("running").getAsBoolean());
        assertEquals(PROJECT, status.get("project").getAsString());
        assertEquals(0, status.getAsJsonArray("needs_human").size());

        // the loop's first cycle plans wave 1 from the backlog and launches it
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && wavesStatus().get("waves_planned").getAsInt() < 1) {
            Thread.sleep(50);
        }
        assertEquals(1, wavesStatus().get("waves_planned").getAsInt());
        assertEquals(FleetJob.State.MERGED, awaitState(id, FleetJob.State.MERGED));

        assertOk(provider.call("fleet_waves_stop", null));
        assertFalse(wavesStatus().get("enabled").getAsBoolean());
    }

    @Test
    public void wavesSurfacesNeedsHumanRowsForBlockedTickets() throws Exception {
        sessionCompletes();
        String id = backlogTicket("waiting at the owner");
        store.setBlocked(PROJECT, id, "needs a decision", "test");
        assertOk(provider.call("fleet_waves_start", args("project", PROJECT)));

        long deadline = System.currentTimeMillis() + 10_000;
        JsonArray needsHuman = wavesStatus().getAsJsonArray("needs_human");
        while (System.currentTimeMillis() < deadline && needsHuman.size() < 1) {
            Thread.sleep(50);
            needsHuman = wavesStatus().getAsJsonArray("needs_human");
        }
        assertEquals(1, needsHuman.size());
        assertEquals(id, needsHuman.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("needs a decision",
                needsHuman.get(0).getAsJsonObject().get("blocker").getAsString());
        // blocked tickets park the loop — it stays enabled, watching for the unblock
        assertTrue(wavesStatus().get("running").getAsBoolean());
        assertEquals(0, wavesStatus().get("waves_planned").getAsInt());

        assertOk(provider.call("fleet_waves_stop", null));
    }

    @Test
    public void wavesAndAutoLoopsAreExclusive() {
        sprintTicket(); // S-01 with one ticket
        assertOk(provider.call("fleet_auto_start", args("project", PROJECT, "sprint", "S-01")));
        assertTrue(JsonParser.parseString(provider.call("fleet_auto_status", null).text())
                .getAsJsonObject().get("running").getAsBoolean());

        assertOk(provider.call("fleet_waves_start", args("project", PROJECT)));

        assertFalse("enabling waves disabled the one-sprint loop",
                JsonParser.parseString(provider.call("fleet_auto_status", null).text())
                        .getAsJsonObject().get("running").getAsBoolean());
        assertTrue(wavesStatus().get("enabled").getAsBoolean());
    }

    private String sprintTicket() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    private static JsonObject args(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty(kv[i], kv[i + 1]);
        }
        return o;
    }

    private static void assertOk(McpToolResult r) {
        assertFalse("unexpected error result: " + r.text(), r.isError());
    }

    /** Polls fleet_jobs until the ticket reaches the expected state (or times out). */
    private FleetJob.State awaitState(String ticketId, FleetJob.State expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        FleetJob.State last = null;
        while (System.currentTimeMillis() < deadline) {
            McpToolResult r = provider.call("fleet_jobs", new JsonObject());
            assertOk(r);
            JsonArray jobs = JsonParser.parseString(r.text()).getAsJsonArray();
            for (var e : jobs) {
                if (ticketId.equals(e.getAsJsonObject().getAsJsonPrimitive("ticket_id").getAsString())) {
                    last = FleetJob.State.valueOf(e.getAsJsonObject().getAsJsonPrimitive("state").getAsString());
                    if (last == expected) {
                        return last;
                    }
                }
            }
            Thread.sleep(50);
        }
        return last;
    }

    @Test
    public void dispatchRunsTheFleetAndTheJobMerges() throws Exception {
        String id = sprintTicket();
        sessionCompletes();

        McpToolResult r = provider.call("fleet_dispatch",
                args("project", PROJECT, "ticket_id", id, "timeout_minutes", "1"));
        assertOk(r);
        JsonObject out = JsonParser.parseString(r.text()).getAsJsonObject();
        assertEquals(id, out.getAsJsonPrimitive("ticket_id").getAsString());
        assertEquals("RUNNING", out.getAsJsonPrimitive("state").getAsString());

        assertEquals(FleetJob.State.MERGED, awaitState(id, FleetJob.State.MERGED));
        assertEquals("in-review", store.get(PROJECT, id).status);
    }

    @Test
    public void jobsIsEmptyBeforeAnyDispatch() {
        McpToolResult r = provider.call("fleet_jobs", new JsonObject());
        assertOk(r);
        assertEquals(0, JsonParser.parseString(r.text()).getAsJsonArray().size());
    }

    @Test
    public void jobActivityRequiresTicketOrSession() {
        McpToolResult r = provider.call("fleet_job_activity", new JsonObject());
        assertTrue(r.text(), r.isError());
        assertTrue(r.text(), r.text().contains("ticket_id or session_id"));
    }

    @Test
    public void jobActivityWithAnUnknownTicketStatesTheHint() {
        McpToolResult r = provider.call("fleet_job_activity", args("ticket_id", "T-999"));
        assertOk(r);
        JsonObject out = JsonParser.parseString(r.text()).getAsJsonObject();
        assertEquals("unknown", out.getAsJsonPrimitive("state").getAsString());
        assertTrue(out.getAsJsonPrimitive("hint").getAsString().contains("session_id"));
    }

    @Test
    public void jobActivityObservesTheJobsSessionLive() throws Exception {
        String id = sprintTicket();
        sessionCompletes();
        // the worker's raw transcript (newest first): a finished shell, then
        // an assistant mid-tool-call - exactly what the observer must surface
        client.messagesJsonBySession.put("ses_1", JsonParser.parseString("""
                [
                 {"id":"m2","type":"shell","command":"git status","status":"exited","exit":0,
                  "output":{"output":"clean"}},
                 {"id":"m1","type":"assistant","agent":"executor","cost":0.01,
                  "tokens":{"input":10,"output":2},
                  "content":[
                    {"type":"tool","tool":"bash","state":{"status":"running","input":{"command":"mvn verify"}}},
                    {"type":"text","text":"building now"}]}
                ]
                """).getAsJsonArray());

        McpToolResult r = provider.call("fleet_dispatch",
                args("project", PROJECT, "ticket_id", id, "timeout_minutes", "1"));
        assertOk(r);
        assertEquals(FleetJob.State.MERGED, awaitState(id, FleetJob.State.MERGED));

        r = provider.call("fleet_job_activity", args("ticket_id", id));
        assertOk(r);
        JsonObject out = JsonParser.parseString(r.text()).getAsJsonObject();
        assertEquals("MERGED", out.getAsJsonPrimitive("state").getAsString());
        assertEquals("ses_1", out.getAsJsonPrimitive("session_id").getAsString());
        assertEquals("idle", out.getAsJsonPrimitive("status").getAsString());
        assertEquals("tool: bash mvn verify", out.getAsJsonPrimitive("activity").getAsString());
        assertEquals("building now", out.getAsJsonPrimitive("last_text").getAsString());
        JsonArray tools = out.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        assertEquals("bash", tools.get(0).getAsJsonObject().getAsJsonPrimitive("name").getAsString());
        assertEquals("mvn verify", tools.get(0).getAsJsonObject().getAsJsonPrimitive("target").getAsString());
        JsonArray shells = out.getAsJsonArray("shells");
        assertEquals(1, shells.size());
        assertEquals("git status", shells.get(0).getAsJsonObject().getAsJsonPrimitive("command").getAsString());
        assertEquals(0, shells.get(0).getAsJsonObject().getAsJsonPrimitive("exit").getAsInt());
        assertEquals(0.01, out.getAsJsonPrimitive("cost_usd").getAsDouble(), 0.0001);
    }

    @Test
    public void jobActivityBySessionIdNeedsNoTicket() {
        client.messagesJsonBySession.put("ses_x", JsonParser.parseString("""
                [{"id":"m1","type":"assistant","content":[{"type":"text","text":"subagent working"}]}]
                """).getAsJsonArray());

        McpToolResult r = provider.call("fleet_job_activity", args("session_id", "ses_x"));

        assertOk(r);
        JsonObject out = JsonParser.parseString(r.text()).getAsJsonObject();
        assertFalse("no ticket fields on a direct observation", out.has("ticket_id"));
        assertEquals("ses_x", out.getAsJsonPrimitive("session_id").getAsString());
        assertEquals("subagent working", out.getAsJsonPrimitive("last_text").getAsString());
    }

    @Test
    public void permissionsListIsEmptyWhenNothingIsPending() {
        McpToolResult r = provider.call("fleet_permissions", new JsonObject());
        assertOk(r);
        assertEquals(0, JsonParser.parseString(r.text()).getAsJsonArray().size());
    }

    @Test
    public void permissionsListPendingAskWithItsFields() {
        enginePermissions.offer(asked("per_1"));

        McpToolResult r = provider.call("fleet_permissions", new JsonObject());
        assertOk(r);
        JsonArray asks = JsonParser.parseString(r.text()).getAsJsonArray();
        assertEquals(1, asks.size());
        JsonObject ask = asks.get(0).getAsJsonObject();
        assertEquals("per_1", ask.getAsJsonPrimitive("permission_id").getAsString());
        assertEquals("ses_1", ask.getAsJsonPrimitive("session_id").getAsString());
        assertEquals("bash", ask.getAsJsonPrimitive("permission").getAsString());
        assertEquals("git push origin main", ask.getAsJsonPrimitive("title").getAsString());
        assertEquals("git push", ask.getAsJsonArray("patterns").get(0).getAsString());
    }

    @Test
    public void answerOnceSucceedsAndDropsTheAskFromTheList() {
        enginePermissions.offer(asked("per_1"));

        McpToolResult r = provider.call("fleet_permissions_answer",
                args("permission_id", "per_1", "response", "once"));
        assertOk(r);
        assertTrue(r.text(), r.text().contains("answered per_1"));
        assertEquals(List.of("ses_1|per_1|once|false"), responder.calls);

        McpToolResult after = provider.call("fleet_permissions", new JsonObject());
        assertOk(after);
        assertEquals(0, JsonParser.parseString(after.text()).getAsJsonArray().size());
    }

    @Test
    public void answerRejectWithRememberReachesTheResponder() {
        enginePermissions.offer(asked("per_1"));

        McpToolResult r = provider.call("fleet_permissions_answer",
                args("permission_id", "per_1", "response", "reject", "remember", "true"));
        assertOk(r);

        assertEquals(List.of("ses_1|per_1|reject|true"), responder.calls);
    }

    @Test
    public void answerUnknownIdReturnsTheFailureMessage() {
        McpToolResult r = provider.call("fleet_permissions_answer",
                args("permission_id", "per_nope", "response", "once"));
        assertOk(r);
        assertTrue(r.text(), r.text().contains("unknown permission request per_nope"));
    }

    @Test
    public void answerMalformedResponseIsAParamError() {
        assertThrows(ParamError.class, () -> provider.call("fleet_permissions_answer",
                args("permission_id", "per_1", "response", "maybe")));
    }

    @Test
    public void dispatchUnknownTicketIsAnError() {
        McpToolResult r = provider.call("fleet_dispatch", args("project", PROJECT, "ticket_id", "T-999"));
        assertTrue(r.isError());
        assertTrue(r.text(), r.text().contains("T-999"));
    }

    @Test
    public void dispatchBlockedOrDoneTicketsAreErrors() {
        String blocked = sprintTicket();
        store.setBlocked(PROJECT, blocked, "waiting on upstream", "test");
        McpToolResult r1 = provider.call("fleet_dispatch", args("project", PROJECT, "ticket_id", blocked));
        assertTrue(r1.isError());
        assertTrue(r1.text(), r1.text().contains("blocked"));

        String done = sprintTicket();
        store.update(PROJECT, done, Map.of("status", "done"));
        McpToolResult r2 = provider.call("fleet_dispatch", args("project", PROJECT, "ticket_id", done));
        assertTrue(r2.isError());
        assertTrue(r2.text(), r2.text().contains("done"));
    }

    @Test
    public void dispatchTwiceWhileInFlightIsAnError() throws Exception {
        String id = sprintTicket();
        sessionCompletes(); // arms completion for when the block releases
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.blockOnSend = () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        assertOk(provider.call("fleet_dispatch",
                args("project", PROJECT, "ticket_id", id, "timeout_minutes", "1")));
        assertTrue("fleet never started the session", entered.await(10, java.util.concurrent.TimeUnit.SECONDS));

        McpToolResult second = provider.call("fleet_dispatch",
                args("project", PROJECT, "ticket_id", id));
        assertTrue(second.isError());
        assertTrue(second.text(), second.text().contains("in flight"));

        client.blockOnSend = null;
        release.countDown();
        assertEquals(FleetJob.State.MERGED, awaitState(id, FleetJob.State.MERGED));
    }

    @Test
    public void storeToolsReportOnAPlainDirectory() {
        McpToolResult status = provider.call("fleet_status_store", new JsonObject());
        assertOk(status);
        assertEquals("store is not inside a git repository", status.text());

        McpToolResult sync = provider.call("fleet_sync_store", new JsonObject());
        assertOk(sync);
        assertTrue(sync.text(), sync.text().contains("NOT_A_REPO"));
    }

    @Test
    public void repoRootDerivesFromTheStoreRoot() {
        Path root = Path.of("repo", ".opencode", "tasks");
        assertEquals(Path.of("repo").toAbsolutePath(),
                FleetControl.repoRootOf(root));
    }
}
