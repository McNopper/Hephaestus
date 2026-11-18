package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * T-052 settings migration: a persisted Board override pointing at the pre-relocation store ({@code
 * <repo>/.opencode/tasks}) is re-pointed to the relocated sibling when that exists; everything else
 * is honored unchanged (the visible not-found notice contract stays intact).
 */
public class TasksOverrideMigrationTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void legacyOverrideWithExistingRelocationIsRepointed() throws IOException {
    Path repo = tmp.getRoot().toPath().resolve("repo");
    Path relocated = repo.resolve(".harness").resolve("tasks");
    Files.createDirectories(relocated);
    Path legacy = repo.resolve(".opencode").resolve("tasks");
    Files.createDirectories(legacy);

    assertEquals(relocated.toString(), TasksOverrideMigration.migrate(legacy.toString()));
    assertEquals(
        "forward slashes are tolerated",
        relocated.toString(),
        TasksOverrideMigration.migrate(legacy.toString().replace('\\', '/')));
  }

  @Test
  public void legacyOverrideWithoutRelocationStaysVisible() throws IOException {
    Path legacy = tmp.getRoot().toPath().resolve("repo").resolve(".opencode").resolve("tasks");
    Files.createDirectories(legacy);

    assertEquals(
        "no relocated sibling -> honor the value so the not-found notice applies",
        legacy.toString(),
        TasksOverrideMigration.migrate(legacy.toString()));
  }

  @Test
  public void nonLegacyAndBlankValuesPassThroughUnchanged() {
    String modern = "C:\\repo\\.harness\\tasks";
    assertEquals(modern, TasksOverrideMigration.migrate(modern));
    assertEquals("C:\\whatever", TasksOverrideMigration.migrate("C:\\whatever"));
    assertEquals("  x  ", TasksOverrideMigration.migrate("  x  "));
    assertNull(TasksOverrideMigration.migrate(null));
    assertEquals("", TasksOverrideMigration.migrate(""));
    assertEquals(
        "blank input passes through verbatim", "   ", TasksOverrideMigration.migrate("   "));
  }

  @Test
  public void aRepoShapedLegacyPathWithoutTwoParentsIsUnchanged() {
    // defensive: relative one-segment values never claim a parent chain
    assertEquals(
        ".opencode" + java.io.File.separator + "tasks",
        TasksOverrideMigration.migrate(".opencode" + java.io.File.separator + "tasks"));
  }
}
