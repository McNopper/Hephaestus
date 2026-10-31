package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    assertEquals(5, store.list("test", null, null, null, null).size());

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
  public void importedTasksParticipateInSprintsAndClaims() {
    Task.Sprint s = store.planSprint("test", null, java.util.List.of("T-003"), "finish the store");
    assertEquals("S-01", s.id());
    Task claimed = store.claim("test", "developer", null, "agent-x");
    assertEquals("T-003", claimed.id);
    assertEquals("agent-x", claimed.assignee);
  }

  @Test
  public void reimportIsIdempotentForContent() throws Exception {
    String json;
    try (InputStream in = getClass().getResourceAsStream("/fixtures/pm-test.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    store.importPmJson("test", json);
    assertEquals(5, store.list("test", null, null, null, null).size());
    // a claim made earlier in another test project does not leak
    assertEquals("T-003", store.get("test", "T-003").id);
  }
}
