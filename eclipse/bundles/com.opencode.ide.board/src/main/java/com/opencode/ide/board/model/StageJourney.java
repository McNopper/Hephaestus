package com.opencode.ide.board.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * U-026 V-flow visibility: the <b>stage journey</b> of one ticket — the
 * ordered sequence of movement events recorded in its {@code history},
 * reconstructed purely by parsing (FR-001: no new recording channel, no
 * side state; FR-007: a read-only projection of recorded state). The
 * store's movement vocabulary (C-001) is
 * <ul>
 * <li>{@code advanced to <stage>} — the one-step hand-forward,
 * <li>{@code sent back to <stage>: <reason>} — the one-step feedback loop
 *     (source stage = the canonical stage AFTER the destination),
 * <li>{@code stage <N> passed: <reason>} — the U-029 pass-through (the
 *     passed stage is {@code N}, the ticket moved on to {@code N+1}),
 * <li>{@code reported to <stage>: <reason>} — the U-031 horizontal report
 *     to the V-level pair (source = the pair of the destination),
 * <li>{@code clarification to/from/escalated …} — the U-023 question
 *     routing (reports a doubt, does not move the ticket),
 * <li>{@code review doubt …} — the B-007 originator retry markers,
 * <li>{@code updated:…stage…} — a direct stage write (DnD): a real
 *     position change the history records without from/to; kept honest
 *     as a {@link Kind#SET} movement.
 * </ul>
 *
 * <p>Two derived projections ride on the same parse (NFR-REDUND-001: one
 * source, two depths): the per-card progress {@code <visited>/10}
 * (FR-002: visited counts the <b>distinct</b> V stages the journey
 * entered — the current stage counts once entered, rework counts once —
 * Q-002's lean answer) and the movement trace (FR-003/FR-004: every
 * transition with timestamp, author, direction and reason; send-backs
 * carry the unmissable {@code sent back from X: <reason>} shape).
 * SWT-free: the view renders, this class decides.</p>
 */
public record StageJourney(List<Movement> movements, int visitedCount) {

    /** The movement directions the history vocabulary records. */
    public enum Kind {
        /** One-step hand-forward ({@code advanced to}). */
        ADVANCE,
        /** One-step feedback loop ({@code sent back to … : reason}). */
        SEND_BACK,
        /** U-029 pass-through ({@code stage N passed: reason}). */
        PASS,
        /** U-031 horizontal report to the V-level pair ({@code reported to}). */
        REPORT,
        /** Direct stage write ({@code updated:…stage…}) — from/to not recorded. */
        SET,
        /** U-023 question routing ({@code clarification …}) — reports, does not move. */
        CLARIFICATION,
        /** B-007 originator doubt round-trip ({@code review doubt …}). */
        REVIEW_DOUBT
    }

    /**
     * One movement event, parsed. {@code fromStage}/{@code toStage} are the
     * canonical stage ids the event implies (both derivable from the marker:
     * a send-back names its destination, its source is the next stage up;
     * a report names its pair); {@code reason} is the recorded rationale
     * where the event carries one. CLARIFICATION/REVIEW_DOUBT keep the raw
     * action text as their {@code reason} and carry no stages.
     */
    public record Movement(Instant ts, String by, Kind kind, String fromStage, String toStage, String reason) {

        /** True for the kinds that change the ticket's position on the V. */
        public boolean changesPosition() {
            return switch (kind) {
                case ADVANCE, SEND_BACK, PASS, REPORT, SET -> true;
                default -> false;
            };
        }

        /**
         * The human-readable movement line (FR-003's direction + reason;
         * FR-004's exact send-back/report shapes). Pure ASCII escapes on
         * purpose — the source stays ASCII like the rest of the board.
         */
        public String describe() {
            return switch (kind) {
                case ADVANCE -> "advanced " + safe(fromStage) + " \u2192 " + safe(toStage);
                case SEND_BACK -> "\u26A0 sent back from " + safe(fromStage) + withReason(reason)
                        + " \u2014 back to " + safe(toStage);
                case PASS -> "passed " + safe(fromStage) + " \u2192 " + safe(toStage) + withReason(reason);
                case REPORT -> "reported to " + safe(toStage) + withReason(reason)
                        + " \u2014 from " + safe(fromStage);
                case SET -> "stage set directly (from/to not recorded in history)";
                default -> safe(reason); // CLARIFICATION / REVIEW_DOUBT: the raw marker
            };
        }

        /** The trace line: {@code [ts] author · describe()}. */
        public String line() {
            String author = by == null || by.isBlank() ? "?" : by;
            return "[" + Task.formatTs(ts) + "] " + author + " \u00b7 " + describe();
        }

        private static String withReason(String reason) {
            return reason == null || reason.isBlank() ? "" : ": " + reason;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    /** The empty journey: no movements, nothing visited ({@code 0/10}). */
    public static StageJourney empty() {
        return new StageJourney(List.of(), 0);
    }

    /** @return the journey of a store task ({@code null}-safe: {@code null} in, empty journey out). */
    public static StageJourney of(Task task) {
        if (task == null) {
            return empty();
        }
        return of(task.stage, task.role, task.history);
    }

    /**
     * The parse core: walks the history once (O(history size), NFR-PERF-001)
     * collecting movements and the distinct stages entered. The ticket's
     * CURRENT stage counts once entered (FR-002) — the stored stage wins,
     * the role fallback covers legacy tickets, untracked tickets enter
     * nothing.
     */
    public static StageJourney of(String stage, String role, List<Task.HistoryEvent> history) {
        List<Movement> movements = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        if (history != null) {
            for (Task.HistoryEvent event : history) {
                if (event == null || event.action() == null) {
                    continue;
                }
                Movement movement = parse(event);
                if (movement != null) {
                    movements.add(movement);
                    if (movement.changesPosition()) {
                        visit(visited, movement.fromStage());
                        visit(visited, movement.toStage());
                    }
                } else if (event.action().startsWith("created")) {
                    visit(visited, createdStage(event.action()));
                }
            }
        }
        visit(visited, stage != null ? stage : VStages.deriveFromRole(role));
        return new StageJourney(List.copyOf(movements), visited.size());
    }

    /**
     * Parses one history event into a movement; {@code null} when the event
     * is not part of the movement vocabulary (or names an unknown stage —
     * hand-edited histories never break the projection).
     */
    private static Movement parse(Task.HistoryEvent event) {
        String action = event.action();
        Instant ts = event.ts();
        String by = event.by();
        if (action.startsWith("advanced to ")) {
            String to = action.substring("advanced to ".length()).trim();
            String from = VStages.previous(to);
            return VStages.isValid(to) && from != null
                    ? new Movement(ts, by, Kind.ADVANCE, from, to, null)
                    : null;
        }
        if (action.startsWith("sent back to ")) {
            String rest = action.substring("sent back to ".length());
            int colon = rest.indexOf(": ");
            String to = (colon < 0 ? rest : rest.substring(0, colon)).trim();
            String reason = colon < 0 ? null : rest.substring(colon + 2).trim();
            // one-step contract: the source is the stage after the destination
            String from = VStages.next(to);
            return VStages.isValid(to) && from != null
                    ? new Movement(ts, by, Kind.SEND_BACK, from, to, reason)
                    : null;
        }
        if (action.startsWith("stage ") && action.contains(" passed: ")) {
            int passed = action.indexOf(" passed: ");
            String reason = action.substring(passed + " passed: ".length()).trim();
            Integer number = numberOf(action.substring("stage ".length(), passed));
            if (number != null && number >= 1 && number <= VStages.STAGES.size()) {
                String from = VStages.STAGES.get(number - 1);
                String to = VStages.next(from);
                // the V tip can never pass (the store rejects it) - a marker
                // claiming it is corrupt, not a movement
                if (to != null) {
                    return new Movement(ts, by, Kind.PASS, from, to, reason);
                }
            }
            return null;
        }
        if (action.startsWith("reported to ")) {
            String rest = action.substring("reported to ".length());
            int colon = rest.indexOf(": ");
            String to = (colon < 0 ? rest : rest.substring(0, colon)).trim();
            String reason = colon < 0 ? null : rest.substring(colon + 2).trim();
            // horizontal contract: the source is the destination's V-level pair
            String from = VStages.pairOf(to);
            return VStages.isValid(to) && from != null
                    ? new Movement(ts, by, Kind.REPORT, from, to, reason)
                    : null;
        }
        if (action.startsWith("clarification") || action.startsWith("review doubt")) {
            // reports a doubt/question without moving the ticket; the raw
            // marker is already a well-formed sentence
            return new Movement(ts, by, action.startsWith("clarification")
                    ? Kind.CLARIFICATION : Kind.REVIEW_DOUBT, null, null, action);
        }
        if (action.startsWith("updated:")) {
            String fields = action.substring("updated:".length());
            for (String field : fields.split(",")) {
                if ("stage".equals(field.trim())) {
                    return new Movement(ts, by, Kind.SET, null, null, null);
                }
            }
        }
        return null;
    }

    /** The entry stage of a {@code created (stage: X …)} marker; {@code null} when absent/unknown. */
    private static String createdStage(String action) {
        int at = action.indexOf("stage: ");
        if (at < 0) {
            return null;
        }
        String rest = action.substring(at + "stage: ".length());
        int end = rest.indexOf(" -");
        if (end < 0) {
            end = rest.indexOf(')');
        }
        String candidate = (end < 0 ? rest : rest.substring(0, end)).trim();
        return VStages.isValid(candidate) ? candidate : null;
    }

    private static Integer numberOf(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Adds {@code stage} to the visited set when it is a canonical V stage. */
    private static void visit(Set<String> visited, String stage) {
        if (VStages.isValid(stage)) {
            visited.add(stage);
        }
    }

    /** The card progress (FR-002): {@code <visited>/10}; untracked tickets read {@code 0/10}. */
    public String progressLabel() {
        return visitedCount + "/" + VStages.STAGES.size();
    }

    /**
     * True when the ticket ever hit a setback (FR-004's unmissable family:
     * a send-back, a horizontal report, or a clarification marker) — the
     * card's {@code \u21A9} marker and the digest's moved-back list key on
     * this shape.
     */
    public boolean hasSetback() {
        return latestSetback() != null;
    }

    /** The latest send-back / report / clarification marker; {@code null} when the journey has none. */
    public Movement latestSetback() {
        Movement latest = null;
        for (Movement movement : movements) {
            if (movement.kind() == Kind.SEND_BACK
                    || movement.kind() == Kind.REPORT
                    || movement.kind() == Kind.CLARIFICATION) {
                latest = movement;
            }
        }
        return latest;
    }

    /** True when any movement changed the ticket's position on the V. */
    public boolean moved() {
        for (Movement movement : movements) {
            if (movement.changesPosition()) {
                return true;
            }
        }
        return false;
    }

    /** The trace lines (US-002): one per movement, oldest first — the shared source of every surface. */
    public List<String> lines() {
        List<String> out = new ArrayList<>(movements.size());
        for (Movement movement : movements) {
            out.add(movement.line());
        }
        return out;
    }

    /**
     * The journey as plain text (FR-006's shape — a ticket comment or chat
     * output): the progress header plus one line per movement.
     */
    public String plainText() {
        StringBuilder sb = new StringBuilder("Stage journey: ").append(progressLabel())
                .append(" stages visited (distinct; the current stage counts once entered)");
        for (Movement movement : movements) {
            sb.append('\n').append(movement.line());
        }
        if (movements.isEmpty()) {
            sb.append("\n(no movements yet)");
        }
        return sb.toString();
    }
}
