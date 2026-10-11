package com.opencode.ide.mojo.tasks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskFileCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read-only loader that turns a store root into renderable {@link BoardRenderer.ProjectBoard}s.
 * Unlike {@code TaskStore} transactions it creates no directories and no lock files, so rendering
 * never mutates the store.
 */
public final class StoreBoards {

  private StoreBoards() {}

  /**
   * Loads one board per project directory. {@code requestedSprint} selects a sprint id explicitly;
   * null picks each project's most recently created active sprint, falling back to the "{@code (no
   * sprint)}" scope (tickets with {@code sprint: null}) when none is active. Unreadable files are
   * reported through {@code onWarn} and skipped.
   */
  public static List<BoardRenderer.ProjectBoard> load(
      Path root, String requestedSprint, Consumer<String> onWarn) throws IOException {
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
      boards.add(BoardRenderer.build(project, tasks, readSprints(dir, onWarn), requestedSprint));
    }
    return boards;
  }

  private static Map<String, Task.Sprint> readSprints(Path dir, Consumer<String> onWarn) {
    Map<String, Task.Sprint> sprints = new LinkedHashMap<>();
    JsonObject meta;
    try {
      meta = StoreIo.readMeta(dir);
    } catch (IOException | RuntimeException e) {
      onWarn.accept("cannot read " + dir.resolve("_meta.json") + ": " + e.getMessage());
      return sprints;
    }
    if (meta == null || meta.get("sprints") == null || !meta.get("sprints").isJsonObject()) {
      return sprints;
    }
    for (Map.Entry<String, JsonElement> e : meta.getAsJsonObject("sprints").entrySet()) {
      if (!e.getValue().isJsonObject()) {
        continue;
      }
      JsonObject s = e.getValue().getAsJsonObject();
      String id = str(s, "id") != null ? str(s, "id") : e.getKey();
      sprints.put(
          e.getKey(),
          new Task.Sprint(
              id,
              str(s, "goal") == null ? "" : str(s, "goal"),
              str(s, "status") == null ? "" : str(s, "status"),
              instantOrNull(str(s, "created_at")),
              instantOrNull(str(s, "closed_at"))));
    }
    return sprints;
  }

  private static String str(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull() ? null : e.getAsString();
  }

  private static Instant instantOrNull(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException e) {
      try {
        return java.time.OffsetDateTime.parse(s).toInstant();
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }
}
