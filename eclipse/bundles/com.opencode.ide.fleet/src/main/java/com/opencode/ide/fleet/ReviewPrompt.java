package com.opencode.ide.fleet;

import java.util.List;

import com.opencode.ide.tasks.Task;

/**
 * Builds the deterministic REVIEW session prompt for autonomous acceptance
 * (U-021): after a fleet launch merges a ticket's branch back and the ticket
 * settles to {@code in-review}, the engine dispatches a second, read-only
 * session under the reviewer agent (see {@link RoleAgents}, role
 * {@code reviewer}) whose job is to JUDGE — every acceptance criterion
 * against the recorded artifacts and the merged result, plus the CI status
 * of the merged commit — and to end with exactly one machine-parseable
 * verdict line (see {@link ReviewVerdict}). The ENGINE, not the reviewer,
 * applies the verdict through the task store; the prompt therefore forbids
 * the reviewer from moving the ticket itself. Plain text, no timestamps -
 * stable for tests. Pure Java, no Eclipse/OSGi.
 */
public final class ReviewPrompt {

    private final Task ticket;
    private String project = "";
    private String storeLocation = ".opencode/tasks/";

    private ReviewPrompt(Task ticket) {
        this.ticket = ticket;
    }

    /** Starts building the review prompt for one settled ticket. */
    public static ReviewPrompt forTicket(Task ticket) {
        return new ReviewPrompt(ticket);
    }

    /** @param project the task-store project the ticket lives in */
    public ReviewPrompt project(String project) {
        this.project = project == null ? "" : project;
        return this;
    }

    /** @param storeLocation where the Markdown task store lives, relative to the repo root */
    public ReviewPrompt storeLocation(String storeLocation) {
        this.storeLocation = storeLocation == null ? "" : storeLocation;
        return this;
    }

    /** Renders the prompt (deterministic: same inputs, same text). */
    public String build() {
        StringBuilder out = new StringBuilder();
        out.append("Review ticket ").append(ticket.id).append(": ").append(ticket.title).append('\n');
        out.append("Project: ").append(project).append('\n');
        out.append("Role: ").append(ticket.role).append('\n');
        if (ticket.stage != null) {
            out.append("Stage: ").append(ticket.stage).append('\n');
        }
        PromptSections.appendTicketBody(out, ticket, storeLocation);
        out.append('\n');
        out.append("Recorded artifacts:\n");
        List<Task.Artifact> artifacts = ticket.artifacts == null ? List.of() : ticket.artifacts;
        if (artifacts.isEmpty()) {
            out.append("(none)\n");
        } else {
            for (Task.Artifact artifact : artifacts) {
                out.append("- ").append(artifact.kind()).append(": ").append(artifact.ref());
                if (artifact.note() != null && !artifact.note().isBlank()) {
                    out.append(" — ").append(artifact.note());
                }
                out.append('\n');
            }
        }
        out.append('\n');
        out.append("REVIEW PROTOCOL:\n");
        out.append("- The worker's branch for this ticket was merged back into the main worktree")
                .append(" this session runs in; the ticket now sits in in-review awaiting acceptance.\n");
        out.append("- Judge EVERY acceptance criterion against the recorded artifacts above and the")
                .append(" merged result: inspect the diff and the artifact refs in this worktree.\n");
        out.append("- Establish the CI status of the merged commit: run the repository's verification")
                .append(" gate (see its AGENTS.md/README, e.g. the build or verify target)")
                .append(" and treat a red gate as a FAIL.\n");
        out.append("- PASS only when every criterion is verifiably met and the verification gate is green.\n");
        out.append("- FAIL when a criterion is unmet or the gate is red; give concrete, actionable")
                .append(" reasons for each unmet criterion.\n");
        out.append("- UNCLEAR when you cannot decide (missing evidence, doubt, verification blocked)")
                .append(" — the ticket then waits for a human.\n");
        out.append("- Re-read the ticket any time with task_get(\"").append(ticket.id)
                .append("\") — its comments carry the run actuals.\n");
        out.append("- You are READ-ONLY: do not edit files and do not move the ticket yourself")
                .append(" (no task_update, task_advance or task_send_back) — the engine applies your verdict.\n");
        out.append("- End your FINAL reply with EXACTLY ONE verdict line as the last line,")
                .append(" in one of these shapes:\n");
        out.append("  VERDICT: PASS - <one-line summary>\n");
        out.append("  VERDICT: FAIL - <concrete reasons>\n");
        out.append("  VERDICT: UNCLEAR - <what you could not determine>\n");
        return out.toString();
    }
}
