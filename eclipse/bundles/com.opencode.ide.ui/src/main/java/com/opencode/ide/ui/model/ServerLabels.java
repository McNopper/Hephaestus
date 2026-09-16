package com.opencode.ide.ui.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opencode.ide.client.SessionOrder;
import com.opencode.ide.client.activity.ActivitySnapshot;
import com.opencode.ide.client.activity.FileActivity;
import com.opencode.ide.client.activity.SessionActivity;
import com.opencode.ide.client.activity.ToolActivity;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Pure label and nesting logic behind the Server view: the name/detail text
 * for server roots, categories, agents and sessions (including the
 * {@code (offline)} tag, live activity labels and relative timestamps), and
 * the {@code parentID}-based session nesting with the owning-server
 * resolution ({@link #ownerOf}) shared by the view and its content provider.
 *
 * <p>SWT-free and JFace-free on purpose — records and static methods over
 * client model types only — so it is unit-testable without a {@code Display}
 * (see {@code ServerLabelsTest} in {@code com.opencode.ide.ui.tests}). The
 * {@code ServerView} delegates here and keeps all SWT/JFace usage to itself.</p>
 */
public final class ServerLabels {

    /**
     * The pure projection of one server root (everything the labels need,
     * nothing the tree mutates): primary vs remote, display label, connection
     * mode/url, health, server version and pid.
     */
    public record Server(boolean primary, String label, String mode, String url,
            boolean healthy, String version, Long pid) {
    }

    private ServerLabels() {
    }

    /**
     * Row-name suffix marking a session that is currently working (itself
     * or via a busy subagent under it) — same visual weight as the
     * {@code (offline)} tag, so busy rows stand out in the name column.
     */
    private static final String WORKING_SUFFIX = "  • working";

    // ---------- server ----------

    /** Label of a server root: fixed for the primary, label/url + offline tag for remotes. */
    public static String serverName(Server server) {
        if (server.primary()) {
            return "opencode server";
        }
        String label = (server.label() == null || server.label().isEmpty()) ? server.url() : server.label();
        return server.healthy() ? label : label + "  (offline)";
    }

    /** Details column of a server root: health, version, (mode, pid for the primary) and url. */
    public static String serverDetail(Server server) {
        if (!server.primary()) {
            StringBuilder sb = new StringBuilder(server.healthy() ? "healthy" : "offline");
            if (server.version() != null) {
                sb.append(" • v").append(server.version());
            }
            if (server.url() != null) {
                sb.append(" • ").append(server.url());
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(server.healthy() ? "healthy" : "unhealthy");
        if (server.version() != null) {
            sb.append(" • v").append(server.version());
        }
        if (server.mode() != null) {
            sb.append(" • ").append(server.mode());
        }
        if (server.url() != null) {
            sb.append(" • ").append(server.url());
        }
        if (server.pid() != null) {
            sb.append(" • pid ").append(server.pid());
        }
        return sb.toString();
    }

    // ---------- category ----------

    /** Label of a tree category with its item count, e.g. {@code "Agents (3)"}. */
    public static String categoryName(String label, int count) {
        return label + " (" + count + ")";
    }

    /**
     * Label of a tree category that can aggregate working state, e.g.
     * {@code "Sessions (5)  • 2 working"} — the working count is appended
     * prominently (name column, not the details column) so active agents
     * and subagents are visible while a category is collapsed; zero
     * working degrades to the plain {@link #categoryName(String, int)}.
     */
    public static String categoryName(String label, int count, int working) {
        String base = categoryName(label, count);
        return working > 0 ? base + "  • " + working + " working" : base;
    }

    /** Details column of the Sessions category: total, top-level and busy counts ("" when empty). */
    public static String sessionsCategoryDetail(List<Session> sessions, Map<String, SessionStatus> statuses) {
        if (sessions == null || sessions.isEmpty()) {
            return "";
        }
        long busy = busyCount(sessions, statuses);
        long rootsCount = sessions.stream().filter(s -> s.parentID() == null).count();
        return sessions.size() + " total, " + rootsCount + " top-level"
                + (busy > 0 ? " • " + busy + " busy" : "");
    }

    /** How many of the sessions are currently working ({@code busy}/{@code retry}). */
    public static long busyCount(List<Session> sessions, Map<String, SessionStatus> statuses) {
        if (sessions == null) {
            return 0;
        }
        return sessions.stream().filter(s -> isBusy(statuses, s)).count();
    }

    // ---------- agent ----------

    /** Details column of an agent: mode, native marker and description (null-safe). */
    public static String agentDetail(Agent a) {
        StringBuilder sb = new StringBuilder();
        if (a.mode() != null) {
            sb.append(a.mode());
        }
        if (a.isNative()) {
            sb.append(" • native");
        }
        if (a.description() != null && !a.description().isEmpty()) {
            sb.append(" — ").append(a.description());
        }
        return sb.toString();
    }

    // ---------- session ----------

    /** Label of a session: agent identity + title (title, else slug, else id). */
    public static String sessionName(Session s) {
        // surface the running agent identity (the Sessions category is flat per agent)
        if (s.agent() != null && !s.agent().isEmpty()) {
            return s.agent() + " — " + nestedSessionName(s);
        }
        return nestedSessionName(s);
    }

    /**
     * Label of a session with its working state: the plain
     * {@link #sessionName(Session)} plus a {@code "  • working"} suffix while
     * the session (or a subagent under it — the caller folds that into the
     * flag) is working, so the row reads as actively working in the name
     * column, not only via its icon and status.
     */
    public static String sessionName(Session s, boolean working) {
        String name = sessionName(s);
        return working ? name + WORKING_SUFFIX : name;
    }

    /**
     * Label of a session nested under the agent that runs it: the bare title
     * (title, else slug, else id) — the agent is already the parent row, so
     * repeating its name would be noise.
     */
    public static String nestedSessionName(Session s) {
        if (s.title() != null && !s.title().isEmpty()) {
            return s.title();
        }
        return s.slug() == null ? s.id() : s.slug();
    }

    /**
     * Label of an agent-nested session row with its working state: the bare
     * title plus the {@code "  • working"} suffix while it (or a subagent
     * under it) works — see {@link #sessionName(Session, boolean)}.
     */
    public static String nestedSessionName(Session s, boolean working) {
        String name = nestedSessionName(s);
        return working ? name + WORKING_SUFFIX : name;
    }

    /**
     * Details column of a session: the live activity label when present (else
     * the status type), the subagent marker, and the relative last-update.
     */
    public static String sessionDetail(Session s, String live, String status) {
        StringBuilder sb = new StringBuilder(live != null ? live : (status == null ? "idle" : status));
        if (s.parentID() != null) {
            sb.append(" • subagent");
        }
        if (s.time() != null && s.time().updated() > 0) {
            sb.append(" • updated ").append(relative(s.time().updated()));
        }
        return sb.toString();
    }

    /** The status type of a session ("idle" when unknown or no status map). */
    public static String statusType(Map<String, SessionStatus> statuses, Session s) {
        if (statuses == null) {
            return "idle";
        }
        SessionStatus status = statuses.get(s.id());
        return (status == null || status.type() == null) ? "idle" : status.type();
    }

    /** Whether the session's status marks it busy ({@code busy}/{@code retry}). */
    public static boolean isBusy(Map<String, SessionStatus> statuses, Session s) {
        if (statuses == null) {
            return false;
        }
        SessionStatus status = statuses.get(s.id());
        if (status == null || status.type() == null) {
            return false;
        }
        return "busy".equalsIgnoreCase(status.type()) || "retry".equalsIgnoreCase(status.type());
    }

    /**
     * Whether any subagent <em>under</em> the given session (children,
     * grandchildren, … — the whole {@code parentID} subtree) is working:
     * a parent session waiting on a busy fleet worker or subagent reads
     * as working even when its own status marker is stale. The session
     * itself is not considered; a visited set guards against malformed
     * cyclic {@code parentID} chains.
     */
    public static boolean hasBusyDescendant(List<Session> sessions, Map<String, SessionStatus> statuses,
            String sessionId) {
        if (sessions == null || sessionId == null) {
            return false;
        }
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(sessionId);
        visited.add(sessionId);
        while (!pending.isEmpty()) {
            String parent = pending.poll();
            for (Session s : sessions) {
                if (parent.equals(s.parentID())) {
                    if (isBusy(statuses, s)) {
                        return true;
                    }
                    if (s.id() != null && visited.add(s.id())) {
                        pending.add(s.id());
                    }
                }
            }
        }
        return false;
    }

    // ---------- live activity ----------

    /** Derived live label from a snapshot: thinking, else the first still-running tool. */
    public static String trackerLabel(ActivitySnapshot snapshot, String sessionId) {
        SessionActivity session = snapshot == null ? null : snapshot.sessions().get(sessionId);
        if (session == null) {
            return null;
        }
        if (session.thinking()) {
            return "thinking…";
        }
        ToolActivity running = session.activity().stream()
                .filter(t -> t.state() == ToolActivity.State.RUNNING)
                .findFirst()
                .orElse(null);
        if (running == null) {
            return null;
        }
        return running.file() != null
                ? "tool: " + running.tool() + " — " + running.file()
                : "tool: " + running.tool();
    }

    /** Whether the snapshot currently sees activity (running/thinking/tool) for the session. */
    public static boolean sessionActive(ActivitySnapshot snapshot, String sessionId) {
        SessionActivity session = snapshot == null ? null : snapshot.sessions().get(sessionId);
        if (session == null) {
            return false;
        }
        return session.running() || session.thinking()
                || session.activity().stream().anyMatch(t -> t.state() == ToolActivity.State.RUNNING);
    }

    /** Maps a streamed part to a live activity label ("thinking" / "running tool" / "responding"). */
    public static String partActivityLabel(OpencodeEvent event) {
        String partType = event.at("part.type");
        if (partType == null) {
            return null;
        }
        return switch (partType) {
            case "reasoning" -> "thinking";
            case "tool" -> "running".equals(event.at("part.state.status")) ? "running tool" : null;
            case "text" -> "responding";
            default -> null;
        };
    }

    /** Label of an active file: {@code file — tool (session-prefix)}. */
    public static String fileActivityName(FileActivity f) {
        return f.file() + " — " + f.tool() + " (" + shortId(f.sessionId()) + ")";
    }

    /** The first 8 characters of a session id ("" when null). */
    public static String shortId(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    /** Relative time label: "just now" / "5m ago" / "3h ago" / "2d ago". */
    public static String relative(long epochMillis) {
        long ago = Instant.now().toEpochMilli() - epochMillis;
        if (ago < 0) {
            ago = 0;
        }
        long minutes = ago / 60_000L;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    // ---------- nesting / ownership ----------

    /**
     * The server whose session list contains the given session's id; the
     * fallback for {@code null} sessions, {@code null} ids and unknown ids.
     * Single source of truth for the view and the content provider.
     */
    public static <S> S ownerOf(List<S> servers, Session session,
            Function<? super S, ? extends List<Session>> sessionsOf, S fallback) {
        if (session != null && session.id() != null) {
            for (S server : servers) {
                for (Session s : sessionsOf.apply(server)) {
                    if (session.id().equals(s.id())) {
                        return server;
                    }
                }
            }
        }
        return fallback;
    }

    /** Top-level sessions (no {@code parentID}), most recently updated first. */
    public static List<Session> topLevelSessions(List<Session> sessions) {
        return sessions.stream()
                .filter(s -> s.parentID() == null)
                .sorted(SessionOrder.MOST_RECENT_FIRST) // most current on top
                .collect(Collectors.toList());
    }

    /** Sessions nested under the given parent id, most recently updated first. */
    public static List<Session> childrenOf(List<Session> sessions, String parentId) {
        return sessions.stream()
                .filter(s -> parentId != null && parentId.equals(s.parentID()))
                .sorted(SessionOrder.MOST_RECENT_FIRST)
                .collect(Collectors.toList());
    }

    /** Whether any session nests under the given session id. */
    public static boolean hasSessionChildren(List<Session> sessions, String sessionId) {
        return sessions.stream().anyMatch(s -> sessionId != null && sessionId.equals(s.parentID()));
    }

    /** The session the given session nests under ({@code null} for roots and orphans). */
    public static Session parentSession(List<Session> sessions, Session session) {
        if (session == null || session.parentID() == null) {
            return null;
        }
        return sessions.stream()
                .filter(s -> session.parentID().equals(s.id()))
                .findFirst()
                .orElse(null);
    }
}
