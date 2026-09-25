package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Unit tests for the SWT-free {@link BoardModel} against a real
 * {@link TaskStore} in a temp directory: columns/counts, blocked rows, the
 * (backlog) pseudo-sprint, the sprint list, and missing-store tolerance.
 */
public class BoardModelTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    private String newSprintTicket(String title) {
        Task t = store.create("p", TaskStore.CreateSpec.of(title));
        store.update("p", t.id, Map.of("story_points", (Object) 3));
        store.planSprint("p", "S-01", List.of(t.id), "ship the board");
        return t.id;
    }

    @Test
    public void columnsMatchStatusesAndCounts() {
        String claimed = newSprintTicket("claimed");
        String waiting = newSprintTicket("waiting");
        store.claim("p", "developer", null, "worker");
        assertEquals("in-progress", store.get("p", claimed).status);
        assertEquals("sprint-backlog", store.get("p", waiting).status);

        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        BoardSnapshot snapshot = model.refresh();

        assertEquals(6, snapshot.columns().size());
        assertEquals(List.of("product-backlog", "sprint-backlog", "in-progress", "in-review", "paused", "done"),
                List.copyOf(snapshot.columns().keySet()));
        assertEquals(1, snapshot.column("sprint-backlog").size());
        assertEquals(1, snapshot.column("in-progress").size());
        assertEquals(0, snapshot.column("product-backlog").size());
        assertEquals(2, snapshot.total());
        assertEquals(0, snapshot.blockedCount());
        assertNull(snapshot.error());
        assertEquals("ID title", claimed + " ",
                snapshot.column("in-progress").get(0).label().substring(0, claimed.length() + 1));
    }

    @Test
    public void blockedRowsCarryBlocker() {
        String id = newSprintTicket("blocked one");
        store.setBlocked("p", id, "waiting on other agent", "pm");

        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        BoardSnapshot snapshot = model.refresh();

        assertEquals(1, snapshot.blockedCount());
        TicketRow row = snapshot.column("in-progress").isEmpty()
                ? snapshot.column("sprint-backlog").get(0)
                : snapshot.column("in-progress").get(0);
        assertTrue(row.blocked());
        assertEquals("waiting on other agent", row.blocker());
        assertTrue(row.label().startsWith("[BLOCKED] " + id));
    }

    @Test
    public void boardShowsAllTicketsWaveAgnostic() {
        // user direction 2026-09-18: display is wave-agnostic (all tickets);
        // BACKLOG only scopes dispatch
        newSprintTicket("sprinted");
        Task loose = store.create("p", TaskStore.CreateSpec.of("loose"));
        store.update("p", loose.id, Map.of("status", (Object) "in-progress"));
        Task other = store.create("p", TaskStore.CreateSpec.of("other"));

        BoardModel model = new BoardModel(root, "p");
        assertEquals(BoardModel.BACKLOG, model.sprint());
        BoardSnapshot snapshot = model.refresh();

        assertEquals(1, snapshot.column("in-progress").size());
        assertEquals(1, snapshot.column("product-backlog").size());
        assertEquals(1, snapshot.column("sprint-backlog").size());
        assertEquals(3, snapshot.total());
        assertEquals(loose.id, snapshot.column("in-progress").get(0).id());
    }

    @Test
    public void sprintGoalComesFromMetaSidecar() {
        newSprintTicket("with goal");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        assertEquals("ship the board", model.refresh().sprintGoal());
        model.setSprint(BoardModel.BACKLOG);
        assertEquals("", model.refresh().sprintGoal());
    }

    @Test
    public void sprintsDeriveFromTicketsAndMeta() {
        newSprintTicket("sprinted");
        Task metaOnly = store.create("p", TaskStore.CreateSpec.of("meta only"));
        store.update("p", metaOnly.id, Map.of("sprint", (Object) "S-09"));

        BoardModel model = new BoardModel(root, "p");
        List<String> sprints = model.sprints();

        assertTrue(sprints.contains("S-01"));
        assertTrue(sprints.contains("S-09"));
        assertEquals(BoardModel.BACKLOG, sprints.get(sprints.size() - 1));
    }

    @Test
    public void peerCreatedSprintAppearsInTheSprintListOnRefresh() {
        // B-002 live case: the board (this model) is already open when a peer
        // plans a sprint and moves tickets into it — a SECOND TaskStore on
        // the same root stands in for the other process. No caching anywhere
        // may keep the new sprint out of the selector's list.
        BoardModel model = new BoardModel(root, "p");
        model.refresh();
        assertFalse("no sprints before the peer acts", model.sprints().contains("sprint-peer"));

        TaskStore peer = new TaskStore(root);
        Task t = peer.create("p", TaskStore.CreateSpec.of("peer ticket"));
        peer.planSprint("p", "sprint-peer", List.of(t.id), "peer work");

        List<String> sprints = model.sprints();
        assertTrue("the peer-planned sprint must be selectable on the next refresh",
                sprints.contains("sprint-peer"));
        // ...and its board is readable through the same store
        model.setSprint("sprint-peer");
        assertEquals(1, model.refresh().total());
    }

    @Test
    public void missingStoreRootYieldsErrorSnapshotWithoutThrowing() {
        BoardModel model = new BoardModel(tmp.getRoot().toPath().resolve("nope"), "p");
        model.setSprint("S-01");
        BoardSnapshot snapshot = model.refresh();

        assertNotNull(snapshot.error());
        assertEquals(6, snapshot.columns().size());
        assertEquals(0, snapshot.total());
        assertTrue(snapshot.column("in-progress").isEmpty());
        assertEquals(List.of(BoardModel.BACKLOG), model.sprints());
    }

    @Test
    public void ticketRowMapsTaskFields() {
        String id = newSprintTicket("row mapping");
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
}
