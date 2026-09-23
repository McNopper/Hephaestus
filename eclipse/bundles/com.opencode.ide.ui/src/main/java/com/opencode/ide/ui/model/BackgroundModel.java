package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.ShellTask;

/**
 * SWT-free row model behind the Background view (U-040/U-041/T-005/T-004
 * visibility): the complete "what is going on" overview the opencode TUI
 * gives - every agent session and its subagents with live activity, every
 * shell launched, every permission ask pending. Pure joins over the client
 * surface so the mapping is unit-testable without SWT ({@code ui.tests}
 * convention).
 */
public final class BackgroundModel {

    /** One agent session (or subagent child) in tree order. */
    public record AgentRow(
            String sessionId,
            String parentSessionId,
            int depth,
            String name,
            String status,
            String activity,
            String lastText,
            String cost,
            String tokens,
            boolean observed) {
    }

    /** One shell task launched by any session. */
    public record ShellRow(String id, String command, String status, Integer exit, String started) {
    }

    /** One pending permission ask. */
    public record AskRow(String sessionId, String permissionId, String title, String detail) {
    }

    private BackgroundModel() {
    }

    /**
     * Sessions as a depth-first tree (roots first, {@code parentID} nesting -
     * the v2 subagent shape), with the live activity of OBSERVED sessions
     * (callers observe the busy ones to keep the poll cheap).
     */
    public static List<AgentRow> agents(List<Session> sessions, Map<String, SessionStatus> statuses,
            Map<String, SessionObservation> observations) {
        List<Session> safe = sessions == null ? List.of() : sessions.stream()
                .filter(s -> s != null && s.id() != null)
                .toList();
        Map<String, List<Session>> childrenOf = new LinkedHashMap<>();
        List<Session> roots = new ArrayList<>();
        for (Session session : safe) {
            String parent = session.parentID();
            if (parent == null || safe.stream().noneMatch(s -> parent.equals(s.id()))) {
                roots.add(session);
            } else {
                childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(session);
            }
        }
        List<AgentRow> rows = new ArrayList<>();
        for (Session root : roots) {
            collect(root, 0, childrenOf, statuses, observations, rows);
        }
        return List.copyOf(rows);
    }

    private static void collect(Session session, int depth, Map<String, List<Session>> childrenOf,
            Map<String, SessionStatus> statuses, Map<String, SessionObservation> observations,
            List<AgentRow> rows) {
        SessionObservation observation = observations == null ? null : observations.get(session.id());
        SessionStatus status = statuses == null ? null : statuses.get(session.id());
        String state = status != null && status.type() != null ? status.type()
                : session.outcome() != null ? session.outcome() : "idle";
        rows.add(new AgentRow(
                session.id(),
                session.parentID(),
                depth,
                name(session),
                state,
                observation == null ? "" : blank(observation.activity()),
                observation == null ? "" : blank(observation.lastText()),
                cost(session, observation),
                tokens(session, observation),
                observation != null));
        for (Session child : childrenOf.getOrDefault(session.id(), List.of())) {
            collect(child, depth + 1, childrenOf, statuses, observations, rows);
        }
    }

    /** Display rows never carry null text (the view fills columns directly). */
    private static String blank(String value) {
        return value == null ? "" : value;
    }

    /** Title, falling back to the agent name and the id (v2 titles are lazy). */
    public static String name(Session session) {
        if (session.title() != null && !session.title().isBlank()) {
            return session.title();
        }
        if (session.agent() != null && !session.agent().isBlank()) {
            return "<" + session.agent() + ">";
        }
        return session.id();
    }

    /** The session row's aggregates win over per-message sums (SessionObserver's rule). */
    static String cost(Session session, SessionObservation observation) {
        Double value = session.cost() != null ? session.cost()
                : observation == null ? null : observation.cost();
        return value == null ? "" : String.format(java.util.Locale.ROOT, "$%.4f", value);
    }

    static String tokens(Session session, SessionObservation observation) {
        Session.Tokens totals = session.tokens();
        // no conditional expression here: a primitive long branch would unbox
        // the nullable Long branch (B-008-style NPE trap)
        Long value = null;
        if (totals != null) {
            value = totals.input() + totals.output() + totals.reasoning();
        } else if (observation != null) {
            value = observation.tokens();
        }
        return value == null ? "" : Long.toString(value.longValue());
    }

    /** Shell tasks, running ones first (the live work floats up). */
    public static List<ShellRow> shells(List<ShellTask> tasks) {
        List<ShellTask> safe = tasks == null ? List.of() : tasks;
        List<ShellRow> rows = new ArrayList<>();
        for (ShellTask task : safe) {
            if (task == null || task.id() == null) {
                continue;
            }
            rows.add(new ShellRow(task.id(), task.command(), task.status(), task.exit(),
                    task.time() == null || task.time().started() == null
                            ? "" : Long.toString(task.time().started())));
        }
        rows.sort((a, b) -> Boolean.compare(isRunning(b.status()), isRunning(a.status())));
        return List.copyOf(rows);
    }

    /** Pending asks only (the answered ones leave the overview). */
    public static List<AskRow> asks(List<PermissionRequest> requests) {
        List<PermissionRequest> safe = requests == null ? List.of() : requests;
        List<AskRow> rows = new ArrayList<>();
        for (PermissionRequest request : safe) {
            if (request == null || !request.pending()) {
                continue;
            }
            rows.add(new AskRow(request.sessionId(), request.permissionId(),
                    request.title() == null ? request.permission() : request.title(),
                    request.display()));
        }
        return List.copyOf(rows);
    }

    private static boolean isRunning(String status) {
        return "running".equals(status);
    }
}
