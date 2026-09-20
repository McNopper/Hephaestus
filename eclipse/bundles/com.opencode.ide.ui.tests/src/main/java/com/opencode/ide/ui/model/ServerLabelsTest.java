package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.activity.ActivitySnapshot;
import com.opencode.ide.client.activity.FileActivity;
import com.opencode.ide.client.activity.SessionActivity;
import com.opencode.ide.client.activity.ToolActivity;
import com.opencode.ide.client.activity.ToolActivity.State;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.ui.model.ServerLabels.Server;

/**
 * Unit tests for the SWT-free {@link ServerLabels} label and nesting logic
 * behind the Server view: no SWT, no JFace, no Display.
 */
public class ServerLabelsTest {

    private static final long NOW = System.currentTimeMillis();

    // ---------- fixtures ----------

    private static Session session(String id, String parentID, long updated) {
        return new Session(id, null, "Title " + id, null, parentID, null,
                new Session.Time(NOW, updated, 0L), null, null, null, null);
    }

    private static Agent agent(String mode, String description) {
        // v2 Agent.Info: (id, name, description, mode, hidden, permissions,
        // steps, color, model, system) — no native/builtIn component any more
        return new Agent("name", "name", description, mode, null, null, null, null, null, null);
    }

    /** A bare v2 session: only the fields a label test cares about. */
    private static Session named(String id, String title, String agent) {
        return new Session(id, null, title, agent, null, null, null, null, null, null, null);
    }

    private record FakeServer(String name, List<Session> sessions) {
    }

    // ---------- server labels ----------

    @Test
    public void primaryServerNameIsFixed() {
        Server server = new Server(true, null, null, "http://localhost:4096", false, null, null);

        assertEquals("opencode server", ServerLabels.serverName(server));
    }

    @Test
    public void remoteServerNameTagsOfflineWhenUnhealthy() {
        Server healthy = new Server(false, "ci", null, "http://ci:4096", true, null, null);
        Server unhealthy = new Server(false, "ci", null, "http://ci:4096", false, null, null);

        assertEquals("ci", ServerLabels.serverName(healthy));
        assertEquals("ci  (offline)", ServerLabels.serverName(unhealthy));
    }

    @Test
    public void remoteServerNameFallsBackToUrlWhenLabelMissing() {
        Server noLabel = new Server(false, null, null, "http://ci:4096", false, null, null);
        Server emptyLabel = new Server(false, "", null, "http://ci:4096", true, null, null);

        assertEquals("http://ci:4096  (offline)", ServerLabels.serverName(noLabel));
        assertEquals("http://ci:4096", ServerLabels.serverName(emptyLabel));
    }

    @Test
    public void serverDetailFormatsHealthVersionModeUrlAndPid() {
        Server primary = new Server(true, "primary", "idex", "http://localhost:4096", true, "1.18.2", 42L);
        Server primaryDown = new Server(true, null, null, "http://x", false, null, null);
        Server remote = new Server(false, "ci", null, "http://ci:4096", true, "1.2", null);
        Server remoteDown = new Server(false, "ci", null, "http://ci:4096", false, null, null);

        assertEquals("healthy • v1.18.2 • idex • http://localhost:4096 • pid 42",
                ServerLabels.serverDetail(primary));
        assertEquals("unhealthy • http://x", ServerLabels.serverDetail(primaryDown));
        assertEquals("healthy • v1.2 • http://ci:4096", ServerLabels.serverDetail(remote));
        assertEquals("offline • http://ci:4096", ServerLabels.serverDetail(remoteDown));
    }

    // ---------- agent labels ----------

    @Test
    public void agentDetailCombinesModeNativeAndDescription() {
        Agent a = agent("build", "Runs the build");
        // v2's Agent.Info carries neither `native` nor `builtIn` and
        // Agent#isNative() is hardcoded to false, so the marker is dormant —
        // assert against the record's own answer instead of hard-coding the
        // v1 expectation, and the test survives the marker being retired
        String nativeMarker = a.isNative() ? " • native" : "";

        assertEquals("build" + nativeMarker + " — Runs the build", ServerLabels.agentDetail(a));
        assertEquals("", ServerLabels.agentDetail(agent(null, null)));
        assertEquals(" — writes code", ServerLabels.agentDetail(agent(null, "writes code")));
    }

    // ---------- session labels ----------

    @Test
    public void sessionNamePrefersTitleThenIdAndPrefixesAgent() {
        // v2 sessions carry no slug, so the fallback chain is title -> id
        assertEquals("Fix build", ServerLabels.sessionName(named("s1", "Fix build", null)));
        assertEquals("s1", ServerLabels.sessionName(named("s1", null, null)));
        assertEquals("s1", ServerLabels.sessionName(named("s1", "", null)));
        assertEquals("build — Fix build", ServerLabels.sessionName(named("s1", "Fix build", "build")));
    }

