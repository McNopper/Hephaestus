package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Root resolution: explicit property wins, otherwise walk up for .opencode/tasks. */
public class StoreIoTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void walksUpToDotOpencodeTasks() throws Exception {
    Path base = tmp.getRoot().toPath();
    Path store = base.resolve(".opencode").resolve("tasks");
    Files.createDirectories(store);
    Path deep = base.resolve("a").resolve("b").resolve("c");
    Files.createDirectories(deep);
    assertEquals(store, StoreIo.resolveRoot(null, deep));
  }

  @Test
  public void returnsNullWhenNoStoreAbove() throws Exception {
    Path deep = tmp.getRoot().toPath().resolve("a").resolve("b");
    Files.createDirectories(deep);
    assertNull(StoreIo.resolveRoot(null, deep));
  }

  @Test
  public void explicitRootWinsOverWalkUp() {
    File elsewhere = new File(tmp.getRoot(), "elsewhere");
    assertEquals(elsewhere.toPath(), StoreIo.resolveRoot(elsewhere, tmp.getRoot().toPath()));
  }

  @Test
  public void projectDirsSortCaseSensitivelyAndSkipNonProjects() throws Exception {
    Path root = tmp.getRoot().toPath();
    withTaskFile(root.resolve("a-dir"));
    withTaskFile(root.resolve("B-dir"));
    // a project may also be just a _meta.json holder (no task files at all)
    Files.createDirectories(root.resolve("meta-only"));
    Files.writeString(root.resolve("meta-only").resolve("_meta.json"), "{}");
    // neither meta nor task files: not a project
    Files.createDirectories(root.resolve("zz-empty"));
    // a plain file at the store root must never be mistaken for a project
    Files.writeString(root.resolve("stray.txt"), "not a directory");

    List<Path> dirs = StoreIo.projectDirs(root);

    // U-091: on NTFS Files.list is case-insensitive ("a-dir" before "B-dir"),
    // while List::sort with String.compareTo is case-sensitive ("B-dir" first),
    // so this exact order proves the sort (and its key extractor) really runs;
    // a meta-only project counts, an empty directory and a stray file do not.
    assertEquals(List.of("B-dir", "a-dir", "meta-only"), names(dirs));
  }

  @Test
  public void taskFilesSortCaseSensitivelyAndSkipNonRegularFiles() throws Exception {
    Path dir = tmp.newFolder("proj").toPath();
    Files.writeString(dir.resolve("a-note.md"), "x");
    Files.writeString(dir.resolve("B-note.md"), "x");
    Files.writeString(dir.resolve("readme.txt"), "x");
    Files.writeString(dir.resolve("_meta.json"), "{}");
    Files.writeString(dir.resolve(".hidden.md"), "x");
    // a DIRECTORY whose name ends in .md must not be listed as a task file
    Files.createDirectories(dir.resolve("sub.md"));

    // U-091: same NTFS-vs-compareTo case argument as in projectDirs above
    assertEquals(List.of("B-note.md", "a-note.md"), names(StoreIo.taskFiles(dir)));
  }

  private static void withTaskFile(Path dir) throws IOException {
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("keep.md"), "placeholder");
  }

  private static List<String> names(List<Path> paths) {
    return paths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
  }
}
