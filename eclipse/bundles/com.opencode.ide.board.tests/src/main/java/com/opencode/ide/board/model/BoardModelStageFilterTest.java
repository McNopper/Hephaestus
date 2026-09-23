package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * Model tests for the stage visibility filter's edge cases beyond
 * {@link BoardModelPipelineTest}: populated-but-hidden columns (stages and
 * untracked), invalid stage ids in the filter set, the flat-mode untracked
 * case, the combination with blockedOnly in the BACKLOG pseudo-sprint,
 * clearing the filter, and stored-stage-wins filtering for tickets whose
 * role would derive a different stage.
 */
public class BoardModelStageFilterTest extends BoardModelTestHarness {

    /** Creates a sprint-planned ticket with the given stage (nullable) and role. */
    private String sprintTicket(String title, String stage, String role) {
        var t = store.create("p", spec(title, role));
        if (stage != null) {
            store.update("p", t.id, Map.of("stage", (Object) stage));
        }
        store.planSprint("p", "S-01", List.of(t.id), "goal");
        return t.id;
    }

    /** Creates a backlog (sprint-less) ticket with the given stage and role. */
    private String backlogTicket(String title, String stage, String role) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", "task", role, "medium", 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        if (stage != null) {
            Map<String, Object> changes = new HashMap<>();
            changes.put("stage", (Object) stage);
            store.update("p", t.id, changes);
        }
        return t.id;
    }

    private BoardModel pipelineModel() {
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        model.setMode(BoardModel.BoardMode.PIPELINE);
        return model;
    }

    private static List<String> stageColumns(PipelineSnapshot pipeline) {
        List<String> stages = new ArrayList<>();
        pipeline.columns().forEach(c -> stages.add(c.stage()));
        return stages;
    }

    @Test
    public void requirementsOnlyFilterHidesPopulatedDesignAndUntracked() {
        sprintTicket("req work", "requirements", "pm");
        sprintTicket("design work", "design", "developer");
        sprintTicket("stage-less", null, "research"); // untracked
        BoardModel model = pipelineModel();
        model.setStageFilter(java.util.Set.of("requirements"));

        BoardSnapshot snapshot = model.refresh();

        assertEquals(List.of("requirements"), stageColumns(snapshot.pipeline()));
        assertEquals(1, snapshot.pipeline().column("requirements").rows().size());
        assertEquals("untracked group must be hidden even though it has a ticket",
                0, snapshot.pipeline().column(PipelineSnapshot.UNTRACKED).rows().size());
        assertEquals(1, snapshot.total());
    }

    @Test
    public void untrackedOnlyFilterHidesStagedColumnsEvenWhenPopulated() {
        String untracked = sprintTicket("stage-less", null, "research");
        sprintTicket("design work", "design", "developer");
        BoardModel model = pipelineModel();
        model.setStageFilter(java.util.Set.of(PipelineSnapshot.UNTRACKED));

        BoardSnapshot snapshot = model.refresh();

        assertEquals(List.of(PipelineSnapshot.UNTRACKED), stageColumns(snapshot.pipeline()));
        assertEquals(untracked, snapshot.pipeline().column(PipelineSnapshot.UNTRACKED).rows().get(0).id());
        assertEquals(1, snapshot.total());
    }

    @Test
    public void invalidStageInFilterMatchesNothingWithoutCrashing() {
        sprintTicket("design work", "design", "developer");
        BoardModel model = pipelineModel();

        model.setStageFilter(java.util.Set.of("bogus"));
        BoardSnapshot solo = model.refresh();
        assertNull("an unknown id must not make the refresh fail", solo.error());
        assertTrue("an unknown id alone matches no column",
                stageColumns(solo.pipeline()).isEmpty());
        assertEquals(0, solo.total());

        model.setStageFilter(java.util.Set.of("bogus", "design"));
        BoardSnapshot mixed = model.refresh();
        assertEquals("unknown ids are ignored; valid ones still filter",
                List.of("design"), stageColumns(mixed.pipeline()));
        assertEquals(1, mixed.total());
    }

    @Test
    public void flatModeUntrackedOnlyFilterHidesAllStagedRows() {
        sprintTicket("stage-less", null, "research");
        sprintTicket("staged", "design", "developer");
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        model.setStageFilter(java.util.Set.of(PipelineSnapshot.UNTRACKED));

        BoardSnapshot snapshot = model.refresh();

        assertEquals(1, snapshot.total());
        assertEquals("stage-less", snapshot.column("sprint-backlog").get(0).title());
        int rows = snapshot.columns().values().stream().mapToInt(List::size).sum();
        assertEquals(1, rows);
    }

    @Test
    public void blockedOnlyAndStageFilterCombineInBacklogScope() {
        String blockedDesign = backlogTicket("blocked design", "design", "developer");
        backlogTicket("free design", "design", "developer");
        backlogTicket("blocked stray", null, "research"); // blocked + untracked
        store.setBlocked("p", blockedDesign, "waiting", "pm");
        BoardModel model = new BoardModel(root, "p");
        model.setMode(BoardModel.BoardMode.PIPELINE);
        assertEquals(BoardModel.BACKLOG, model.sprint());
        model.setBlockedOnly(true);
        model.setStageFilter(java.util.Set.of("design"));

        BoardSnapshot snapshot = model.refresh();

        assertEquals(List.of("design"), stageColumns(snapshot.pipeline()));
        assertEquals(blockedDesign, snapshot.pipeline().column("design").rows().get(0).id());
        assertEquals(1, snapshot.total());
        assertEquals(1, snapshot.blockedCount());
    }

    @Test
    public void clearingTheFilterRestoresAllElevenColumns() {
        sprintTicket("design work", "design", "developer");
        BoardModel model = pipelineModel();
        model.setStageFilter(java.util.Set.of("design"));
        assertEquals(List.of("design"), stageColumns(model.refresh().pipeline()));

        model.setStageFilter(null);

        assertNull(model.stageFilter());
        List<String> expected = new ArrayList<>(VStages.STAGES);
        expected.add(PipelineSnapshot.UNTRACKED);
        assertEquals(expected, stageColumns(model.refresh().pipeline()));
        assertEquals(1, model.refresh().total());
    }

    @Test
    public void storedStageWinsOverRoleDerivationInTheFilter() {
        // role pm would derive "requirements"; the stored stage is "design"
        String id = sprintTicket("pm on design", "design", "pm");

        BoardModel model = pipelineModel();
        model.setStageFilter(java.util.Set.of("requirements"));
        BoardSnapshot byRole = model.refresh();
        assertEquals("role-derived stage must NOT place the ticket in requirements",
                0, byRole.pipeline().column("requirements").rows().size());
        assertEquals(0, byRole.total());

        model.setStageFilter(java.util.Set.of("design"));
        BoardSnapshot byStored = model.refresh();
        assertEquals(id, byStored.pipeline().column("design").rows().get(0).id());
        assertEquals(1, byStored.total());
    }
}