    @Test
    public void nestedSessionNameShowsBareTitleWithoutAgentPrefix() {
        // nested under the agent row, the agent prefix would be noise
        assertEquals("Fix build", ServerLabels.nestedSessionName(named("s1", "Fix build", "build")));
        assertEquals("s1", ServerLabels.nestedSessionName(named("s1", null, "build")));
        assertEquals("s1", ServerLabels.nestedSessionName(named("s1", null, null)));
        // the Sessions category keeps the agent-prefixed form
        assertEquals("build — Fix build", ServerLabels.sessionName(named("s1", "Fix build", "build")));
    }

    @Test
    public void sessionDetailPrefersLiveLabelAndMarksSubagents() {
        Session root = session("s1", null, 0L);
        Session subagent = session("s2", "s1", 0L);

        assertEquals("thinking…", ServerLabels.sessionDetail(root, "thinking…", "busy"));
        assertEquals("busy", ServerLabels.sessionDetail(root, null, "busy"));
        assertEquals("idle", ServerLabels.sessionDetail(root, null, null));
        assertEquals("busy • subagent", ServerLabels.sessionDetail(subagent, null, "busy"));
    }

    @Test
    public void sessionNameAppendsWorkingSuffixOnlyWhileWorking() {
        Session s = named("s1", "Fix build", "build");

        assertEquals("build — Fix build", ServerLabels.sessionName(s, false));
        assertEquals("build — Fix build  • working", ServerLabels.sessionName(s, true));
        assertEquals("Fix build", ServerLabels.nestedSessionName(s, false));
        assertEquals("Fix build  • working", ServerLabels.nestedSessionName(s, true));
    }

    @Test
    public void sessionDetailAppendsRelativeUpdateTime() {
        Session s = new Session("s1", null, null, null, null, null,
                new Session.Time(NOW, NOW - 5 * 60_000L - 10_000L, 0L), null, null, null, null);

        assertEquals("busy • updated 5m ago", ServerLabels.sessionDetail(s, null, "busy"));
    }

    @Test
    public void relativeTimeBuckets() {
        assertEquals("just now", ServerLabels.relative(NOW - 30_000L));
        assertEquals("5m ago", ServerLabels.relative(NOW - 5 * 60_000L - 10_000L));
        assertEquals("3h ago", ServerLabels.relative(NOW - 3 * 3_600_000L - 300_000L));
        assertEquals("2d ago", ServerLabels.relative(NOW - 2 * 86_400_000L - 3_600_000L));
        assertEquals("just now", ServerLabels.relative(NOW + 60_000L)); // future clamps to 0
    }

    // ---------- status ----------

    @Test
    public void statusTypeDefaultsToIdleAndIsBusyMatchesBusyOrRetry() {
        Map<String, SessionStatus> statuses = Map.of(
                "s1", new SessionStatus("busy"),
                "s2", new SessionStatus("idle"),
                "s3", new SessionStatus(null),
                "s4", new SessionStatus("Retry"));
        Session s1 = session("s1", null, 0L);
        Session s2 = session("s2", null, 0L);
        Session s3 = session("s3", null, 0L);
        Session s4 = session("s4", null, 0L);
        Session unknown = session("zz", null, 0L);

        assertEquals("busy", ServerLabels.statusType(statuses, s1));
        assertEquals("idle", ServerLabels.statusType(statuses, s2));
        assertEquals("idle", ServerLabels.statusType(statuses, s3));
        assertEquals("idle", ServerLabels.statusType(statuses, unknown));
        assertEquals("idle", ServerLabels.statusType(null, s1));

        assertTrue(ServerLabels.isBusy(statuses, s1));
        assertTrue(ServerLabels.isBusy(statuses, s4)); // case-insensitive
        assertFalse(ServerLabels.isBusy(statuses, s2));
        assertFalse(ServerLabels.isBusy(null, s1));
    }

    // ---------- live activity labels ----------

    @Test
    public void trackerLabelPrefersThinkingThenRunningTool() {
        ActivitySnapshot thinking = new ActivitySnapshot(
                Map.of("s1", new SessionActivity("s1", false, true, List.of())), Map.of());
        ActivitySnapshot toolWithFile = new ActivitySnapshot(
                Map.of("s1", new SessionActivity("s1", true, false,
                        List.of(new ToolActivity("edit", "Main.java", State.RUNNING),
                                new ToolActivity("bash", null, State.COMPLETED)))), Map.of());
        ActivitySnapshot toolWithoutFile = new ActivitySnapshot(
                Map.of("s1", new SessionActivity("s1", true, false,
                        List.of(new ToolActivity("bash", null, State.RUNNING)))), Map.of());
        ActivitySnapshot completedOnly = new ActivitySnapshot(
                Map.of("s1", new SessionActivity("s1", false, false,
                        List.of(new ToolActivity("edit", "Main.java", State.COMPLETED)))), Map.of());

        assertEquals("thinking…", ServerLabels.trackerLabel(thinking, "s1"));
        assertEquals("tool: edit — Main.java", ServerLabels.trackerLabel(toolWithFile, "s1"));
        assertEquals("tool: bash", ServerLabels.trackerLabel(toolWithoutFile, "s1"));
        assertNull(ServerLabels.trackerLabel(completedOnly, "s1"));
        assertNull(ServerLabels.trackerLabel(toolWithFile, "other"));
        assertNull(ServerLabels.trackerLabel(null, "s1"));
    }

