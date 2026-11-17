package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Component test pinning the release-cadence contracts where they actually live: the ticker's
 * prompt text ({@code .opencode/skills/stage-pipeline/SKILL.md}, T-138's landing, tick step 6(d))
 * and its wording source ({@code docs/release-cadence-flow.md} §4.1). The evaluation executes as
 * prompt text, so the carriage IS the contract surface ([DSG §6]: skills are the carriage of
 * process change) - which is why T-121's design verdict note prescribes exactly this pin: "pin
 * DS-002's write schedule in a component test" (T-139's body carries the note).
 *
 * <p>Pinned here: DS-002's two-write schedule (digest line on EVERY evaluation + instance comment
 * at first determination and every flip), the four-step evidence query with the owner set and
 * content floor, the fixed evaluation order (after the mint, before the close), and the structural
 * C2→C4 denial (the four do-NOTs; no execution verb on the evaluator path). Whitespace is
 * normalized before matching - these are prose documents with reflowed lines, and the contract is
 * the text, not its line breaks.
 */
public class ReleaseCadenceContractTest {

  private static String skill;
  private static String flow;

  @BeforeClass
  public static void loadTheTwoCarriages() {
    Path root = repoRoot();
    skill = normalize(read(root.resolve(".opencode/skills/stage-pipeline/SKILL.md")));
    flow = normalize(read(root.resolve("docs/release-cadence-flow.md")));
  }

  private static String read(Path file) {
    try {
      assertTrue("carriage exists: " + file, Files.exists(file));
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new AssertionError("cannot read " + file + ": " + e, e);
    }
  }

  /** Reflows whitespace so line-wrapped prose can be matched as one stream of words. */
  private static String normalize(String text) {
    return text.replaceAll("\\s+", " ");
  }

  /** The repo root carries both carriages; one shared walk-up (TestRepoRoot), no local copy. */
  private static Path repoRoot() {
    return TestRepoRoot.find(
        ".opencode/skills/stage-pipeline/SKILL.md", "docs/release-cadence-flow.md");
  }

  /** The step-6(d) block: from the evaluation's start to the close step that follows it. */
  private static String evaluationBlock() {
    int start = skill.indexOf("(d) RELEASE-CADENCE EVALUATION");
    assertTrue("the evaluation rides tick step 6 as (d)", start >= 0);
    int end = skill.indexOf("(e) close the record", start);
    assertTrue("the close follows the evaluation as (e)", end > start);
    return skill.substring(start, end);
  }

  // ---------- DS-002's write schedule (the design verdict's prescribed pin) ----------

  @Test
  public void skillCarriesBothWritesOfTheWriteSchedule() {
    String block = evaluationBlock();

    assertTrue(
        "write 1: ONE digest line on EVERY evaluation",
        block.contains("pass digest on EVERY evaluation"));
    assertTrue(
        "write 1: the digest line's literal format",
        block.contains("release cadence: wave <W id>"));

    assertTrue("write 2: the instance comment verb", block.contains("task_add_comment"));
    assertTrue("write 2: at first determination", block.contains("first determination"));
    assertTrue("write 2: and at every flip", block.contains("every flip"));
    assertTrue(
        "the schedule is named as DS-002's (two writes, no state)",
        block.contains("DS-002 write schedule") && block.contains("two writes, no state"));
  }

  @Test
  public void flowWordingSourceCarriesTheSameSchedule() {
    assertTrue(
        "the digest line in the wording source", flow.contains("release cadence: wave <W id>"));
    assertTrue(
        "the instance comment in the wording source",
        flow.contains("a comment on W's acceptance instance"));
    assertTrue(
        "the evaluation is pinned between mint and close",
        flow.contains(
            "after the wave-completion recurrence mint, before the sprint record closes"));
  }

  // ---------- the four-step query (T-138 AC1) ----------

  @Test
  public void skillCarriesTheFourStepQueryVerbatim() {
    String block = evaluationBlock();

    assertTrue("step 1: the wave's member list", block.contains("task_list(sprint=<W id>)"));
    assertTrue("step 2: the instance + members read", block.contains("task_get"));
    assertTrue(
        "step 3: the owner set", block.contains("`owner`, `owner-directive`, `owner-acceptance`"));
    assertTrue("step 4: the content floor", block.contains("content floor"));
    assertTrue("the never-counts", block.contains("Never history, never assignees"));
    assertTrue("the conservative asymmetry (R4)", block.contains("ambiguity"));
    assertTrue("the outcome vocabulary", block.contains("PENDING"));
    assertTrue(
        "the no-ticker fallback exists (H7)",
        block.contains("kickoff assistant") && block.contains("fallback evaluator"));
  }

  @Test
  public void skillAndFlowAgreeOnTheFixedOrder() {
    String block = evaluationBlock();
    assertTrue(
        "after the mint so the fresh instance is visible",
        block.contains("AFTER the recurrence mint in (b)"));
    assertTrue("before the record closes", block.contains("BEFORE the record closes in (e)"));
    int stepC = skill.indexOf("(c) the sibling release-cadence");
    int stepD = skill.indexOf("(d) RELEASE-CADENCE EVALUATION");
    assertTrue(
        "the read-only seam note sits in step 6(c), before the evaluation",
        stepC > 0 && stepC < stepD && skill.contains("read-only across the seam"));
  }

  // ---------- the structural C2->C4 denial (T-138 AC2) ----------

  @Test
  public void theEvaluatorPathCarriesNoExecutionVerb() {
    String block = evaluationBlock();

    assertTrue("do-NOT 1 names the cut script", block.contains("Do NOT run `release.ps1`"));
    assertTrue(
        "do-NOT 4 refuses NEEDS-HUMAN escalation",
        block.contains("Do NOT escalate a PENDING REVIEW as NEEDS-HUMAN"));
    assertEquals(
        "release.ps1 appears exactly once in the block - inside its do-NOT",
        1,
        countOccurrences(block, "release.ps1"));
    assertFalse(
        "no shell/http execution verb on the evaluator path",
        block.contains("Invoke-RestMethod") || block.contains("Start-Process"));
    assertTrue(
        "the denial is stated structurally, not only as a list item",
        block.contains("no execution verb exists here"));
  }

  @Test
  public void skillMentionsReleasePs1OnlyInsideTheDoNot() {
    int total = countOccurrences(skill, "release.ps1");
    int block = countOccurrences(evaluationBlock(), "release.ps1");
    assertEquals("the whole skill mentions the cut exactly once", 1, total);
    assertEquals("and that one mention is inside the step-6(d) block", 1, block);
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int idx = haystack.indexOf(needle);
    while (idx >= 0) {
      count++;
      idx = haystack.indexOf(needle, idx + needle.length());
    }
    return count;
  }
}
