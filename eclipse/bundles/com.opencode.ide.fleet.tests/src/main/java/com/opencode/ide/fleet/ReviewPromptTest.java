package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for the {@link ReviewPrompt} builder: the ticket facts, the
 * recorded artifacts, and the full REVIEW PROTOCOL (judge the criteria,
 * establish the merged commit's CI status, end with one verdict line, stay
 * read-only) must appear, deterministically, in a compact prompt.
 */
public class ReviewPromptTest {

    private static Task ticket() {
        Task t = new Task();
        t.id = "U-9";
        t.title = "Harden the gate";
        t.description = "Do the thing.";
        t.role = "developer";
        t.stage = "implementation";
        t.type = "story";
        t.acceptanceCriteria.addAll(List.of("ac one", "ac two"));
        t.artifacts.add(new Task.Artifact("git", "opencode/U-9", "fleet branch merged back",
                "fleet", Instant.parse("2026-09-18T10:00:00Z")));
        return t;
    }

    @Test
    public void promptContainsTicketFactsAndArtifacts() {
        String prompt = ReviewPrompt.forTicket(ticket()).project("demo").build();

        assertTrue(prompt.contains("Review ticket U-9: Harden the gate"));
        assertTrue(prompt.contains("Project: demo"));
        assertTrue(prompt.contains("Role: developer"));
        assertTrue(prompt.contains("Stage: implementation"));
        assertTrue(prompt.contains("Type: story"));
        assertTrue(prompt.contains("1. ac one"));
        assertTrue(prompt.contains("2. ac two"));
        assertTrue("the recorded artifacts are the judge's evidence",
                prompt.contains("- git: opencode/U-9 — fleet branch merged back"));
    }

    @Test
    public void promptContainsTheReviewProtocol() {
        String prompt = ReviewPrompt.forTicket(ticket()).project("demo").build();

        assertTrue(prompt.contains("REVIEW PROTOCOL:"));
        assertTrue("judge against the recorded artifacts + merged result",
                prompt.contains("Judge EVERY acceptance criterion"));
        assertTrue("the CI status of the merged commit is part of the judgment",
                prompt.contains("CI status of the merged commit"));
        assertTrue("the three outcomes are named", prompt.contains("VERDICT: PASS"));
        assertTrue(prompt.contains("VERDICT: FAIL"));
        assertTrue(prompt.contains("VERDICT: UNCLEAR"));
        assertTrue("the reviewer must not move the ticket itself",
                prompt.contains("READ-ONLY"));
        assertTrue(prompt.contains("no task_update, task_advance or task_send_back"));
        assertTrue(prompt.contains("task_get(\"U-9\")"));
    }

    @Test
    public void promptIsDeterministicAndCompact() {
        String first = ReviewPrompt.forTicket(ticket()).project("demo").build();
        String second = ReviewPrompt.forTicket(ticket()).project("demo").build();

        assertEquals(first, second);
        assertTrue("prompt should stay under ~40 lines, got " + first.split("\n", -1).length,
                first.split("\n", -1).length <= 40);
    }

    @Test
    public void blankPartsRenderPlaceholders() {
        Task t = new Task();
        t.id = "T-1";
        t.title = "tiny";
        t.role = "tester";

        String prompt = ReviewPrompt.forTicket(t).project("p").build();

        assertTrue(prompt.contains("(none)"));
        assertFalse("no stage line without a stage", prompt.contains("Stage:"));
    }
}
