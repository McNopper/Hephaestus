package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.client.model.Turns;
import com.opencode.ide.tasks.Task;

/**
 * U-040: the SWT-free composition of the Fleet view's tree: engine → wave →
 * job (ticket) → worker session → subagent sessions → console/shell tasks.
 * Pure data in (job rows, store tickets, observed sessions), a node tree
 * out; the view fetches and renders, never the other way round, so the
 * composition is unit-testable without a shell or a server.
 *
 * <p>Every node carries a label, a detail line, the shared board badge
 * language for ticket nodes ({@link TicketRow#statusSymbol} +
 * {@link TicketRow#typeTag}, the U-005/U-006 language), the running/failed
 * flag pair for the live decoration, the token/cost totals rolled up from
 * its subtree (null = nothing known below — never zero-faked), and the
 * action targets (task/session/worktree ids, the shell's output tail) the
 * view's context actions need. Peer-engine jobs (F-004) keep their
 * {@code external} flag all the way down so the view renders them grey and
 * view-only.</p>
 */
public final class FleetTree {

    /** Node kinds in the fleet tree, top to bottom. */
    public enum Kind {
        ENGINE, WAVE, JOB, SESSION, SHELL
    }

    /**
     * One tree node: the kind, label and detail, the badge text, the live
     * flags, the rolled-up totals, the action targets and the children.
     */
    public record Node(Kind kind, String label, String detail, String badge,
            boolean running, boolean failed, boolean external,
            Long tokens, Double cost,
            String taskId, String sessionId, String worktree, String ticketType,
            FleetJobHandle.State jobState, String shellTail,
            List<Node> children) {

        public Node {
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** @return true for a job (ticket) node — the level the job actions act on. */
        public boolean isJob() {
            return kind == Kind.JOB;
        }

        /** @return true for a session node (worker or subagent) — watch/transcript actions. */
        public boolean isSession() {
            return kind == Kind.SESSION;
        }

        /** @return true for a console/shell task node — output-tail action. */
        public boolean isShell() {
            return kind == Kind.SHELL;
        }
    }

    private FleetTree() {
    }

    /**
     * Composes the tree from the current job rows.
     *
     * @param jobs own + peer job rows (peers carry {@code external = true})
     * @param ticketOf taskId → the store ticket (badges + wave grouping); a
     *                 null ticket degrades to no badge and the no-wave group
     * @param observationOf sessionId → the observed session (live activity,
     *                      shells, subagents, tokens/cost); a null answer
     *                      degrades the session node to its id, recursion
     *                      only goes as deep as observations exist
     * @param ownEngineLabel label of this engine's root (e.g. "this Eclipse")
     * @param peerEngineLabel label of the peer-engine root (null omits peers)
     * @return the engine root nodes, empty when there are no jobs
     */
    public static List<Node> compose(List<FleetJobHandle> jobs,
            Function<String, Task> ticketOf,
            Function<String, SessionObservation> observationOf,
            String ownEngineLabel, String peerEngineLabel) {
        List<Node> roots = new ArrayList<>();
        if (jobs == null || jobs.isEmpty()) {
            return roots;
        }
        addEngine(roots, jobs, false, ticketOf, observationOf, ownEngineLabel);
        if (peerEngineLabel != null) {
            addEngine(roots, jobs, true, ticketOf, observationOf, peerEngineLabel);
        }
        return roots;
    }

    /** Adds one engine root (own or peer) with its wave/job/session subtree. */
    private static void addEngine(List<Node> roots, List<FleetJobHandle> jobs, boolean external,
            Function<String, Task> ticketOf, Function<String, SessionObservation> observationOf,
            String label) {
        List<FleetJobHandle> mine = new ArrayList<>();
        for (FleetJobHandle job : jobs) {
            if (job.external() == external) {
                mine.add(job);
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        // wave grouping by the ticket's sprint (input order kept); no-wave last
        Map<String, List<FleetJobHandle>> byWave = new LinkedHashMap<>();
        for (FleetJobHandle job : mine) {
            Task ticket = ticketOf == null ? null : ticketOf.apply(job.taskId());
            String wave = ticket == null || ticket.sprint == null || ticket.sprint.isBlank()
                    ? "" : ticket.sprint;
            byWave.computeIfAbsent(wave, w -> new ArrayList<>()).add(job);
        }
        List<String> waveOrder = new ArrayList<>(byWave.keySet());
        waveOrder.sort((a, b) -> a.isEmpty() ? (b.isEmpty() ? 0 : 1)
                : b.isEmpty() ? -1 : a.compareTo(b));
        List<Node> waveNodes = new ArrayList<>();
        for (String wave : waveOrder) {
            List<Node> jobNodes = new ArrayList<>();
            for (FleetJobHandle job : byWave.get(wave)) {
                jobNodes.add(jobNode(job, ticketOf, observationOf));
            }
            waveNodes.add(rollup(new Node(Kind.WAVE,
                    wave.isEmpty() ? "(no wave)" : wave,
                    byWave.get(wave).size() + (byWave.get(wave).size() == 1 ? " job" : " jobs"),
                    null, waveNodes.stream().anyMatch(Node::running),
                    false, external, null, null,
                    null, null, null, null, null, null, jobNodes)));
        }
        roots.add(rollup(new Node(Kind.ENGINE, label,
                mine.size() + (mine.size() == 1 ? " job" : " jobs"),
                null, mine.stream().anyMatch(j -> j.state() == FleetJobHandle.State.RUNNING),
                false, external, null, null,
                null, null, null, null, null, null, waveNodes)));
    }

    /** A job (ticket) node: badges from the store ticket, session subtree below. */
    private static Node jobNode(FleetJobHandle job, Function<String, Task> ticketOf,
            Function<String, SessionObservation> observationOf) {
        Task ticket = ticketOf == null ? null : ticketOf.apply(job.taskId());
        String label = job.taskId() + (ticket == null || ticket.title == null || ticket.title.isBlank()
                ? "" : " — " + ticket.title.trim());
        String badge = null;
        if (ticket != null) {
            TicketRow row = TicketRow.from(ticket);
            String symbol = TicketRow.statusSymbol(row.status());
            String type = row.typeTag();
            badge = (symbol == null ? "" : symbol)
                    + (type == null || type.isEmpty() ? "" : " " + type);
            if (row.displayBlocked()) {
                badge = "[BLOCKED] " + badge;
            }
        }
        List<Node> children = new ArrayList<>();
        SessionObservation observation = observationOf == null || job.sessionId() == null
                || job.sessionId().isBlank() ? null : observationOf.apply(job.sessionId());
        children.add(sessionNode(job.sessionId(), observation, observationOf, job.external()));
        return rollup(new Node(Kind.JOB, label, job.detail(), badge,
                job.state() == FleetJobHandle.State.RUNNING,
                job.failed(), job.external(), null, null,
                job.taskId(), job.sessionId(), job.worktree(),
                ticket == null ? null : ticket.type, job.state(), null, children));
    }

    /** A session node: live activity detail, subagents and shells below (recursive). */
    private static Node sessionNode(String sessionId, SessionObservation observation,
            Function<String, SessionObservation> observationOf, boolean external) {
        if (sessionId == null || sessionId.isBlank()) {
            return new Node(Kind.SESSION, "(no session yet)", null, null,
                    false, false, external, null, null,
                    null, null, null, null, null, null, List.of());
        }
        if (observation == null) {
            return new Node(Kind.SESSION, sessionId, null, null,
                    false, false, external, null, null,
                    null, sessionId, null, null, null, null, List.of());
        }
        List<Node> children = new ArrayList<>();
        for (SessionObservation.Child child : observation.subagents()) {
            // one level of recursion: a subagent's own children render when
            // the view observed that session too; otherwise the child node
            // still carries the parent's rollup data
            SessionObservation childObs = observationOf == null ? null
                    : observationOf.apply(child.sessionId());
            children.add(sessionNode(child.sessionId(), observation(child, childObs),
                    observationOf, external));
        }
        for (SessionObservation.ShellRun shell : observation.shells()) {
            children.add(new Node(Kind.SHELL, "shell: " + shell.command(),
                    shellDetail(shell), null,
                    shell.status() != null && Turns.shellInFlight(shell.status()), false, external,
                    null, null, null, sessionId, null, null, null, shell.outputTail(), List.of()));
        }
        return rollup(new Node(Kind.SESSION,
                observation.title() == null || observation.title().isBlank()
                        ? sessionId : observation.title().trim(),
                observationDetail(observation), null,
                observation.activity() != null && !observation.activity().isBlank(),
                false, external, observation.tokens(), observation.cost(),
                null, sessionId, null, null, null, null, children));
    }

    /** Synthesizes an observation for a subagent when only the parent's child row is known. */
    private static SessionObservation observation(SessionObservation.Child child,
            SessionObservation observed) {
        return observed != null ? observed
                : new SessionObservation(child.sessionId(), child.title(), child.agent(),
                        null, child.status(), null, child.cost(), child.tokens(),
                        null, null, null, null, null);
    }

    /** The session detail line: the current activity, else the last text snippet. */
    private static String observationDetail(SessionObservation observation) {
        if (observation.activity() != null && !observation.activity().isBlank()) {
            return observation.activity().trim();
        }
        if (observation.lastText() != null && !observation.lastText().isBlank()) {
            String oneLine = observation.lastText().replace('\n', ' ').trim();
            return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 89) + "…";
        }
        return observation.status();
    }

    /** The shell detail line: lifecycle + exit code. */
    private static String shellDetail(SessionObservation.ShellRun shell) {
        StringBuilder detail = new StringBuilder(shell.status() == null ? "" : shell.status());
        if (shell.exit() != null) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append("exit ").append(shell.exit());
        }
        return detail.toString();
    }

    /** Rolls tokens/cost up from the children (null when nothing is known below). */
    private static Node rollup(Node node) {
        long tokens = 0L;
        double cost = 0.0;
        boolean knownTokens = false;
        boolean knownCost = false;
        if (node.tokens() != null) {
            tokens += node.tokens();
            knownTokens = true;
        }
        if (node.cost() != null) {
            cost += node.cost();
            knownCost = true;
        }
        for (Node child : node.children()) {
            if (child.tokens() != null) {
                tokens += child.tokens();
                knownTokens = true;
            }
            if (child.cost() != null) {
                cost += child.cost();
                knownCost = true;
            }
        }
        boolean running = node.running() || node.children().stream().anyMatch(Node::running);
        return new Node(node.kind(), node.label(), node.detail(), node.badge(),
                running, node.failed(), node.external(),
                knownTokens ? tokens : null, knownCost ? cost : null,
                node.taskId(), node.sessionId(), node.worktree(), node.ticketType(), node.jobState(),
                node.shellTail(), node.children());
    }
}
