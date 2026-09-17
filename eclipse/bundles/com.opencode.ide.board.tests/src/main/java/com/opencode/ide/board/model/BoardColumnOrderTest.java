package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;

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
}
