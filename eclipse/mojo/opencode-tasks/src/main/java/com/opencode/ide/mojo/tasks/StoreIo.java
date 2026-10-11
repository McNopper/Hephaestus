package com.opencode.ide.mojo.tasks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Read-only scanning of a task-store root (the directory holding one subdirectory per project),
 * plus the store's atomic write pattern so fixes replace files the same way {@code TaskStore}
 * itself does. Unlike {@code TaskStore} transactions, nothing here creates directories or lock
 * files: validation and rendering stay non-mutating.
 */
public final class StoreIo {

  private StoreIo() {}

  /**
   * Resolves the store root: the explicitly configured directory when given, otherwise the nearest
   * ancestor of {@code startDir} containing a {@code .opencode/tasks} directory. Returns null when
   * nothing is found (the caller decides whether that is a skip or an error).
   */
  public static Path resolveRoot(File configured, Path startDir) {
    if (configured != null) {
      return configured.toPath();
    }
    for (Path dir = startDir; dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(".opencode").resolve("tasks");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * The project directories of a store root, sorted by name. A directory counts as a project when
   * it holds a {@code _meta.json} or at least one candidate task file.
   */
  public static List<Path> projectDirs(Path root) {
    List<Path> out = new ArrayList<>();
    try (var stream = Files.list(root)) {
      stream.filter(Files::isDirectory).forEach(out::add);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + root, e);
    }
    out.removeIf(dir -> !looksLikeProject(dir));
    out.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return out;
  }

  private static boolean looksLikeProject(Path dir) {
    if (Files.isRegularFile(dir.resolve("_meta.json"))) {
      return true;
    }
    return !taskFiles(dir).isEmpty();
  }

  /**
   * The candidate task files of one project directory, sorted by name, excluding files starting
   * with {@code .} or {@code _} (same filter as the store's loader).
   */
  public static List<Path> taskFiles(Path projectDir) {
    List<Path> out = new ArrayList<>();
    try (var stream = Files.list(projectDir)) {
      stream.filter(Files::isRegularFile).forEach(out::add);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + projectDir, e);
    }
    out.removeIf(
        p -> {
          String name = p.getFileName().toString();
          return !name.endsWith(".md") || name.startsWith(".") || name.startsWith("_");
        });
    out.sort(Comparator.comparing(p -> p.getFileName().toString()));
    return out;
  }

  /**
   * Parses a project's {@code _meta.json}; null when absent. Throws a runtime exception when
   * present but not a JSON object (the caller reports it).
   */
  public static JsonObject readMeta(Path projectDir) throws IOException {
    Path meta = projectDir.resolve("_meta.json");
    if (!Files.isRegularFile(meta)) {
      return null;
    }
    return JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  /**
   * The store's write pattern: temp file next to the target, then replace (the Windows-safe
   * replace; {@code ATOMIC_MOVE + REPLACE_EXISTING} is undefined per spec).
   */
  public static void writeAtomic(Path target, String content) throws IOException {
    Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
