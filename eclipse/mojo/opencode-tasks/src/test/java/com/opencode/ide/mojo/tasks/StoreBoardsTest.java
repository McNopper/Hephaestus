package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Board loading straight from hand-written store fixtures: the loader reads EVERY task file of a
 * project (owner model 2026-11-20 - no sprint scope, sprints are gone from the store), skips
 * unparsable files with a warning, and renders the seven status columns. Legacy fixtures carry the
 * retired backlog statuses and a {@code sprint:} key - both migrate on read (backlog statuses read
 * as {@code ready}, the sprint key is discarded), so they land in the ready column.
 */
public class StoreBoardsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void unparsableTaskFilesAreSkippedAndWarned() throws Exception {
    Path dir = project("warn-store", "{}");
    ticket(dir, "T-001", "Healthy ticket", "ready", null);
    Files.writeString(dir.resolve("broken.md"), "not a ticket at all\n", StandardCharsets.UTF_8);

    List<String> warnings = new ArrayList<>();
    List<BoardRenderer.ProjectBoard> boards = StoreBoards.load(dir.getParent(), warnings::add);

    assertEquals(1, boards.size());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("skipping unparsable task file"));
    assertTrue(warnings.get(0).contains("broken.md"));
    String md = BoardRenderer.markdown(boards);
    assertTrue(md.contains("Healthy ticket"));
    assertFalse(md.contains("not a ticket at all"));
  }

  @Test
  public void everyValidStatusColumnRendersEvenWhenEmpty() throws Exception {
    Path dir = project("columns-store", "{}");
    ticket(dir, "T-001", "Only ready work", "ready", null);

    String md = render(dir);

    for (String status : com.opencode.ide.tasks.Task.VALID_STATUSES) {
      assertTrue("column header for " + status, md.contains("## " + status + " ("));
    }
    assertTrue(md.contains("## ready (1)"));
    assertTrue("empty columns say so", md.contains("_(empty)_"));
    assertTrue(md.contains("Only ready work"));
  }

  @Test
  public void legacyBacklogTicketsLandInTheReadyColumn() throws Exception {
    // a pre-model fixture: the retired backlog statuses read as ready (migration
    // on read) and the sprint key is discarded - no sprint scope anywhere
    Path dir = project("legacy-store", "{}");
    ticket(dir, "T-001", "Legacy backlog work", "product-backlog", "S-01");
    ticket(dir, "T-002", "Legacy sprint work", "sprint-backlog", "S-01");

    String md = render(dir);

    assertTrue(md.contains("## ready (2)"));
    assertTrue(md.contains("Legacy backlog work"));
    assertTrue(md.contains("Legacy sprint work"));
    assertFalse("no backlog columns exist anymore", md.contains("## product-backlog"));
    assertFalse(md.contains("## sprint-backlog"));
    assertFalse("no sprint line in the render", md.contains("Sprint:"));
    assertFalse(md.contains("S-01"));
  }

  @Test
  public void brokenMetaDoesNotStopTheProjectFromRendering() throws Exception {
    // the loader never parses _meta.json (only its presence marks a project
    // directory), so corrupt sidecar metadata cannot break the board
    Path dir = project("meta-store", "{ this is not json");
    ticket(dir, "T-001", "Orphaned ticket", "ready", null);

    List<String> warnings = new ArrayList<>();
    String md = BoardRenderer.markdown(StoreBoards.load(dir.getParent(), warnings::add));

    assertTrue("nothing parsed, nothing warned", warnings.isEmpty());
    assertTrue(md.contains("Orphaned ticket"));
  }

  @Test
  public void totalsLineCountsStoryPointsAcrossTheBoard() throws Exception {
    Path dir = project("points-store", "{}");
    ticket(dir, "T-001", "Three points", "ready", null, 3);
    ticket(dir, "T-002", "Two points done", "done", null, 2);

    String md = render(dir);

    assertTrue(md.contains("**Totals:** 5 points total, 2 points done."));
  }

  // -- fixture helpers -----------------------------------------------------

  /** Creates one project directory holding the given raw _meta.json content. */
  private Path project(String name, String metaJson) throws IOException {
    Path root = tmp.newFolder(name).toPath();
    Path dir = root.resolve("proj");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("_meta.json"), metaJson, StandardCharsets.UTF_8);
    return dir;
  }

  /** Writes one task file into the project directory (legacy status/sprint keys allowed). */
  private static void ticket(Path dir, String id, String title, String status, String sprint)
      throws IOException {
    ticket(dir, id, title, status, sprint, 0);
  }

  private static void ticket(
      Path dir, String id, String title, String status, String sprint, int points)
      throws IOException {
    Files.writeString(
        dir.resolve(id + ".md"),
        "---\n"
            + "id: "
            + id
            + "\n"
            + "title: "
            + title
            + "\n"
            + "type: task\n"
            + "status: "
            + status
            + "\n"
            + "priority: medium\n"
            + "role: developer\n"
            + "stage: null\n"
            + "model: null\n"
            + "story_points: "
            + points
            + "\n"
            + "sprint: "
            + (sprint == null ? "null" : sprint)
            + "\n"
            + "assignee: null\n"
            + "resume_to: null\n"
            + "blocker: null\n"
            + "labels: []\n"
            + "acceptance_criteria: []\n"
            + "created_at: 2026-01-01T00:00:00.000Z\n"
            + "updated_at: 2026-01-01T00:00:00.000Z\n"
            + "---\n\n"
            + title
            + "\n",
        StandardCharsets.UTF_8);
  }

  private static String render(Path projectDir) throws IOException {
    return BoardRenderer.markdown(StoreBoards.load(projectDir.getParent(), s -> {}));
  }
}
