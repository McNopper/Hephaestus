package com.opencode.ide.ui.model;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.ShellTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-179 (server browser inventory): the server &rarr; session &rarr; terminal projection over the
 * v2 {@code GET /api/shell} surface — one SWT-free grouping of shell tasks by their owning session
 * ({@code metadata.sessionID}), plus the row label/status the tree renders.
 *
 * <p>Read-only inventory: no output rendering (that is {@code ShellTasksConsole}'s job on
 * click-through), no polling of its own — the view calls {@link #load} once when a session row is
 * first expanded (expand-to-poll, AC4) and re-calls it when a {@code session.shell.*} event already
 * flows through the SSE stream. Shells without a session binding are dropped here (the Background
 * view already lists them server-wide).
 */
public final class SessionTerminals {

  private SessionTerminals() {}

  /** One terminal row in the browser tree: the shell task, projected for display. */
  public record Row(String shellId, String command, String status, Integer exit) {

    public boolean running() {
      return "running".equals(status);
    }

    /** {@code $ git status · running} / {@code $ make · exit 1} — the name column. */
    public String label() {
      String cmd = command == null || command.isBlank() ? "(no command)" : command;
      String state =
          running()
              ? "running"
              : (exit == null ? (status == null ? "done" : status) : "exit " + exit);
      return "$ " + cmd + " · " + state;
    }
  }

  /**
   * Groups session-bound shell tasks by session id, preserving input order. Session-less tasks are
   * dropped (Background view covers them); {@code null}/empty input yields an empty map.
   */
  public static Map<String, List<Row>> group(List<ShellTask> tasks) {
    Map<String, List<Row>> out = new LinkedHashMap<>();
    if (tasks == null) {
      return out;
    }
    for (ShellTask task : tasks) {
      if (task == null || task.sessionId() == null || task.sessionId().isBlank()) {
        continue;
      }
      out.computeIfAbsent(task.sessionId(), id -> new ArrayList<>())
          .add(new Row(task.id(), task.command(), task.status(), task.exit()));
    }
    return out;
  }

  /**
   * The single fetch behind the view's expand-to-poll (AC4): one {@code GET /api/shell} for the
   * whole server, grouped per session. Never called until a session row expands or a {@code
   * session.shell.*} event arrives — the model owns no timers, loops or caches.
   */
  public static Map<String, List<Row>> load(OpencodeClient client) throws OpencodeException {
    return group(client.listShellTasks());
  }
}
