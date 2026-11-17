package com.opencode.ide.tasks;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * T-155/AC2 - the SKILL contract the engine's verdict step relies on: the tick's IN-REVIEW →
 * VERDICT step SKIPS tickets whose {@code review} field is {@code human} (first/last-stage tickets
 * reserved for the OWNER's verdict - the engine never verdicts them), while {@code review:
 * ai}/absent keeps the engine path, and AGENTS.md documents the field for the chat-first plane.
 */
public class ReviewHumanSkillContractTest {

  @Test
  public void theTickReviewStepSkipsHumanReviewTickets() throws Exception {
    Path root = TestRepoRoot.find(".opencode/skills/stage-pipeline/SKILL.md", "AGENTS.md");
    String skill =
        Files.readString(
                root.resolve(".opencode/skills/stage-pipeline/SKILL.md"), StandardCharsets.UTF_8)
            .replaceAll("\\s+", " ");

    assertTrue(
        "the verdict step names the skip condition", skill.contains("`review` field is `human`"));
    assertTrue(
        "the skip reserves first/last-stage tickets for the OWNER's verdict",
        skill.contains("reserved for the OWNER's verdict - the engine never verdicts them"));
    assertTrue(
        "the engine path is unchanged for ai/absent",
        skill.contains("`review: ai` or absent keeps this engine path"));
  }

  @Test
  public void agentsDocumentsTheReviewFieldForTheChatPlane() throws Exception {
    Path root = TestRepoRoot.find("AGENTS.md");
    String agents = Files.readString(root.resolve("AGENTS.md"), StandardCharsets.UTF_8);

    assertTrue("AGENTS names the field", agents.contains("review: human"));
    assertTrue(
        "AGENTS states the first/last-stage constraint", agents.contains("first/last stage only"));
    assertTrue("AGENTS states who verdicts", agents.contains("the engine's review step skips it"));
  }
}
