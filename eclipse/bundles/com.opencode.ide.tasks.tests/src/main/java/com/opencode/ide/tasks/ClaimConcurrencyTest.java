package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Atomic self-claim under contention: N threads, each with its OWN store instance (so only the lock
 * file coordinates them), claiming the same role. Every ticket must be claimed exactly once; late
 * claimers get null.
 */
public class ClaimConcurrencyTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void concurrentClaimsAreDistinctAndComplete() throws Exception {
    Path root = tmp.getRoot().toPath().resolve("tasks");
    TaskStore setup = new TaskStore(root);
    final int tickets = 12;
    for (int i = 0; i < tickets; i++) {
      String id = setup.create("p", TaskStore.CreateSpec.of("t" + i)).id;
      setup.planSprint("p", "S-01", List.of(id), "g");
    }
    final int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        final String agent = "agent-" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  List<String> claimed = new ArrayList<>();
                  TaskStore store =
                      new TaskStore(root); // own instance: file lock is the only guard
                  for (int round = 0; round < tickets; round++) {
                    Task t = store.claim("p", "developer", null, agent);
                    if (t == null) {
                      break;
                    }
                    claimed.add(t.id);
                  }
                  return claimed;
                }));
      }
      start.countDown();
      Set<String> all = new HashSet<>();
      for (Future<List<String>> f : futures) {
        List<String> claimed = f.get(30, TimeUnit.SECONDS);
        for (String id : claimed) {
          assertTrue("ticket claimed twice: " + id, all.add(id));
        }
      }
      assertEquals("every sprint-backlog ticket claimed exactly once", tickets, all.size());
      assertEquals(0, new TaskStore(root).list("p", null, "sprint-backlog", null, null).size());
      assertTrue(
          "store drained: further claims return null",
          new TaskStore(root).claim("p", "developer", null, null) == null);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void interleavedCommentAndClaimDoNotLoseWrites() throws Exception {
    Path root = tmp.getRoot().toPath().resolve("tasks2");
    TaskStore setup = new TaskStore(root);
    String id = setup.create("p", TaskStore.CreateSpec.of("target")).id;
    setup.planSprint("p", "S-01", List.of(id), "g");
    final int comments = 40;
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < comments; i++) {
        final int n = i;
        futures.add(
            pool.submit(
                () -> {
                  TaskStore store = new TaskStore(root);
                  if (n % 4 == 0) {
                    store.claim("p", "developer", null, "w" + n);
                    store.release("p", id, "w" + n);
                  } else {
                    store.addComment("p", id, "c" + n, "w" + n);
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
      assertEquals(
          "no comment lost to the claim/release races",
          comments - comments / 4,
          new TaskStore(root).get("p", id).comments.size());
    } finally {
      pool.shutdownNow();
    }
  }

  // T-113 double-claim (2026-11-16): task_claim is the atomic path; a bare update
  // may not take a live claim. Release, idempotent re-writes and the FIRST claim
  // stay open.
  @Test
  public void updateMayNotStealALiveClaim() {
    Path root = tmp.getRoot().toPath().resolve("tasks3");
    TaskStore store = new TaskStore(root);
    String id = store.create("p", TaskStore.CreateSpec.of("steal")).id;
    store.planSprint("p", "S-01", List.of(id), "g");
    store.claim("p", "developer", null, "agent-a");
    TaskStore.Invalid refused =
        assertThrows(
            TaskStore.Invalid.class,
            () -> store.update("p", id, Map.of("status", "in-progress", "assignee", "agent-b")));
    assertTrue(
        "the refusal names the live holder",
        refused.getMessage().contains("already claimed by agent-a"));
    Task after = store.get("p", id);
    assertEquals("the live claim survives", "agent-a", after.assignee);
    assertEquals("the status survives", "in-progress", after.status);
    // idempotent re-write by the same holder stays open
    store.update("p", id, Map.of("status", "in-progress", "assignee", "agent-a"));
    assertEquals("agent-a", store.get("p", id).assignee);
    // release + the atomic re-claim by the next actor stay open
    store.release("p", id, "agent-a");
    Task next = store.claim("p", "developer", null, "agent-b");
    assertEquals("the released ticket is claimable again", id, next.id);
    assertEquals("agent-b", store.get("p", id).assignee);
    // the FIRST claim through update (from sprint-backlog) stays open
    String id2 = store.create("p", TaskStore.CreateSpec.of("first")).id;
    store.planSprint("p", "S-02", List.of(id2), "g");
    store.update("p", id2, Map.of("status", "in-progress", "assignee", "agent-c"));
    assertEquals("agent-c", store.get("p", id2).assignee);
  }
}
