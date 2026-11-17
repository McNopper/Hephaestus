package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-105 - the governed /goal seam's store logic (AC1/AC3 + the engine-only completion rule of AC2):
 * the objective lands VERBATIM as a requirements-stage ticket; pause parks the goal ticket (never
 * blocked) with the open-descendant count; resume puts it back to the pump ({@code
 * product-backlog}); clear closes-or-splits WITHOUT stranding the pipeline - the split closes the
 * original with the continuation FILE as its artifact so every child's basis chain stays satisfied
 * (done + artifact, never an archived live base).
 */
public class GoalIntakeTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  /** A division child as the pipeline creates them: basis-linked, own stage. */
  private Task child(String basis, String stage, String title) {
    return store.create(
        "p",
        new TaskStore.CreateSpec(
            title,
            "",
            "task",
            "developer",
            "medium",
            0,
            List.of(),
            List.of(),
            null,
            "T",
            null,
            null,
            basis),
        stage);
  }

  @Test
  public void captureRecordsTheObjectiveVerbatimAsARequirementsTicket() {
    String objective = "Ship the whole thing\nincluding the docs";
    GoalIntake.Result result = GoalIntake.capture(store, "p", objective);

    assertTrue(result.notice(), result.notice().contains("goal captured as T-001"));
    assertTrue(
        "the notice states the engine-only completion rule",
        result.notice().contains("done ONLY by the engine's review pass"));
    assertEquals("T-001", result.goalId());

    Task goal = store.get("p", "T-001");
    assertEquals("requirements", goal.stage);
    assertEquals("objective verbatim as the definition artifact", objective, goal.description);
    assertEquals(
        "the first line is the title, tagged with the stage by the store (T-175)",
        "[requirements] Ship the whole thing",
        goal.title);
    assertTrue("discovery label", goal.labels.contains(GoalIntake.GOAL_LABEL));
    assertEquals("born into the pump, not dispatched from here", "product-backlog", goal.status);
  }

  @Test
  public void aBlankObjectivePrintsTheUsage() {
    GoalIntake.Result result = GoalIntake.capture(store, "p", "   ");
    assertTrue(result.notice(), result.notice().contains("usage: /goal <objective>"));
    assertEquals(null, result.goalId());
  }

  @Test
  public void pauseParksTheGoalAndCountsOpenDescendants_resumePutsItBackToThePump() {
    GoalIntake.Result captured = GoalIntake.capture(store, "p", "Reach the milestone");
    String goalId = captured.goalId();
    Task child1 = child(goalId, "system", "step one");
    child(child1.id, "design", "step two");
    Task doneChild = child(goalId, "test-implementation", "verification step");
    store.update("p", doneChild.id, Map.of("status", "done")); // verification: exempt from the gate

    GoalIntake.Result paused = GoalIntake.verb(store, "p", goalId, "pause");
    assertEquals("paused", store.get("p", goalId).status);
    assertTrue(
        "open descendants counted (child1 + grandchild, done excluded)",
        paused.notice().contains("2 open child ticket(s) keep running"));
    assertTrue(paused.notice().contains("disarm the ticker"));

    GoalIntake.Result resumed = GoalIntake.verb(store, "p", goalId, "resume");
    assertEquals("back to the pump", "product-backlog", store.get("p", goalId).status);
    assertTrue(resumed.notice().contains("back to the pump"));

    // idempotence guards
    assertEquals(
        "already paused", true, GoalIntake.verb(store, "p", goalId, "resume").goalId() != null);
    store.setPaused("p", goalId, "again", "chat");
    assertTrue(GoalIntake.verb(store, "p", goalId, "pause").notice().contains("already paused"));
  }

  @Test
  public void verbsWithoutARememberedGoalExplainWhatToDo() {
    GoalIntake.Result result = GoalIntake.verb(store, "p", null, "pause");
    assertTrue(result.notice(), result.notice().contains("/goal <objective> first"));
    assertEquals(null, result.goalId());
  }

  @Test
  public void clearWithoutOpenChildrenArchivesTheGoal() {
    GoalIntake.capture(store, "p", "Finished objective");
    GoalIntake.Result cleared = GoalIntake.verb(store, "p", "T-001", "clear");

    assertEquals(null, cleared.goalId()); // nothing to remember
    assertTrue(cleared.notice().contains("archived"));
    assertThrows(
        "the goal leaves the live store", RuntimeException.class, () -> store.get("p", "T-001"));
  }

  @Test
  public void clearWithOpenChildrenSplitsAndKeepsEveryBasisChainSatisfied() {
    String objective = "Half-done objective";
    GoalIntake.capture(store, "p", objective);
    Task child1 = child("T-001", "system", "remaining step");
    Task grandchild = child(child1.id, "design", "nested remaining step");

    GoalIntake.Result cleared = GoalIntake.verb(store, "p", "T-001", "clear");
    String continuation = cleared.goalId();

    // the continuation carries the objective verbatim and the discovery label
    Task cont = store.get("p", continuation);
    assertTrue(cont.title.contains("(continued)"));
    assertTrue(
        "objective carried verbatim into the continuation", cont.description.contains(objective));
    assertTrue(cont.labels.contains(GoalIntake.GOAL_LABEL));

    // the open tickets re-home under the continuation's epic (basis edges untouched)
    assertEquals(continuation, store.get("p", child1.id).epic);
    assertEquals(continuation, store.get("p", grandchild.id).epic);
    assertEquals("T-001", store.get("p", child1.id).basis);

    // the original CLOSES (done + the continuation file as its artifact) - never an archived
    // live basis, so the children's readiness stays decidable
    Task original = store.get("p", "T-001");
    assertEquals("done", original.status);
    assertTrue(
        "the artifact points at the continuation's store file",
        original.artifacts.stream().anyMatch(a -> a.ref().endsWith(continuation + ".md")));
    assertTrue(cleared.notice().contains("2 open ticket(s) re-homed"));
  }
}
