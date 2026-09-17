package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * U-016 AC5: the board model's column ordering — the V-model order
 * (definition leg descending, verification leg ascending, untracked last)
 * for the stage layout, and the workflow-progress order for the flat
 * status kanban (None grouping).
 */
public class BoardColumnOrderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    /** Creates a sprint-planned ticket with the given stage and role. */
    private void sprintTicket(String title, String stage, String role) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", "task", role, "medium", 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        Map<String, Object> changes = new HashMap<>();
        changes.put("stage", stage);
        store.update("p", t.id, changes);
        store.planSprint("p", "S-01", List.of(t.id), "goal");
    }

    @Test
    public void pipelineColumnsFollowTheVModelOrder() {
        sprintTicket("solo", "design", "developer"); // one ticket; the board still carries all columns
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        model.setMode(BoardModel.BoardMode.PIPELINE);

        List<String> stages = new ArrayList<>();
        model.refresh().pipeline().columns().forEach(c -> stages.add(c.stage()));

        List<String> v = new ArrayList<>(VStageLayout.definitionLeg());
        v.addAll(VStageLayout.verificationLeg());
        v.add(PipelineSnapshot.UNTRACKED);
        assertEquals("stage columns = definition leg, then verification leg, then untracked", v, stages);
    }

    @Test
    public void flatColumnsFollowWorkflowProgressOrder() {
        sprintTicket("solo", "design", "developer");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        List<String> keys = new ArrayList<>(model.refresh().columns().keySet());

        assertEquals(List.of("product-backlog", "sprint-backlog", "in-progress", "in-review", "done"),
                keys);
        assertEquals("flat order IS the workflow-progress order", Task.VALID_STATUSES, keys);
    }

    /** Creates a sprint-planned ticket with an explicit priority. */
    private String priorityTicket(String title, String priority) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", "task", "developer", priority, 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        store.planSprint("p", "S-01", List.of(t.id), "goal");
        return t.id;
    }

    @Test
    public void cardsWithinAColumnSortByPriorityThenStageDepth() {
        priorityTicket("low thing", "low");
        priorityTicket("critical thing", "critical");
        priorityTicket("medium thing", "medium");
        priorityTicket("high thing", "high");
        priorityTicket("also high deeper stage", "high");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        List<String> titles = new ArrayList<>();
        model.refresh().column("sprint-backlog").forEach(r -> titles.add(r.title()));

        assertEquals("critical first, then highs (id tiebreak), medium, low",
                List.of("critical thing", "high thing", "also high deeper stage", "medium thing", "low thing"),
                titles);
    }

    @Test
    public void setStatusMovesTheTicketBetweenFlatColumns() {
        String id = priorityTicket("movable", "high");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        assertNull(model.setStatus(id, "in-progress"));

        assertEquals(1, model.refresh().column("in-progress").size());
        assertTrue(model.refresh().column("sprint-backlog").isEmpty());
    }

    @Test
    public void setStageBackwardNeedsAReasonAndCarriesTheSendBackContract() {
        String id = sprintTicketAtStage("backmover", "design");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        assertNotNull("backward without a reason is refused", model.setStage(id, "requirements", null));
        assertNull(model.setStage(id, "requirements", "spec changed"));

        Task moved = store.get("p", id);
        assertEquals("requirements", moved.stage);
        assertEquals("product-backlog", moved.status);
        assertTrue(moved.blocked);
        assertEquals("sent back from design: spec changed", moved.blocker);
    }

    @Test
    public void setStageForwardIsAPlainStageUpdate() {
        String id = sprintTicketAtStage("forwardmover", "design");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        assertNull(model.setStage(id, "test-design", null));

        Task moved = store.get("p", id);
        assertEquals("test-design", moved.stage);
        assertFalse(moved.blocked);
    }

    @Test
    public void setStageToNullClearsTheStage() {
        String id = sprintTicketAtStage("unstager", "system");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");

        assertNull(model.setStage(id, null, null));

        assertNull(store.get("p", id).stage);
    }

    /** Creates a sprint-planned ticket pinned to a stage (update-after-create). */
    private String sprintTicketAtStage(String title, String stage) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", "task", "developer", "medium", 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        Map<String, Object> changes = new HashMap<>();
        changes.put("stage", stage);
        store.update("p", t.id, changes);
        store.planSprint("p", "S-01", List.of(t.id), "goal");
        return t.id;
    }
}
