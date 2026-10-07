package com.opencode.ide.mojo.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.opencode.ide.tasks.Task;

/**
 * Renders sprint boards as Markdown and as a standalone HTML page (inline CSS,
 * escaped content, no external assets). Output is deterministic — no
 * timestamps — so identical store state renders identical bytes.
 */
public final class BoardRenderer {

    /** Board title used when a project has no active sprint and none was requested. */
    public static final String NO_SPRINT = "(no sprint)";

    /** One row of the Epics section. */
    public record EpicRow(String id, String title, int open, int total) {
    }

    /** One project's board, precomputed from loaded tasks. */
    public static final class ProjectBoard {

        public final String project;
        public final String sprintId;
        public final String sprintGoal;
        public final String sprintStatus;
        public final Map<String, List<Task>> columns;
        public final List<EpicRow> epics;

        ProjectBoard(String project, String sprintId, String sprintGoal, String sprintStatus,
                Map<String, List<Task>> columns, List<EpicRow> epics) {
            this.project = project;
            this.sprintId = sprintId;
            this.sprintGoal = sprintGoal;
            this.sprintStatus = sprintStatus;
            this.columns = columns;
            this.epics = epics;
        }

        /** Story points across all tickets on the board. */
        public int totalPoints() {
            int sum = 0;
            for (List<Task> column : columns.values()) {
                for (Task t : column) {
                    sum += t.storyPoints;
                }
            }
            return sum;
        }

        /** Story points of the tickets in the done column. */
        public int donePoints() {
            int sum = 0;
            for (Task t : columns.get("done")) {
                sum += t.storyPoints;
            }
            return sum;
        }
    }

    private static final Comparator<Task.Sprint> SPRINT_ORDER = Comparator
            .comparing((Task.Sprint s) -> s.createdAt() == null ? Instant.EPOCH : s.createdAt())
            .thenComparing(Task.Sprint::id);

    private BoardRenderer() {
    }

    /**
     * Scopes the tasks to the requested sprint (or each project's most
     * recently created active one, or the unassigned ones when none is active)
     * and groups them into the five canonical status columns.
     */
    public static ProjectBoard build(String project, List<Task> tasks, Map<String, Task.Sprint> sprints,
            String requestedSprint) {
        String sprintId = requestedSprint != null && !requestedSprint.isBlank()
                ? requestedSprint
                : latestActiveSprintKey(sprints);
        Map<String, List<Task>> columns = new LinkedHashMap<>();
        for (String status : Task.VALID_STATUSES) {
            columns.put(status, new ArrayList<>());
        }
        Map<String, Task> byId = new HashMap<>();
        for (Task t : tasks) {
            byId.put(t.id, t);
            if (sprintId == null ? t.sprint == null : sprintId.equals(t.sprint)) {
                List<Task> column = columns.get(t.status);
                if (column != null) {
                    column.add(t);
                }
            }
        }
        Map<String, int[]> epicCounts = new LinkedHashMap<>();
        for (List<Task> column : columns.values()) {
            for (Task t : column) {
                if (t.epic == null || t.epic.isBlank()) {
                    continue;
                }
                int[] c = epicCounts.computeIfAbsent(t.epic, k -> new int[2]);
                c[1]++;
                if (!"done".equals(t.status)) {
                    c[0]++;
                }
            }
        }
        List<EpicRow> epics = new ArrayList<>();
        for (Map.Entry<String, int[]> e : epicCounts.entrySet()) {
            Task epicTask = byId.get(e.getKey());
            epics.add(new EpicRow(e.getKey(), epicTask == null ? null : epicTask.title,
                    e.getValue()[0], e.getValue()[1]));
        }
        epics.sort(Comparator.comparing(EpicRow::id));
        Task.Sprint sprint = sprintId == null ? null : sprints.get(sprintId);
        return new ProjectBoard(project, sprintId,
                sprint == null ? null : sprint.goal(),
                sprint == null ? null : sprint.status(),
                columns, epics);
    }

