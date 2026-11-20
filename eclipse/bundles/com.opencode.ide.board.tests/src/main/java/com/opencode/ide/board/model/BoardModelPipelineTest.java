package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * Unit tests for the PIPELINE layout of the SWT-free {@link BoardModel}: all-ten-columns grouping
 * (stored stage, role fallback), blocked counts/points per column, the blockedOnly filter in both
 * modes, and the sprint selector's effect on the pipeline.
 */
public class BoardModelPipelineTest extends BoardModelTestHarness {

  /** Creates a sprint-planned ticket with the given stage (nullable) and role. */
  private String sprintTicket(String title, String stage, String role) {
    var t = store.create("p", spec(title, role));
    Map<String, Object> changes = new HashMap<>();
    changes.put("story_points", 3);
    if (stage != null) {
      changes.put("stage", stage);
    }
    store.update("p", t.id, changes);
    return t.id;
  }

  private BoardModel pipelineModel() {
    BoardModel model = new BoardModel(root, "p");
    model.setMode(BoardModel.BoardMode.PIPELINE);
    return model;
  }

  @Test
  public void defaultsAreFlatAndUnfiltered() {
    BoardModel model = new BoardModel(root, "p");
    assertEquals(BoardModel.BoardMode.FLAT, model.mode());
    assertFalse(model.blockedOnly());
    assertNull(model.refresh().pipeline());
  }

  @Test
  public void allTenStageColumnsAlwaysPresentInCanonicalOrder() {
    sprintTicket("one", "design", "developer");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    assertNotNull(pipeline);
    List<String> stages = new ArrayList<>();
    pipeline.columns().forEach(c -> stages.add(c.stage()));
    assertEquals(new ArrayList<>(VStages.STAGES), stages);
  }

  @Test
  public void emptyStagesStillHavePresentEmptyColumns() {
    sprintTicket("solo", "design", "developer");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    StageColumn requirements = pipeline.column("requirements");
    assertTrue(requirements.rows().isEmpty());
    assertEquals(0, requirements.blockedCount());
    assertEquals(0, requirements.points());
  }

  @Test
  public void stagedTicketLandsInItsStageColumn() {
    String id = sprintTicket("designed", "design", "developer");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    assertEquals(id, pipeline.column("design").rows().get(0).id());
    assertTrue(pipeline.column("implementation").rows().isEmpty());
  }

  @Test
  public void legacyDeveloperTicketLandsInImplementationViaRole() {
    String id = sprintTicket("legacy", null, "developer");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    assertEquals(id, pipeline.column("implementation").rows().get(0).id());
  }

  @Test
  public void pmRoleTicketLandsInRequirements() {
    String id = sprintTicket("scope work", null, "pm");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    assertEquals(id, pipeline.column("requirements").rows().get(0).id());
  }

  @Test
  public void stagelessRowLandsInNoVColumn() {
    String id = sprintTicket("odd role", null, "research");
    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();

    // the stage-less group is retired (owner directive 2026-11-19): a row with
    // no resolvable stage has no V column - flat mode still lists it
    for (String stage : VStages.STAGES) {
      assertTrue(
          "stage-less row must not appear in " + stage,
          pipeline.column(stage).rows().stream().noneMatch(r -> id.equals(r.id())));
    }
  }

  @Test
  public void blockedCountsAndPointsPerColumn() {
    String a = sprintTicket("a", "design", "developer");
    sprintTicket("b", "design", "developer");
    store.setBlocked("p", a, "waiting on other agent", "pm");

    PipelineSnapshot pipeline = pipelineModel().refresh().pipeline();
    StageColumn design = pipeline.column("design");

    assertEquals(2, design.rows().size());
    assertEquals(1, design.blockedCount());
    assertEquals(6, design.points());
    assertEquals(0, pipeline.column("requirements").blockedCount());
  }

  @Test
  public void blockedOnlyFiltersPipelineColumns() {
    String blockedId = sprintTicket("blocked one", "design", "developer");
    sprintTicket("free", "design", "developer");
    store.setBlocked("p", blockedId, "waiting", "pm");

    BoardModel model = pipelineModel();
    model.setBlockedOnly(true);
    assertTrue(model.blockedOnly());
    BoardSnapshot snapshot = model.refresh();

    List<TicketRow> design = snapshot.pipeline().column("design").rows();
    assertEquals(1, design.size());
    assertEquals(blockedId, design.get(0).id());
    assertEquals(1, snapshot.total());
    assertEquals(1, snapshot.blockedCount());
  }

  @Test
  public void blockedOnlyFiltersFlatColumnsToo() {
    String blockedId = sprintTicket("blocked one", "design", "developer");
    sprintTicket("free", "design", "developer");
    store.setBlocked("p", blockedId, "waiting", "pm");

    BoardModel model = new BoardModel(root, "p");
    model.setBlockedOnly(true);
    BoardSnapshot snapshot = model.refresh();

    assertEquals(1, snapshot.total());
    assertEquals(1, snapshot.blockedCount());
    int rows = snapshot.columns().values().stream().mapToInt(List::size).sum();
    assertEquals(1, rows);
    assertEquals(blockedId, snapshot.column("blocked").get(0).id());
  }

