package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.VStages;
import org.junit.Test;

/**
 * U-058 (U-029 FR-004/FR-005/FR-010): the launch path honors a declared no-impact stage by
 * RECORDING A STAGE PASS instead of dispatching a worker - zero sessions, no claim, no worktree,
 * the "stage N passed: reason" history event - while a ticket without an active declaration still
 * launches exactly as before (the dispatch path itself is untouched).
 */
public class StagePassThroughTest extends FleetTestHarness {

  @Test
  public void declaredNoImpactStagePassesWithoutLaunchingASession() {
    String id = stagedTicket("architecture");
    store.addComment(
        PROJECT,
        id,
        "pass-declare: architecture - no architecture impact: local change",
        "architect");

    FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

    assertEquals("the dispatch settles as a stage pass", FleetJob.State.PASSED, job.state());
    assertEquals("ZERO sessions launched", 0, client.createdTitles.size());
    assertTrue("no prompt was ever sent", client.sentRequests.isEmpty());
    assertTrue("no worktree was branched", worktrees.createdTaskIds.isEmpty());
    assertTrue("no claim commit ran", worktrees.commitMessages.isEmpty());

    Task after = store.get(PROJECT, id);
    assertEquals("the stage moved on exactly like an advance", "design", after.stage);
    assertEquals(VStages.roleOf("design"), after.role);
    assertEquals("product-backlog", after.status);
    assertNull("the assignee is cleared", after.assignee);
    assertFalse(after.isBlocked());
    JsonArray history = after.toJson().getAsJsonArray("history");
    JsonObject last = history.get(history.size() - 1).getAsJsonObject();
    assertEquals(
        "stage 3 passed: no architecture impact: local change", last.get("action").getAsString());
    assertEquals(
        "the pass carries the declaring comment's author",
        "architect",
        last.get("by").getAsString());
    assertFalse(
        "nothing was claimed, so no launch comment exists",
        after.comments.stream()
            .anyMatch(c -> c.text() != null && c.text().contains("launched into worktree")));
  }

  @Test
  public void aWithdrawnDeclarationLaunchesTheDeclaredStageNormally() {
    String id = stagedTicket("architecture");
    store.addComment(PROJECT, id, "pass-declare: architecture - no impact", "architect");
    store.addComment(PROJECT, id, "pass-withdraw: architecture", "pm");
    sessionCompletes();

    FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

    assertEquals(FleetJob.State.MERGED, job.state());
    assertEquals(
        "the withdrawn declaration launched exactly one session", 1, client.createdTitles.size());
    Task after = store.get(PROJECT, id);
    assertEquals("still at the declared stage", "architecture", after.stage);
    assertEquals("in-review", after.status);
    assertFalse("no pass was recorded", after.toJson().toString().contains("passed:"));
  }

  @Test
  public void aTicketWithoutADeclarationStillLaunches() {
    String id = stagedTicket("architecture");
    sessionCompletes();

    FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

    assertEquals(FleetJob.State.MERGED, job.state());
    assertEquals("the normal dispatch ran its session", 1, client.createdTitles.size());
    Task after = store.get(PROJECT, id);
    assertEquals("architecture", after.stage);
    assertEquals("in-review", after.status);
  }

  /** FR-003: a stale declaration at the V tip never passes - the tip always dispatches. */
  @Test
  public void aDeclaredVTipStillDispatchesBecauseTheTipNeverPasses() {
    String id = stagedTicket(VStages.last());
    store.addComment(
        PROJECT, id, "pass-declare: test-requirements - nothing left to verify", "tester");
    sessionCompletes();

    FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

    assertEquals(FleetJob.State.MERGED, job.state());
    assertEquals("the tip stage did not move", VStages.last(), store.get(PROJECT, id).stage);
    assertEquals(1, client.createdTitles.size());
  }

  /** The shared fixture's session-completion script (copied shape: see {@link TaskFleetTest}). */
  private void sessionCompletes() {
    client.replyOnSend = "done";
    client.sessionType = "idle";
  }
}
