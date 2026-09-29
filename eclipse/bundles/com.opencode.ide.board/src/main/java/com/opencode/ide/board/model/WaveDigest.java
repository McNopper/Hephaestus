package com.opencode.ide.board.model;

import java.util.ArrayList;
import java.util.List;

import com.opencode.ide.tasks.Task;

/**
 * U-026 FR-005/FR-006: the <b>per-wave movement digest</b> — a compact
 * plain-text summary of how one wave's tickets moved, derived purely from
 * their recorded histories (FR-001/FR-007: a projection, never a write).
 * Plain text on purpose (FR-006): the same render serves the Eclipse
 * dialog, a ticket comment, or chat output — the chat-first control plane
 * can produce it without the UI.
 *
 * <p>Shape (AC-003): counts of advances, passes, send-backs and reports;
 * the tickets that moved back (send-back or horizontal report), each with
 * its latest reason; and the tickets that never moved.</p>
 */
public final class WaveDigest {

    private WaveDigest() {
    }

    /**
     * Renders the digest of one wave's tickets. Tolerant of {@code null}
     * ids/tickets and empty waves — a digest always renders.
     *
     * @param waveId the wave (sprint) id, shown in the header
     * @param tasks  the wave's tickets (their histories are the input)
     */
    public static String render(String waveId, List<Task> tasks) {
        int tickets = 0;
        int advances = 0;
        int passes = 0;
        int sendBacks = 0;
        int reports = 0;
        List<String> movedBack = new ArrayList<>();
        List<String> neverMoved = new ArrayList<>();
        if (tasks != null) {
            for (Task task : tasks) {
                if (task == null) {
                    continue;
                }
                tickets++;
                StageJourney journey = StageJourney.of(task);
                for (StageJourney.Movement movement : journey.movements()) {
                    switch (movement.kind()) {
                        case ADVANCE -> advances++;
                        case PASS -> passes++;
                        case SEND_BACK -> sendBacks++;
                        case REPORT -> reports++;
                        default -> { /* trace-only kinds are not counted (FR-005 names four) */ }
                    }
                }
                StageJourney.Movement setback = journey.latestSetback();
                if (setback != null && setback.kind() != StageJourney.Kind.CLARIFICATION) {
                    movedBack.add(idOf(task) + " \u2014 " + setback.describe());
                }
                if (!journey.moved()) {
                    neverMoved.add(idOf(task));
                }
            }
        }
        StringBuilder sb = new StringBuilder("Wave ").append(safe(waveId)).append(" movement digest\n");
        sb.append(tickets).append(" tickets \u00b7 ").append(advances).append(" advanced \u00b7 ")
                .append(passes).append(" passed \u00b7 ").append(sendBacks).append(" sent back \u00b7 ")
                .append(reports).append(" reported\n\n");
        sb.append("Moved back:\n");
        if (movedBack.isEmpty()) {
            sb.append("(no send-backs or reports)\n");
        } else {
            for (String line : movedBack) {
                sb.append("- ").append(line).append('\n');
            }
        }
        sb.append("\nNever moved:\n");
        if (neverMoved.isEmpty()) {
            sb.append("(every ticket moved)\n");
        } else {
            sb.append("- ").append(String.join(", ", neverMoved)).append('\n');
        }
        return sb.toString();
    }

    private static String idOf(Task task) {
        return task.id == null ? "?" : task.id;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
