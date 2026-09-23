package com.opencode.ide.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared real-git fixtures for the git tests (2026-09-23 CPD findings: these
 * helpers were copy-pasted across GitWorktreeManagerTest, StoreGitStatusTest
 * and StoreSyncTest). Everything talks to the REAL git binary in temp
 * directories; a non-zero exit throws with the captured stderr.
 */
final class GitTestRepos {

    private GitTestRepos() {
    }

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
            throw new IllegalStateException("git " + List.of(args) + " failed (exit " + code + "): " + err);
        }
        return out;
    }

    /** True when a real git is on this machine - the tests skip without it. */
    static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Writable-first recursive delete so Windows can clean up git's files. */
    static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** A local one-commit repo on {@code main} with a configured author. */
    static Path newRepo(Path base, String name) throws Exception {
        Path repo = base.resolve(name);
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "a@b.c");
        git(repo, "config", "user.name", "Test");
        write(repo, "file.txt", "one\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        git(repo, "branch", "-M", "main");
        return repo;
    }

    /** A bare origin cloned from a fresh {@link #newRepo} seed (the seed is removed). */
    static Path sharedOrigin(Path base) throws Exception {
        Path seed = newRepo(base, "seed");
        Path origin = base.resolve("origin.git");
        git(base, "clone", "--bare", seed.toString(), origin.toString());
        deleteRecursively(seed);
        return origin;
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
}
