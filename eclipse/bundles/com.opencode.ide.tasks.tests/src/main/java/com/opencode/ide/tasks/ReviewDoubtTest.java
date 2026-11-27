package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * B-007 FR-009..FR-012 (AC-003, AC-004): reviewer doubt round-trips to the originator - the ticket
 * returns to its OWN stage's backlog for exactly one retry per stage visit, recorded as an
 * attributable comment plus a history marker; a recursing doubt inside the visit escalates to
 * blocked (blocked = needs-a-human, reached only after the originator had its attempt). A stage
 * transition resets the budget (Q-003/C-004). Also pins FR-006's store-side evidence window.
 */
public class ReviewDoubtTest {

  private static final String PROJECT = "p";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  private Task staged(String stage) {
    Task t =
        store.create(
            PROJECT,
            new TaskStore.CreateSpec(
                "Stage work",
                "Do the thing.",
                "task",
                VStages.roleOf(stage),
                "high",
                3,
                List.of("ac one"),
                List.of(),
                "D"),
            stage);
    // U-080: definition-stage exits require a recorded product
    store.addArtifact(
        PROJECT, t.id, "doc", "docs/" + stage + "-product.md", "stage product", "fixture");
    return t;
  }

  @Test
  public void aFirstDoubtRoutesToTheStageBacklogForOneRetry() {
    Task t = staged("system");

    Task after = store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");

    assertFalse("doubt is never the first-outcome block (FR-011)", after.isBlocked());
    assertEquals(
        "the stage keeps the originator (FR-009: the stage's own backlog)", "system", after.stage);
    assertEquals("ready", after.status);
    assertNull(after.assignee);
    assertTrue(
        after.history.stream()
            .anyMatch(
                h ->
                    h.action()
                            .equals("review doubt retry (1/1) for stage system: cannot determine X")
                        && "reviewer".equals(h.by())));
    assertTrue(
        "FR-012: attributable comment records the doubt + retry",
        after.comments.stream()
            .anyMatch(
                c -> "reviewer".equals(c.by()) && c.text().contains("review doubt retry (1/1)")));
  }

  @Test
  public void aRecurringDoubtBlocksOnlyAfterTheRetryIsConsumed() {
    Task t = staged("system");
    store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");

    Task after = store.routeReviewDoubt(PROJECT, t.id, "still cannot determine X", "reviewer");

    assertTrue("FR-010: blocked is the needs-a-human signal after the retry", after.isBlocked());
    assertTrue(
        after.blocker,
        after.blocker.startsWith(
            "review doubt unresolved after "
                + TaskStore.REVIEW_DOUBT_RETRY_LIMIT
                + " originator retries: still cannot determine X"));
    assertTrue(
        after.history.stream()
            .anyMatch(h -> h.action().startsWith("review doubt escalated to NEEDS-HUMAN")));
  }

  // U-110: the C-004 per-visit budget reset rode the retired stage transitions; a stationary
  // ticket's repeated doubts escalate to NEEDS-HUMAN after the limit (covered by the
  // escalation test above). The premise of a fresh budget per stage visit is retired with the
  // wandering model.

