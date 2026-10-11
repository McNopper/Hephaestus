package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for the {@link SelfClaimPrompt} builder: the ticket facts and
 * the full INSTRUCTIONS block must appear, deterministically, in a compact
 * prompt.
 */
public class SelfClaimPromptTest {

    private static Task ticket() {
        Task t = new Task();
        t.id = "H1-001";
        t.title = "Fix the widget";
        t.description = "Do the thing.\nExactly as described.";
        t.role = "developer";
        t.type = "task";
        t.acceptanceCriteria.addAll(List.of("ac one", "ac two"));
        return t;
    }

    private static int lineCount(String s) {
        return s.split("\n", -1).length;
    }

    @Test
    public void promptContainsTicketFacts() {
        String prompt = SelfClaimPrompt.forTicket(ticket()).project("demo").build();

        assertTrue(prompt.contains("Ticket H1-001: Fix the widget"));
        assertTrue(prompt.contains("Project: demo"));
        assertTrue(prompt.contains("Role: developer"));
        assertTrue(prompt.contains("Type: task"));
        assertTrue(prompt.contains("Do the thing."));
        assertTrue(prompt.contains("Exactly as described."));
        assertTrue("criteria are a numbered checklist", prompt.contains("1. ac one"));
        assertTrue("criteria are a numbered checklist", prompt.contains("2. ac two"));
    }

    @Test
    public void promptContainsSelfClaimInstructions() {
        String prompt = SelfClaimPrompt.forTicket(ticket()).project("demo").build();

        assertTrue(prompt.contains("ALREADY claimed"));
        assertTrue("must not claim anything else", prompt.contains("task_claim"));
        assertTrue(prompt.contains("task_get(\"H1-001\")"));
        assertTrue(prompt.contains("task_add_artifact"));
        assertTrue(prompt.contains("task_update"));
        assertTrue(prompt.contains("in-review"));
        assertTrue(prompt.contains("task_set_blocked"));
        assertTrue(prompt.contains("task_* MCP tools"));
    }

    @Test
    public void promptIsDeterministicAndCompact() {
        String first = SelfClaimPrompt.forTicket(ticket()).project("demo").build();
        String second = SelfClaimPrompt.forTicket(ticket()).project("demo").build();

        assertEquals(first, second);
        assertTrue("prompt should stay under ~40 lines, got " + lineCount(first),
                lineCount(first) <= 40);
    }

    @Test
    public void blankDescriptionAndCriteriaRenderPlaceholders() {
        Task t = new Task();
        t.id = "T-001";
        t.title = "tiny";

        String prompt = SelfClaimPrompt.forTicket(t).project("p").build();

        assertTrue(prompt.contains("Ticket T-001: tiny"));
        assertTrue(prompt.contains("(none)"));
    }

    @Test
    public void stagedTicketGainsStageBlockAndPipelineProtocol() {
        Task definition = ticket();
        definition.stage = "design";
        String prompt = SelfClaimPrompt.forTicket(definition).project("demo").build();

        assertTrue("stage block names the stage and its skill family",
                prompt.contains("Stage: design — work in the software-design skill"));
        assertTrue(prompt.contains("PIPELINE PROTOCOL:"));
        assertTrue("protocol: only this stage's work",
                prompt.contains("Do ONLY this stage's work (the software-design skill defines it)"));
        assertTrue("protocol must mention the advance tool", prompt.contains("task_advance"));
        assertTrue("protocol must mention the send-back tool", prompt.contains("task_send_back"));
        assertTrue("protocol: never do the next stage's work yourself",
                prompt.contains("never do the next stage's work yourself"));
        assertEquals("staged prompt stays deterministic",
                prompt, SelfClaimPrompt.forTicket(definition).project("demo").build());
        // U-079: the staged prompt carries the UPSTREAM DEFINITIONS section and
        // the coverage/verdict protocol lines, so its budget grows with them
        assertTrue("staged prompt should stay under ~60 lines, got " + lineCount(prompt),
                lineCount(prompt) <= 60);
    }

