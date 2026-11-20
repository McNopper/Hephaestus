package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Model tests for the stage visibility filter's edge cases beyond {@link BoardModelPipelineTest}:
 * populated-but-hidden columns, invalid stage ids in the filter set, stage-less rows under a
 * filter, the combination with blockedOnly in the BACKLOG pseudo-sprint, clearing the filter, and
 * stored-stage-wins filtering for tickets whose role would derive a different stage.
 */
public class BoardModelStageFilterTest extends BoardModelTestHarness {

  /** Creates a sprint-planned ticket with the given stage (nullable) and role. */
  private String sprintTicket(String title, String stage, String role) {
    var t = store.create("p", spec(title, role));
    if (stage != null) {
      store.update("p", t.id, Map.of("stage", (Object) stage));
    }
    return t.id;
  }

  /** Creates a backlog (sprint-less) ticket with the given stage and role. */
  private String backlogTicket(String title, String stage, String role) {
    TaskStore.CreateSpec spec =
        new TaskStore.CreateSpec(title, "", "task", role, "medium", 0, List.of(), List.of(), "T");
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
    model.setMode(BoardModel.BoardMode.PIPELINE);
    return model;
  }

  private static List<String> stageColumns(PipelineSnapshot pipeline) {
    List<String> stages = new ArrayList<>();
    pipeline.columns().forEach(c -> stages.add(c.stage()));
    return stages;
  }

  @Test
  public void requirementsOnlyFilterHidesPopulatedDesignAndStageless() {
    sprintTicket("req work", "requirements", "pm");
    sprintTicket("design work", "design", "developer");
    sprintTicket("stage-less", null, "research"); // no stage: matches no selection
    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("requirements"));

    BoardSnapshot snapshot = model.refresh();

    assertEquals(List.of("requirements"), stageColumns(snapshot.pipeline()));
    assertEquals(1, snapshot.pipeline().column("requirements").rows().size());
    // the stage-less row is filtered out with the design row: only req work counts
    assertEquals(1, snapshot.total());
  }

  @Test
  public void invalidStageInFilterMatchesNothingWithoutCrashing() {
    sprintTicket("design work", "design", "developer");
    BoardModel model = pipelineModel();

    model.setStageFilter(java.util.Set.of("bogus"));
    BoardSnapshot solo = model.refresh();
    assertNull("an unknown id must not make the refresh fail", solo.error());
    assertTrue("an unknown id alone matches no column", stageColumns(solo.pipeline()).isEmpty());
    assertEquals(0, solo.total());

    model.setStageFilter(java.util.Set.of("bogus", "design"));
    BoardSnapshot mixed = model.refresh();
    assertEquals(
        "unknown ids are ignored; valid ones still filter",
        List.of("design"),
        stageColumns(mixed.pipeline()));
    assertEquals(1, mixed.total());
  }

  @Test
  public void flatModeRetiredUntrackedFilterIdMatchesNothingWithoutCrashing() {
    sprintTicket("stage-less", null, "research");
    sprintTicket("staged", "design", "developer");
    BoardModel model = new BoardModel(root, "p");
    // the retired id (an old settings file may still carry it before the view's
    // sanitization kicks in) behaves like any unknown id: matches nothing
    model.setStageFilter(java.util.Set.of("(untracked)"));

    BoardSnapshot snapshot = model.refresh();

    assertNull("an unknown id must not make the refresh fail", snapshot.error());
    assertEquals(0, snapshot.total());
    int rows = snapshot.columns().values().stream().mapToInt(List::size).sum();
    assertEquals(0, rows);
  }

  @Test
  public void blockedOnlyAndStageFilterCombineInBacklogScope() {
    String blockedDesign = backlogTicket("blocked design", "design", "developer");
    backlogTicket("free design", "design", "developer");
    backlogTicket("blocked stray", null, "research"); // blocked + stage-less (filtered out)
    store.setBlocked("p", blockedDesign, "waiting", "pm");
    BoardModel model = new BoardModel(root, "p");
    model.setMode(BoardModel.BoardMode.PIPELINE);
    model.setBlockedOnly(true);
    model.setStageFilter(java.util.Set.of("design"));

    BoardSnapshot snapshot = model.refresh();

    assertEquals(List.of("design"), stageColumns(snapshot.pipeline()));
    assertEquals(blockedDesign, snapshot.pipeline().column("design").rows().get(0).id());
    assertEquals(1, snapshot.total());
    assertEquals(1, snapshot.blockedCount());
  }

  @Test
  public void clearingTheFilterRestoresAllTenColumns() {
    sprintTicket("design work", "design", "developer");
    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("design"));
    assertEquals(List.of("design"), stageColumns(model.refresh().pipeline()));

    model.setStageFilter(null);

    assertNull(model.stageFilter());
    assertEquals(new ArrayList<>(VStages.STAGES), stageColumns(model.refresh().pipeline()));
    assertEquals(1, model.refresh().total());
  }

  @Test
  public void storedStageWinsOverRoleDerivationInTheFilter() {
    // role pm would derive "requirements"; the stored stage is "design"
    String id = sprintTicket("pm on design", "design", "pm");

    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("requirements"));
    BoardSnapshot byRole = model.refresh();
    assertEquals(
        "role-derived stage must NOT place the ticket in requirements",
        0,
        byRole.pipeline().column("requirements").rows().size());
    assertEquals(0, byRole.total());

    model.setStageFilter(java.util.Set.of("design"));
    BoardSnapshot byStored = model.refresh();
    assertEquals(id, byStored.pipeline().column("design").rows().get(0).id());
    assertEquals(1, byStored.total());
  }
}
