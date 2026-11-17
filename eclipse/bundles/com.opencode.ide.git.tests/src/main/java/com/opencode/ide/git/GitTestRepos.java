package com.opencode.ide.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared real-git fixtures for the git tests (2026-09-23 CPD findings: these helpers were
 * copy-pasted across GitWorktreeManagerTest, StoreGitStatusTest and StoreSyncTest). Everything
 * talks to the REAL git binary in temp directories; a non-zero exit throws with the captured
 * stderr.
 */
final class GitTestRepos {

  /** One {@code git --version} probe per JVM instead of one per test. */
  private static volatile Boolean gitAvailable;

  private GitTestRepos() {}

  /** Real git in the directory: returns the stdout, throws on a non-zero exit. */
  static String git(Path dir, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(dir.toString());
    command.addAll(List.of(args));
    Process p = new ProcessBuilder(command).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    int code = p.waitFor();
    if (code != 0) {
      throw new IllegalStateException(
          "git " + List.of(args) + " failed (exit " + code + "): " + err);
    }
    return out;
  }

  /** True when a real git is on this machine - the tests skip without it. */
  static boolean gitAvailable() {
    Boolean cached = gitAvailable;
    if (cached != null) {
      return cached;
    }
    boolean available;
    try {
      Process p = new ProcessBuilder("git", "--version").start();
      p.getErrorStream().readAllBytes();
      available = p.waitFor() == 0;
    } catch (Exception e) {
      available = false;
    }
    gitAvailable = available;
    return available;
  }

  /** Writable-first recursive delete so Windows can clean up git's files. */
  static void deleteRecursively(Path root) {
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  p.toFile().setWritable(true);
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }

  /** Recursive copy of a template repo into {@code target} (created if missing). */
  static Path copyTree(Path source, Path target) throws IOException {
    try (var walk = Files.walk(source)) {
      walk.forEach(
          p -> {
            Path destination = target.resolve(source.relativize(p));
            try {
              if (Files.isDirectory(p)) {
                Files.createDirectories(destination);
              } else {
                Files.createDirectories(destination.getParent());
                Files.copy(p, destination);
              }
            } catch (IOException e) {
              throw new IllegalStateException("template copy failed for " + p + ": " + e, e);
            }
          });
    }
    return target;
  }

  /** A local one-commit repo on {@code main} with a configured author. */
  static Path newRepo(Path base, String name, String file, String content) throws Exception {
    Path repo = base.resolve(name);
    Files.createDirectories(repo);
    git(repo, "init");
    git(repo, "config", "user.email", "a@b.c");
    git(repo, "config", "user.name", "Test");
    write(repo, file, content);
    git(repo, "add", ".");
    git(repo, "commit", "-m", "initial");
    git(repo, "branch", "-M", "main");
    return repo;
  }

  /** A clone of the origin with a configured author. */
  static Path cloneOf(Path base, String name, Path origin) throws Exception {
    Path clone = base.resolve(name);
    git(base, "clone", origin.toString(), clone.toString());
    git(clone, "config", "user.email", "a@b.c");
    git(clone, "config", "user.name", "Test");
    return clone;
  }

  /** Writes the file as UTF-8, creating missing parent directories. */
  static void write(Path repo, String file, String content) throws Exception {
    Files.createDirectories(repo.resolve(file).getParent());
    Files.writeString(repo.resolve(file), content, StandardCharsets.UTF_8);
  }

  /** {@code git add .} + {@code git commit -m message}. */
  static void commit(Path repo, String message) throws Exception {
    git(repo, "add", ".");
    git(repo, "commit", "-m", message);
  }

  /**
   * Per-class repo templates (T-007): build the seed repos ONCE in {@code @BeforeClass} with the
   * same git commands the old per-test setup ran, then hand every test a pristine COPY by file copy
   * - a copy is state-identical to a freshly created repo, so test isolation is unchanged while the
   * fixture cost drops from 6-12 git spawns per test to (at most) one {@code remote set-url}.
   */
  static final class Fixture {

    private final Path templates;
    private final Path plain;
    private final Path origin;
    private final Path clone;

    private Fixture(Path templates) {
      this.templates = templates;
      this.plain = templates.resolve("plain");
      this.origin = templates.resolve("origin.git");
      this.clone = templates.resolve("clone");
    }

    /** Templates with the default seed file ({@code file.txt} = {@code one}). */
    static Fixture create(String prefix) throws Exception {
      return create(prefix, "file.txt", "one\n");
    }

    /** Templates with a custom seed file, then {@link #close()} removes them after the class. */
    static Fixture create(String prefix, String file, String content) throws Exception {
      Path templates =
          Files.createTempDirectory(prefix + "-templates").toAbsolutePath().normalize();
      Fixture fixture = new Fixture(templates);
      try {
        newRepo(templates, "plain", file, content);
        git(templates, "clone", "--bare", fixture.plain.toString(), fixture.origin.toString());
        cloneOf(templates, "clone", fixture.origin);
        return fixture;
      } catch (Exception e) {
        deleteRecursively(templates);
        throw e;
      }
    }

    /** A pristine copy of the plain template at {@code target}. */
    Path copyPlainRepo(Path target) throws IOException {
      return copyTree(plain, target);
    }

    /** A pristine copy of the bare-origin template at {@code target}. */
    Path copyOrigin(Path target) throws IOException {
      return copyTree(origin, target);
    }

    /** A clone template copy whose origin remote points at {@code origin} (one git call). */
    Path copyClone(Path target, Path origin) throws Exception {
      copyTree(clone, target);
      git(target, "remote", "set-url", "origin", origin.toString());
      return target;
    }

    /** Removes the class-level templates. */
    void close() {
      deleteRecursively(templates);
    }
  }
}
