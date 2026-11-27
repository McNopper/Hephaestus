package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Migration parity: the retired Python pm store's real fixture (originally {@code
 * mcp/pm/data/test.json}, now vendored verbatim as the test resource {@code fixtures/pm-test.json})
 * imports losslessly - all five tasks, their todos/history ordering, per-prefix counters (so new
 * ids continue, never reuse) and field values.
 */
public class PmImportTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;
  private int count;

  @Before
  public void setUp() throws Exception {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    String json;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/pm-test.json")) {
      if (in == null) {
        throw new IllegalStateException(
            "fixture /fixtures/pm-test.json not found on the test classpath");
      }
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    count = store.importPmJson("test", json);
  }

  @Test
  public void importsAllTasksWithFields() {
    assertEquals(5, count);
    assertEquals(5, store.list("test", null, null, null).size());

    Task t1 = store.get("test", "T-001");
    assertEquals("Capture requirements for demo tracker", t1.title);
    assertEquals("story", t1.type);
    assertEquals("high", t1.priority);
    assertEquals("pm", t1.role);
    assertEquals(3, t1.storyPoints);
    assertEquals(
        java.util.List.of("User stories documented", "NFRs listed"), t1.acceptanceCriteria);
    assertEquals(3, t1.todos.size());
    assertTrue("List NFRs was toggled done", t1.todos.get(1).done());
    assertEquals(5, t1.history.size());
    assertEquals("todo_toggled:1", t1.history.get(4).action());
    // the store pins millisecond precision; Python's microseconds truncate
    assertEquals(Instant.parse("2026-07-16T04:24:36.093Z"), t1.createdAt);

    Task t5 = store.get("test", "T-005");
    assertEquals("spike", t5.type);
    assertEquals("low", t5.priority);
  }

  @Test
  public void countersCarryOverSoIdsContinue() {
    Task next = store.create("test", TaskStore.CreateSpec.of("the next one"));
    assertEquals("T-006", next.id);
    // into a different project: fresh counters
    Task other = store.create("other", TaskStore.CreateSpec.of("fresh"));
    assertEquals("T-001", other.id);
  }

  @Test
  public void importedTasksParticipateInTheReadyQueueAndClaims() {
    Task claimed = store.claim("test", "developer", null, "agent-x");
    assertTrue("imported tickets are born ready and claimable", claimed != null);
    assertEquals("agent-x", claimed.assignee);
  }

  @Test
  public void reimportIsIdempotentForContent() throws Exception {
    String json;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/pm-test.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    store.importPmJson("test", json);
    assertEquals(5, store.list("test", null, null, null).size());
    // a claim made earlier in another test project does not leak
    assertEquals("T-003", store.get("test", "T-003").id);
  }

  /**
   * T-242 (AC5, folded in by T-226's verb sweep): the per-ticket overwrite in {@code importPmJson}
   * used to be UNGUARDED - {@code data.tasks.put()} replaced any existing record blindly, so a pm
   * doc imported over a done id silently un-terminated it (the S4 class T-225 closed in
   * update/setPaused, T-226 in routeReviewDoubt). Now guarded VERDICT-based: a terminal verdict
   * refuses, an ARCHIVED id refuses (the same defect against the history pool - importing it would
   * fork the id across pools, T-240), while a live record re-imports and a fresh id always imports
   * - exactly what legitimate pm migration needs. This is the verb-grep table's "REPORTED, NOT
   * FIXED" row for importPmJson moving to "GUARDED" (table started on T-226's evidence comment).
   */
  @Test
  public void importRefusesToReplaceATerminalOrArchivedRecordButKeepsLiveAndFreshImports() {
    // (1) LIVE record: a re-import over a ready row works (migration refresh, idempotent)
    int liveCount =
        store.importPmJson(
            "test",
            "{\"tickets\":{\"T-003\":{\"id\":\"T-003\",\"title\":\"refreshed\",\"status\":\"ready\"}}}");
    assertEquals(1, liveCount);
    assertEquals("refreshed", store.get("test", "T-003").title);

    // (2) FRESH id: the migration's core need - still imports, pm backlog maps to ready
    int freshCount =
        store.importPmJson(
            "test",
            "{\"tickets\":{\"T-900\":{\"id\":\"T-900\",\"title\":\"fresh arrival\","
                + "\"status\":\"product-backlog\"}}}");
    assertEquals(1, freshCount);
    assertEquals("ready", store.get("test", "T-900").status);

    // (3) TERMINAL verdict: over a done record -> Invalid, the record stays untouched
    store.update("test", "T-001", Map.of("status", "done"));
    try {
      store.importPmJson(
          "test",
          "{\"tickets\":{\"T-001\":{\"id\":\"T-001\",\"title\":\"clobbered\",\"status\":\"ready\"}}}");
      fail("import over a done record must refuse (it would replace/un-terminal the record)");
    } catch (TaskStore.Invalid expected) {
      assertTrue(
          "the refusal names the ticket and its verdict: " + expected.getMessage(),
          expected.getMessage().contains("cannot import over ticket T-001")
              && expected.getMessage().contains("'done'"));
    }
    Task stillDone = store.get("test", "T-001");
    assertEquals("the done record is untouched after the refusal", "done", stillDone.status);
    assertEquals(
        "and was never clobbered with the pm title",
        "Capture requirements for demo tracker",
        stillDone.title);

    // (4) ARCHIVED id: same defect class against the history pool -> Invalid; the id never
    // reappears in the LIVE pool (a fork across both pools is what the guard prevents)
    store.update("test", "T-002", Map.of("status", "done"));
    store.archive("test", "T-002", "wave close");
    try {
      store.importPmJson(
          "test",
          "{\"tickets\":{\"T-002\":{\"id\":\"T-002\",\"title\":\"clobbered\",\"status\":\"ready\"}}}");
      fail("import over an archived record must refuse (it would fork the id across pools)");
    } catch (TaskStore.Invalid expected) {
      assertTrue(
          "the refusal names the archive: " + expected.getMessage(),
          expected.getMessage().contains("cannot import over ticket T-002")
              && expected.getMessage().contains("it is archived"));
    }
    assertFalse(
        "the refused import never resurrects the archived id in the live pool",
        store.list("test", null, null, null).stream().anyMatch(t -> t.id.equals("T-002")));
  }
}