    private static String latestActiveSprintKey(Map<String, Task.Sprint> sprints) {
        String bestKey = null;
        Task.Sprint best = null;
        for (Map.Entry<String, Task.Sprint> e : sprints.entrySet()) {
            if (!"active".equals(e.getValue().status())) {
                continue;
            }
            if (best == null || SPRINT_ORDER.compare(e.getValue(), best) > 0) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey;
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    /** Renders all boards into one Markdown document (projects separated by horizontal rules). */
    public static String markdown(List<ProjectBoard> boards) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (ProjectBoard board : boards) {
            if (!first) {
                b.append("---\n\n");
            }
            first = false;
            appendMarkdownBoard(b, board);
        }
        return b.toString();
    }

    private static void appendMarkdownBoard(StringBuilder b, ProjectBoard board) {
        b.append("# opencode-tasks board — ").append(board.project).append("\n\n");
        appendMarkdownSprint(b, board);
        for (Map.Entry<String, List<Task>> e : board.columns.entrySet()) {
            List<Task> column = e.getValue();
            b.append("## ").append(e.getKey()).append(" (").append(column.size()).append(")\n");
            if (column.isEmpty()) {
                b.append("_(empty)_\n");
            } else {
                for (Task t : column) {
                    b.append("- ").append(ticketLine(t)).append('\n');
                }
            }
            b.append('\n');
        }
        b.append("## Epics\n");
        if (board.epics.isEmpty()) {
            b.append("_(none)_\n");
        } else {
            for (EpicRow e : board.epics) {
                b.append("- ");
                if (e.title() != null) {
                    b.append('[').append(e.id()).append("] ").append(e.title());
                } else {
                    b.append(e.id());
                }
                b.append(" — ").append(e.open()).append(" open of ").append(e.total()).append('\n');
            }
        }
        b.append('\n');
        b.append("**Totals:** ").append(board.totalPoints()).append(" points total, ")
                .append(board.donePoints()).append(" points done.\n");
    }

    private static void appendMarkdownSprint(StringBuilder b, ProjectBoard board) {
        if (board.sprintId == null) {
            b.append("Sprint: **").append(NO_SPRINT).append("** — tickets not assigned to any sprint.\n\n");
            return;
        }
        b.append("Sprint: **").append(board.sprintId).append("**");
        if (board.sprintStatus != null) {
            b.append(" (").append(board.sprintStatus).append(")");
        }
        if (board.sprintGoal != null && !board.sprintGoal.isBlank()) {
            b.append(" — ").append(board.sprintGoal);
        }
        if (board.sprintStatus == null) {
            b.append(" — no sprint metadata in _meta.json");
        }
        b.append("\n\n");
    }

    private static String ticketLine(Task t) {
        StringBuilder b = new StringBuilder("[").append(t.id).append("] ")
                .append(t.title == null ? "" : t.title);
        b.append(" — ").append(t.role);
        b.append(", ").append(t.storyPoints).append(t.storyPoints == 1 ? " point" : " points");
        if (t.assignee != null && !t.assignee.isBlank()) {
            b.append(", ").append(t.assignee);
        }
        if (t.isBlocked()) {
            b.append(", ⚠ blocked: ")
                    .append(t.blocker == null || t.blocker.isBlank() ? "unspecified" : t.blocker);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------

    /** Renders all boards into one standalone HTML page (inline CSS, escaped content). */
    public static String html(List<ProjectBoard> boards) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        b.append("<title>opencode-tasks board</title>\n<style>\n");
        b.append("body{font-family:system-ui,sans-serif;max-width:56em;margin:2rem auto;padding:0 1rem;color:#222;background:#fff}\n");
        b.append("h1{font-size:1.4rem;margin-bottom:.2rem}\n");
        b.append(".sprint{color:#444;margin-top:.2rem}\n");
        b.append("h2{font-size:1.05rem;border-bottom:1px solid #ddd;padding-bottom:.2rem;margin-top:1.6rem}\n");
        b.append("ul{list-style:none;padding-left:0;margin:.4rem 0}\n");
        b.append("li{padding:.18rem 0}\n");
        b.append(".id{font-family:ui-monospace,monospace;background:#f2f2f2;padding:0 .3rem;border-radius:3px}\n");
        b.append(".count{color:#777;font-weight:400}\n");
        b.append(".blocked{color:#b00}\n");
        b.append(".totals{margin-top:1.6rem;font-weight:600}\n");
        b.append(".sep{border:0;border-top:1px solid #ddd;margin:2.5rem 0}\n");
        b.append("</style>\n</head>\n<body>\n");
        boolean first = true;
        for (ProjectBoard board : boards) {
            if (!first) {
                b.append("<hr class=\"sep\">\n");
            }
            first = false;
            appendHtmlBoard(b, board);
        }
        b.append("</body>\n</html>\n");
        return b.toString();
    }

    private static void appendHtmlBoard(StringBuilder b, ProjectBoard board) {
        b.append("<h1>opencode-tasks board — ").append(esc(board.project)).append("</h1>\n");
        b.append("<p class=\"sprint\">");
        if (board.sprintId == null) {
            b.append("Sprint: <strong>").append(NO_SPRINT).append("</strong> — tickets not assigned to any sprint.");
        } else {
            b.append("Sprint: <strong>").append(esc(board.sprintId)).append("</strong>");
            if (board.sprintStatus != null) {
                b.append(" (").append(esc(board.sprintStatus)).append(")");
            }
            if (board.sprintGoal != null && !board.sprintGoal.isBlank()) {
                b.append(" — ").append(esc(board.sprintGoal));
            }
            if (board.sprintStatus == null) {
                b.append(" — no sprint metadata in _meta.json");
            }
        }
        b.append("</p>\n");
        for (Map.Entry<String, List<Task>> e : board.columns.entrySet()) {
            b.append("<section class=\"col\" id=\"").append(esc(e.getKey())).append("\">\n");
            b.append("<h2>").append(esc(e.getKey())).append(" <span class=\"count\">(")
                    .append(e.getValue().size()).append(")</span></h2>\n");
            if (e.getValue().isEmpty()) {
                b.append("<p class=\"empty\">(empty)</p>\n");
            } else {
                b.append("<ul>\n");
                for (Task t : e.getValue()) {
                    appendHtmlTicket(b, t);
                }
                b.append("</ul>\n");
            }
            b.append("</section>\n");
        }
        b.append("<section class=\"epics\">\n<h2>Epics</h2>\n");
        if (board.epics.isEmpty()) {
            b.append("<p class=\"empty\">(none)</p>\n");
        } else {
            b.append("<ul>\n");
            for (EpicRow e : board.epics) {
                b.append("<li>");
                if (e.title() != null) {
                    b.append("<span class=\"id\">[").append(esc(e.id())).append("]</span> ")
                            .append(esc(e.title()));
                } else {
                    b.append("<span class=\"id\">").append(esc(e.id())).append("</span>");
                }
                b.append(" — ").append(e.open()).append(" open of ").append(e.total()).append("</li>\n");
            }
            b.append("</ul>\n");
        }
        b.append("</section>\n");
        b.append("<p class=\"totals\">Totals: ").append(board.totalPoints())
                .append(" points total, ").append(board.donePoints()).append(" points done.</p>\n");
    }

    private static void appendHtmlTicket(StringBuilder b, Task t) {
        b.append("<li><span class=\"id\">[").append(esc(t.id)).append("]</span> ")
                .append(esc(t.title == null ? "" : t.title));
        b.append(" — ").append(esc(t.role));
        b.append(", ").append(t.storyPoints).append(t.storyPoints == 1 ? " point" : " points");
        if (t.assignee != null && !t.assignee.isBlank()) {
            b.append(", ").append(esc(t.assignee));
        }
        if (t.isBlocked()) {
            b.append(", <span class=\"blocked\">⚠ blocked: ")
                    .append(esc(t.blocker == null || t.blocker.isBlank() ? "unspecified" : t.blocker))
                    .append("</span>");
        }
        b.append("</li>\n");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
