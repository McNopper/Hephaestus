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
 * Board loading straight from hand-written store fixtures (U-091): the readSprints/instantOrNull
 * branches that decide which active sprint a board shows. Each test writes _meta.json and ticket
 * files directly, then renders through StoreBoards.load and asserts which sprint won.
 *
 * <p>Equivalent-mutant note (PIT NO_COVERAGE, deliberately untested): the early {@code return
 * sprints} statements in readSprints (unreadable/absent sprints metadata) are mutated to {@code
 * return Collections.emptyMap()} - the map is empty at both points, so the mutant is
 * observationally identical and no test can kill it.
 */
public class StoreBoardsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void latestActiveSprintIsTheNewestActiveOne() throws Exception {
    // insertion order favors S-02; the newer created_at of S-01 must win
    // (kills the Instant.parse -> null mutant: with both timestamps gone the
    // id tiebreak would pick the lexicographically larger S-02)
    Path dir =
        project(
            "sprint-store",
            "{\"sprints\":{"
                + "\"S-02\":{\"id\":\"S-02\",\"status\":\"active\",\"goal\":\"older goal\","
                + "\"created_at\":\"2026-01-01T00:00:00Z\"},"
                + "\"S-01\":{\"id\":\"S-01\",\"status\":\"active\",\"goal\":\"newer goal\","
                + "\"created_at\":\"2026-01-02T00:00:00Z\"}},"
                + "\"seq\":{\"T\":2},\"counter\":2}");
    ticket(dir, "T-001", "Newer ticket", "S-01");
    ticket(dir, "T-002", "Older ticket", "S-02");

