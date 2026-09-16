package com.opencode.ide.ui.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opencode.ide.client.SessionOrder;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Pure agent→live-session nesting logic for the Agents category of the Server
 * view: which running sessions belong under which agent definition (a
 * top-level session's {@code agent} field equal to the agent's name), their
 * busy-first ordering, the {@code "name — n running"} label suffix, and the
 * owning-server resolution for agent nodes.
 *
 * <p>SWT-free and JFace-free on purpose — static methods over client model
 * types only — so it is unit-testable without a {@code Display} (see
 * {@code AgentSessionsTest} in {@code com.opencode.ide.ui.tests}). The
 * {@code ServerView} delegates here and keeps all SWT/JFace usage to itself.</p>
 */
public final class AgentSessions {

    private AgentSessions() {
    }

    /**
     * Whether the given session runs as the given agent and therefore nests
     * under the agent's definition row: top-level only ({@code parentID == null};
     * subagents stay nested under their parent session) and an exact, non-empty
     * name match on the session's {@code agent} field.
     */
    public static boolean runsAgent(Agent agent, Session session) {
        if (agent == null || session == null || session.parentID() != null) {
            return false;
        }
        String name = agent.name();
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.equals(session.agent());
    }

    /**
     * The server's sessions that run as the given agent (see
     * {@link #runsAgent}), busy sessions first, most recently updated first
     * within each group.
     */
    public static List<Session> sessionsOf(List<Session> sessions, Agent agent, Map<String, SessionStatus> statuses) {
        if (sessions == null) {
            return List.of();
        }
        Comparator<Session> busyFirst = Comparator.comparingInt(
                (Session s) -> ServerLabels.isBusy(statuses, s) ? 0 : 1);
        return sessions.stream()
                .filter(s -> runsAgent(agent, s))
                .sorted(busyFirst.thenComparing(SessionOrder.MOST_RECENT_FIRST))
                .collect(Collectors.toList());
    }

    /** Whether any session of the server runs as the given agent. */
    public static boolean hasSessions(List<Session> sessions, Agent agent) {
        return sessions != null && sessions.stream().anyMatch(s -> runsAgent(agent, s));
    }

    /** How many of the server's sessions currently run as the given agent. */
    public static int runningCount(List<Session> sessions, Agent agent) {
        if (sessions == null) {
            return 0;
        }
        return (int) sessions.stream().filter(s -> runsAgent(agent, s)).count();
    }

    /**
     * How many of the agent's live sessions are currently working — busy
     * themselves or with a busy subagent under them (see
     * {@code ServerLabels#hasBusyDescendant}). This is the count surfaced
     * in the agent row's {@code "n working"} suffix and the Agents
     * category's aggregate, so an agent whose subagents grind away is
     * visible at the parent level even while collapsed.
     */
    public static int workingCount(List<Session> sessions, Map<String, SessionStatus> statuses, Agent agent) {
        if (sessions == null) {
            return 0;
        }
        return (int) sessions.stream()
                .filter(s -> runsAgent(agent, s))
                .filter(s -> ServerLabels.isBusy(statuses, s)
                        || ServerLabels.hasBusyDescendant(sessions, statuses, s.id()))
                .count();
    }

    /**
     * Label of an agent definition row: the bare name, plus
     * {@code " — n running"} when live sessions are nested under it
     * (e.g. {@code "build — 2 running"}); {@code "(unnamed)"} for a null/empty name.
     */
    public static String agentName(String name, int running) {
        return agentName(name, running, 0);
    }

    /**
     * Label of an agent definition row with its working state: the bare
     * name, plus {@code " — n running"} while live sessions are nested
     * under it and a prominent {@code "  • n working"} while any of them
     * works (e.g. {@code "build — 2 running  • 1 working"}); zero counts
     * degrade to the plain name.
     */
    public static String agentName(String name, int running, int working) {
        String base = (name == null || name.isEmpty()) ? "(unnamed)" : name;
        StringBuilder sb = new StringBuilder(base);
        if (running > 0) {
            sb.append(" — ").append(running).append(" running");
        }
        if (working > 0) {
            sb.append("  • ").append(working).append(" working");
        }
        return sb.toString();
    }

    /**
     * The server whose agent list contains the given agent — identity match
     * first (the viewer passes the exact element it got from
     * {@code getChildren}, which disambiguates structurally equal agent
     * definitions on two servers), structural equality as the fallback (for
     * stale elements after a refresh). {@code null} when no server owns it.
     */
    public static <S> S serverOfAgent(List<S> servers, Agent agent,
            Function<? super S, ? extends List<Agent>> agentsOf) {
        if (agent == null || servers == null) {
            return null;
        }
        for (S server : servers) {
            for (Agent candidate : agentsOf.apply(server)) {
                if (candidate == agent) {
                    return server;
                }
            }
        }
        for (S server : servers) {
            if (agentsOf.apply(server).contains(agent)) {
                return server;
            }
        }
        return null;
    }
}
