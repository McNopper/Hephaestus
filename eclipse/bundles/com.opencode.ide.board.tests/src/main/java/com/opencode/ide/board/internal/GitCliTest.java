package com.opencode.ide.board.internal;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Real-process tests for the {@link GitCli} seam (git is on PATH; skipped
 * otherwise — mirrors the git bundle's GitWorktreeManagerTest style): happy
 * diff against a task branch, non-zero exit, missing binary and the
 * destroyForcibly timeout.
 */
public class GitCliTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path repo;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("git not available", runOk("git", "--version"));
        repo = tmp.newFolder("repo").toPath().toAbsolutePath().normalize();
        git("init");
        git("config", "user.email", "a@b.c");
        git("config", "user.name", "Test");
        Files.writeString(repo.resolve("file.txt"), "line1\nline2\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");
        git("branch", "-M", "main");
    }

    @After
    public void tearDown() {
        if (repo != null && Files.isDirectory(repo)) {
            // git object files are read-only on Windows — make deletable for TemporaryFolder
            try (var walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().setWritable(true));
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    @Test
    public void diffShowsChangedLineAgainstTaskBranch() throws Exception {
        git("checkout", "-b", "opencode/T-001");
        Files.writeString(repo.resolve("file.txt"), "line1\nline2\nline3-added\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "task change");
        git("checkout", "main");

        String diff = GitCli.diff(repo, "T-001");

        assertTrue(diff, diff.contains("line3-added"));
    }

    @Test
    public void nonZeroExitBecomesIllegalStateMentioningExit() {
        try {
            GitCli.diff(repo, "T-NOPE");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exit"));
        }
    }

    @Test
    public void missingBinaryFailsToStart() {
        try {
            GitCli.run(List.of("definitely-not-git-xyz-123"), Duration.ofSeconds(2));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Failed to start"));
        }
    }

    @Test
    public void timeoutDestroysTheProcessQuickly() {
        // a portable ~30s command: sleeps are shell builtins, so run the shell
        List<String> command = File.separatorChar == '\\'
                ? List.of("cmd", "/c", "ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
        long start = System.nanoTime();
        try {
            GitCli.run(command, Duration.ofMillis(300));
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("timed out"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("run() took " + elapsedMs + " ms to time out (destroyForcibly not effective?)",
                elapsedMs < 4_500);
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).start();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + List.of(args) + " failed (exit " + code + "): " + err);
        }
    }

    private static boolean runOk(String... command) {
        try {
            Process p = new ProcessBuilder(List.of(command)).start();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