    String md = render(dir);
    assertTrue(md.contains("Sprint: **S-01** (active)"));
    assertTrue(md.contains("Newer ticket"));
    assertFalse(md.contains("Older ticket"));
  }

  @Test
  public void missingCreatedAtLosesToAKnownInstant() throws Exception {
    // S-Z has no created_at (treated as Instant.EPOCH), S-A has one; S-A must
    // win even though S-Z sorts after it by id (kills the null-createdAt
    // comparator mutants: they NPE or flip the winner to S-Z)
    Path dir =
        project(
            "instant-store",
            "{\"sprints\":{"
                + "\"S-Z\":{\"id\":\"S-Z\",\"status\":\"active\",\"goal\":\"no timestamp\"},"
                + "\"S-A\":{\"id\":\"S-A\",\"status\":\"active\",\"goal\":\"known instant\","
                + "\"created_at\":\"2026-03-01T00:00:00Z\"}},"
                + "\"seq\":{\"T\":2},\"counter\":2}");
    ticket(dir, "T-001", "Zephyr ticket", "S-Z");
    ticket(dir, "T-002", "Anchor ticket", "S-A");

    String md = render(dir);
    assertTrue(md.contains("Sprint: **S-A** (active)"));
    assertTrue(md.contains("Anchor ticket"));
    assertFalse(md.contains("Zephyr ticket"));
  }

  @Test
  public void offsetCreatedAtIsDecodedForSprintOrdering() throws Exception {
    // 06:30+02:00 == 04:30Z, later than 04:00Z, so S-M must win; the minute-
    // precision offset form is rejected by Instant.parse and only decodes via
    // the OffsetDateTime fallback, killing its replaced-null mutant (S-N would
    // win instead)
    Path dir =
        project(
            "offset-store",
            "{\"sprints\":{"
                + "\"S-N\":{\"id\":\"S-N\",\"status\":\"active\",\"goal\":\"zulu\","
                + "\"created_at\":\"2026-02-01T04:00:00Z\"},"
                + "\"S-M\":{\"id\":\"S-M\",\"status\":\"active\",\"goal\":\"offset\","
                + "\"created_at\":\"2026-02-01T06:30+02:00\"}},"
                + "\"seq\":{\"T\":2},\"counter\":2}");
    ticket(dir, "T-001", "Offset ticket", "S-M");

    String md = render(dir);
    assertTrue(md.contains("Sprint: **S-M** (active)"));
  }

  @Test
  public void sprintIdFallsBackToTheMetaKeyWhenIdIsAbsent() throws Exception {
    // equal created_at -> the id tiebreak decides; SB carries no "id" field so
    // its id falls back to the meta key "SB", which sorts after "IGNORED-ID";
    // SB must win. Kills the negated id-fallback conditional (SB's id becomes
    // null and the comparison crashes instead).
    Path dir =
        project(
            "fallback-store",
            "{\"sprints\":{"
                + "\"SA\":{\"id\":\"IGNORED-ID\",\"status\":\"active\",\"goal\":\"goal from sa\","
                + "\"created_at\":\"2026-05-01T00:00:00Z\"},"
                + "\"SB\":{\"status\":\"active\",\"goal\":\"goal from sb\","
                + "\"created_at\":\"2026-05-01T00:00:00Z\"}},"
                + "\"seq\":{\"T\":1},\"counter\":2}");
    ticket(dir, "T-001", "Fallback ticket", "SB");

    String md = render(dir);
    assertTrue(md.contains("Sprint: **SB** (active) \u2014 goal from sb"));
    assertTrue(md.contains("Fallback ticket"));
  }

  @Test
  public void equalSprintTieKeepsTheFirstEntry() throws Exception {
    // identical created_at AND identical id: compare() == 0 and the first
    // entry stays the winner (kills the > -> >= boundary mutant, which would
    // let the later entry steal the board)
    Path dir =
        project(
            "tie-store",
            "{\"sprints\":{"
                + "\"K1\":{\"id\":\"DUP\",\"status\":\"active\",\"goal\":\"first goal\","
                + "\"created_at\":\"2026-06-01T00:00:00Z\"},"
                + "\"K2\":{\"id\":\"DUP\",\"status\":\"active\",\"goal\":\"second goal\","
                + "\"created_at\":\"2026-06-01T00:00:00Z\"}},"
                + "\"seq\":{\"T\":1},\"counter\":2}");
    ticket(dir, "T-001", "Tie ticket", null);

    String md = render(dir);
    assertTrue(md.contains("Sprint: **K1** (active) \u2014 first goal"));
    assertFalse(md.contains("second goal"));
  }

  @Test
  public void unparsableTaskFilesAreSkippedAndWarned() throws Exception {
    Path dir = project("warn-store", "{}");
    ticket(dir, "T-001", "Healthy ticket", null);
    Files.writeString(dir.resolve("broken.md"), "not a ticket at all\n", StandardCharsets.UTF_8);

    List<String> warnings = new ArrayList<>();
    List<BoardRenderer.ProjectBoard> boards =
        StoreBoards.load(dir.getParent(), null, warnings::add);

    assertEquals(1, boards.size());
    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("skipping unparsable task file"));
    assertTrue(warnings.get(0).contains("broken.md"));
    String md = BoardRenderer.markdown(boards);
    assertTrue(md.contains("Healthy ticket"));
    assertFalse(md.contains("not a ticket at all"));
  }

  @Test
  public void unreadableMetaWarnsAndFallsBackToNoSprint() throws Exception {
    Path dir = project("meta-store", "{ this is not json");
    ticket(dir, "T-001", "Orphaned ticket", null);

    List<String> warnings = new ArrayList<>();
    String md = BoardRenderer.markdown(StoreBoards.load(dir.getParent(), null, warnings::add));

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).contains("cannot read"));
    assertTrue(warnings.get(0).contains("_meta.json"));
    assertTrue(md.contains("Sprint: **" + BoardRenderer.NO_SPRINT + "**"));
    assertTrue(md.contains("Orphaned ticket"));
  }

  @Test
  public void metaWithoutSprintsKeyRendersNoSprint() throws Exception {
    Path dir = project("nokey-store", "{\"seq\":{}}");
    ticket(dir, "T-001", "Unassigned ticket", null);

    List<String> warnings = new ArrayList<>();
    String md = BoardRenderer.markdown(StoreBoards.load(dir.getParent(), null, warnings::add));

    assertTrue(warnings.isEmpty());
    assertTrue(md.contains("Sprint: **" + BoardRenderer.NO_SPRINT + "**"));
    assertTrue(md.contains("Unassigned ticket"));
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

  /** Writes one canonical task file into the project directory. */
  private static void ticket(Path dir, String id, String title, String sprint) throws IOException {
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
            + "status: product-backlog\n"
            + "priority: medium\n"
            + "role: developer\n"
            + "stage: null\n"
            + "model: null\n"
            + "story_points: 0\n"
            + "sprint: "
            + (sprint == null ? "null" : sprint)
            + "\n"
            + "epic: null\n"
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
    return BoardRenderer.markdown(StoreBoards.load(projectDir.getParent(), null, s -> {}));
  }
}
