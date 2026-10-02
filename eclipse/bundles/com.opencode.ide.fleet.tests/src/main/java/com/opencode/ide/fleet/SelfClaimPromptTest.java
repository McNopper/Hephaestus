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
        assertTrue("staged prompt should stay under ~40 lines, got " + lineCount(prompt),
                lineCount(prompt) <= 40);
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
}
