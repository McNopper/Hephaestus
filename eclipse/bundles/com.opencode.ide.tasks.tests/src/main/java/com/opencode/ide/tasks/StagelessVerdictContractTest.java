package com.opencode.ide.tasks;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * T-174 - the STAGELESS verdict lane's contract, pinned in BOTH carriages: the stage-pipeline skill
 * (the procedure the steward loads) and the arm-time tick-prompt template in ticker-setup.ps1 (the
 * parameterization the schedule carries - it enumerates the steps independently of the skill, so a
 * lane present in only one of them never runs). The lane exists because the stage loop is blind to
 * no-stage tickets by construction: step 1 takes "only this stage's tickets" and readiness reports
 * a no-stage ticket NOT_APPLICABLE, so step 3 never sees an {@code in-review} ticket without a
 * stage.
 */
public class StagelessVerdictContractTest {

  private static String read(String repoRelative) throws Exception {
    Path root = TestRepoRoot.find(".opencode/skills/stage-pipeline/SKILL.md");
    return Files.readString(root.resolve(repoRelative), StandardCharsets.UTF_8);
  }

  private static String flat(String text) {
    return text.replaceAll("\\s+", " ");
  }

  /**
   * The prompt is a PowerShell string-CONCATENATION block ({@code "… " + "…"}), so after the
   * whitespace collapse the seams still carry {@code " + "} debris - strip the delimiters first,
   * then flatten, and the fragments read as the one prompt string the schedule actually sends.
   */
  private static String flatPrompt(String text) {
    return flat(text.replace("\"", "").replace("+", ""));
  }

  @Test
  public void theSkillCarriesTheStagelessVerdictLane() throws Exception {
    String skill = flat(read(".opencode/skills/stage-pipeline/SKILL.md"));

    assertTrue(
        "the lane exists once per pass, placed between the stage loop and the close",
        skill.contains(
            "STAGELESS verdict lane (once per pass, after the stage loop, before step 6)"));
    assertTrue(
        "the blindness is documented where it was found (NOT_APPLICABLE + this-stage-only scan)",
        skill.contains("readiness reports a no-stage ticket `NOT_APPLICABLE`")
            && skill.contains("step 3 never sees them"));
    assertTrue(
        "the treatment mirrors step 3 exactly, incl. the review: human skip (owner's verdict)",
        skill.contains("skip a ticket whose `review` field is `human`")
            && skill.contains("spawn ONE read-only reviewer subagent"));
    assertTrue(
        "FAIL has no basis edge - it surfaces to the Product Owner at the next step 2",
        skill.contains("the blocker surfaces to the Product Owner on the next pass's step 2"));
    assertTrue(
        "UNCLEAR keeps the AGENTS unstaged rule (doubt stays as a comment)",
        skill.contains("the doubt stays as a comment on the `in-review` ticket"));
    assertTrue(
        "verdicts only: no division, no claim/kickoff for stageless tickets",
        skill.contains("this lane never claims one"));
  }

  @Test
  public void theTickPromptTemplateCarriesTheLaneToo() throws Exception {
    String script = flatPrompt(read(".opencode/skills/stage-pipeline/ticker-setup.ps1"));

    assertTrue(
        "the prompt enumerates the stageless verdict step before the close",
        script.contains("Once for the whole pass BEFORE the close: STAGELESS in-review tickets"));
    assertTrue(
        "the prompt states why the per-stage scan misses them",
        script.contains("the per-stage scan reports them NOT_APPLICABLE and never sees them"));
    assertTrue(
        "the prompt carries the same three verdict outcomes as step 3",
        script.contains("PASS: task_update done")
            && script.contains("FAIL: task_set_blocked with the reasons")
            && script.contains("UNCLEAR: the doubt stays as a comment"));
    assertTrue(
        "the prompt keeps the review: human skip (T-155) and the verdicts-only boundary",
        script.contains("tickets with review:human (the owner's verdict)")
            && script.contains("stageless tickets are never divided, claimed or kicked off"));
  }
}
