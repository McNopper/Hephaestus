package com.opencode.ide.mojo.tasks;

import com.opencode.ide.tasks.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the task board of every project as Markdown and as a standalone HTML page (inline CSS,
 * escaped content, no external assets). Output is deterministic - no timestamps - so identical
 * store state renders identical bytes. Owner model 2026-11-20: all seven status columns, no sprint
 * scope (sprints are gone from the store).
 */
public final class BoardRenderer {

  /** One project's board, precomputed from loaded tasks. */
  public static final class ProjectBoard {

    public final String project;
    public final Map<String, List<Task>> columns;

    ProjectBoard(String project, Map<String, List<Task>> columns) {
      this.project = project;
      this.columns = columns;
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

  private BoardRenderer() {}

  /**
   * Groups the tasks into the seven canonical status columns (owner model 2026-11-20: ALL tickets,
   * no sprint scope).
   */
  public static ProjectBoard build(String project, List<Task> tasks) {
    Map<String, List<Task>> columns = new LinkedHashMap<>();
    for (String status : Task.VALID_STATUSES) {
      columns.put(status, new ArrayList<>());
    }
    for (Task t : tasks) {
      List<Task> column = columns.get(t.status);
      if (column != null) {
        column.add(t);
      }
    }
    return new ProjectBoard(project, columns);
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
    b.append("# opencode-tasks board - ").append(board.project).append("\n\n");
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
    b.append("**Totals:** ")
        .append(board.totalPoints())
        .append(" points total, ")
        .append(board.donePoints())
        .append(" points done.\n");
  }

  private static String ticketLine(Task t) {
    StringBuilder b =
        new StringBuilder("[").append(t.id).append("] ").append(t.title == null ? "" : t.title);
    b.append(" - ").append(t.role);
    b.append(", ").append(t.storyPoints).append(t.storyPoints == 1 ? " point" : " points");
    if (t.assignee != null && !t.assignee.isBlank()) {
      b.append(", ").append(t.assignee);
    }
    if (t.isBlocked()) {
      b.append(", ? blocked: ")
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
    b.append(
        "body{font-family:system-ui,sans-serif;max-width:56em;margin:2rem auto;padding:0 1rem;color:#222;background:#fff}\n");
    b.append("h1{font-size:1.4rem;margin-bottom:.2rem}\n");
    b.append(
        "h2{font-size:1.05rem;border-bottom:1px solid #ddd;padding-bottom:.2rem;margin-top:1.6rem}\n");
    b.append("ul{list-style:none;padding-left:0;margin:.4rem 0}\n");
    b.append("li{padding:.18rem 0}\n");
    b.append(
        ".id{font-family:ui-monospace,monospace;background:#f2f2f2;padding:0 .3rem;border-radius:3px}\n");
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
    b.append("<h1>opencode-tasks board - ").append(esc(board.project)).append("</h1>\n");
    for (Map.Entry<String, List<Task>> e : board.columns.entrySet()) {
      b.append("<section class=\"col\" id=\"").append(esc(e.getKey())).append("\">\n");
      b.append("<h2>")
          .append(esc(e.getKey()))
          .append(" <span class=\"count\">(")
          .append(e.getValue().size())
          .append(")</span></h2>\n");
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
    b.append("<p class=\"totals\">Totals: ")
        .append(board.totalPoints())
        .append(" points total, ")
        .append(board.donePoints())
        .append(" points done.</p>\n");
  }

  private static void appendHtmlTicket(StringBuilder b, Task t) {
    b.append("<li><span class=\"id\">[")
        .append(esc(t.id))
        .append("]</span> ")
        .append(esc(t.title == null ? "" : t.title));
    b.append(" - ").append(esc(t.role));
    b.append(", ").append(t.storyPoints).append(t.storyPoints == 1 ? " point" : " points");
    if (t.assignee != null && !t.assignee.isBlank()) {
      b.append(", ").append(esc(t.assignee));
    }
    if (t.isBlocked()) {
      b.append(", <span class=\"blocked\">? blocked: ")
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
