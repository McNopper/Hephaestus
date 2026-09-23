package com.opencode.ide.fleet;

import java.util.List;

import com.opencode.ide.tasks.Task;

/**
 * Shared prompt sections (2026-09-23 CPD finding: ReviewPrompt and
 * SelfClaimPrompt copy-pasted this body). Keeps the prompt text of both
 * dispatch prompts identical where it must be.
 */
final class PromptSections {

    private PromptSections() {
    }

    /** Type/store/description/acceptance criteria - the body both prompts share. */
    static void appendTicketBody(StringBuilder out, Task ticket, String storeLocation) {
        out.append("Type: ").append(ticket.type).append('\n');
        out.append("Task store: ").append(storeLocation).append(" (one Markdown file per ticket)\n");
        out.append('\n');
        out.append("Description:\n");
        String description = ticket.description == null ? "" : ticket.description.strip();
        out.append(description.isEmpty() ? "(none)" : description).append('\n');
        out.append('\n');
        out.append("Acceptance criteria:\n");
        List<String> criteria = ticket.acceptanceCriteria == null ? List.of() : ticket.acceptanceCriteria;
        if (criteria.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (int i = 0; i < criteria.size(); i++) {
                out.append(i + 1).append(". ").append(criteria.get(i)).append('\n');
            }
        }
    }
}
