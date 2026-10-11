package com.opencode.ide.fleet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;
import java.util.List;
import org.junit.Test;

/**
 * Contract tests for the staged ({@link VStages}) prompts of {@link SelfClaimPrompt}: EVERY one of
 * the ten stages must name its stage and its skill, carry the PIPELINE PROTOCOL (task_advance /
 * task_send_back / never do the next stage's work), a stage-less legacy ticket must carry neither
 * the stage line nor the protocol, and the advance-ordering question (in-review BEFORE
 * task_advance) is pinned as a documented finding on the actual prompt text - never silently
 * assumed.
 */
public class StagedPromptContractTest {

  private static Task stagedTicket(String stage) {
    Task t = new Task();
    t.id = "V-001";
    t.title = "Fix the widget";
    t.description = "Do the thing.";
    t.type = "task";
    t.role = stage == null ? "developer" : VStages.roleOf(stage);
    t.stage = stage;
    t.acceptanceCriteria.addAll(List.of("ac one", "ac two"));
    return t;
  }

  private static String promptFor(String stage) {
    return SelfClaimPrompt.forTicket(stagedTicket(stage)).project("demo").build();
  }

  /** The PIPELINE PROTOCOL block only (from its heading up to INSTRUCTIONS). */
  private static String protocolBlock(String prompt) {
    int start = prompt.indexOf("PIPELINE PROTOCOL:");
    int end = prompt.indexOf("INSTRUCTIONS:");
    return prompt.substring(start, end);
  }

  @Test
  public void everyStageNamesItsStageAndItsSkill() {
    for (String stage : VStages.STAGES) {
      String prompt = promptFor(stage);
      String skill = VStages.skillOf(stage);
      assertTrue(stage + " must name its stage", prompt.contains("Stage: " + stage));
      assertTrue(
          stage + " must name its skill " + skill,
          prompt.contains("work in the " + skill + " skill"));
      assertTrue(
          stage + " must combine stage and skill on one line",
          prompt.contains("Stage: " + stage + " — work in the " + skill + " skill"));
    }
  }

  @Test
  public void everyStageCarriesThePipelineProtocolTools() {
    for (String stage : VStages.STAGES) {
      String prompt = promptFor(stage);
      assertTrue(stage + " carries the protocol block", prompt.contains("PIPELINE PROTOCOL:"));
      assertTrue(stage + " must mention the advance tool", prompt.contains("task_advance"));
      assertTrue(stage + " must mention the send-back tool", prompt.contains("task_send_back"));
      assertTrue(
          stage + " must forbid the next stage's work",
          prompt.contains("never do the next stage's work yourself"));
    }
  }

  @Test
  public void protocolConfinesEveryStageToItsOwnSkill() {
    for (String stage : VStages.STAGES) {
      String prompt = promptFor(stage);
      assertTrue(
          stage + " scopes the work to its skill",
          prompt.contains(
              "Do ONLY this stage's work (the " + VStages.skillOf(stage) + " skill defines it)"));
      assertTrue(
          stage + " names the stage-role dispatch",
          prompt.contains("The fleet dispatches you by stage role."));
    }
  }

  @Test
  public void stagelessLegacyPromptHasNeitherStageLineNorProtocol() {
    String prompt = promptFor(null);

    assertFalse("no stage line without a stage", prompt.contains("Stage:"));
    assertFalse("no pipeline protocol without a stage", prompt.contains("PIPELINE PROTOCOL"));
    assertFalse("no advance tool without a stage", prompt.contains("task_advance"));
    assertFalse("no send-back tool without a stage", prompt.contains("task_send_back"));
    assertFalse(
        "no stage-role dispatch line without a stage",
        prompt.contains("The fleet dispatches you by stage role."));
  }

  @Test
  public void unknownStageKeepsProtocolButNamesNoSkill() {
    // Hand-edited store files can carry a non-canonical stage; the prompt
    // must degrade to "the stage's skill" rather than printing "null".
    String prompt = promptFor("bogus");

    assertTrue(prompt.contains("Stage: bogus"));
    assertFalse("no skill suffix for an unknown stage", prompt.contains("work in the "));
    assertTrue(prompt.contains("PIPELINE PROTOCOL:"));
    assertTrue(
        "degraded skill reference, never 'null'",
        prompt.contains("Do ONLY this stage's work (the stage's skill defines it)"));
  }

  /**
   * The advance ORDERING contract (was a flagged gap, now fixed in SelfClaimPrompt): {@code
   * TaskStore.advance} only accepts in-review/done, so the PIPELINE PROTOCOL itself must spell out
   * "FIRST in-review, THEN task_advance" — an agent following the protocol literally while still
   * in-progress would otherwise get its advance rejected.
   */
  @Test
  public void advanceOrderingIsSpelledOutInTheProtocol() {
    String prompt = promptFor("design");

    assertTrue("the advance tool is named", prompt.contains("task_advance"));
    String protocol = protocolBlock(prompt);
    assertTrue("the protocol gates task_advance on in-review", protocol.contains("in-review"));
    assertTrue(
        "the protocol orders the status update FIRST",
        protocol.contains("FIRST move the ticket to review"));
    assertTrue(
        "the protocol then orders task_advance", protocol.contains("THEN call task_advance"));
    assertTrue("the protocol states the gate", protocol.contains("rejected from any other status"));
  }

  @Test
  public void vTipStageHasNoAdvanceGuidance() {
    String prompt = promptFor(VStages.last());

    assertTrue(
        "the V tip is named with its no-advance rule",
        prompt.contains("is the last stage (the V tip): there is no task_advance from it"));
  }

  @Test
  public void sentBackTicketCarriesTheSentBackNote() {
    // a ticket whose history records a send-back tells the next agent to
    // address the stated reason (the store delivered it blocked; the blocker
    // was cleared to relaunch)
    Task ticket = stagedTicket("requirements");
    ticket.history.add(
        new Task.HistoryEvent(null, "sent back to requirements: unclear goal", "fleet"));
    String prompt = SelfClaimPrompt.forTicket(ticket).project("demo").build();

    assertTrue(prompt.contains("SENT BACK from a later stage"));
    assertTrue(prompt.contains("Address the stated reason"));

    // and the regression side: a never-sent-back ticket has no note
    assertFalse(promptFor("requirements").contains("SENT BACK"));
  }

  @Test
  public void stagedPromptStaysDeterministicPerStage() {
    for (String stage : VStages.STAGES) {
      assertTrue(stage + " renders deterministically", promptFor(stage).equals(promptFor(stage)));
    }
  }
}
