package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-052 / D-003 option B - the {@code inputs_changed_at} stamp and the production marker tick:
 *
 * <ul>
 *   <li>the stamp bumps ONLY on structured writes (status/stage/basis/role, artifacts, the
 *       claim/plan status moves) - comments and bookkeeping never move it (the W-006 incident pin:
 *       a comment on an upstream does NOT stale its downstream, a structured write DOES);
 *   <li>STALE compares the stamp on both sides with a per-side legacy fallback to {@code
 *       updated_at};
 *   <li>the PRODUCTION readiness tick ({@code task_readiness} -&gt; {@link
 *       TaskStore#readiness(String)}) records exactly one {@code inputs changed:} marker per
 *       invalidation, with no duplicates on later ticks even when a comment lands in between (AC1).
 * </ul>
 */
public class InputsChangedStampTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private Path root;
  private TaskStore store;

  @Before
  public void setUp() {
    root = tmp.getRoot().toPath().resolve("tasks");
    store = new TaskStore(root);
  }

  /** Distinct millisecond timestamps: STALE compares strictly-after. */
  private static void tick() throws InterruptedException {
    Thread.sleep(20);
  }

  private static long markers(Task t) {
    return t.history.stream().filter(e -> e.action().startsWith("inputs changed:")).count();
  }

  private StageReadiness.Kind kind(String id) {
    return StageReadiness.evaluate(store.list("p", null, null, null)).get(id).kind();
  }

  private Instant stamp(String id) {
    return store.get("p", id).inputsChangedAt;
  }

  private int stampHistorySize(String id) {
    return store.get("p", id).history.size();
  }

  // ---------- the W-006 pin ----------

  @Test
  public void aCommentOnTheUpstreamDoesNotStaleItsDownstreamButAStructuredWriteDoes()
      throws Exception {
    Task parent = store.create("p", TaskStore.CreateSpec.of("anchor"), "architecture");
    tick();
    store.addArtifact("p", parent.id, "doc", "docs/anchor.md", "stage product", "fixture");
    store.update("p", parent.id, Map.of("status", "done"));
    tick();
    Task child = store.create("p", TaskStore.CreateSpec.of("downstream"), "design");
    store.update("p", child.id, Map.of("basis", parent.id)); // child ran; its stamp is newest
    assertEquals("parent satisfied, not stale", StageReadiness.Kind.READY, kind(child.id));

    // bookkeeping on the upstream: updatedAt moves, the INPUTS stamp does not -> no staleness
    Instant parentStamp = stamp(parent.id);
    tick();
    store.addComment("p", parent.id, "a note for the record", "pm");
    Task commented = store.get("p", parent.id);
    assertTrue("the comment touched updated_at", commented.updatedAt.isAfter(parentStamp));
    assertEquals("comments never bump the stamp", parentStamp, commented.inputsChangedAt);
    assertFalse("W-006: no STALE from bookkeeping", kind(child.id) == StageReadiness.Kind.STALE);

    // a structured write (the artifact list) DOES move the stamp -> the downstream re-runs
    tick();
    store.addArtifact("p", parent.id, "doc", "docs/anchor-v2.md", "stage product", "fixture");
    assertEquals(StageReadiness.Kind.STALE, kind(child.id));
  }

  // ---------- the stamp matrix per mutation verb ----------

  @Test
  public void onlyStructuredWritesBumpTheStamp() throws Exception {
    Task t = store.create("p", TaskStore.CreateSpec.of("x"), "design");
    Task root = store.create("p", TaskStore.CreateSpec.of("e"), "requirements");
    Task claimable = store.create("p", TaskStore.CreateSpec.of("c"), "design");
    Task planned = store.create("p", TaskStore.CreateSpec.of("pl"), "design");
    assertNotNull("born stamped", stamp(t.id));
    assertEquals(
        "a new ticket's inputs start at birth", store.get("p", t.id).createdAt, stamp(t.id));

    // bookkeeping: the stamp stays put while updatedAt moves
    Instant born = stamp(t.id);
    tick();
    store.addComment("p", t.id, "note", "pm");
    assertEquals(born, stamp(t.id));
    tick();
    store.update("p", t.id, Map.of("title", "x renamed"));
    assertEquals(born, stamp(t.id));
    tick();
    store.addTodo("p", t.id, "a checklist line", false, "pm");
    assertEquals(born, stamp(t.id));

    // structured writes: each moves the stamp strictly forward
    tick();
    store.update("p", t.id, Map.of("basis", root.id));
    Instant s1 = stamp(t.id);
    assertTrue("basis bumps", s1.isAfter(born));
    tick();
    store.update("p", t.id, Map.of("status", "in-progress"));
    Instant s2 = stamp(t.id);
    assertTrue("status bumps", s2.isAfter(s1));
    tick();
    store.update("p", t.id, Map.of("role", "tester"));
    Instant s3 = stamp(t.id);
    assertTrue("role bumps", s3.isAfter(s2));
    tick();
    store.update("p", t.id, Map.of("stage", "system"));
    Instant s4 = stamp(t.id);
    assertTrue("stage bumps", s4.isAfter(s3));
    tick();
    store.addArtifact("p", t.id, "doc", "docs/x.md", "stage product", "pm");
    Instant s5 = stamp(t.id);
    assertTrue("artifact add bumps", s5.isAfter(s4));
    tick();
    store.removeArtifact("p", t.id, 0, "pm");
    assertTrue("artifact remove bumps", stamp(t.id).isAfter(s5));

    // the claim status move bumps too (owner model: born ready, claimed in)
    tick();
    Instant plannedBorn = stamp(planned.id);
    store.update("p", planned.id, Map.of("status", "ready"));
    assertTrue("a status write bumps", stamp(planned.id).isAfter(plannedBorn));
    tick();
    // deterministic queue: the critical ticket wins over the other born-ready ones
    store.update("p", claimable.id, Map.of("priority", "critical"));
    Instant afterPlan = stamp(claimable.id);
    Task claimed = store.claim("p", claimable.role, "ready", "worker");
    assertEquals("the critical ready ticket wins the claim", claimable.id, claimed.id);
    assertTrue("claim bumps", stamp(claimable.id).isAfter(afterPlan));
  }

  @Test
  public void legacyRowsWithoutTheStampFallBackToUpdatedAtPerSide() {
    Task legacy = store.create("p", TaskStore.CreateSpec.of("legacy"));
    legacy.inputsChangedAt = null; // the pre-D-003 shape
    assertEquals(legacy.updatedAt, legacy.inputsEffectiveAt());

    Task stamped = store.create("p", TaskStore.CreateSpec.of("stamped"));
    stamped.inputsChangedAt = stamped.updatedAt.minusSeconds(60);
    assertEquals(
        "the stamp wins when present", stamped.inputsChangedAt, stamped.inputsEffectiveAt());
  }

  // ---------- AC1: the production tick's marker ----------

  /**
   * {parent, child}: the child ran first (basis-set), the parent lands its structured product after
   * - so the basis-linked child is STALE against the parent's structured stamp.
   */
  private String[] stalePair() throws InterruptedException {
    Task parent = store.create("p", TaskStore.CreateSpec.of("anchor"), "architecture");
    Task child = store.create("p", TaskStore.CreateSpec.of("downstream"), "design");
    store.update("p", child.id, Map.of("basis", parent.id));
    tick();
    store.addArtifact("p", parent.id, "doc", "docs/arch.md", "stage product", "fixture");
    store.update("p", parent.id, Map.of("status", "done"));
    assertEquals(StageReadiness.Kind.STALE, kind(child.id));
    return new String[] {parent.id, child.id};
  }

  @Test
  public void theProductionReadinessTickLeavesExactlyOneMarkerAndLaterTicksAddNone()
      throws Exception {
    String[] ids = stalePair();
    Instant childStamp = stamp(ids[1]);

    store.readiness("p"); // the production tick (task_readiness's report)

    Task child = store.get("p", ids[1]);
    assertEquals("one marker for the invalidation", 1, markers(child));
    assertTrue(
        "named by the upstream's structured stamp",
        child
            .history
            .get(child.history.size() - 1)
            .action()
            .startsWith("inputs changed: upstream " + ids[0] + " updated "));
    assertEquals(
        "the marker is bookkeeping - it never moves the child's OWN inputs stamp",
        childStamp,
        child.inputsChangedAt);
    int sizeAfterFirst = child.history.size();

    // a write landing AFTER the marker must not re-append it: a bookkeeping write on the CHILD
    // (history row, no stamp change -> still STALE) between ticks - the old last-event guard would
    // re-mark here, the whole-history guard must not
    store.update("p", ids[1], Map.of("title", "downstream renamed"));
    assertEquals(
        "the rename interleaved a history row", sizeAfterFirst + 1, stampHistorySize(ids[1]));
    store.readiness("p");
    store.readiness("p");

    Task twice = store.get("p", ids[1]);
    assertEquals("still exactly one marker", 1, markers(twice));
    assertEquals(
        "only the interleaved rename is new - no duplicated marker rows",
        sizeAfterFirst + 1,
        twice.history.size());
  }

  /**
   * U-059: the RECORDER host consults the archive - an archived upstream still stales its child.
   */
  @Test
  public void archivedUpstreamStalenessStillLandsItsMarker() throws Exception {
    String[] ids = stalePair();
    store.archive("p", ids[0], "wave close"); // archived AFTER its structured product

    List<Map<String, Object>> rows = store.readiness("p"); // the production tick
    Map<String, Object> childRow = null;
    for (Map<String, Object> r : rows) {
      if (ids[1].equals(r.get("id"))) {
        childRow = r;
      }
    }
    assertNotNull("the child has a row", childRow);
    assertEquals("STALE against the archived upstream: " + childRow, "STALE", childRow.get("kind"));
    assertEquals("the marker names the archived cause", 1, markers(store.get("p", ids[1])));

    int sizeAfter = store.get("p", ids[1]).history.size();
    store.readiness("p");
    assertEquals(
        "idempotent through the archive path too",
        sizeAfter,
        store.get("p", ids[1]).history.size());
  }

  @Test
  public void theStampRoundTripsThroughTheFiles() throws Exception {
    String[] ids = stalePair();
    store.readiness("p");

    Task reopened = new TaskStore(root).get("p", ids[1]);
    assertEquals(
        "stamp survives the file round trip",
        store.get("p", ids[1]).inputsChangedAt,
        reopened.inputsChangedAt);
    assertEquals(1, markers(reopened));
    assertEquals(
        "the verdict stays decidable from the reloaded stamps",
        StageReadiness.Kind.STALE,
        StageReadiness.evaluate(new TaskStore(root).list("p", null, null, null))
            .get(ids[1])
            .kind());
  }
}
