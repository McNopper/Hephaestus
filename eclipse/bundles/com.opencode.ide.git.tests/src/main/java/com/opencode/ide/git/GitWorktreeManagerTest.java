package com.opencode.ide.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.opencode.ide.git.internal.GitWorktreeManager;

/**
 * End-to-end tests against a real git repo in a temp dir. Skipped when git
 * is not available.
 */
public class GitWorktreeManagerTest {

    private Path repo;
    private GitWorktreeManager manager;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("git not available", gitAvailable());
        repo = Files.createTempDirectory("opencode-git-test").toAbsolutePath().normalize();
        git("init");
        git("config", "user.email", "a@b.c");
        git("config", "user.name", "Test");
        Files.writeString(repo.resolve("file.txt"), "line1\nline2\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");
        git("branch", "-M", "main");
        manager = new GitWorktreeManager();
    }

    @After
    public void tearDown() {
        if (repo != null && Files.isDirectory(repo)) {
            runQuiet("worktree", "prune");
            deleteRecursively(repo);
        }
    }

    @Test
    public void createMakesBranchAndWorktreeUnderFleetDir() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        assertEquals("opencode/t1", wt.branch());
        assertEquals(repo.resolve(".git/opencode-fleet/t1"), wt.path());
        assertTrue(Files.isDirectory(wt.path()));
        assertTrue(gitOk("rev-parse", "--verify", "--quiet", "refs/heads/opencode/t1"));
    }

    @Test
    public void listSeesOnlyFleetWorktrees() throws Exception {
        manager.create(repo, "t1");
        manager.create(repo, "t2");
        List<String> ids = manager.list(repo).stream().map(Worktree::taskId).sorted().toList();
        assertEquals(List.of("t1", "t2"), ids);
        assertFalse(git("worktree", "list", "--porcelain").split("\\R")[0].contains("opencode-fleet"));
    }

    /**
     * Regression (CI 2026-08-27): discovery keys on the fleet BRANCH, not the
     * path — git reports worktree paths in its own canonical form, which can
     * differ from the caller's spelling of the same directory (8.3 short
     * names on Windows runners, symlinks), and the old path match dropped
     * every worktree on such machines. A fleet branch outside the fleet dir
     * must still be found; a worktree inside the fleet dir on a foreign
     * branch must not.
     */
    @Test
    public void listKeysOnTheFleetBranchNotThePath() throws Exception {
        Path elsewhere = Files.createDirectory(repo.resolve("elsewhere"));
        git("worktree", "add", "-b", "opencode/t9", elsewhere.resolve("t9").toString(), "HEAD");
        git("branch", "foreign");
        git("worktree", "add", repo.resolve(".git").resolve("opencode-fleet").resolve("stray").toString(), "foreign");
        List<Worktree> found = manager.list(repo);
        assertTrue(found.toString(), found.stream().anyMatch(w -> w.taskId().equals("t9")));
        assertFalse(found.toString(), found.stream().anyMatch(w -> w.taskId().equals("stray")));
        runQuiet("worktree", "remove", "--force", elsewhere.resolve("t9").toString());
        runQuiet("worktree", "remove", "--force",
                repo.resolve(".git").resolve("opencode-fleet").resolve("stray").toString());
    }

    @Test
    public void doubleCreateFailsWithClearMessage() {
        manager.create(repo, "t1");
        try {
            manager.create(repo, "t1");
            fail("expected WorktreeException");
        } catch (WorktreeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already exists"));
        }
    }

    @Test
    public void removeCleansWorktreeAndBranch() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        manager.remove(repo, "t1", false);
        assertFalse(Files.exists(wt.path()));
        assertFalse(gitOk("rev-parse", "--verify", "--quiet", "refs/heads/opencode/t1"));
        assertFalse(git("worktree", "list", "--porcelain").contains("opencode-fleet"));
        try {
            manager.remove(repo, "t1", false);
            fail("expected WorktreeException");
        } catch (WorktreeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("t1"));
        }
    }

    @Test
    public void statusCleanAfterCreate() {
        manager.create(repo, "t1");
        WorktreeStatus st = manager.status(repo, "t1");
        assertTrue(st.exists());
        assertEquals(0, st.dirtyFiles());
        assertTrue("short sha expected: '" + st.head() + "'", st.head().matches("[0-9a-f]{7,40}"));
    }

    @Test
    public void statusDirtyAfterWritingFile() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(wt.path().resolve("new.txt"), "hello\n", StandardCharsets.UTF_8);
        WorktreeStatus st = manager.status(repo, "t1");
        assertTrue(st.exists());
        assertEquals(1, st.dirtyFiles());
        assertFalse(manager.status(repo, "missing").exists());
    }

    @Test
    public void mergeBackHappyPathMergesIntoMain() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(wt.path().resolve("file.txt"), "line1\nline2\nline3\n", StandardCharsets.UTF_8);
        commitIn(wt.path(), "worktree change");
        MergeResult result = manager.mergeBack(repo, "t1");
        assertTrue(result.output(), result.merged());
        assertTrue(result.conflictedFiles().isEmpty());
        assertTrue(Files.readString(repo.resolve("file.txt")).contains("line3"));
    }

    @Test
    public void mergeBackConflictAbortsMergeAndReportsFiles() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(repo.resolve("file.txt"), "main-change\nline2\n", StandardCharsets.UTF_8);
        commitIn(repo, "main change");
        Files.writeString(wt.path().resolve("file.txt"), "worktree-change\nline2\n", StandardCharsets.UTF_8);
        commitIn(wt.path(), "worktree change");
        MergeResult result = manager.mergeBack(repo, "t1");
        assertFalse(result.merged());
        assertTrue(result.conflictedFiles().toString(), result.conflictedFiles().contains("file.txt"));
        assertEquals("", git("status", "--porcelain").trim());
        assertTrue(Files.readString(repo.resolve("file.txt")).startsWith("main-change"));
        assertTrue(gitOk("rev-parse", "--verify", "--quiet", "refs/heads/opencode/t1"));
    }

    @Test
    public void commitAllCommitsPendingChangesAndToleratesCleanTree() throws Exception {
        Files.writeString(repo.resolve("scratch.txt"), "fleet pre-claim\n", StandardCharsets.UTF_8);
        manager.commitAll(repo, ".", "fleet: pre-claim t1");
        assertEquals("", git("status", "--porcelain").trim());
        // nothing staged is fine (idempotent bookkeeping step)
        manager.commitAll(repo, ".", "fleet: pre-claim t1 again");
        assertEquals("", git("status", "--porcelain").trim());
    }

    /**
     * Review F1/F4 (2026-09-13): fleet commits must be SCOPED to the store
     * subtree - in the production layout (.opencode/tasks inside the host
     * repo) a pathspec-less add would sweep the host repo's unrelated WIP
     * into a fleet commit and push it.
     */
    @Test
    public void commitAllIsScopedToTheStoreSubtree() throws Exception {
        Files.writeString(repo.resolve("WIP.java"), "user work in progress\n", StandardCharsets.UTF_8);
        Path store = Files.createDirectories(repo.resolve(".opencode/tasks"));
        Files.writeString(store.resolve("T-1.md"), "ticket\n", StandardCharsets.UTF_8);
        manager.commitAll(repo, ".opencode/tasks", "fleet: pre-claim T-1");
        assertEquals("out-of-store WIP must stay uncommitted",
                "?? WIP.java", git("status", "--porcelain").trim());
        assertTrue("the store file must be committed",
                git("show", "--name-only", "--format=", "HEAD").contains("T-1.md"));
    }

    /**
     * Review F5: a store write landing between the pre-claim and the merge
     * (a PM comment, another ticket's telemetry) must not refuse the merge -
     * the store subtree is committed first, then the branch merges.
     */
    @Test
    public void peerStoreWriteDoesNotRefuseTheMerge() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(wt.path().resolve("code.txt"), "worker change\n", StandardCharsets.UTF_8);
        commitIn(wt.path(), "worker work");
        Path store = Files.createDirectories(repo.resolve(".opencode/tasks"));
        Files.writeString(store.resolve("T-1.md"), "peer comment\n", StandardCharsets.UTF_8);
        MergeResult result = manager.mergeBack(repo, "t1");
        assertTrue(result.output(), result.merged());
        assertTrue(Files.readString(repo.resolve("code.txt")).contains("worker change"));
    }

    /**
     * Milestone V finding #8: a worker that finishes without committing must
     * not produce a fake MERGED - its pending worktree changes are
     * auto-committed on the branch and then merged for real.
     */
    @Test
    public void mergeBackAutoCommitsPendingWorkerChanges() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(wt.path().resolve("file.txt"), "line1\nline2\nline3\n", StandardCharsets.UTF_8);
        MergeResult result = manager.mergeBack(repo, "t1");
        assertTrue(result.output(), result.merged());
        assertTrue(Files.readString(repo.resolve("file.txt")).contains("line3"));
        assertTrue("worktree clean after the auto-commit",
                git("-C", wt.path().toString(), "status", "--porcelain").isBlank());
    }

    /**
     * Milestone V finding #8, the empty case: no commits AND no pending edits
     * means the worker produced nothing - the merge fails explicitly instead
     * of exiting 0 "already up to date" and faking completion.
     */
    @Test
    public void mergeBackFailsWhenTheWorkerProducedNothing() throws Exception {
        manager.create(repo, "t1");
        MergeResult result = manager.mergeBack(repo, "t1");
        assertFalse(result.merged());
        assertTrue(result.output(), result.output().contains("no changes"));
        assertEquals("main untouched", "", git("status", "--porcelain").trim());
    }

    /**
     * R2 (RepoGate): two concurrent main-tree mutations on ONE repo (a
     * commitAll racing a store sync - the pre-claim vs auto-sync case) must
     * serialize instead of intermittently losing git's index.lock race.
     */
    @Test(timeout = 300_000)
    public void concurrentCommitAndSyncSerializeThroughTheRepoGate() throws Exception {
        Path store = Files.createDirectories(repo.resolve(".opencode/tasks"));
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        int iterations = 3; // CI runners spawn git ~10x slower - keep the total bounded
        Runnable committer = () -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    Files.writeString(store.resolve("T-" + i + ".md"), "tick " + i + "\n",
                            StandardCharsets.UTF_8);
                    manager.commitAll(repo, ".opencode/tasks", "concurrent commit " + i);
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        };
        Runnable syncer = () -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    StoreSync.sync(store, "concurrent sync " + i);
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        };
        Thread a = new Thread(committer, "gate-test-commit");
        Thread b = new Thread(syncer, "gate-test-sync");
        a.start(); b.start(); a.join(); b.join();
        assertEquals("no git mutation lost the index.lock race", 0, failures.get());
        assertEquals("", git("status", "--porcelain").trim());
    }

    /**
     * AC-path gate evidence: changedFiles reports the branch's committed
     * changes (from the fork point) plus pending worktree edits - and never
     * main-side commits made after the fork.
     */
    @Test
    public void changedFilesListsCommittedAndPendingWorkerChangesOnly() throws Exception {
        Worktree wt = manager.create(repo, "t1");
        Files.writeString(wt.path().resolve("file.txt"), "worker edit\n", StandardCharsets.UTF_8);
        commitIn(wt.path(), "worker commit");
        Files.writeString(wt.path().resolve("pending.txt"), "pending\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("main-side.txt"), "main edit\n", StandardCharsets.UTF_8);
        commitIn(repo, "main moves on");

        List<String> changed = manager.changedFiles(repo, "t1");

        assertTrue(changed.toString(), changed.contains("file.txt"));
        assertTrue(changed.toString(), changed.contains("pending.txt"));
        assertFalse("main-side commits must not count as worker changes",
                changed.contains("main-side.txt"));
        assertEquals(changed.toString(), 2, changed.size());
    }

    private void commitIn(Path worktree, String message) throws Exception {
        git(worktree, "add", ".");
        git(worktree, "commit", "-m", message);
    }

    private String git(String... args) throws Exception {
        return git(repo, args);
    }

    private static String git(Path dir, String... args) throws Exception {
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

    private boolean gitOk(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(command).start();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runQuiet(String... args) {
        try {
            git(args);
        } catch (Exception ignored) {
        }
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
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
}
