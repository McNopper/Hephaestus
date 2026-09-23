package com.opencode.ide.fleet;


import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;

/**
 * Builds the deterministic session prompt for a ticket the fleet has already
 * claimed: the ticket facts (id/title/description/acceptance criteria, plus a
 * stage line when the ticket rides the V pipeline), the project context, and
 * the INSTRUCTIONS block that tells the agent to work through the
 * {@code task_*} MCP tools and to finish in {@code in-review} (or blocked
 * with a concrete reason). A staged ticket also carries the PIPELINE
 * PROTOCOL (work only this stage; advance or send back via the store tools).
 * Plain text, no timestamps - stable for tests. Pure Java, no Eclipse/OSGi.
 */
public final class SelfClaimPrompt {

    private final Task ticket;
    private String project = "";
    private String storeLocation = ".opencode/tasks/";

    private SelfClaimPrompt(Task ticket) {
        this.ticket = ticket;
    }

    /** Starts building the prompt for one claimed ticket. */
    public static SelfClaimPrompt forTicket(Task ticket) {
        return new SelfClaimPrompt(ticket);
    }

    /** @param project the task-store project the ticket lives in */
    public SelfClaimPrompt project(String project) {
        this.project = project == null ? "" : project;
        return this;
    }

    /** @param storeLocation where the Markdown task store lives, relative to the repo root */
    public SelfClaimPrompt storeLocation(String storeLocation) {
        this.storeLocation = storeLocation == null ? "" : storeLocation;
        return this;
    }

    /** True when the ticket's history records a pipeline send-back (it arrives with the reason). */
    private boolean wasSentBack() {
        if (ticket.history == null) {
            return false;
        }
        for (Task.HistoryEvent event : ticket.history) {
            if (event.action() != null && event.action().startsWith("sent back to")) {
                return true;
            }
        }
        return false;
    }

    /** Renders the prompt (deterministic: same inputs, same text). */
    public String build() {
        StringBuilder out = new StringBuilder();
        out.append("Ticket ").append(ticket.id).append(": ").append(ticket.title).append('\n');
        out.append("Project: ").append(project).append('\n');
        out.append("Role: ").append(ticket.role).append('\n');
        String stageSkill = ticket.stage == null ? null : VStages.skillOf(ticket.stage);
        if (ticket.stage != null) {
            out.append("Stage: ").append(ticket.stage);
            if (stageSkill != null) {
                out.append(" — work in the ").append(stageSkill).append(" skill");
            }
            out.append('\n');
        }
        PromptSections.appendTicketBody(out, ticket, storeLocation);
        if (ticket.stage != null) {
            out.append('\n');
            out.append("PIPELINE PROTOCOL:\n");
            out.append("- Do ONLY this stage's work (the ").append(stageSkill == null ? "stage's" : stageSkill)
                    .append(" skill defines it).\n");
            out.append("- When the work is done AND every acceptance criterion is verified:")
                    .append(" FIRST move the ticket to review with task_update(status=\"in-review\"),")
                    .append(" THEN call task_advance — it is rejected from any other status")
                    .append(" and moves the ticket to the next stage's backlog.\n");
            out.append("- ").append(VStages.last()).append(" is the last stage (the V tip): there is no")
                    .append(" task_advance from it — finish in in-review/done for acceptance.\n");
            out.append("- If you cannot proceed (missing input from the previous stage, unclear requirement),")
                    .append(" call task_send_back with a concrete reason — it returns the ticket to the")
                    .append(" previous stage's backlog, blocked with your reason.\n");
            out.append("- Never silently stop and never do the next stage's work yourself.\n");
            out.append("- The fleet dispatches you by stage role.\n");
        }
        out.append('\n');
        if (wasSentBack()) {
            out.append("NOTE: this ticket was SENT BACK from a later stage (see its history and comments")
                    .append(" for the reason). Address the stated reason as part of this stage's work.\n");
            out.append('\n');
        }
        out.append("INSTRUCTIONS:\n");
        out.append("- Your ticket is ALREADY claimed for you in this worktree's task store")
                .append(" (in-progress, assignee \"fleet\"). Do NOT call task_claim for this or any other ticket.\n");
        out.append("- Re-read the ticket any time with task_get(\"").append(ticket.id).append("\").\n");
        out.append("- Do the work described above, in this worktree.\n");
        out.append("- Your WRITE SCOPE is exactly the file paths the acceptance criteria name")
                .append(" (plus the ticket file itself): the executor contract's \"touched_files\" ARE those")
                .append(" paths. Writing them is not just allowed - it is the task.\n");
        out.append("- A run that only analyzes, plans or explains WITHOUT producing the named")
                .append(" files/changes is a FAILED run: the merge-back refuses empty results.\n");
        out.append("- Verify the result against every acceptance criterion.\n");
        out.append("- Record every produced artifact with task_add_artifact (kind: file/git/path/url/doc) BEFORE finishing.\n");
        out.append("- When the work is done, move the ticket to review with task_update(status=\"in-review\").\n");
        out.append("- If you cannot finish, call task_set_blocked with a concrete blocker reason instead of silently stopping.\n");
        out.append("- The task_* MCP tools above are already configured for this session.\n");
        return out.toString();
    }
}
