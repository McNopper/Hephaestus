package com.opencode.ide.board.model;

import java.util.List;

import com.opencode.ide.tasks.Task;

/**
 * Composes one ticket into a single markdown document for the Board's details
 * view (rendered by the chat web component, so tables, math and mermaid
 * diagrams stored in tickets render as diagrams).
 *
 * <p>Sections: description, stage journey (U-026: progress + movement
 * trace), acceptance criteria, todos, artifacts, comments.
 * Checkmark items are plain literal {@code [x]}/{@code [ ]} text (the renderer
 * has no task-list plugin) — honest for a read-only, agent-owned store.
 * Comments render as top-level markdown so a diagram stored in a comment
 * renders as a diagram.</p>
 */
public final class TicketMarkdown {

    private TicketMarkdown() {
    }

    /** @return the full ticket document (description first, newest comment last). */
    public static String document(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(task.description == null || task.description.isBlank()
                ? "_no description_" : task.description.trim()));

        journeySection(sb, task);

        section(sb, "Acceptance criteria", task.acceptanceCriteria.isEmpty());
        for (String item : task.acceptanceCriteria) {
            sb.append("- [ ] ").append(safe(item)).append('\n');
        }

        section(sb, "Todos", task.todos.isEmpty());
        for (Task.Todo todo : task.todos) {
            sb.append("- ").append(todo.done() ? "[x]" : "[ ]")
                    .append(' ').append(safe(todo.text())).append('\n');
        }

        section(sb, "Artifacts", task.artifacts.isEmpty());
        for (Task.Artifact artifact : task.artifacts) {
            sb.append("- `").append(safe(artifact.kind())).append("` `").append(safe(artifact.ref()))
                    .append('`');
            if (artifact.note() != null && !artifact.note().isBlank()) {
                sb.append(" — ").append(safe(artifact.note()));
            }
            sb.append('\n');
        }

        section(sb, "Comments", task.comments.isEmpty());
        List<Task.Comment> comments = task.comments;
        int from = Math.max(0, comments.size() - 10); // newest last, capped
        for (int i = from; i < comments.size(); i++) {
            Task.Comment c = comments.get(i);
            if (i > from) {
                sb.append('\n');
            }
            sb.append("**[").append(Task.formatTs(c.ts())).append("] ")
                    .append(c.by() == null ? "?" : c.by()).append(":**\n\n")
                    .append(safe(c.text())).append('\n');
        }
        return sb.toString();
    }

    /**
     * U-026 FR-002/FR-003/FR-004: the stage-journey section — the progress
     * ({@code **4/10** stages visited}, the same parser the card progress
     * uses — NFR-REDUND-001: one source, two depths) followed by the
     * movement trace, one line per transition with timestamp, author,
     * direction and reason. Send-back lines render bold so the feedback
     * loop is unmissable (AC-002).
     */
    private static void journeySection(StringBuilder sb, Task task) {
        StageJourney journey = StageJourney.of(task);
        sb.append("\n## Stage journey\n");
        sb.append("**").append(journey.progressLabel()).append("** stages visited \u2014 ")
                .append("distinct V stages entered; the current stage counts once entered\n\n");
        if (journey.movements().isEmpty()) {
            sb.append("_(no movements)_\n");
            return;
        }
        for (StageJourney.Movement movement : journey.movements()) {
            if (movement.kind() == StageJourney.Kind.SEND_BACK) {
                sb.append("- **").append(movement.line()).append("**\n");
            } else {
                sb.append("- ").append(movement.line()).append('\n');
            }
        }
    }

    private static void section(StringBuilder sb, String title, boolean empty) {
        sb.append("\n## ").append(title).append('\n');
        if (empty) {
            sb.append("_(none)_\n");
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
