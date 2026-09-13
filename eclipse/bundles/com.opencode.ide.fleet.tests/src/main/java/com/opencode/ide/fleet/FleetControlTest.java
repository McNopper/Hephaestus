package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
import org.junit.Test;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Tests for the {@link FleetControl} seams around the fleet engine: the
 * permission queue before the engine exists (cheap listing, never a server
 * spawn), the generated server password ({@link FleetControl#resolvePassword}
 * - P-002), and the best-effort store auto-sync after a headless launch
 * (H-003) against a real temp git repo, using the same in-memory
 * client/worktree fakes as the other fleet tests.
 */
public class FleetControlTest {

    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Path base;

    @Before
    public void setUp() throws Exception {
        base = Files.createTempDirectory("opencode-fleet-control").toAbsolutePath().normalize();
    }

    @After
    public void tearDown() {
        if (base != null && Files.isDirectory(base)) {
            deleteRecursively(base);
        }
    }

    @Test
    public void permissionsBeforeEngineStartIsAnEmptyObservationalQueue() {
        FleetControl control = new FleetControl(base.resolve("repo/.opencode/tasks"), root -> {
            throw new IllegalStateException("listing asks must never spawn the engine");
        });

        PermissionQueue queue = control.permissions();

        assertTrue(queue.pending().isEmpty());
        assertFalse("nothing is answerable before the first dispatch",
                queue.answer("per_1", PermissionQueue.Response.ONCE, false).success());
        assertFalse("the engine is deliberately not spawned", control.engineStarted());
        assertSame("the stand-in is shared, not rebuilt per call", queue, control.permissions());
        control.close();
    }

    @Test
    public void missingServerPasswordBecomesAFresh64CharHexValue() {
        for (String missing : new String[] { null, "", "   " }) {
            String one = FleetControl.resolvePassword(missing);
            String two = FleetControl.resolvePassword(missing);

            assertEquals(64, one.length());
            assertTrue(one + " is not lowercase hex", one.matches("[0-9a-f]{64}"));
            assertNotEquals("a fresh password per spawn", one, two);
        }
    }

    @Test
    public void setServerPasswordPassesThroughExactly() {
        assertEquals("s3cret pass", FleetControl.resolvePassword("s3cret pass"));
        assertEquals("x", FleetControl.resolvePassword("x"));
    }

    @Test
    public void dispatchAutoSyncsTheStoreAfterTheLaunchSettles() throws Exception {
        Assume.assumeTrue("git not available", gitAvailable());
        Path repo = newRepo();
        TaskStore store = storeIn(repo);
        FakeClient client = new FakeClient();
        client.replyOnSend = "done";
        client.sessionType = "idle";
        FleetControl control = controlOver(store, client);

        String id = sprintTicket(store);
        control.dispatch(PROJECT, id, TIMEOUT);

        assertTrue("the launch's bookkeeping was auto-synced into a commit",
                storeClean(repo, "opencode fleet: store sync after " + id));
        control.close();
    }

    @Test
    public void dispatchSyncsAndNeverPropagatesWhenTheLaunchThrows() throws Exception {
        Assume.assumeTrue("git not available", gitAvailable());
        Path repo = newRepo();
        TaskStore store = storeIn(repo);
        FleetControl control = controlOver(store, new FakeClient());

        String id = sprintTicket(store);
        store.setBlocked(PROJECT, id, "waiting on upstream", "test");

        control.dispatch(PROJECT, id, TIMEOUT);

        assertTrue("bookkeeping was synced although the launch threw",
                storeClean(repo, "opencode fleet: store sync after " + id));
        control.close();
    }

    /** A store root at {@code <repo>/.opencode/tasks} (the fleet's layout). */
    private static TaskStore storeIn(Path repo) {
        return new TaskStore(repo.resolve(".opencode").resolve("tasks"));
    }

    /** A control whose engine is a real {@link TaskFleet} on the in-memory fakes over the given store. */
    private static FleetControl controlOver(TaskStore store, FakeClient client) {
        return new FleetControl(store.root(), root -> new FleetControl.Engine() {

            private final TaskFleet fleet = new TaskFleet(
                    new FleetRunner(client, new FakeWorktreeManager(), () -> { }), store);
            private final PermissionQueue queue = new PermissionQueue(null);

            @Override
            public TaskFleet fleet() {
                return fleet;
            }

            @Override
            public PermissionQueue permissions() {
                return queue;
            }

            @Override
            public void close() {
                // nothing to release in the fake engine
            }
        });
    }

    private static String sprintTicket(TaskStore store) {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Fix the widget", "Do the thing.", "task", "developer", "high", 3,
                List.of("ac one"), List.of(), null, "H1"));
        store.planSprint(PROJECT, "S-01", List.of(t.id), "goal");
        return t.id;
    }

    /**
     * Polls until the repo's working copy is clean and the newest commit
     * carries the expected message - i.e. the auto-sync ran and committed.
     */
    private static boolean storeClean(Path repo, String expectedLastMessage) throws Exception {
        // generous deadline: CI runners spawn git ~10x slower than a local dev
        // box and this poll must not flake there (two CI failures 2026-08-28)
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            // the exclude keeps the store's transient .lock out of the verdict
            // (an in-flight transaction must not read as store dirt)
            if (gitOut(repo, "status", "--porcelain", "--", ".", ":(exclude)*.lock").isBlank()) {
                return expectedLastMessage.equals(gitOut(repo, "log", "-1", "--format=%s").trim());
            }
            Thread.sleep(100);
        }
        System.err.println("[storeClean] TIMEOUT diagnostics for " + repo);
        System.err.println("[storeClean] status: " + gitOut(repo, "status", "--porcelain"));
        System.err.println("[storeClean] log: " + gitOut(repo, "log", "--oneline", "-5"));
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (thread.getName().startsWith("fleet-") || thread.getName().contains("pool")) {
                System.err.println("[storeClean] thread " + thread.getName() + " state=" + thread.getState());
                for (int i = 0; i < Math.min(6, frames.length); i++) {
                    System.err.println("[storeClean]   at " + frames[i]);
                }
            }
        });
        return false;
    }

    /** A fresh git repo with an initial commit, on branch {@code main} (the StoreSyncTest pattern). */
    private Path newRepo() throws Exception {
        Path repo = base.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init");
        git(repo, "config", "user.email", "a@b.c");
        git(repo, "config", "user.name", "Test");
        Files.writeString(repo.resolve("README.md"), "store repo\n", StandardCharsets.UTF_8);
        git(repo, "add", ".");
        git(repo, "commit", "-m", "initial");
        git(repo, "branch", "-M", "main");
        return repo;
    }

    private static String gitOut(Path dir, String... args) throws Exception {
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

    private static void git(Path dir, String... args) throws Exception {
        gitOut(dir, args);
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
