package com.opencode.ide.mojo.tasks;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskFileCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Read-only loader that turns a store root into renderable {@link BoardRenderer.ProjectBoard}s.
 * Unlike {@code TaskStore} transactions it creates no directories and no lock files, so rendering
 * never mutates the store.
 */
public final class StoreBoards {

  private StoreBoards() {}

  /**
   * Loads one board per project directory - ALL tickets (owner model 2026-11-20: no sprint scope).
   * Unreadable files are reported through {@code onWarn} and skipped.
   */
  public static List<BoardRenderer.ProjectBoard> load(Path root, Consumer<String> onWarn)
      throws IOException {
    List<BoardRenderer.ProjectBoard> boards = new ArrayList<>();
    for (Path dir : StoreIo.projectDirs(root)) {
      String project = dir.getFileName().toString();
      List<Task> tasks = new ArrayList<>();
      for (Path file : StoreIo.taskFiles(dir)) {
        try {
          tasks.add(TaskFileCodec.read(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
          onWarn.accept("skipping unparsable task file " + file + ": " + e.getMessage());
        }
      }
      boards.add(BoardRenderer.build(project, tasks));
    }
    return boards;
  }
}