    @Test
    public void fileActivityNameUsesFileToolAndShortenedSessionId() {
        assertEquals("src/Main.java — edit (session-)",
                ServerLabels.fileActivityName(new FileActivity("session-abcdefgh123", "edit", "src/Main.java")));
        assertEquals("abc", ServerLabels.shortId("abc"));
        assertEquals("", ServerLabels.shortId(null));
    }

    @Test
    public void activityLabelMapsV2EventTypesToTheLiveLabel() {
        // v1 carried the part kind in the payload (part.type of a
        // message.part.updated); v2 encodes it in the event name, so the
        // label is a pure function of the type
        assertEquals("thinking", ServerLabels.activityLabel("session.reasoning.started"));
        assertEquals("thinking", ServerLabels.activityLabel("session.reasoning.delta"));
        assertEquals("running tool", ServerLabels.activityLabel("session.tool.called"));
        assertEquals("running tool", ServerLabels.activityLabel("session.tool.input.started"));
        assertEquals("running tool", ServerLabels.activityLabel("session.tool.input.delta"));
        assertEquals("running tool", ServerLabels.activityLabel("session.tool.progress"));
        assertEquals("responding", ServerLabels.activityLabel("session.text.started"));
        assertEquals("responding", ServerLabels.activityLabel("session.text.delta"));
    }

    @Test
    public void activityLabelIgnoresNonStreamingAndRetiredV1Types() {
        assertNull(ServerLabels.activityLabel("session.idle"));
        assertNull(ServerLabels.activityLabel("session.status"));
        assertNull(ServerLabels.activityLabel("session.execution.succeeded"));
        assertNull(ServerLabels.activityLabel("message.part.updated")); // v1, gone
        assertNull(ServerLabels.activityLabel("message.part.delta"));   // v1, gone
        assertNull(ServerLabels.activityLabel(""));
        assertNull(ServerLabels.activityLabel(null));
    }

    // ---------- category ----------

    @Test
    public void sessionsCategoryDetailCountsTotalTopLevelAndBusy() {
        Session root1 = session("a", null, 300L);
        Session child = session("c1", "a", 999L);
        Session root2 = session("b", null, 100L);
        List<Session> sessions = List.of(root1, child, root2);
        Map<String, SessionStatus> busy = Map.of("c1", new SessionStatus("busy"));

        assertEquals("Agents (2)", ServerLabels.categoryName("Agents", 2));
        assertEquals("", ServerLabels.sessionsCategoryDetail(List.of(), null));
        assertEquals("3 total, 2 top-level • 1 busy",
                ServerLabels.sessionsCategoryDetail(sessions, busy));
        assertEquals("3 total, 2 top-level",
                ServerLabels.sessionsCategoryDetail(sessions, null));
    }

    @Test
    public void categoryNameAppendsWorkingCountProminently() {
        assertEquals("Sessions (5)", ServerLabels.categoryName("Sessions", 5, 0));
        assertEquals("Sessions (5)  • 2 working", ServerLabels.categoryName("Sessions", 5, 2));
        assertEquals("Agents (0)  • 1 working", ServerLabels.categoryName("Agents", 0, 1));
    }

    @Test
    public void busyCountsBusyAndRetrySessionsOnly() {
        List<Session> sessions = List.of(session("a", null, 1L), session("b", null, 2L),
                session("c", "a", 3L));
        Map<String, SessionStatus> statuses = Map.of(
                "a", new SessionStatus("busy"), "b", new SessionStatus("retry"));

        assertEquals(2, ServerLabels.busyCount(sessions, statuses));
        assertEquals(0, ServerLabels.busyCount(sessions, null));
        assertEquals(0, ServerLabels.busyCount(null, statuses));
    }

    // ---------- working aggregation ----------

