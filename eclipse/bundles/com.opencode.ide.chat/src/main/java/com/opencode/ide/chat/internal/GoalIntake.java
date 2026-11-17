package com.opencode.ide.chat.internal;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * U-105 - the GOVERNED {@code /goal} seam (client-side, per the 2026-11-09 finding on the ticket):
 * the composer's {@code /goal} NEVER reaches the server's session self-review loop (the
 * independence violation by our standards). Instead:
 *
 * <ul>
 *   <li>{@code /goal <objective>} - captures the objective VERBATIM as a requirements-stage ticket
 *       (the definition artifact); its staged children divide through the V, so fulfillment shows
 *       as pipeline stages on the Board;
 *   <li>{@code /goal pause} - the goal ticket -&gt; {@code paused} (never blocked), with the open
 *       descendant count and the ticker-disarm hint in the notice (U-038 semantics);
 *   <li>{@code /goal resume} - back to the pump ({@code product-backlog} - the ticker plans, claims
 *       and spawns it again);
 *   <li>{@code /goal clear} - close-or-split with the pipeline state preserved: no open children
 *       -&gt; archive the goal; open children -&gt; a continuation ticket carries the objective,
 *       the open tickets re-home under its {@code epic}, and the original CLOSES with the
 *       continuation file as its artifact so every child's basis chain stays satisfied (done +
 *       artifact - never an archived non-done base).
 * </ul>
 *
 * <p>Completion is NEVER set here: the goal ticket only reaches {@code done} through the split
 * close above or - normally - through the engine's review pass on its children (AC2). Pure store
 * logic, SWT-free; the controller renders {@link Result#notice()}.
 */
public final class GoalIntake {

  /** Label every captured goal ticket carries (the discovery marker for tooling and the Board). */
  public static final String GOAL_LABEL = "goal";

  public static final String USAGE =
      "usage: /goal <objective> | /goal pause | /goal resume | /goal clear";

  private GoalIntake() {}

  /**
   * One result: what to render, and the goal ticket to remember ({@code null} = forget/cleared).
   */
  public record Result(String notice, String goalId) {}

  /** AC1: the intake - objective verbatim as the definition artifact, requirements stage. */
  public static Result capture(TaskStore store, String project, String objective) {
    if (objective == null || objective.isBlank()) {
      return new Result(USAGE, null);
    }
    String trimmed = objective.trim();
    String title = trimmed.split("\\R", 2)[0]; // frontmatter titles are one line
    Task goal =
        store.create(
            project,
            new TaskStore.CreateSpec(
                title,
                trimmed,
                "task",
                "developer",
                "medium",
                0,
                List.of(),
                List.of(GOAL_LABEL),
                null,
                "T",
                null,
                null,
                null,
                null),
            "requirements");
    return new Result(
        "goal captured as "
            + goal.id
            + " - objective recorded verbatim; fulfillment rides the pump (staged children"
            + " divide through the V); done ONLY by the engine's review pass. /goal pause |"
            + " resume | clear act on it.",
        goal.id);
  }

  /** AC3: the session-primitive verbs, mapped to store semantics on the remembered goal. */
  public static Result verb(TaskStore store, String project, String goalId, String verb) {
    if (goalId == null || goalId.isBlank()) {
      return new Result("no goal recorded for this chat - /goal <objective> first; " + USAGE, null);
    }
    Task goal;
    try {
      goal = store.get(project, goalId);
    } catch (RuntimeException e) {
      return new Result(
          "goal " + goalId + " is not in the live store - /goal <objective> to capture a new one",
          null);
    }
    return switch (verb) {
      case "pause" -> pause(store, project, goal);
      case "resume" -> resume(store, project, goal);
      case "clear" -> clear(store, project, goal);
      default -> new Result(USAGE, goalId);
    };
  }

  private static Result pause(TaskStore store, String project, Task goal) {
    if ("done".equals(goal.status)) {
      return new Result("goal " + goal.id + " is already done - nothing to pause", goal.id);
    }
    if ("paused".equals(goal.status)) {
      return new Result("goal " + goal.id + " is already paused", goal.id);
    }
    store.setPaused(project, goal.id, "goal paused (U-105)", "chat");
    int open = openDescendants(store, project, goal.id).size();
    String children =
        open > 0
            ? "; "
                + open
                + " open child ticket(s) keep running - disarm the ticker (Board schedules"
                + " switch) to stop dispatch too"
            : "";
    return new Result(
        "goal "
            + goal.id
            + " paused (paused ticket, never blocked)"
            + children
            + ". Resume with /goal resume.",
        goal.id);
  }

  private static Result resume(TaskStore store, String project, Task goal) {
    if (!"paused".equals(goal.status)) {
      return new Result("goal " + goal.id + " is " + goal.status + " - nothing to resume", goal.id);
    }
    store.update(project, goal.id, Map.of("status", "product-backlog"));
    return new Result(
        "goal "
            + goal.id
            + " resumed - back to the pump (product-backlog; the ticker plans/claims/spawns it"
            + " with its staged children)",
        goal.id);
  }

  private static Result clear(TaskStore store, String project, Task goal) {
    List<Task> open = openDescendants(store, project, goal.id);
    if (open.isEmpty()) {
      store.addComment(
          project,
          goal.id,
          "goal cleared by the owner (U-105): no open children - archived",
          "chat");
      store.archive(project, goal.id, "chat");
      return new Result(
          "goal "
              + goal.id
              + " cleared (no open children) - archived; nothing downstream depended on it",
          null);
    }
    // close-or-SPLIT: the continuation carries the objective verbatim, the open tickets re-home
    // under its epic, and the original CLOSES with the continuation file as its artifact - so
    // every child's basis chain stays satisfied (done + artifact, never an archived live base)
    String title = goal.title == null || goal.title.isBlank() ? "continued goal" : goal.title;
    String body =
        "Continuation of "
            + goal.id
            + " (cleared/split by the owner, U-105).\n\n"
            + (goal.description == null ? "" : goal.description);
    Task cont =
        store.create(
            project,
            new TaskStore.CreateSpec(
                title + " (continued)",
                body,
                "task",
                "developer",
                "medium",
                0,
                List.of(),
                List.of(GOAL_LABEL),
                null,
                "T",
                null,
                null,
                null,
                null),
            "requirements");
    for (Task child : open) {
      store.update(project, child.id, Map.of("epic", cont.id));
    }
    store.addComment(
        project,
        goal.id,
        "cleared by SPLIT (U-105): continuation "
            + cont.id
            + "; "
            + open.size()
            + " open ticket(s) re-homed",
        "chat");
    store.addArtifact(
        project,
        goal.id,
        "doc",
        ".harness/tasks/" + project + "/" + cont.id + ".md",
        "cleared -> split continuation",
        "chat");
    store.update(project, goal.id, Map.of("status", "done"));
    return new Result(
        "goal "
            + goal.id
            + " cleared by SPLIT -> "
            + cont.id
            + " ("
            + open.size()
            + " open ticket(s) re-homed; original closed with the continuation as its artifact so"
            + " every basis chain stays valid)",
        cont.id);
  }

  /**
   * The open (not-done) descendants of the goal over BOTH links the pipeline uses - {@code basis}
   * (the derivation chain division creates) and {@code epic} (scope) - computed to a fixed point
   * over the LIVE store; the root itself is excluded.
   */
  static List<Task> openDescendants(TaskStore store, String project, String rootId) {
    List<Task> all = store.list(project, null, null, null, null);
    Set<String> inTree = new HashSet<>();
    inTree.add(rootId);
    List<Task> open = new ArrayList<>();
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Task t : all) {
        if (t == null || t.id == null || inTree.contains(t.id)) {
          continue;
        }
        boolean child =
            (t.basis != null && inTree.contains(t.basis))
                || (t.epic != null && inTree.contains(t.epic));
        if (child) {
          inTree.add(t.id);
          grew = true;
          if (!"done".equals(t.status)) {
            open.add(t);
          }
        }
      }
    }
    return open;
  }
}
