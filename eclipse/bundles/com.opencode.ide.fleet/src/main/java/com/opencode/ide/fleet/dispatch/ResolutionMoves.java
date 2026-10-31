package com.opencode.ide.fleet.dispatch;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * U-050: the store side of the U-031 resolution-first pump - the ONE callback the production
 * schedulers (the fleet's {@code FleetControl} auto loop, the {@link RecurringWaves} drain, the
 * Board's {@code BoardDispatch}) hand to {@link DispatchScheduler#withResolution}. {@link
 * ResolutionPolicy} picks the route; this class executes it in the task store:
 *
 * <ul>
 *   <li>{@code VERTICAL} - {@link TaskStore#sendBack} to the previous definition-chain stage,
 *   <li>{@code HORIZONTAL} - {@link TaskStore#reportHorizontal} to the V-level pair stage,
 *   <li>then {@link TaskStore#clearBlocked}: the ticket lands in the routed stage's product
 *       backlog, READY for rework via its own flow history (the StageReadiness contract: a
 *       sent-back ticket, once unblocked, is READY via its own advance history) - so the
 *       scheduler's launch of the attempt has something dispatchable. That is U-031's "a resolution
 *       attempt is itself a dispatch with the blocked reason as context": the route records the
 *       reason on the ticket ({@code sent back from <stage>: <blocker>} / {@code reported from
 *       <stage>: <blocker>}) before the flag clears, and the history rides into the attempt's
 *       prompt.
 * </ul>
 *
 * <p>Never throws - a failing store move (e.g. a requirements-stage ticket has no previous stage)
 * is logged and the ticket left blocked: the scheduler already counted the attempt, the host's
 * launch seam honestly defers the still-blocked ticket, and once the round-trips are spent the
 * policy's NEEDS-HUMAN route stops the agent attempts (the human's only regular duty). Keeping the
 * throw out of the callback is what keeps DispatchScheduler's "one dispatch wave, never throws"
 * contract true in production.
 */
public final class ResolutionMoves {

  private static final Logger LOG = Logger.getLogger(ResolutionMoves.class.getName());

  /** The history author every pump resolution move is recorded under. */
  public static final String BY = "pump";

  private ResolutionMoves() {}

  /**
   * @param store the host's task store (the coordination blackboard)
   * @param project the project the pumped tickets live in
   * @return the {@link DispatchScheduler#withResolution} callback: route the blocked ticket in the
   *     store, then clear the flag so the attempt's dispatch can run
   */
  public static BiConsumer<String, ResolutionPolicy.Route> of(TaskStore store, String project) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(project, "project");
    return (id, route) -> move(store, project, id, route);
  }

  /** One routed move; all store failures degrade to a logged line (see the class contract). */
  private static void move(
      TaskStore store, String project, String id, ResolutionPolicy.Route route) {
    try {
      Task task = store.get(project, id);
      String reason = task.blocker == null || task.blocker.isBlank() ? "blocked" : task.blocker;
      if (route == ResolutionPolicy.Route.VERTICAL) {
        store.sendBack(project, id, reason, BY);
      } else if (route == ResolutionPolicy.Route.HORIZONTAL) {
        store.reportHorizontal(project, id, reason, BY);
      } else {
        return; // NEEDS_HUMAN: no agent route left - the ticket stays at the human
      }
      store.clearBlocked(project, id, BY);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "resolution move of "
              + id
              + " failed (attempt counted, ticket left blocked): "
              + e.getMessage(),
          e);
    }
  }
}
