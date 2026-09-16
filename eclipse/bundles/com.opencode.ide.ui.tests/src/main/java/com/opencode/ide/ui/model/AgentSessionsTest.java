package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Unit tests for the SWT-free {@link AgentSessions} agent→live-session
 * nesting logic of the Server view's Agents category: no SWT, no JFace, no
 * Display.
 */
public class AgentSessionsTest {

    private static final long NOW = System.currentTimeMillis();

    // ---------- fixtures ----------

    private static Session session(String id, String agent, String parentID, long updated) {
        return new Session(id, "slug-" + id, "Title " + id, agent, parentID,
                new Session.Time(NOW, updated), null, null, null);
    }

    private static Agent agent(String name) {
        return new Agent(name, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static final Agent BUILD = agent("build");

    private record FakeServer(List<Agent> agents) {
    }

    // ---------- matching ----------

    @Test
    public void runsAgentMatchesTopLevelSessionWithExactAgentName() {
        assertTrue(AgentSessions.runsAgent(BUILD, session("s1", "build", null, 0L)));
        assertFalse(AgentSessions.runsAgent(BUILD, session("s1", null, null, 0L)));   // no agent field
        assertFalse(AgentSessions.runsAgent(BUILD, session("s1", "", null, 0L)));     // blank agent field
        assertFalse(AgentSessions.runsAgent(BUILD, session("s1", "Build", null, 0L))); // exact match only
        assertFalse(AgentSessions.runsAgent(BUILD, session("s1", "plan", null, 0L))); // different agent
        assertFalse(AgentSessions.runsAgent(BUILD, session("s1", "build", "p1", 0L))); // subagents stay under their parent session
        assertFalse(AgentSessions.runsAgent(agent(null), session("s1", null, null, 0L)));
        assertFalse(AgentSessions.runsAgent(BUILD, null));
        assertFalse(AgentSessions.runsAgent(null, session("s1", "build", null, 0L)));
    }

    // ---------- nesting / ordering ----------

    @Test
    public void sessionsOfNestsMatchingTopLevelSessionsBusyFirst() {
        Session idleNew = session("a", "build", null, 900L);
        Session busyOld = session("b", "build", null, 100L);
        Session otherAgent = session("c", "plan", null, 500L);
        Session subagent = session("d", "build", "x", 999L);
        Session noAgent = session("e", null, null, 800L);
        Map<String, SessionStatus> statuses = Map.of("b", new SessionStatus("busy"));

        List<Session> nested = AgentSessions.sessionsOf(
                List.of(idleNew, busyOld, otherAgent, subagent, noAgent), BUILD, statuses);

        assertEquals(List.of("b", "a"), nested.stream().map(Session::id).toList());
    }

    @Test
    public void sessionsOfKeepsMostRecentFirstWithinBusyAndIdleGroups() {
        Session busyOld = session("b1", "build", null, 100L);
        Session busyNew = session("b2", "build", null, 300L);
        Session idleNew = session("i1", "build", null, 900L);
        Session idleOld = session("i2", "build", null, 200L);
        Map<String, SessionStatus> statuses = Map.of(
                "b1", new SessionStatus("busy"), "b2", new SessionStatus("retry"));

        List<Session> nested = AgentSessions.sessionsOf(List.of(idleOld, idleNew, busyOld, busyNew),
                BUILD, statuses);

        assertEquals(List.of("b2", "b1", "i1", "i2"), nested.stream().map(Session::id).toList());
    }

    @Test
    public void sessionsOfToleratesNullSessionsAndNullStatuses() {
        Session a = session("a", "build", null, 100L);
        Session b = session("b", "build", null, 300L);

        assertEquals(List.of(), AgentSessions.sessionsOf(null, BUILD, null));
        assertEquals(List.of("b", "a"), // no statuses -> nothing busy, most recent first
                AgentSessions.sessionsOf(List.of(a, b), BUILD, null).stream().map(Session::id).toList());
    }

    // ---------- counts / labels ----------

    @Test
    public void hasSessionsAndRunningCountConsiderOnlyTopLevelMatches() {
        Session a = session("a", "build", null, 100L);
        Session b = session("b", "build", null, 300L);
        Session subagent = session("d", "build", "x", 999L);
        Session other = session("c", "plan", null, 500L);
        List<Session> sessions = List.of(a, b, subagent, other);

        assertTrue(AgentSessions.hasSessions(sessions, BUILD));
        assertEquals(2, AgentSessions.runningCount(sessions, BUILD)); // subagent "d" does not count
        assertTrue(AgentSessions.hasSessions(sessions, agent("plan"))); // "c" is a top-level "plan" session
        assertEquals(1, AgentSessions.runningCount(sessions, agent("plan")));
        assertFalse(AgentSessions.hasSessions(sessions, agent("review"))); // nobody runs it
        assertEquals(0, AgentSessions.runningCount(sessions, agent("review")));
        assertFalse(AgentSessions.hasSessions(null, BUILD));
        assertEquals(0, AgentSessions.runningCount(null, BUILD));
    }

    @Test
    public void agentNameAppendsRunningCountOnlyWhenPresent() {
        assertEquals("build — 2 running", AgentSessions.agentName("build", 2));
        assertEquals("build — 1 running", AgentSessions.agentName("build", 1));
        assertEquals("build", AgentSessions.agentName("build", 0));   // zero sessions: unchanged label
        assertEquals("(unnamed) — 2 running", AgentSessions.agentName(null, 2));
        assertEquals("(unnamed)", AgentSessions.agentName(null, 0));
        assertEquals("(unnamed)", AgentSessions.agentName("", 0));
    }

    @Test
    public void agentNameAppendsWorkingCountProminently() {
        assertEquals("build — 2 running  • 1 working", AgentSessions.agentName("build", 2, 1));
        assertEquals("build — 2 running", AgentSessions.agentName("build", 2, 0));
        assertEquals("build — 1 running  • 1 working", AgentSessions.agentName("build", 1, 1));
        assertEquals("build", AgentSessions.agentName("build", 0, 0));
        assertEquals("(unnamed) — 1 running  • 1 working", AgentSessions.agentName(null, 1, 1));
    }

    @Test
    public void workingCountIncludesBusySessionsAndBusySubagentsBelowThem() {
        Session busyTop = session("a", "build", null, 100L);
        Session idleTopWithBusyChild = session("b", "build", null, 200L);
        Session busyChild = session("c", "build", "b", 300L);
        Session idleTop = session("d", "build", null, 400L);
        Session otherAgent = session("e", "plan", null, 500L);
        List<Session> sessions = List.of(busyTop, idleTopWithBusyChild, busyChild, idleTop, otherAgent);
        Map<String, SessionStatus> statuses = Map.of(
                "a", new SessionStatus("busy"), "c", new SessionStatus("busy"));

        assertEquals(2, AgentSessions.workingCount(sessions, statuses, BUILD));   // a busy + b via busy subagent c
        assertEquals(0, AgentSessions.workingCount(sessions, statuses, agent("plan")));   // "e" is idle
        assertEquals(0, AgentSessions.workingCount(sessions, null, BUILD));      // no statuses -> nothing busy
        assertEquals(0, AgentSessions.workingCount(null, statuses, BUILD));
    }

    // ---------- owning server ----------

    @Test
    public void serverOfAgentPrefersIdentityThenEqualityFallback() {
        Agent primaryBuild = agent("build");
        Agent remoteBuild = agent("build"); // structurally equal definition on another server
        FakeServer primary = new FakeServer(List.of(primaryBuild, agent("plan")));
        FakeServer remote = new FakeServer(List.of(remoteBuild));
        List<FakeServer> servers = List.of(primary, remote);

        assertSame(remote, AgentSessions.serverOfAgent(servers, remoteBuild, f -> f.agents()));  // identity
        assertSame(primary, AgentSessions.serverOfAgent(servers, primaryBuild, f -> f.agents()));
        assertSame(primary, AgentSessions.serverOfAgent(servers, agent("build"), f -> f.agents())); // equals fallback
        assertSame(primary, AgentSessions.serverOfAgent(servers, agent("plan"), f -> f.agents()));
        assertNull(AgentSessions.serverOfAgent(servers, agent("missing"), f -> f.agents()));
        assertNull(AgentSessions.serverOfAgent(servers, null, f -> f.agents()));
        assertNull(AgentSessions.<FakeServer>serverOfAgent(null, agent("build"), f -> f.agents()));
    }
}