  @Test
  public void anUnstagedTicketCannotHostADoubtRetry() {
    Task t =
        store.create(
            PROJECT,
            new TaskStore.CreateSpec(
                "Unstaged work",
                "d",
                "task",
                "developer",
                "high",
                2,
                List.of("ac"),
                List.of(),
                "D"));
    try {
      store.routeReviewDoubt(PROJECT, t.id, "doubt", "reviewer");
      throw new AssertionError("expected Invalid for an unstaged ticket");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("no stage"));
    }
  }

  @Test
  public void blankDoubtReasonsAreRejected() {
    Task t = staged("design");
    try {
      store.routeReviewDoubt(PROJECT, t.id, "  ", "reviewer");
      throw new AssertionError("expected Invalid for a blank reason");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage().contains("reason"));
    }
  }

  // T-226 (T-225 review follow-up 1): the terminal guard routeReviewDoubt was missing - both of
  // its status writes (the ready retry and the escalation's enterBlocked) must never EXIT a
  // terminal verdict, judged by T-225's verdictStatus so a chain-blocked hop reads resume_to.

  @Test
  public void aDoneTicketCannotTakeAReviewDoubt() {
    Task t = staged("design");
    store.update(PROJECT, t.id, Map.of("status", "done"));

    try {
      store.routeReviewDoubt(PROJECT, t.id, "still doubtful", "reviewer");
      throw new AssertionError("expected Invalid for a done ticket");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(t.id));
      assertTrue(expected.getMessage(), expected.getMessage().contains("'done'"));
      assertTrue(expected.getMessage(), expected.getMessage().contains("terminal"));
    }
    assertEquals(
        "the terminal verdict survives the refusal", "done", store.get(PROJECT, t.id).status);
    assertFalse(
        "no doubt round-trip was recorded",
        store.get(PROJECT, t.id).history.stream()
            .anyMatch(h -> h.action().startsWith("review doubt")));
  }

  @Test
  public void aNotImplementedTicketCannotTakeAReviewDoubt() {
    Task t = staged("design");
    store.update(PROJECT, t.id, Map.of("status", "not-implemented"));

    try {
      store.routeReviewDoubt(PROJECT, t.id, "still doubtful", "reviewer");
      throw new AssertionError("expected Invalid for a not-implemented ticket");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("not-implemented"));
    }
    assertEquals("not-implemented survives", "not-implemented", store.get(PROJECT, t.id).status);
  }

  @Test
  public void aPausedTicketCannotTakeAReviewDoubt() {
    Task t = staged("design");
    store.setPaused(PROJECT, t.id, "branch maintenance", "pm");

    try {
      store.routeReviewDoubt(PROJECT, t.id, "doubt on a parked ticket", "reviewer");
      throw new AssertionError("expected Invalid for a paused ticket");
    } catch (TaskStore.Invalid expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("paused"));
      assertTrue(expected.getMessage(), expected.getMessage().contains("resume"));
    }
    assertEquals(
        "the dependency wait survives - only the resume path moves paused",
        "paused",
        store.get(PROJECT, t.id).status);
  }

  @Test
  public void aChainBlockedDoneHopIsJudgedByItsResumeToVerdict() {
    Task upstream = staged("architecture");
    store.update(PROJECT, upstream.id, Map.of("status", "done"));
    Task child = staged("design");
    store.update(PROJECT, child.id, Map.of("basis", upstream.id));
    // T-221: setBlocked's chain walk re-opens the done hop - status blocked, verdict in resume_to
    store.setBlocked(PROJECT, child.id, "upstream needs rework", "reviewer");
    assertEquals("blocked", store.get(PROJECT, upstream.id).status);
    assertEquals("done", store.get(PROJECT, upstream.id).resumeTo);

    try {
      store.routeReviewDoubt(PROJECT, upstream.id, "doubt on a chain hop", "reviewer");
      throw new AssertionError("expected Invalid for a chain-blocked done hop");
    } catch (TaskStore.Invalid expected) {
      assertTrue(
          "the refusal judges resume_to, not the blocked status: " + expected.getMessage(),
          expected.getMessage().contains("'done'"));
    }
    assertEquals(
        "the chain hop keeps its T-221 blocked state (ChainBlockTest's pin)",
        "blocked",
        store.get(PROJECT, upstream.id).status);
    assertEquals("done", store.get(PROJECT, upstream.id).resumeTo);
  }

  @Test
  public void anInReviewTicketStillRoundTripsItsDoubtToTheOriginator() {
    Task t = staged("implementation");
    store.update(PROJECT, t.id, Map.of("status", "in-review"));

    Task after = store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");

    assertEquals("the normal round-trip is untouched", "ready", after.status);
    assertNull(after.assignee);
    assertTrue(
        after.history.stream().anyMatch(h -> h.action().startsWith("review doubt retry (1/1)")));
  }

  /** FR-006: the store-side evidence window sees run writes, never bookkeeping before it. */
  @Test
  public void bookkeepingBeforeTheWindowIsNotEvidence() {
    Task t = staged("requirements");

    assertFalse(
        "bookkeeping before the window is not evidence",
        store.hasStoreSideWrites(PROJECT, t.id, Instant.now()));
  }

  @Test
  public void storeSideWritesAreDetectedInsideTheRunWindow() {
    Task t = staged("requirements");
    Instant since = Instant.now().minusMillis(2);

    store.addComment(PROJECT, t.id, "requirements doc written via task_add_artifact", "executor");

    assertTrue(
        "a run-window store write IS stage evidence (FR-006)",
        store.hasStoreSideWrites(PROJECT, t.id, since));
  }
}