  @Test
  public void pipelineShowsAllTicketsRegardlessOfSprintSelection() {
    // user direction 2026-09-18: the wave selector is gone - the board
    // displays ALL tickets; the selected sprint only scopes dispatch
    sprintTicket("in sprint", "design", "developer");
    var loose = store.create("p", TaskStore.CreateSpec.of("loose"));
    store.update("p", loose.id, Map.of("stage", (Object) "design"));

    BoardModel model = new BoardModel(root, "p");
    model.setMode(BoardModel.BoardMode.PIPELINE);
    PipelineSnapshot sprintBoard = model.refresh().pipeline();
    assertEquals(2, sprintBoard.column("design").rows().size());

    PipelineSnapshot backlogBoard = model.refresh().pipeline();
    assertEquals(2, backlogBoard.column("design").rows().size());
  }

  @Test
  public void flatModeStillGroupsByStatusWithoutPipeline() {
    sprintTicket("flat view", "design", "developer");
    BoardModel model = new BoardModel(root, "p");
    BoardSnapshot snapshot = model.refresh();

    assertNull(snapshot.pipeline());
    assertEquals(
        "flat columns = the valid statuses (the owner's 7-state model)",
        7,
        snapshot.columns().size());
    assertTrue(snapshot.column("not-implemented").isEmpty());
    assertEquals(1, snapshot.column("ready").size());
  }

  // ---------- stage visibility filter ----------

  @Test
  public void stageFilterHidesPipelineColumnsNotSelected() {
    sprintTicket("req work", "requirements", "pm");
    sprintTicket("arch work", "architecture", "architect");
    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("requirements"));

    PipelineSnapshot pipeline = model.refresh().pipeline();

    List<String> stages = new ArrayList<>();
    pipeline.columns().forEach(c -> stages.add(c.stage()));
    assertEquals("only the selected stage column may be present", List.of("requirements"), stages);
    assertEquals(1, pipeline.column("requirements").rows().size());
  }

  @Test
  public void stageFilterKeepsCanonicalOrderOfSelectedColumns() {
    sprintTicket("a", "requirements", "pm"); // also ensures the project dir exists
    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("test-design", "design", "requirements"));

    List<String> stages = new ArrayList<>();
    model.refresh().pipeline().columns().forEach(c -> stages.add(c.stage()));

    assertEquals(List.of("requirements", "design", "test-design"), stages);
  }

  @Test
  public void stageFilterHidesStagelessRows() {
    sprintTicket("scope-ish", null, "weird-role"); // no stage: role maps to no stage
    BoardModel model = pipelineModel();

    // with a filter active a stage-less row matches no stage selection -> hidden
    model.setStageFilter(java.util.Set.of("design"));
    BoardSnapshot filtered = model.refresh();
    List<String> stages = new ArrayList<>();
    filtered.pipeline().columns().forEach(c -> stages.add(c.stage()));
    assertEquals("only the selected stage column may be present", List.of("design"), stages);
    assertEquals(0, filtered.total());

    // without a filter it is visible again (flat mode lists it), but it still
    // has no V column to land in
    model.setStageFilter(null);
    BoardSnapshot unfiltered = model.refresh();
    assertEquals(1, unfiltered.total());
    assertTrue(
        unfiltered.pipeline().columns().stream()
            .flatMap(c -> c.rows().stream())
            .noneMatch(r -> "scope-ish".equals(r.title())));
  }

  @Test
  public void stageFilterAlsoFiltersFlatModeRowsByEffectiveStage() {
    sprintTicket("req work", "requirements", "pm");
    sprintTicket("impl work", "implementation", "developer");
    BoardModel model = new BoardModel(root, "p");
    model.setStageFilter(java.util.Set.of("requirements"));

    BoardSnapshot snapshot = model.refresh();

    assertEquals(1, snapshot.total());
    // T-175: the staged create tags the name - the Board shows the tagged title
    assertEquals("[requirements] req work", snapshot.column("ready").get(0).title());
  }

  @Test
  public void nullAndEmptyStageFilterMeanNoFiltering() {
    sprintTicket("a", "design", "developer");
    sprintTicket("b", "requirements", "pm");
    BoardModel model = pipelineModel();

    assertNull(model.stageFilter());
    assertEquals(VStages.STAGES.size(), model.refresh().pipeline().columns().size());

    model.setStageFilter(java.util.Set.of());
    assertNull("empty set normalizes to no filter", model.stageFilter());
    assertEquals(VStages.STAGES.size(), model.refresh().pipeline().columns().size());
  }

  @Test
  public void stageFilterCombinedWithBlockedOnly() {
    String blocked = sprintTicket("blocked req", "requirements", "pm");
    sprintTicket("free req", "requirements", "pm");
    store.setBlocked("p", blocked, "waiting", "test");
    BoardModel model = pipelineModel();
    model.setStageFilter(java.util.Set.of("requirements"));
    model.setBlockedOnly(true);

    PipelineSnapshot pipeline = model.refresh().pipeline();

    assertEquals(1, pipeline.column("requirements").rows().size());
    assertEquals(blocked, pipeline.column("requirements").rows().get(0).id());
    assertEquals(1, pipeline.column("requirements").blockedCount());
  }
}
