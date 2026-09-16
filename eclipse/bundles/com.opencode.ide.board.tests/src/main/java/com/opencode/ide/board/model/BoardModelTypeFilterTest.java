package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.TaskStore;

/**
 * Model tests for the U-005 "Bugs only" triage filter: bug tickets survive
 * the filter in both layouts, story/task/spike rows are hidden while it is
 * on, clearing it restores the full board, and it composes with the
 * blocked-only filter over the same snapshot.
 */
public class BoardModelTypeFilterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    /** Creates a sprint-planned ticket of the given type. */
    private String sprintTicket(String title, String type) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", type, "developer", "medium", 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        store.planSprint("p", "S-01", List.of(t.id), "goal");
        return t.id;
    }

    private BoardModel model() {
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        return model;
    }

    @Test
    public void bugsOnlyHidesNonBugRowsInBothLayouts() {
        String bug = sprintTicket("crash", "bug");
        sprintTicket("feature", "story");
        sprintTicket("chore", "task");

        BoardModel flat = model();
        flat.setBugsOnly(true);
        BoardSnapshot snapshot = flat.refresh();
        assertEquals(1, snapshot.total());
        assertEquals(bug, snapshot.column("sprint-backlog").get(0).id());
        assertTrue(snapshot.column("sprint-backlog").get(0).isBug());

        BoardModel pipeline = model();
        pipeline.setBugsOnly(true);
        pipeline.setMode(BoardModel.BoardMode.PIPELINE);
        BoardSnapshot piped = pipeline.refresh();
        assertEquals(1, piped.total());
        assertFalse("the story/task rows must not hide in the untracked group either",
                piped.pipeline().columns().stream()
                        .flatMap(c -> c.rows().stream())
                        .anyMatch(r -> !"bug".equals(r.type())));
    }

    @Test
    public void bugsOnlyOffShowsEveryType() {
        sprintTicket("crash", "bug");
        sprintTicket("feature", "story");
        sprintTicket("chore", "task");
        sprintTicket("probe", "spike");

        BoardModel model = model();
        assertEquals(4, model.refresh().total());

        model.setBugsOnly(true);
        assertEquals(1, model.refresh().total());

        model.setBugsOnly(false);
        assertEquals("clearing the filter restores the full board", 4, model.refresh().total());
    }

    @Test
    public void bugsOnlyComposesWithBlockedOnly() {
        String openBug = sprintTicket("open bug", "bug");
        String blockedBug = sprintTicket("blocked bug", "bug");
        store.setBlocked("p", blockedBug, "waiting", "pm");
        sprintTicket("blocked feature", "story");

        BoardModel model = model();
        model.setBugsOnly(true);
        model.setBlockedOnly(true);
        BoardSnapshot snapshot = model.refresh();

        assertEquals(1, snapshot.total());
        assertEquals(blockedBug, snapshot.column("sprint-backlog").get(0).id());
        assertFalse(openBug.equals(snapshot.column("sprint-backlog").get(0).id()));
    }

    @Test
    public void bugsOnlyOverAnAllFeatureSprintRendersEmptyNotBroken() {
        sprintTicket("feature a", "story");
        sprintTicket("feature b", "task");

        BoardModel model = model();
        model.setBugsOnly(true);
        BoardSnapshot snapshot = model.refresh();

        assertEquals(0, snapshot.total());
        assertTrue(snapshot.column("sprint-backlog").isEmpty());
        assertNull("the empty board carries no error", snapshot.error());
    }

    @Test
    public void typeFieldSurvivesTheRowMapping() {
        sprintTicket("typed", "bug");

        BoardModel model = model();
        TicketRow row = model.refresh().column("sprint-backlog").get(0);
        assertEquals("bug", row.type());
        assertEquals("[bug]", row.typeTag());
        assertTrue(row.isBug());
    }
}