    /**
     * B-020: the staged prompt teaches the U-023 clarification loop - a
     * "clarification:" send-back to the ORIGINATOR first (up to 3
     * round-trips), and task_set_blocked only as the documented last
     * resort. Mirrors executor.md's guardrail word for word in doctrine.
     */
    @Test
    public void stagedPromptAsksTheOriginatorBeforeBlocking() {
        Task definition = ticket();
        definition.stage = "design";
        String prompt = SelfClaimPrompt.forTicket(definition).project("demo").build();

        assertTrue("the question path names the prefix",
                prompt.contains("\"clarification:\""));
        assertTrue(prompt.contains("ORIGINATOR"));
        assertTrue("the round-trip limit is stated", prompt.contains("up to 3 round-trips"));
        assertTrue("the defect path stays a plain send-back",
                prompt.contains("blocked with your reason"));
        int question = prompt.indexOf("clarification:");
        int blocked = prompt.indexOf("task_set_blocked");
        assertTrue("the clarification path is taught BEFORE blocking",
                question >= 0 && blocked > question);
        assertTrue("blocking is framed as the last resort", prompt.contains("LAST resort"));
    }

    @Test
    public void verificationStageNamesItsSkillFamily() {
        Task verification = ticket();
        verification.stage = "test-implementation";

        String prompt = SelfClaimPrompt.forTicket(verification).project("demo").build();

        assertTrue(prompt.contains("Stage: test-implementation — work in the test-software-implementation skill"));
        assertTrue(prompt.contains("task_advance"));
        assertTrue(prompt.contains("task_send_back"));
    }

    @Test
    public void stagelessTicketKeepsLegacyPrompt() {
        String prompt = SelfClaimPrompt.forTicket(ticket()).project("demo").build();

        assertFalse("no stage line without a stage", prompt.contains("Stage:"));
        assertFalse("no pipeline protocol without a stage", prompt.contains("PIPELINE PROTOCOL"));
        assertFalse("no advance tool without a stage", prompt.contains("task_advance"));
        assertFalse("no send-back tool without a stage", prompt.contains("task_send_back"));
        assertFalse("no clarification loop without a stage (the store rejects it)",
                prompt.contains("clarification:"));
    }

    // ---- U-079: upstream injection - top-down coverage delivered by the harness ----

    /** A governing epic-chain definition fixture (pure; no store, no clock). */
    private static Task governing(String id, String stage, String status, String... refs) {
        Task u = new Task();
        u.id = id;
        u.stage = stage;
        u.status = status;
        for (String ref : refs) {
            u.artifacts.add(new Task.Artifact("doc", ref, null, null, null));
        }
        return u;
    }

    @Test
    public void upstreamSectionDeliversGoverningArtifacts() {
        Task t = ticket();
        t.stage = "implementation";

        String prompt = SelfClaimPrompt.forTicket(t).project("demo")
                .upstream(List.of(
                        governing("U-010", "requirements", "done", "docs/requirements/x.md"),
                        governing("U-011", "architecture", "in-review", "docs/arch.md")))
                .build();

        assertTrue(prompt.contains("UPSTREAM DEFINITIONS (cover these):"));
        assertTrue(prompt.contains("- U-010 \u00b7 requirements \u00b7 done \u00b7 docs/requirements/x.md"));
        assertTrue(prompt.contains("- U-011 \u00b7 architecture \u00b7 in-review \u00b7 docs/arch.md"));
    }

    @Test
    public void artifactlessGoverningDefinitionBlocksWorkingBare() {
        Task t = ticket();
        t.stage = "implementation";

        String prompt = SelfClaimPrompt.forTicket(t).project("demo")
                .upstream(List.of(governing("U-012", "architecture", "done")))
                .build();

        assertTrue(prompt.contains(
                "GOVERNING DEFINITION MISSING: U-012 (architecture) claims done but has no artifact."));
        assertTrue(prompt.contains("Do NOT work bare"));
        assertTrue(prompt.contains("task_add_artifact"));
    }

