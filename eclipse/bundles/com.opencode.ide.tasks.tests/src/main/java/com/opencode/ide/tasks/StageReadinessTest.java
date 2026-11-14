package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The pure readiness function over real store snapshots for the linked stage pipeline (U-110): a
 * ticket with a {@code basis} waits on exactly its basis ticket - WAIT_UPSTREAM until the basis is
 * done/in-review AND carries its product (U-081), STALE when the basis changed after the ticket ran
 * (strictly-after timestamps), READY otherwise, NOT_APPLICABLE when done. Entry-stage tickets
 * (requirements) without a basis are READY; tickets without any stage are NOT_APPLICABLE. BLOCKED
 * outranks everything, RUNNING outranks the wait. Tickets WITHOUT a basis keep the legacy
 * epic-chain fallback (migration, design &sect;10) - pinned by the last two cases. Deterministic by
 * construction: every timestamp is a pinned constant.
 */
public class StageReadinessTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final Instant T1 = Instant.parse("2026-08-23T10:00:00.000Z");
  private static final Instant T2 = Instant.parse("2026-08-23T11:00:00.000Z");
  private static final Instant T3 = Instant.parse("2026-08-23T12:00:00.000Z");

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  /** A hand-built ticket (pure fixture; no store, no clock). */
  private static Task ticket(String id, String stage, String status) {
    Task t = new Task();
    t.id = id;
    t.stage = stage;
    t.status = status;
    t.createdAt = T1;
    t.updatedAt = T1;
    // U-081: a normal ticket carries its product - tests that need a bare
    // claim clear the list explicitly
    t.artifacts.add(new Task.Artifact("doc", "docs/" + stage + "-product.md", null, null, T1));
    return t;
  }

  private static Task basisOf(Task t, List<Task> pool) {
    for (Task u : pool) {
      if (u.id.equals(t.basis)) {
        return u;
      }
    }
    return null;
  }

  private static StageReadiness.Readiness verdictOf(Task t, List<Task> pool) {
    return StageReadiness.evaluate(List.of(t), pool).get(t.id);
  }

  @Test
  public void basisStillRunningWaits() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "IMP-0";
    Task impl = ticket("IMP-0", "implementation", "in-progress");
    var v = verdictOf(design, List.of(design, impl));
    assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, v.kind());
    assertTrue(v.reason().contains("IMP-0"));
  }

  @Test
  public void basisDoneWithoutArtifactWaitsForTheProduct() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "SYS-1";
    Task sys = ticket("SYS-1", "system", "done");
    sys.artifacts.clear(); // the bare claim: U-081
    var v = verdictOf(design, List.of(design, sys));
    assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, v.kind());
    assertTrue(v.reason().contains("no artifact"));
  }

  @Test
  public void basisDoneSatisfiesTheTicket() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "SYS-1";
    Task sys = ticket("SYS-1", "system", "done");
    var v = verdictOf(design, List.of(design, sys));
    assertEquals(StageReadiness.Kind.READY, v.kind());
    assertTrue(v.reason().contains("SYS-1"));
  }

  @Test
  public void doneTicketWithFreshInputsIsNotApplicable() {
    Task design = ticket("D-1", "design", "done");
    design.basis = "SYS-1";
    design.updatedAt = T3;
    Task sys = ticket("SYS-1", "system", "done");
    var v = verdictOf(design, List.of(design, sys));
    assertEquals(StageReadiness.Kind.NOT_APPLICABLE, v.kind());
  }

  @Test
  public void basisChangedAfterTheTicketRanIsStale() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "SYS-1";
    design.history("kicked off", null); // ranBefore: any history beyond "created"
    Task sys = ticket("SYS-1", "system", "done");
    sys.updatedAt = T2; // the basis moved AFTER the downstream ran at T1
    var v = verdictOf(design, List.of(design, sys));
    assertEquals(StageReadiness.Kind.STALE, v.kind());
    assertTrue(v.reason().contains("re-run"));
  }

  @Test
  public void rerunAfterTheBasisChangedIsReadyAgain() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "SYS-1";
    design.history("kicked off", null);
    design.updatedAt = T3;
    Task sys = ticket("SYS-1", "system", "done");
    sys.updatedAt = T2;
    assertEquals(StageReadiness.Kind.READY, verdictOf(design, List.of(design, sys)).kind());
  }

  @Test
  public void missingBasisTicketWaits() {
    Task design = ticket("D-1", "design", "product-backlog");
    design.basis = "GONE-9";
    var v = verdictOf(design, List.of(design));
    assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, v.kind());
    assertTrue(v.reason().contains("not found"));
  }

  @Test
  public void entryStageWithoutBasisIsReady() {
    Task req = ticket("REQ-1", "requirements", "product-backlog");
    assertEquals(StageReadiness.Kind.READY, verdictOf(req, List.of(req)).kind());
  }

  @Test
  public void blockedOutranksASatisfiedBasis() {
    Task design = ticket("D-1", "design", "blocked");
    design.basis = "SYS-1";
    design.enterBlocked("needs a human");
    Task sys = ticket("SYS-1", "system", "done");
    assertEquals(StageReadiness.Kind.BLOCKED, verdictOf(design, List.of(design, sys)).kind());
  }

  @Test
  public void noStageAtAllIsNotApplicable() {
    Task legacy = ticket("LEG-1", null, "product-backlog");
    assertEquals(StageReadiness.Kind.NOT_APPLICABLE, verdictOf(legacy, List.of(legacy)).kind());
  }

  @Test
  public void legacyTicketWithoutBasisKeepsTheEpicChainFallback() {
    // migration: an unbasis'd design ticket with a done epic parent in the upstream stage
    // (the LADDER upstream of design is architecture - that is where the legacy walk looks)
    Task design = ticket("D-9", "design", "product-backlog");
    design.epic = "SYS-1";
    Task sys = ticket("SYS-1", "architecture", "done");
    assertEquals(StageReadiness.Kind.READY, verdictOf(design, List.of(design, sys)).kind());
  }

  @Test
  public void legacyEpicUpstreamWithoutArtifactStillWaits() {
    Task design = ticket("D-9", "design", "product-backlog");
    design.epic = "SYS-1";
    Task sys = ticket("SYS-1", "architecture", "done");
    sys.artifacts.clear();
    assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, verdictOf(design, List.of(design, sys)).kind());
  }

  @Test
  public void realStoreDivisionProducesAReadyChild() {
    // end-to-end over the store: SYS-1 done -> its implementation child (basis=SYS-1) is READY
    Task sys = store.create("p", TaskStore.CreateSpec.of("system work"), "system");
    store.addArtifact("p", sys.id, "doc", "docs/sys-product.md", "stage product", null);
    store.update("p", sys.id, java.util.Map.of("status", "done"));
    Task impl =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "implement the system product",
                null,
                "task",
                "developer",
                "medium",
                null,
                null,
                null,
                sys.id, // epic: the scope container the child inherits
                null,
                null,
                null,
                sys.id), // basis: the derivation edge
            "implementation");
    var v = StageReadiness.evaluate(store.list("p", null, null, null, null)).get(impl.id);
    assertEquals(StageReadiness.Kind.READY, v.kind());
  }
}
