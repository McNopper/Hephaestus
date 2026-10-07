package com.opencode.ide.board.model;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

import java.util.Comparator;
import java.util.Locale;

/**
 * One row of the kanban board: a pure, SWT-free projection of a
 * {@link Task} for display (mapping only, no store access). Carries the
 * nullable V-model {@code stage} plus the role-derived fallback
 * {@link #effectiveStage()} for legacy tickets without one, and the ticket
 * {@code type} behind the {@link #typeTag()} decoration (U-005: bugs must be
 * visible at a glance on the row itself).
 */
public record TicketRow(String id, String title, String type, String role, int points, String assignee,
        boolean blocked, String blocker, String status, String stage, String priority, String epic) {

    /** Maps a store {@link Task} to a row ({@code null}-safe: {@code null} in, {@code null} out). */
    public static TicketRow from(Task task) {
        if (task == null) {
            return null;
        }
        return new TicketRow(task.id, task.title, task.type, task.role, task.storyPoints,
                task.assignee, task.isBlocked(), task.blocker, task.status, task.stage, task.priority,
                task.epic);
    }

    /**
     * The swimlane key for the Epic layout (U-018): the stored epic id, or
     * {@code NO_EPIC} for unepicked tickets (they swim together).
     */
    public static final String NO_EPIC = "(no epic)";

    /** @return the epic lane key - the epic id or {@link #NO_EPIC}; never blank. */
    public String epicLane() {
        return epic == null || epic.isBlank() ? NO_EPIC : epic.trim();
    }

    /**
     * Sort rank of the ticket's priority: critical first, then high, medium,
     * low; unknown values sort last (stable within equal rank). The board's
     * within-column ordering uses this (U-016: the None grouping is sorted
     * by progress - columns in lifecycle order, cards by priority).
     */
    public int priorityRank() {
        return switch (priority == null ? "" : priority.trim().toLowerCase()) {
            case "critical" -> 0;
            case "high" -> 1;
            case "medium" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

    /**
     * The row's depth along the V ladder ({@link VStages#STAGES} index of
     * the effective stage), {@code -1} for untracked rows; the secondary
     * within-column sort key after priority.
     */
    public int stageDepth() {
        // null-safe by contract (-1 for untracked): List.of(...).indexOf(null)
        // throws NPE, which used to crash the whole board refresh the moment
        // one role-untrackable ticket existed (U-026 acceptance found it)
        String effective = effectiveStage();
        return effective == null ? -1 : VStages.STAGES.indexOf(effective);
    }

    /**
     * The pipeline column this row belongs to: the stored stage when present,
     * else the display-only role fallback ({@link VStages#deriveFromRole}).
     * May be {@code null} (no stage, unknown role) — such rows are untracked.
     */
    public String effectiveStage() {
        return stage != null ? stage : VStages.deriveFromRole(role);
    }

    /**
     * The display-time blocked flag: never true for a done ticket, even when
     * the stored flag drifted (done+blocked legacy data), so no blocked
     * decoration is ever rendered on a done row. The store clears the drift
     * on the next write; this is the render-side guard.
     */
    public boolean displayBlocked() {
        return blocked && !"done".equals(status);
    }

    /**
     * Whether this row is a bug ticket — the one type that gets the distinct
     * red accent on the board (U-005: bugs are triaged first, so they must
     * stand out from features). Kept as a predicate so the SWT side stays a
     * one-liner and the semantics stay testable.
     */
    public boolean isBug() {
        return "bug".equals(type);
    }

    /**
     * The compact type badge for row labels: lowercase bracketed, so type
     * tags ({@code [bug]}) read distinctly from the uppercase state tags
     * ({@code [IP]}, {@code [BLOCKED]}). Valid store types (bug/story/task/
     * spike) map to themselves; an unknown non-blank type (hand-edited
     * frontmatter) is bracketed verbatim rather than hidden; {@code null}/
     * blank reads as "" (legacy rows without a type render untagged, no
     * stray spaces).
     */
    public static String typeTag(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        return "[" + type.trim() + "]";
    }

    /** The row's own type badge; see {@link #typeTag(String)}. */
    public String typeTag() {
        return typeTag(type);
    }

    /**
     * Compact status prefix for pipeline/swimlane rows; unknown/null statuses read as "".
     * @deprecated textual codes ([IP]…) — superseded by {@link #statusSymbol} (user
     * direction 2026-09-18: pictographs are easier to read; kept for the legend tooltip).
     */
    @Deprecated
    public static String statusPrefix(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "product-backlog" -> "[PB]";
            case "sprint-backlog" -> "[SB]";
            case "in-progress" -> "[IP]";
            case "in-review" -> "[IR]";
            case "done" -> "[D]";
            default -> "";
        };
    }

    /**
     * The status pictograph for pipeline/swimlane cards (user direction
     * 2026-09-18: glyphs read faster than bracket codes; the view colors
     * them - done green, running blue, review amber, backlog gray):
     * <pre>
     * product-backlog  📌  outlined box (idea, not yet in a wave)
     * sprint-backlog   📋  queued (in a wave, waiting to run)
     * in-progress      🏃  running
     * in-review        👀  half-full (being judged)
     * done             ✅  done
     * </pre>
     */
    public static String statusSymbol(String status) {
        return switch (status == null ? "" : status) {
            case "product-backlog" -> "\uD83D\uDCCC"; // 📌
            case "sprint-backlog" -> "\uD83D\uDCCB";  // 📋
            case "in-progress" -> "\uD83C\uDFC3";     // 🏃
            case "in-review" -> "\uD83D\uDC40";       // 👀
            case "done" -> "\u2705";            // ✅
            case "blocked" -> "\uD83D\uDEAB";  // no-entry sign (the state)
            default -> "";
        };
    }


    /**
     * The type emoji for the board's type column (U-065 owner direction:
     * emoji where possible - colored, visible, DPI-proof; no image files).
     * Unknown/blank reads as "" (untagged, no stray spaces).
     */
    public static String typeEmoji(String type) {
        return switch (type == null ? "" : type.trim().toLowerCase()) {
            case "bug" -> "\uD83D\uDC1E";         // lady beetle
            case "story" -> "\uD83D\uDCD6";       // open book
            case "task" -> "\uD83D\uDEE0\uFE0F"; // hammer and wrench
            case "spike" -> "\u26A1\uFE0F";       // high voltage
            default -> "";
        };
    }

    /**
     * The click-to-sort comparator for the board's card columns (owner
     * direction 2026-10-07: sorting on the cards). Keys: {@code status}
     * (pipeline order), {@code type} and {@code title} (case-insensitive),
     * {@code points} (numeric). An unset key - unknown status, blank value,
     * zero points - sorts LAST in both directions, the ticket id breaks ties
     * ascending (stable in either direction); unknown keys sort like points.
     */
    public static Comparator<TicketRow> cardComparator(String column, boolean ascending) {
        String key = column == null ? "" : column;
        Comparator<TicketRow> unsetLast =
                Comparator.comparingInt(row -> sortUnset(key, row) ? 1 : 0);
        Comparator<TicketRow> value = switch (key) {
            case "status" -> Comparator.comparingInt(
                    row -> row.status() == null ? -1 : Task.VALID_STATUSES.indexOf(row.status()));
            case "type" -> Comparator.comparing(row -> sortKey(row.type()));
            case "title" -> Comparator.comparing(row -> sortKey(row.title()));
            default -> Comparator.comparingInt(TicketRow::points);
        };
        return unsetLast
                .thenComparing(ascending ? value : value.reversed())
                .thenComparing(row -> row.id() == null ? "" : row.id());
    }

    /** True when the column's key carries no sortable content for this row. */
    private static boolean sortUnset(String column, TicketRow row) {
        return switch (column) {
            case "status" -> row.status() == null
                    || !Task.VALID_STATUSES.contains(row.status());
            case "type" -> row.type() == null || row.type().isBlank();
            case "title" -> row.title() == null || row.title().isBlank();
            default -> row.points() == 0;
        };
    }

    /** The case-insensitive sort key for a nullable text field. */
    private static String sortKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The status column header text (U-065): the status pictograph first -
     * the same glyph the cards' status column shows - then the status name,
     * so every group header carries its kind's marker in all three Group-by
     * modes.
     */
    public static String statusHeaderText(String status) {
        String symbol = statusSymbol(status);
        String name = status == null ? "" : status;
        return symbol.isEmpty() ? name : symbol + " " + name;
    }

    /**
     * The one-line meaning of a status for tooltips (owner direction
     * 2026-10-07: hover help on the board's group headers). Blank for a
     * null/unknown status - the caller then shows no tooltip at all.
     */
    public static String statusHelp(String status) {
        return switch (status == null ? "" : status) {
            case "product-backlog" -> "not yet planned into a wave";
            case "sprint-backlog" -> "planned into the wave, ready to claim";
            case "in-progress" -> "claimed - a worker is running";
            case "in-review" -> "worker finished - awaiting acceptance";
            case "paused" -> "parked for maintenance (never blocked)";
            case "done" -> "accepted - shipped";
            case "blocked" -> "waiting on a human or an input - clearing returns it to resume";
            default -> "";
        };
    }

    /**
     * The quiet gray tail for cards: the stage (cross-mode awareness,
     * U-016) — {@code · design}; empty when the ticket carries no stage.
     */
    public String labelTail() {
        return stage == null || stage.isBlank() ? "" : "· " + stage.trim();
    }

    /** The flat-board column text: {@code [BLOCKED] ID [type] title · stage}. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        if (displayBlocked()) {
            sb.append("[BLOCKED]");
        }
        appendTag(sb, id);
        appendTag(sb, typeTag());
        if (title != null && !title.isBlank()) {
            appendTag(sb, title.trim());
        }
        // cross-mode awareness (U-016): even in the flat status kanban a
        // staged ticket shows its stage at the label's tail
        String tail = labelTail();
        if (!tail.isEmpty()) {
            appendTag(sb, tail);
        }
        return sb.toString();
    }

    /** The compact pipeline column text: {@code 🏃 [BLOCKED] [type] title}. */
    public String pipelineLabel() {
        StringBuilder sb = new StringBuilder();
        String symbol = statusSymbol(status);
        if (!symbol.isEmpty()) {
            sb.append(symbol);
        }
        if (displayBlocked()) {
            appendTag(sb, "[BLOCKED]");
        }
        appendTag(sb, typeTag());
        if (title != null && !title.isBlank()) {
            appendTag(sb, title.trim());
        }
        return sb.toString();
    }

    /**
     * Appends {@code tag} to {@code sb} with a separating space when needed;
     * a blank tag appends nothing (no stray double spaces around missing
     * pieces).
     */
    private static void appendTag(StringBuilder sb, String tag) {
        if (tag == null || tag.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(tag);
    }

    /** The points column text. */
    public String pointsLabel() {
        return String.valueOf(points);
    }
}