    @Test
    public void hasBusyDescendantWalksTheWholeSubagentTree() {
        Session root = session("root", null, 1L);
        Session child = session("child", "root", 2L);
        Session grandchild = session("gc", "child", 3L);
        List<Session> sessions = List.of(root, child, grandchild);
        Map<String, SessionStatus> busyGrandchild = Map.of("gc", new SessionStatus("busy"));
        Map<String, SessionStatus> busyChild = Map.of("child", new SessionStatus("busy"));

        assertTrue(ServerLabels.hasBusyDescendant(sessions, busyGrandchild, "root"));   // transitive
        assertTrue(ServerLabels.hasBusyDescendant(sessions, busyChild, "root"));        // direct child
        assertFalse(ServerLabels.hasBusyDescendant(sessions, Map.of(), "root"));        // nothing busy
        assertFalse(ServerLabels.hasBusyDescendant(sessions, busyChild, "gc"));         // descendants only
        assertFalse(ServerLabels.hasBusyDescendant(sessions, busyChild, null));
        assertFalse(ServerLabels.hasBusyDescendant(null, busyChild, "root"));
    }

    @Test(timeout = 5_000)
    public void hasBusyDescendantSurvivesCyclicParentChains() {
        Session a = session("a", "b", 1L);
        Session b = session("b", "a", 2L);
        List<Session> sessions = List.of(a, b);

        // terminates despite a <-> b, and still sees the busy node in the cycle
        assertTrue(ServerLabels.hasBusyDescendant(sessions, Map.of("b", new SessionStatus("busy")), "a"));
        assertFalse(ServerLabels.hasBusyDescendant(sessions, Map.of(), "a"));
    }

    // ---------- nesting / ownership ----------

    @Test
    public void ownerOfFindsTheServerOwningTheSessionAndFallsBackOtherwise() {
        FakeServer primary = new FakeServer("primary", List.of(session("s1", null, 1L)));
        FakeServer remote = new FakeServer("remote", List.of(session("s2", null, 2L)));
        List<FakeServer> servers = List.of(primary, remote);

        assertEquals(remote, ServerLabels.ownerOf(servers, session("s2", null, 0L), f -> f.sessions(), primary));
        assertEquals(primary, ServerLabels.ownerOf(servers, session("s1", null, 0L), f -> f.sessions(), remote));
        assertEquals(primary, ServerLabels.ownerOf(servers, session("zz", null, 0L), f -> f.sessions(), primary));
        assertEquals(primary, ServerLabels.ownerOf(servers, null, f -> f.sessions(), primary));
        assertEquals(primary, ServerLabels.ownerOf(servers,
                named(null, null, null), f -> f.sessions(), primary));
    }

    @Test
    public void topLevelSessionsAreParentlessAndMostRecentFirst() {
        Session root1 = session("a", null, 300L);
        Session child = session("c1", "a", 999L);
        Session root2 = session("b", null, 100L);

        List<Session> top = ServerLabels.topLevelSessions(List.of(root1, child, root2));

        assertEquals(List.of("a", "b"), top.stream().map(Session::id).toList());
    }

    @Test
    public void childrenOfNestsByParentIdMostRecentFirstAndStableForEqualTimes() {
        Session root = session("a", null, 300L);
        Session cNew = session("c1", "a", 999L);
        Session cOld = session("c2", "a", 500L);
        Session other = session("b", null, 100L);
        List<Session> sessions = List.of(root, cNew, cOld, other);

        assertEquals(List.of("c1", "c2"),
                ServerLabels.childrenOf(sessions, "a").stream().map(Session::id).toList());
        assertEquals(List.of(), ServerLabels.childrenOf(sessions, null));
        assertEquals(List.of(), ServerLabels.childrenOf(sessions, "zz"));

        Session e1 = session("e1", "a", 100L);
        Session e2 = session("e2", "a", 100L);
        assertEquals(List.of("e1", "e2"), // equal timestamps keep list order
                ServerLabels.childrenOf(List.of(e1, e2), "a").stream().map(Session::id).toList());
    }

    @Test
    public void parentSessionResolvesParentNullForRootsAndOrphans() {
        Session root = session("a", null, 300L);
        Session child = session("c1", "a", 999L);
        Session orphan = session("o", "missing", 100L);
        List<Session> sessions = List.of(root, child, orphan);

        assertEquals("a", ServerLabels.parentSession(sessions, child).id());
        assertNull(ServerLabels.parentSession(sessions, root));
        assertNull(ServerLabels.parentSession(sessions, orphan));
        assertNull(ServerLabels.parentSession(sessions, null));
    }

    @Test
    public void hasSessionChildrenDetectsNestedSessions() {
        Session root = session("a", null, 300L);
        Session child = session("c1", "a", 999L);
        Session leaf = session("b", null, 100L);
        List<Session> sessions = List.of(root, child, leaf);

        assertTrue(ServerLabels.hasSessionChildren(sessions, "a"));
        assertFalse(ServerLabels.hasSessionChildren(sessions, "b"));
        assertFalse(ServerLabels.hasSessionChildren(sessions, null));
    }
}
