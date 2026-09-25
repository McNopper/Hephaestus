package com.opencode.ide.board.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.fleet.TaskFleet;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * F-004 (interim visibility until V-006's daemon+attach): rebuilds read-only
 * Fleet view rows for jobs launched by a PEER engine. FleetControl is
 * per-process — a chat session's fleet MCP server owns its own engine, so
 * its jobs never reach this process's {@link FleetJobsModel}. The shared
 * on-disk truth reconstructs them: task-store claims (tickets
 * {@code in-progress} with assignee {@link TaskFleet#ASSIGNEE}) plus the
 * fleet worktrees under {@code <repo>/.git/opencode-fleet/<taskId>}.
 * Deduplicated by ticket id against the live engine's own rows (live state
 * wins — a ticket both engines see never yields a duplicate row).
 *
 * <p>Pure over its inputs (no hidden state, no writes); the disk-gathering
 * helpers are testable with plain temp folders — no git repository needed,
 * the fleet-root path resolves for non-repos too. SWT-free.</p>
 */
public final class PeerJobReconstructor {

    /** Row detail for reconstructed rows: peer-engine job, no session or actions in THIS process. */
    private static final String EXTERNAL_DETAIL = "external engine — view-only";

    private PeerJobReconstructor() {
    }

    /**
     * Gathers and reconstructs in one call: every store project's
     * in-progress tickets plus the repo's fleet worktrees, minus the live
     * engine's own task ids. A project that cannot be read (mid-write in
     * another process) is skipped, never fatal.
     *
     * @param store       the shared task store (read-only use)
     * @param repoRoot    the main worktree / repo root; {@code null} means
     *                    no worktree paths — claims still show, pathless
     * @param liveTaskIds task ids the live engine already knows; they win
     * @return external-engine rows, in store-claim order
     */
    public static List<FleetJobHandle> externalJobs(TaskStore store, Path repoRoot, Set<String> liveTaskIds) {
        return externalJobs(store, worktreesUnder(repoRoot == null ? null : FleetGit.fleetRoot(repoRoot)),
                liveTaskIds);
    }

    /**
     * {@link #externalJobs(TaskStore, Path, Set)} with the worktree map
     * supplied by the caller - typically {@link #worktreesVia} (the service's
     * view) with the local scan as the offline fallback.
     */
    public static List<FleetJobHandle> externalJobs(TaskStore store, Map<String, Path> worktrees,
            Set<String> liveTaskIds) {
        return externalRows(claims(store), worktrees == null ? Map.of() : worktrees, liveTaskIds);
    }

    /** Every store project's in-progress tickets; a mid-write project is skipped, never fatal. */
    private static List<Task> claims(TaskStore store) {
        if (store == null) {
            return List.of();
        }
        List<Task> claims = new ArrayList<>();
        for (String project : store.projects()) {
            try {
                claims.addAll(store.list(project, null, "in-progress", null, null));
            } catch (RuntimeException e) {
                // a project mid-write elsewhere is skipped, not fatal
            }
        }
        return claims;
    }

    /**
     * The pure seam: in-progress fleet-claimed tickets become external
     * RUNNING rows. Tickets the live engine knows, non-fleet assignees and
     * duplicate ids are dropped (first claim wins); a claim without a
     * worktree still shows, pathless with a detail note.
     *
     * @param tickets      in-progress tickets of the store projects,
     *                     unfiltered — the fleet-claim match happens here
     * @param worktrees    fleet worktree directories by task id
     *                     (see {@link #worktreesUnder})
     * @param liveTaskIds  task ids the live engine already knows; they win
     */
    public static List<FleetJobHandle> externalRows(List<Task> tickets, Map<String, Path> worktrees,
            Set<String> liveTaskIds) {
        List<FleetJobHandle> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Task ticket : tickets) {
            if (ticket == null || !"in-progress".equals(ticket.status)
                    || !TaskFleet.ASSIGNEE.equals(ticket.assignee)
                    || liveTaskIds.contains(ticket.id) || !seen.add(ticket.id)) {
                continue;
            }
            Path worktree = worktrees.get(ticket.id);
            rows.add(new FleetJobHandle(ticket.id, null,
                    worktree == null ? null : worktree.toString(),
                    FleetJobHandle.State.RUNNING,
                    worktree == null ? EXTERNAL_DETAIL + " (no worktree)" : EXTERNAL_DETAIL,
                    true));
        }
        return rows;
    }

    /**
     * The fleet worktree directories under a fleet root, keyed by task id
     * (the directory name), name-sorted for determinism. A missing or
     * unreadable root yields an empty map — claims without worktrees still
     * show, just pathless.
     */
    /**
     * The fleet worktree directories AS THE SERVICE SEES THEM (v2
     * {@code worktree.*} - capability alignment 2026-09-25; a live experiment
     * proved git-created fleet worktrees appear after
     * {@code worktree/refresh} with {@code strategy:"git"}), keyed by task id
     * exactly like {@link #worktreesUnder}. Requires the service project id
     * (from {@code getProjects()}; see the adoption doc - the id is not yet
     * on {@code ProjectSummary}). Failures yield an empty map, matching
     * {@link #worktreesUnder}'s "unreadable root" semantics: claims without
     * worktrees still show, pathless.
     */
    public static Map<String, Path> worktreesVia(com.opencode.ide.client.OpencodeClient client, String projectID) {
        Map<String, Path> worktrees = new LinkedHashMap<>();
        try {
            client.refreshWorktrees(projectID);
            for (Map<String, Object> entry : client.listWorktrees(projectID)) {
                Object directory = entry.get("directory");
                if (directory == null || String.valueOf(directory).isBlank()) {
                    continue;
                }
                Path dir = Path.of(String.valueOf(directory));
                worktrees.put(dir.getFileName().toString(), dir);
            }
        } catch (com.opencode.ide.client.OpencodeException | RuntimeException e) {
            // service unreachable or rejecting: the caller decides on the
            // fallback; an empty map never breaks the view
        }
        return worktrees;
    }

    public static Map<String, Path> worktreesUnder(Path fleetRoot) {
        Map<String, Path> worktrees = new LinkedHashMap<>();
        if (fleetRoot == null || !Files.isDirectory(fleetRoot)) {
            return worktrees;
        }
        try (var stream = Files.list(fleetRoot)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                    .forEach(dir -> worktrees.put(dir.getFileName().toString(), dir));
        } catch (IOException e) {
            // unreadable fleet root: pathless rows only
        }
        return worktrees;
    }
}
