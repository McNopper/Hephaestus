package com.opencode.ide.fleet.dispatch;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * U-031 resolution-first pumping: how a BLOCKED ticket is handed for rework.
 * Pure decision - the pump asks, the store acts ({@code sendBack} for
 * {@link Route#VERTICAL}, {@code reportHorizontal} for {@link Route#HORIZONTAL}).
 *
 * <p>The routes follow the V geometry (AGENTS.md, user direction 2026-09-19):
 * a ticket blocked on the <b>definition</b> leg goes back one stage (vertical,
 * the definition chain), a ticket blocked on the <b>verification</b> leg
 * reports to its V-level pair (horizontal - a test-design failure reports to
 * design). When the round-trips are exhausted the agent routes end and the
 * ticket stays NEEDS-HUMAN, the human's only regular duty.</p>
 */
public final class ResolutionPolicy {

    /** Round-trips before the agent routes are exhausted (U-023's limit). */
    public static final int ATTEMPT_LIMIT = 3;

    /** The resolution route of one blocked ticket. */
    public enum Route {
        /** back one stage on the definition chain (store: {@code sendBack}) */
        VERTICAL,
        /** to the V-level pair stage (store: {@code reportHorizontal}) */
        HORIZONTAL,
        /** no agent route left - the human decides */
        NEEDS_HUMAN
    }

    private ResolutionPolicy() {
    }

    /**
     * @param task         the blocked ticket
     * @param attemptsSoFar resolution attempts already spent on it this run
     * @return the route to try next
     */
    public static Route route(Task task, int attemptsSoFar) {
        if (attemptsSoFar >= ATTEMPT_LIMIT) {
            return Route.NEEDS_HUMAN;
        }
        if (task == null || task.stage == null || !VStages.isValid(task.stage)) {
            return Route.NEEDS_HUMAN;
        }
        return VStages.isVerification(task.stage) ? Route.HORIZONTAL : Route.VERTICAL;
    }
}
