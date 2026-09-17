package com.opencode.ide.board.model;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * One row of the kanban board: a pure, SWT-free projection of a
 * {@link Task} for display (mapping only, no store access). Carries the
 * nullable V-model {@code stage} plus the role-derived fallback
 * {@link #effectiveStage()} for legacy tickets without one, and the ticket
 * {@code type} behind the {@link #typeTag()} decoration (U-005: bugs must be
 * visible at a glance on the row itself).
 */
public record TicketRow(String id, String title, String type, String role, int points, String assignee,
        boolean blocked, String blocker, String status, String stage, String priority) {

    /** Maps a store {@link Task} to a row ({@code null}-safe: {@code null} in, {@code null} out). */
    public static TicketRow from(Task task) {
        if (task == null) {
            return null;
        }
        return new TicketRow(task.id, task.title, task.type, task.role, task.storyPoints,
                task.assignee, task.blocked, task.blocker, task.status, task.stage, task.priority);
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
        return VStages.STAGES.indexOf(effectiveStage());
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

    /** Compact status prefix for pipeline rows; unknown/null statuses read as "". */
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
        if (stage != null && !stage.isBlank()) {
            appendTag(sb, "· " + stage.trim());
        }
        return sb.toString();
    }

    /** The compact pipeline column text: {@code [IP] [BLOCKED] [type] title}. */
    public String pipelineLabel() {
        StringBuilder sb = new StringBuilder(statusPrefix(status));
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
