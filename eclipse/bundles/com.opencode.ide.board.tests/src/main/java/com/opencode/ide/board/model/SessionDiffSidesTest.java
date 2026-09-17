package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.model.FileDiff;

/**
 * Real-process tests for {@link SessionDiffSides} (git on PATH; skipped
 * otherwise — the GitCliTest style): the before/after sides resolve from
 * the diffs' git revisions, missing revisions and unknown revs degrade to
 * null sides instead of throwing.
 */
public class SessionDiffSidesTest {

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
        Files.writeString(repo.resolve("file.txt"), "old line\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");
        git("branch", "-M", "main");
    }

    @After
    public void tearDown() {
        if (repo != null && Files.isDirectory(repo)) {
            try (var walk = Files.walk(repo)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().setWritable(true));
            } catch (IOException ignored) {
                // best effort cleanup for TemporaryFolder
            }
        }
    }

    private void git(String... args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command(args)).redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            in.readAllBytes();
        }
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        }
    }

    private List<String> command(String... args) {
        List<String> full = new java.util.ArrayList<>();
        full.add("git");
        full.add("-C");
        full.add(repo.toString());
        full.addAll(List.of(args));
        return full;
    }

    private static boolean runOk(String... command) {
        try {
            Process p = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String rev(String ref) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command("rev-parse", ref)).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    @Test
    public void resolvesBeforeAndAfterFromTheRevisions() throws Exception {
        String before = rev("HEAD");
        Files.writeString(repo.resolve("file.txt"), "new line\nplus another\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "change");
        String after = rev("HEAD");

        List<SessionDiffSides.Side> sides = SessionDiffSides.resolve(repo,
                List.of(new FileDiff("file.txt", before, after, "patch")));

        assertEquals(1, sides.size());
        assertEquals("old line\n", sides.get(0).before());
        assertEquals("new line\nplus another\n", sides.get(0).after());
        assertEquals(1, SessionDiffSides.resolved(sides));
    }

    @Test
    public void blankRevisionsFallBackToHeadBeforeAndEmptyAfter() {
        // blank before -> HEAD; blank after -> nothing (renders empty right side)
        List<SessionDiffSides.Side> sides = SessionDiffSides.resolve(repo,
                List.of(new FileDiff("file.txt", null, null, "patch")));

        assertEquals(1, sides.size());
        assertEquals("old line\n", sides.get(0).before());
        assertEquals("", sides.get(0).after());
        assertEquals(1, SessionDiffSides.resolved(sides));
    }

    @Test
    public void workingRevisionReadsTheWorktreeFile() throws Exception {
        Files.writeString(repo.resolve("file.txt"), "worktree state\n", StandardCharsets.UTF_8);

        List<SessionDiffSides.Side> sides = SessionDiffSides.resolve(repo,
                List.of(new FileDiff("file.txt", "HEAD", "WORKING", "patch")));

        assertEquals(1, sides.size());
        assertEquals("old line\n", sides.get(0).before());
        assertEquals("worktree state\n", sides.get(0).after());
    }

    @Test
    public void unknownRevisionDegradesToEmptyBeforeInsteadOfThrowing() {
        // an unresolvable before-rev renders the left side empty (reads as
        // all-added) rather than killing the whole compare - the after side
        // still resolves
        List<SessionDiffSides.Side> sides = SessionDiffSides.resolve(repo,
                List.of(new FileDiff("file.txt", "no-such-rev", "HEAD", "patch")));

        assertEquals(1, sides.size());
        assertEquals("", sides.get(0).before());
        assertEquals("old line\n", sides.get(0).after());
        assertEquals(1, SessionDiffSides.resolved(sides));
    }

    @Test
    public void nullRepoOrNullDiffsAreTolerated() {
        assertTrue(SessionDiffSides.resolve(null, null).isEmpty());
        List<SessionDiffSides.Side> noRepo = SessionDiffSides.resolve(null,
                List.of(new FileDiff("file.txt", "a", "b", "patch")));
        assertEquals(1, noRepo.size());
        assertNull(noRepo.get(0).before());
    }
}
