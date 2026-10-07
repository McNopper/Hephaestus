package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.FleetJobHandle.State;
import com.opencode.ide.board.model.FleetTree.Kind;
import com.opencode.ide.board.model.FleetTree.Node;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.tasks.Task;

/**
 * U-040: the Fleet view's tree composition ({@link FleetTree}) — engine/wave
 * grouping, session/subagent/shell nesting, rollup totals, the shared badge
 * language, external (peer) separation and the degradation paths when the
 * store ticket or the observation is missing. Pure fixtures; no server, no
 * shell, no store.
 */
public class FleetTreeTest {

    private static final String OWN = "This Eclipse (board fleet)";
    private static final String PEER = "Peer engines (shared store)";

    private static Task ticket(String id, String sprint, String type, String status) {
        Task task = new Task();
        task.id = id;
        task.title = "title of " + id;
        task.sprint = sprint;
        task.type = type;
        task.status = status;
        return task;
    }

    private static FleetJobHandle job(String taskId, String sessionId, State state) {
        return new FleetJobHandle(taskId, sessionId, "/wt/" + taskId, state, "detail of " + taskId);
    }

    private static SessionObservation observation(String sessionId, String activity,
            Double cost, Long tokens, List<SessionObservation.ShellRun> shells,
            List<SessionObservation.Child> subagents) {
        return new SessionObservation(sessionId, "t-" + sessionId, "agent", null, "busy", null,
                cost, tokens, activity, null, List.of(), shells, subagents);
    }

    private static SessionObservation.ShellRun shell(String command, String status, Integer exit) {
        return new SessionObservation.ShellRun(command, status, exit, "out\n");
    }

    private static SessionObservation.Child child(String sessionId, Double cost, Long tokens) {
        return new SessionObservation.Child(sessionId, "sub-" + sessionId, "worker", "busy",
                cost, tokens);
    }

