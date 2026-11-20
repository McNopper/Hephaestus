package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for the SWT-free {@link BoardModel} against a real {@link TaskStore} in a temp
 * directory: columns/counts, blocked rows, wave-agnostic display, and missing-store tolerance.
 */
public class BoardModelTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;
  private Path root;

  @Before
  public void setUp() {
    root = tmp.getRoot().toPath().resolve("tasks");
    store = new TaskStore(root);
  }

  private String newReadyTicket(String title) {
    Task t = store.create("p", TaskStore.CreateSpec.of(title));
    store.update("p", t.id, Map.of("story_points", (Object) 3));
    return t.id;
  }

  @Test
  public void columnsMatchStatusesAndCounts() {
    String claimed = newReadyTicket("claimed");
    String waiting = newReadyTicket("waiting");
    store.claim("p", "developer", null, "worker");
    assertEquals("in-progress", store.get("p", claimed).status);
    assertEquals("ready", store.get("p", waiting).status);

    BoardModel model = new BoardModel(root, "p");
    BoardSnapshot snapshot = model.refresh();

    assertEquals(7, snapshot.columns().size());
    assertEquals(
        List.of(
            "ready", "in-progress", "in-review", "done", "not-implemented", "paused", "blocked"),
        List.copyOf(snapshot.columns().keySet()));
    assertEquals(Task.VALID_STATUSES, List.copyOf(snapshot.columns().keySet()));
    assertEquals(1, snapshot.column("ready").size());
    assertEquals(1, snapshot.column("in-progress").size());
    assertEquals(0, snapshot.column("not-implemented").size());
    assertEquals(2, snapshot.total());
    assertEquals(0, snapshot.blockedCount());
    assertNull(snapshot.error());
    assertEquals(
        "ID title",
        claimed + " ",
        snapshot.column("in-progress").get(0).label().substring(0, claimed.length() + 1));
  }

  @Test
  public void blockedRowsCarryBlocker() {
    String id = newReadyTicket("blocked one");
    store.setBlocked("p", id, "waiting on other agent", "pm");

    BoardModel model = new BoardModel(root, "p");
    BoardSnapshot snapshot = model.refresh();

    assertEquals(1, snapshot.blockedCount());
    TicketRow row = snapshot.column("blocked").get(0);
    assertTrue(row.blocked());
    assertEquals("waiting on other agent", row.blocker());
    assertTrue(row.label().startsWith("[BLOCKED] " + id));
  }

  @Test
  public void boardShowsAllTicketsWaveAgnostic() {
    // user direction 2026-09-18: display is wave-agnostic (all tickets);
    // display is wave-agnostic (all tickets) - there is no wave filter at all
    newReadyTicket("born ready");
    Task loose = store.create("p", TaskStore.CreateSpec.of("loose"));
    store.update("p", loose.id, Map.of("status", (Object) "in-progress"));
    Task other = store.create("p", TaskStore.CreateSpec.of("other"));

    BoardModel model = new BoardModel(root, "p");
    BoardSnapshot snapshot = model.refresh();

    assertEquals(1, snapshot.column("in-progress").size());
    assertEquals(2, snapshot.column("ready").size());
    assertEquals(3, snapshot.total());
    assertEquals(loose.id, snapshot.column("in-progress").get(0).id());
    assertNotNull(other.id);
  }

  @Test
  public void missingStoreRootYieldsErrorSnapshotWithoutThrowing() {
    BoardModel model = new BoardModel(tmp.getRoot().toPath().resolve("nope"), "p");
    BoardSnapshot snapshot = model.refresh();

    assertNotNull(snapshot.error());
    assertEquals(7, snapshot.columns().size());
    assertEquals(0, snapshot.total());
    assertTrue(snapshot.column("in-progress").isEmpty());
  }

  @Test
  public void ticketRowMapsTaskFields() {
    String id = newReadyTicket("row mapping");
    store.update("p", id, Map.of("assignee", (Object) "worker", "story_points", (Object) 5));
    TicketRow row = TicketRow.from(store.get("p", id));

    assertEquals(id, row.id());
    assertEquals("row mapping", row.title());
    assertEquals("developer", row.role());
    assertEquals(5, row.points());
    assertEquals("worker", row.assignee());
    assertEquals("5", row.pointsLabel());
    assertNull(TicketRow.from(null));
  }

  /**
   * U-059: the BOARD host consults the archive pool - an archived upstream keeps its child READY.
   */
  @Test
  public void archivedUpstreamKeepsItsChildReadyOnTheBoard() {
    Task parent = store.create("p", TaskStore.CreateSpec.of("chain head"), "requirements");
    store.addArtifact(
        "p", parent.id, "doc", "docs/requirements-product.md", "stage product", "fixture");
    store.update("p", parent.id, Map.of("status", "done"));
    store.archive("p", parent.id, "wave close");
    // the child is touched last: the archived upstream is fresh evidence, never a stale input
    Task child = store.create("p", TaskStore.CreateSpec.of("downstream"), "system");
    store.update("p", child.id, Map.of("basis", parent.id));

    BoardModel model = new BoardModel(root, "p");
    BoardSnapshot snapshot = model.refresh();

    assertNotNull("the child carries a verdict", snapshot.readinessOf(child.id));
    assertEquals(
        "READY through the archived upstream",
        com.opencode.ide.tasks.StageReadiness.Kind.READY,
        snapshot.readinessOf(child.id).kind());
  }
}
