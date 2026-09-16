package com.opencode.ide.board.model;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * One row of the kanban board: a pure, SWT-free projection of a
 * {@link Task} for display (mapping only, no store access). Carries the
 * nullable V-model {@code stage} plus the role-derived fallback
 * {@link #effectiveStage()} for legacy tickets without one.
 */
public record TicketRow(String id, String title, String role, int points, String assignee,
        boolean blocked, String blocker, String status, String stage) {

    /** Maps a store {@link Task} to a row ({@code null}-safe: {@code null} in, {@code null} out). */
    public static TicketRow from(Task task) {
        if (task == null) {
            return null;
        }
        return new TicketRow(task.id, task.title, task.role, task.storyPoints,
                task.assignee, task.blocked, task.blocker, task.status, task.stage);
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

    /** The flat-board column text: {@code [BLOCKED] ID title}. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        if (displayBlocked()) {
            sb.append("[BLOCKED] ");
        }
        if (id != null) {
            sb.append(id).append(' ');
        }
        if (title != null) {
            sb.append(title);
        }
        return sb.toString().trim();
    }

    /** The compact pipeline column text: {@code [IP] [BLOCKED] title}. */
    public String pipelineLabel() {
        StringBuilder sb = new StringBuilder(statusPrefix(status));
        if (displayBlocked()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("[BLOCKED]");
        }
        if (title != null && !title.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(title.trim());
        }
        return sb.toString();
    }

    /** The points column text. */
    public String pointsLabel() {
        return String.valueOf(points);
    }
}
