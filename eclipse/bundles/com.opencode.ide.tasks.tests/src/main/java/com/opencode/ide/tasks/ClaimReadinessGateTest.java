package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T-226 (S1): {@code task_claim} filters its candidates by the same {@link StageReadiness} verdict
 * the ticker's step-1 scan reports, evaluated inside the claim transaction (the lock serializes the
 * verdict and the claim - no scan/claim race).
 *
 * <ul>
 *   <li>a BASIS-linked candidate gated by its verdict ({@code WAIT_UPSTREAM} / {@code STALE}) is
 *       skipped - the S1 split: birth's {@code basisSatisfied} counts an in-review basis with no
 *       artifact check while readiness requires the artifact (U-081), so such a child is {@code
 *       status: ready} yet must not be scooped;
 *   <li>{@code READY} still claims (the gate opens when the artifact lands);
 *   <li>a BASIS-LESS candidate keeps its plain role-claim whatever its flow-history verdict reads -
 *       no derivation gate, and T-220's doubt-loop fixture ({@code
 *       TaskToolsDispatchTest.unclearVerdictRoundTripsOncePerStageVisitThenEscalatesThroughTheTool})
 *       pins a fresh staged ticket being claimed through this verb;
 *   <li>{@code STALE} re-runs stay reachable through the ticker's targeted claim (step 4b: status +
 *       assignee in one {@code task_update}), which this filter deliberately does not touch.
 * </ul>
 */
public class ClaimReadinessGateTest {

  private static final String PROJECT = "p";

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  /** Distinct millisecond timestamps: STALE compares strictly-after. */
  private static void tick() throws InterruptedException {
    Thread.sleep(20);
  }

  /** The production readiness row's kind for one ticket (the ticker's step-1 scan). */
  private StageReadiness.Kind kindOf(String id) {
    for (Map<String, Object> row : store.readiness(PROJECT)) {
      if (id.equals(row.get("id"))) {
        return StageReadiness.Kind.valueOf((String) row.get("kind"));
      }
    }
    throw new AssertionError("no readiness row for " + id);
  }

  /** The upstream parent: a different role, so the developer claim never sees it. */
  private Task architectParent(String stage) {
    Task parent =
        store.create(
            PROJECT,
            new TaskStore.CreateSpec(
                "upstream work", "", "task", "architect", "medium", 0, List.of(), List.of(), "T"),
            stage);
    store.update(PROJECT, parent.id, Map.of("status", "in-review"));
    return parent;
  }

  /** The child created WITH its basis at birth - the hazard's exact shape (born ready). */
  private Task developerChildBasis(String basis, String stage) {
    Task child =
        store.create(
            PROJECT,
            new TaskStore.CreateSpec(
                "downstream work",
                "",
                "task",
                "developer",
                "medium",
                0,
                List.of(),
                List.of(),
                "T",
                null,
                null,
                basis),
            stage);
    assertEquals("born ready: basisSatisfied counts the in-review verdict", "ready", child.status);
    return child;
  }

  @Test
  public void claimRefusesTheWaitUpstreamChildBornReadyAndClaimsItOnceTheArtifactLands() {
    Task parent = architectParent("system");
    Task child = developerChildBasis(parent.id, "design");

    assertEquals(
        "readiness gates the child on the missing artifact (U-081)",
        StageReadiness.Kind.WAIT_UPSTREAM,
        kindOf(child.id));
    assertNull(
        "the blind role-claim must not scoop a derivation-gated child (S1)",
        store.claim(PROJECT, "developer", null, "worker"));

    // the gate opens with the recorded artifact: READY is claimable
    store.addArtifact(
        PROJECT, parent.id, "doc", "docs/system-product.md", "stage product", "fixture");
    assertEquals(StageReadiness.Kind.READY, kindOf(child.id));
    Task claimed = store.claim(PROJECT, "developer", null, "worker");
    assertNotNull("READY must still be claimable", claimed);
    assertEquals(child.id, claimed.id);
  }

  @Test
  public void claimSkipsTheGatedChildAndHandsTheNextClaimableTicket() {
    Task parent = architectParent("system");
    Task child = developerChildBasis(parent.id, "design");
    Task plain = store.create(PROJECT, TaskStore.CreateSpec.of("plain stageless work"));

    Task claimed = store.claim(PROJECT, "developer", null, "worker");

    assertEquals(
        "the claimable ticket wins - gated rows are skipped, not blocking", plain.id, claimed.id);
    assertNull(
        "nothing claimable is left (the child stays gated)",
        store.claim(PROJECT, "developer", null, null));
    assertEquals("the gated child is untouched", "ready", store.get(PROJECT, child.id).status);
  }

  @Test
  public void staleChildIsNotClaimableByRoleButStaysReachableByTheTargetedClaim() throws Exception {
    Task parent =
        store.create(
            PROJECT,
            new TaskStore.CreateSpec(
                "upstream work", "", "task", "architect", "medium", 0, List.of(), List.of(), "T"),
            "system");
    store.addArtifact(
        PROJECT, parent.id, "doc", "docs/system-product.md", "stage product", "fixture");
    store.update(PROJECT, parent.id, Map.of("status", "done"));
    tick();
    Task child = developerChildBasis(parent.id, "design");
    assertEquals(
        "basis done + artifact: the child starts READY",
        StageReadiness.Kind.READY,
        kindOf(child.id));

    // the child runs (claim + release = history beyond "created" -> ranBefore), then the basis
    // moves AFTER that run - the STALE verdict (ASPICE consistency)
    Task started = store.claim(PROJECT, "developer", null, "worker");
    assertNotNull(started);
    assertEquals(child.id, started.id);
    store.release(PROJECT, child.id, "worker");
    tick();
    store.addArtifact(
        PROJECT, parent.id, "doc", "docs/system-product-v2.md", "stage product v2", "fixture");
    assertEquals(StageReadiness.Kind.STALE, kindOf(child.id));

    assertNull(
        "a blind role-claim never scoops a re-run - step 4b owns STALE with its brief",
        store.claim(PROJECT, "developer", null, "worker"));

    // the ticker's step-4b targeted claim (status + assignee in one task_update) is untouched
    Task targeted =
        store.update(
            PROJECT, child.id, Map.of("status", "in-progress", "assignee", "ticker-v:" + child.id));
    assertEquals("the STALE re-dispatch path still works", "in-progress", targeted.status);
    assertEquals("ticker-v:" + child.id, targeted.assignee);
  }

  @Test
  public void basisLessStagedTicketKeepsItsRoleClaimWhateverTheFlowVerdictReads() {
    Task fresh =
        store.create(PROJECT, TaskStore.CreateSpec.of("fresh staged work"), "implementation");

    assertEquals(
        "the legacy flow-history verdict reads WAIT_UPSTREAM for a basis-less mid-V ticket",
        StageReadiness.Kind.WAIT_UPSTREAM,
        kindOf(fresh.id));
    Task claimed = store.claim(PROJECT, "developer", null, "dev-1");
    assertNotNull(
        "a basis-less row has no derivation gate to wait on - T-220's loop fixture"
            + " (TaskToolsDispatchTest claims exactly this shape) pins it",
        claimed);
    assertEquals(fresh.id, claimed.id);
  }
}