    @Test
    public void onlyDoneClaimsAreFlaggedMissingAndTheListIsJoined() {
        Task t = ticket();
        t.stage = "design";

        String prompt = SelfClaimPrompt.forTicket(t).project("demo")
                .upstream(List.of(
                        governing("U-013", "system", "done"),
                        governing("U-014", "requirements", "done"),
                        governing("U-015", "system", "product-backlog")))
                .build();

        assertTrue(prompt.contains("GOVERNING DEFINITION MISSING: U-013 (system), U-014 (requirements)"));
        assertFalse("an in-flight upstream is context, not missing", prompt.contains("U-015 ("));
    }

    @Test
    public void emptyUpstreamForcesTopDownBackfillExceptForBugsAndFlowedTickets() {
        Task feature = ticket();
        feature.stage = "implementation";
        String prompt = SelfClaimPrompt.forTicket(feature).project("demo").upstream(List.of()).build();
        assertTrue(prompt.contains("NO GOVERNING DEFINITION"));
        assertTrue(prompt.contains("Top-down first"));

        Task bug = ticket();
        bug.stage = "implementation";
        bug.type = "bug";
        String bugPrompt = SelfClaimPrompt.forTicket(bug).project("demo").upstream(List.of()).build();
        assertFalse("bare bugfixes are legitimate", bugPrompt.contains("NO GOVERNING DEFINITION"));

        Task flowed = ticket();
        flowed.stage = "implementation";
        flowed.history.add(new Task.HistoryEvent(java.time.Instant.EPOCH, "advanced to implementation", null));
        String flowedPrompt = SelfClaimPrompt.forTicket(flowed).project("demo").upstream(List.of()).build();
        assertFalse("own advance history proves the definitions were done",
                flowedPrompt.contains("NO GOVERNING DEFINITION"));
    }

    @Test
    public void upstreamSectionIsCappedAndDeterministic() {
        Task t = ticket();
        t.stage = "implementation";
        java.util.List<Task> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(governing(String.format("U-%03d", i), "requirements", "done", "docs/req" + i + ".md"));
        }

        String first = SelfClaimPrompt.forTicket(t).project("demo").upstream(many).build();
        String second = SelfClaimPrompt.forTicket(t).project("demo").upstream(many).build();

        assertEquals(first, second);
        assertTrue(first.contains("... 2 more (task_get for the rest)"));
        assertTrue(first.contains("- U-008 \u00b7"));
        assertFalse("beyond the cap stays out", first.contains("- U-009 \u00b7"));
    }

    @Test
    public void protocolCarriesCoverageAndVerdictLines() {
        Task t = ticket();
        t.stage = "design";

        String prompt = SelfClaimPrompt.forTicket(t).project("demo").build();

        assertTrue(prompt.contains("COVER the upstream"));
        assertTrue(prompt.contains("never by reflex"));
        assertTrue(prompt.contains("the gate/reviewer declares done"));
        assertTrue(prompt.contains("proven on the paired test-* stage"));
    }

    @Test
    public void stagelessPromptStaysFreeOfUpstreamAndCoverage() {
        String prompt = SelfClaimPrompt.forTicket(ticket()).project("demo")
                .upstream(List.of(governing("U-010", "requirements", "done", "docs/x.md"))).build();

        assertFalse("no upstream section without a stage", prompt.contains("UPSTREAM DEFINITIONS"));
        assertFalse("no coverage protocol without a stage", prompt.contains("COVER the upstream"));
    }

    // ---- U-082: the routing lines teach both loops ----

    @Test
    public void protocolRoutesFailuresBetweenTheHorizontalAndVerticalLoops() {
        Task t = ticket();
        t.stage = "test-design";

        String prompt = SelfClaimPrompt.forTicket(t).project("demo").build();

        assertTrue("the horizontal tool is taught", prompt.contains("task_report_horizontal"));
        assertTrue("failed validations go sideways", prompt.contains("HORIZONTALLY"));
        assertTrue("the pair is named", prompt.contains("PAIR stage"));
        assertTrue("missing/wrong upstream keeps the vertical route", prompt.contains("task_send_back"));
        assertTrue(prompt.contains("never by reflex"));
    }
}
