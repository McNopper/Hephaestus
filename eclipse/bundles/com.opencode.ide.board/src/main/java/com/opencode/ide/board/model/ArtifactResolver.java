package com.opencode.ide.board.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * SWT-free resolution of {@code file}/{@code path} ticket artifacts against the repository root.
 * Agent-written refs are <b>untrusted</b>: paths are confined to the repo subtree (lexical
 * normalize + {@code toRealPath} so {@code ..} and symlinks/junctions cannot escape) and
 * executable/script file types are refused — {@code Program.launch} is ShellExecute on Windows.
 */
public final class ArtifactResolver {

  /**
   * Executable/script extensions never opened (deny-list; directories are always allowed). A
   * deny-list is whack-a-mole by nature — extend as ShellExecute-abusable types surface.
   */
  public static final Set<String> UNSAFE_EXTENSIONS =
      Set.of(
          "exe",
          "bat",
          "cmd",
          "com",
          "ps1",
          "psm1",
          "msi",
          "msp",
          "mst",
          "scr",
          "vbs",
          "vbe",
          "js",
          "jse",
          "wsf",
          "wsh",
          "jar",
          "hta",
          "cpl",
          "lnk",
          "sh",
          "url",
          "scf",
          "py",
          "pyw",
          "pl",
          "rb",
          "ahk",
          "settingcontent-ms",
          "reg",
          "diagcab",
          "msc",
          "inf",
          "ade",
          "adp",
          "appx");

  /** Either an openable {@link #path()}, or a {@link #refusal()} reason. */
  public record Result(Path path, String refusal) {

    static Result open(Path path) {
      return new Result(path, null);
    }

    static Result refuse(String reason) {
      return new Result(null, reason);
    }

    public boolean openable() {
      return path != null;
    }
  }

  private ArtifactResolver() {}

  /**
   * @param repoRoot the repository root artifacts are resolved against ({@code null} -> refusal)
   * @param ref the artifact ref (absolute or repo-relative)
   */
  public static Result resolve(Path repoRoot, String ref) {
    if (repoRoot == null) {
      return Result.refuse("Repo root unknown — cannot resolve " + ref);
    }
    if (ref == null || ref.isBlank()) {
      return Result.refuse("Artifact has no reference.");
    }
    Path raw = Path.of(ref);
    Path resolved = (raw.isAbsolute() ? raw : repoRoot.resolve(raw)).normalize();
    Path repo = repoRoot.normalize();
    if (!resolved.startsWith(repo)) {
      return Result.refuse("Refusing to open a path outside the repository:\n" + resolved);
    }
    if (!Files.exists(resolved)) {
      return Result.refuse("Not found: " + resolved);
    }
    // exists -> toRealPath is safe; re-check containment so a symlink/junction
    // inside the repo cannot point out of it
    try {
      Path real = resolved.toRealPath();
      Path realRepo = repo;
      try {
        realRepo = repo.toRealPath();
      } catch (java.io.IOException ignored) {
        // keep the lexical root
      }
      if (!real.startsWith(realRepo)) {
        return Result.refuse(
            "Refusing to open a path that escapes the repository via a link:\n" + resolved);
      }
      resolved = real;
    } catch (java.io.IOException ignored) {
      // unreadable target: the lexical containment above still holds
    }
    if (!Files.isDirectory(resolved)) {
      String name = resolved.getFileName().toString();
      int dot = name.lastIndexOf('.');
      String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
      if (UNSAFE_EXTENSIONS.contains(extension)) {
        return Result.refuse("Refusing to open executable file type:\n" + resolved);
      }
    }
    return Result.open(resolved);
  }
}
