package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The H6 invalidation recorder over the REAL store (temp-dir fixture, files on disk): a STALE
 * ticket gets exactly one "inputs changed: upstream &lt;id&gt; updated &lt;ts&gt;" history event
 * (by "h6") plus an updated_at touch, the run is idempotent (a second run without changes appends
 * nothing; a changed-again upstream gets a second marker naming the new timestamp), non-stale
 * verdicts (READY/BLOCKED/RUNNING/WAIT_UPSTREAM/NOT_APPLICABLE) are never touched, the marker
 * round-trips through the file codec, and racing recorders mark each stale ticket exactly once (the
 * store's file-lock discipline, mirroring the claim concurrency suite).
 */
public class RecordInvalidationsTest {

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

  /**
   * {parent, child}: parent done in architecture, child ran at design with epic -> parent and an
   * older last touch => child STALE.
   */
  private String[] stalePair() throws InterruptedException {
    Task parent = store.create("p", TaskStore.CreateSpec.of("upstream anchor"), "architecture");
    Task child = store.create("p", TaskStore.CreateSpec.of("downstream"), "design");
    store.update("p", child.id, Map.of("epic", parent.id));
    store.update("p", child.id, Map.of("title", child.title + " v2"));
    tick();
    store.update("p", parent.id, Map.of("status", "done"));
    // U-081: the stale pair presupposes a proven (artifacted) upstream -
    // recorded last so the parent stays the newest input
    store.addArtifact(
        "p", parent.id, "doc", "docs/architecture-product.md", "stage product", "fixture");
    return new String[] {parent.id, child.id};
  }

  private static long markers(Task t) {
    return t.history.stream().filter(e -> e.action().startsWith("inputs changed:")).count();
  }

  @Test
  public void staleTicketGetsOneNamingMarkerAndUpdatedTouch() throws Exception {
    String[] ids = stalePair();
    assertEquals(
        StageReadiness.Kind.STALE,
        StageReadiness.evaluate(store.list("p", null, null, null, null)).get(ids[1]).kind());

    Task before = store.get("p", ids[1]);
    assertEquals(1, store.recordInvalidations("p"));

    Task after = store.get("p", ids[1]);
    assertEquals(before.history.size() + 1, after.history.size());
    Task.HistoryEvent last = after.history.get(after.history.size() - 1);
    assertEquals(
        "inputs changed: upstream "
            + ids[0]
            + " updated "
            + Task.formatTs(store.get("p", ids[0]).updatedAt),
        last.action());
    assertEquals("h6", last.by());
    assertNotNull(last.ts());
    assertTrue("the marker touches updated_at", after.updatedAt.isAfter(before.updatedAt));
  }

  @Test
  public void secondRunWithoutChangesAppendsNothing() throws Exception {
    String[] ids = stalePair();
    assertEquals(1, store.recordInvalidations("p"));
    Task once = store.get("p", ids[1]);

    assertEquals(0, store.recordInvalidations("p"));

    Task twice = store.get("p", ids[1]);
    assertEquals("no second marker", once.history.size(), twice.history.size());
    assertEquals(1, markers(twice));
    assertEquals("and no gratuitous updated_at touch", once.updatedAt, twice.updatedAt);
  }

  @Test
  public void changedAgainUpstreamGetsASecondMarkerWithTheNewTs() throws Exception {
    String[] ids = stalePair();
    store.recordInvalidations("p");
    tick();
    store.update("p", ids[0], Map.of("title", "anchor v2"));

    assertEquals(
        "the upstream moved past the first marker: stale again", 1, store.recordInvalidations("p"));

    Task child = store.get("p", ids[1]);
    assertEquals(2, markers(child));
    String first =
        child.history.stream()
            .map(Task.HistoryEvent::action)
            .filter(a -> a.startsWith("inputs changed:"))
            .toList()
            .get(0);
    String second =
        child.history.stream()
            .map(Task.HistoryEvent::action)
            .filter(a -> a.startsWith("inputs changed:"))
            .toList()
            .get(1);
    assertTrue("the second marker names the new upstream timestamp", !first.equals(second));
    assertTrue(second.endsWith(Task.formatTs(store.get("p", ids[0]).updatedAt)));
  }

  @Test
  public void freshReadyTicketIsUntouched() throws Exception {
    Task fresh = store.create("p", TaskStore.CreateSpec.of("fresh"), "requirements");
    assertEquals(
        StageReadiness.Kind.READY,
        StageReadiness.evaluate(store.list("p", null, null, null, null)).get(fresh.id).kind());

    assertEquals(0, store.recordInvalidations("p"));

    Task after = store.get("p", fresh.id);
    assertEquals(1, after.history.size());
    assertEquals("created", after.history.get(0).action());
    assertEquals(fresh.updatedAt, after.updatedAt);
  }

  @Test
  public void blockedRunningAndWaitingTicketsAreUntouched() throws Exception {
    Task blocked = store.create("p", TaskStore.CreateSpec.of("blocked one"), "design");
    store.setBlocked("p", blocked.id, "waiting on legal", null);
    Task running = store.create("p", TaskStore.CreateSpec.of("runner"), "design");
    store.update("p", running.id, Map.of("status", "in-progress"));
    Task waiting = store.create("p", TaskStore.CreateSpec.of("waiter"), "system");
    Instant blockedUpdatedAt = store.get("p", blocked.id).updatedAt;

    assertEquals(0, store.recordInvalidations("p"));

    assertEquals("created + blocked:...", 2, store.get("p", blocked.id).history.size());
    assertEquals("created + updated:status", 2, store.get("p", running.id).history.size());
    assertEquals(1, store.get("p", waiting.id).history.size());
    assertEquals(blockedUpdatedAt, store.get("p", blocked.id).updatedAt);
  }

  @Test
  public void noStageAndDoneFreshTicketsAreUntouched() throws Exception {
    Task parent = store.create("p", TaskStore.CreateSpec.of("anchor"), "architecture");
    store.update("p", parent.id, Map.of("status", "done"));
    Task child = store.create("p", TaskStore.CreateSpec.of("downstream"), "design");
    store.update("p", child.id, Map.of("epic", parent.id));
    store.update("p", child.id, Map.of("status", "done"));
    Task legacy = store.create("p", TaskStore.CreateSpec.of("legacy"));

    assertEquals(0, store.recordInvalidations("p"));

    assertEquals(
        "legacy (NOT_APPLICABLE) keeps only its created event",
        1,
        store.get("p", legacy.id).history.size());
    assertEquals(
        "done with fresh inputs (NOT_APPLICABLE) is not re-marked",
        3,
        store.get("p", child.id).history.size());
    assertEquals(2, store.get("p", parent.id).history.size());
  }

  @Test
  public void emptyProjectMarksNothing() throws Exception {
    assertEquals(0, store.recordInvalidations("untouched-project"));
  }

  @Test
  public void markerRoundTripsThroughTheFiles() throws Exception {
    String[] ids = stalePair();
    store.recordInvalidations("p");

    Task reopened = new TaskStore(root).get("p", ids[1]);
    Task.HistoryEvent last = reopened.history.get(reopened.history.size() - 1);
    assertTrue(
        last.action(),
        last.action().startsWith("inputs changed: upstream " + ids[0] + " updated "));
    assertEquals("h6", last.by());
    assertEquals(
        "the bumped updated_at survived the file round trip at store precision",
        store.get("p", ids[1]).updatedAt,
        reopened.updatedAt);
  }

  @Test
  public void concurrentRunsMarkEachStaleTicketExactlyOnce() throws Exception {
    String[] ids = stalePair();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return new TaskStore(root)
                      .recordInvalidations("p"); // own instance: file lock is the only guard
                }));
      }
      start.countDown();
      int total = 0;
      for (Future<Integer> f : futures) {
        total += f.get(30, TimeUnit.SECONDS);
      }
      assertEquals("the stale ticket is marked exactly once across racing recorders", 1, total);
      assertEquals(1, markers(new TaskStore(root).get("p", ids[1])));
    } finally {
      pool.shutdownNow();
    }
  }
}