    /** Depth-first finder over the composed tree. */
    private static Node find(List<Node> nodes, Kind kind, String labelPrefix) {
        for (Node node : nodes) {
            if (node.kind() == kind && node.label() != null && node.label().startsWith(labelPrefix)) {
                return node;
            }
            Node hit = find(node.children(), kind, labelPrefix);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Test
    public void emptyJobsYieldNoRoots() {
        assertTrue(FleetTree.compose(List.of(), null, null, OWN, PEER).isEmpty());
        assertTrue(FleetTree.compose(null, null, null, OWN, PEER).isEmpty());
    }

    @Test
    public void ownJobNestsUnderOwnEngineAndItsWaveWithTheTicketBadges() {
        Task ticket = ticket("T-1", "S-01", "bug", "in-progress");
        List<Node> roots = FleetTree.compose(List.of(job("T-1", "ses_1", State.RUNNING)),
                id -> ticket, null, OWN, PEER);
        assertEquals(1, roots.size());
        assertEquals(Kind.ENGINE, roots.get(0).kind());
        assertEquals(OWN, roots.get(0).label());
        assertTrue(roots.get(0).running());
        Node wave = roots.get(0).children().get(0);
        assertEquals(Kind.WAVE, wave.kind());
        assertEquals("S-01", wave.label());
        Node jobNode = wave.children().get(0);
        assertEquals(Kind.JOB, jobNode.kind());
        assertTrue(jobNode.label().contains("T-1"));
        assertTrue(jobNode.label().contains("title of T-1"));
        assertTrue(jobNode.badge().contains(TicketRow.statusSymbol("in-progress")));
        assertTrue(jobNode.badge().contains(TicketRow.typeTag("bug")));
        assertEquals("bug", jobNode.ticketType());
        assertEquals("ses_1", jobNode.sessionId());
        // no observation given: the session node degrades to the bare id
        Node session = jobNode.children().get(0);
        assertEquals(Kind.SESSION, session.kind());
        assertEquals("ses_1", session.label());
        assertTrue(session.children().isEmpty());
    }

    @Test
    public void peerJobsGroupUnderThePeerRootAndKeepExternalAllTheWayDown() {
        Task ticket = ticket("T-2", "S-02", "task", "in-progress");
        FleetJobHandle peer = new FleetJobHandle("T-2", "ses_2", "/wt/T-2", State.RUNNING,
                "peer detail", true);
        List<Node> roots = FleetTree.compose(
                List.of(job("T-1", "ses_1", State.RUNNING), peer),
                id -> ticket, id -> null, OWN, PEER);
        assertEquals(2, roots.size());
        Node peerEngine = roots.get(1);
        assertEquals(PEER, peerEngine.label());
        assertTrue(peerEngine.external());
        Node peerJob = find(List.of(peerEngine), Kind.JOB, "T-2");
        assertTrue(peerJob.external());
        assertTrue(peerJob.children().get(0).external());
    }

    @Test
    public void observationsAddSubagentsAndShellsAndRollUpTokensAndCost() {
        SessionObservation observed = observation("ses_1", "tool: write", 0.010, 1000L,
                List.of(shell("mvn verify", "running", null), shell("ls", "completed", 0)),
                List.of(child("ses_sub", 0.020, 500L)));
        List<Node> roots = FleetTree.compose(List.of(job("T-1", "ses_1", State.RUNNING)),
                id -> null, id -> "ses_1".equals(id) ? observed : null, OWN, null);
        Node session = find(roots, Kind.SESSION, "t-ses_1");
        assertEquals("tool: write", session.detail());
        assertTrue(session.running());
        Node subagent = find(roots, Kind.SESSION, "sub-ses_sub");
        assertEquals(Long.valueOf(500L), subagent.tokens());
        assertEquals(Double.valueOf(0.020), subagent.cost());
        List<Node> shells = session.children().stream().filter(Node::isShell).toList();
        assertEquals(2, shells.size());
        assertTrue(shells.get(0).running());
        assertFalse(shells.get(1).running());
        assertEquals("completed exit 0", shells.get(1).detail());
        // rollup: job carries session + subagent totals, engine carries the job
        Node jobNode = find(roots, Kind.JOB, "T-1");
        assertEquals(Long.valueOf(1500L), jobNode.tokens());
        assertEquals(0.030, jobNode.cost(), 0.0001);
        assertEquals(Long.valueOf(1500L), roots.get(0).tokens());
    }

    @Test
    public void nullTicketDegradesToNoBadgeAndTheNoWaveGroup() {
        List<Node> roots = FleetTree.compose(List.of(job("T-9", null, State.RUNNING)),
                id -> null, id -> null, OWN, null);
        Node wave = roots.get(0).children().get(0);
        assertEquals("(no wave)", wave.label());
        Node jobNode = wave.children().get(0);
        assertNull(jobNode.badge());
        assertNull(jobNode.ticketType());
        assertEquals("(no session yet)", jobNode.children().get(0).label());
    }

    @Test
    public void wavelessJobsSortLastAndWavesSortNaturally() {
        Task a = ticket("T-1", "S-02", "task", "in-progress");
        Task b = ticket("T-2", null, "task", "in-progress");
        Task c = ticket("T-3", "S-01", "task", "in-progress");
        java.util.Map<String, Task> tickets = java.util.Map.of("T-1", a, "T-2", b, "T-3", c);
        List<Node> roots = FleetTree.compose(List.of(job("T-1", null, State.RUNNING),
                job("T-2", null, State.COMPLETED), job("T-3", null, State.MERGED)),
                tickets::get, null, OWN, null);
        List<Node> waves = roots.get(0).children();
        assertEquals("S-01", waves.get(0).label());
        assertEquals("S-02", waves.get(1).label());
        assertEquals("(no wave)", waves.get(2).label());
    }

    @Test
    public void runningPropagatesFromAShellUpToTheEngine() {
        SessionObservation observed = observation("ses_1", null, null, null,
                List.of(shell("mvn verify", "running", null)), List.of());
        List<Node> roots = FleetTree.compose(List.of(job("T-1", "ses_1", State.COMPLETED)),
                id -> null, id -> observed, OWN, null);
        assertTrue(roots.get(0).running());
        Node wave = roots.get(0).children().get(0);
        assertTrue(wave.running());
        assertTrue(wave.children().get(0).running());
    }

    @Test
    public void blockedTicketsCarryTheBlockedBadge() {
        Task ticket = ticket("T-1", "S-01", "story", "in-progress");
        ticket.status = "blocked";
        ticket.blocker = "waiting";
        List<Node> roots = FleetTree.compose(List.of(job("T-1", null, State.RUNNING)),
                id -> ticket, null, OWN, null);
        Node jobNode = find(roots, Kind.JOB, "T-1");
        assertTrue(jobNode.badge().contains("[BLOCKED]"));
    }

    @Test
    public void failedAndMergedJobStatesKeepTheirDecorationFlags() {
        List<Node> roots = FleetTree.compose(List.of(job("T-1", null, State.FAILED),
                job("T-2", null, State.MERGED)), id -> null, null, OWN, null);
        Node failed = find(roots, Kind.JOB, "T-1");
        assertTrue(failed.failed());
        assertFalse(failed.running());
        Node merged = find(roots, Kind.JOB, "T-2");
        assertFalse(merged.failed());
    }

    @Test
    public void unknownTotalsStayNullInsteadOfZeroFaked() {
        List<Node> roots = FleetTree.compose(List.of(job("T-1", null, State.RUNNING)),
                id -> null, null, OWN, null);
        assertNull(roots.get(0).tokens());
        assertNull(roots.get(0).cost());
    }

    @Test
    public void thePeerRootIsOmittedWhenAskedForNull() {
        FleetJobHandle peer = new FleetJobHandle("T-2", "ses_2", "/wt/T-2", State.RUNNING,
                "detail", true);
        List<Node> roots = FleetTree.compose(List.of(job("T-1", null, State.RUNNING), peer),
                id -> null, null, OWN, null);
        assertEquals(1, roots.size());
        assertEquals(OWN, roots.get(0).label());
    }
}
