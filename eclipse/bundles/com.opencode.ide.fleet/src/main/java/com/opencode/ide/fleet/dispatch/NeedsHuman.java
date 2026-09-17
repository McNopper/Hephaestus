package com.opencode.ide.fleet.dispatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.opencode.ide.tasks.Task;

/**
 * The U-022 NEEDS-HUMAN escalation set — the explicit human surface of the
 * recurring-waves loop. A ticket is <b>at the user</b> when it carries the
 * blocked flag AND no live fleet job is retrying it: readiness reports it
 * BLOCKED, dispatch skips it (guaranteed today), so the loop parks while the
 * user changes the ticket; clearing the blocker returns it into the cyclic
 * pump (the next planning tick sees it READY again).
 *
 * <p>A pure value function over one snapshot plus the running set: no I/O,
 * no clock, never throws — null and degenerate inputs are tolerated. The
 * three U-022 surfaces all render this one computation: the board badge
 * (count of BLOCKED verdicts, see {@code BoardSnapshot#needsHumanCount}),
 * the {@code fleet_waves_status} tool rows, and the periodic summary
 * {@link RecurringWaves} emits while it waits.</p>
 */
public final class NeedsHuman {

    private NeedsHuman() {
    }

    /** One ticket waiting at the human: its id, title and blocker text. */
    public record Escalation(String id, String title, String blocker) {
    }

    /**
     * The tickets blocked with no in-flight retry, sorted by id (stable
     * across snapshots so summaries and tool rows do not flicker).
     *
     * @param tickets one project snapshot (nulls and id-less tickets skipped)
     * @param running ticket ids with a live fleet job — a blocked ticket
     *                still being retried is NOT yet at the human; it becomes
     *                NEEDS-HUMAN only when the retry settles still blocked
     */
    public static List<Escalation> of(List<Task> tickets, Set<String> running) {
        Set<String> inFlight = running == null ? Set.of() : running;
        List<Escalation> out = new ArrayList<>();
        if (tickets == null) {
            return out;
        }
        for (Task t : tickets) {
            if (t == null || t.id == null || !t.blocked) {
                continue;
            }
            if (inFlight.contains(t.id)) {
                continue; // a live retry owns the ticket for now
            }
            out.add(new Escalation(t.id, t.title == null ? "" : t.title, t.blocker));
        }
        out.sort(Comparator.comparing(Escalation::id));
        return out;
    }

    /**
     * The periodic summary line: {@code NEEDS-HUMAN (2): U-022 — waiting on
     * the product owner; W-004 — sent back from design: ...}. Empty input
     * reads as "no ticket needs the human" — callers skip summarizing then.
     */
    public static String summary(List<Escalation> escalations) {
        if (escalations == null || escalations.isEmpty()) {
            return "NEEDS-HUMAN (0): no ticket needs the human";
        }
        StringBuilder text = new StringBuilder("NEEDS-HUMAN (").append(escalations.size()).append("): ");
        for (int i = 0; i < escalations.size(); i++) {
            Escalation e = escalations.get(i);
            if (i > 0) {
                text.append("; ");
            }
            text.append(e.id());
            String blocker = e.blocker();
            if (blocker != null && !blocker.isBlank()) {
                text.append(" — ").append(blocker);
            }
        }
        return text.toString();
    }
}
