package com.opencode.ide.tasks;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AutoDispatchPreparationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void staleReopeningPersistsTransitionAndAuditExactlyOnce() throws Exception {
        TaskStore store = fixture();
        Task prepared = store.prepareAutoDispatch("p", "T-2", true, "fleet");
        assertEquals("sprint-backlog", prepared.status);
        assertNull(prepared.assignee);
        Task persisted = new TaskStore(store.root()).get("p", "T-2");
        assertEquals(TaskFileCodec.write(prepared), TaskFileCodec.write(persisted));
        assertEquals("reopened for stale rework", persisted.history.getLast().action());
        assertTrue(persisted.comments.getLast().text().startsWith("fleet stale rework:"));
        String after = contents(store);
        store.prepareAutoDispatch("p", "T-2", true, "fleet");
        assertEquals("READY preparation must not touch history or timestamps", after, contents(store));
    }

    @Test
    public void rejectedStatesLeaveTicketBytesUnchanged() throws Exception {
        TaskStore store = fixture();
        rejectUnchanged(store, false); // stale opt-out
        store.update("p", "T-2", Map.of("status", "done")); // now fresh
        rejectUnchanged(store, true);
        store.update("p", "T-1", Map.of("status", "product-backlog"));
        rejectUnchanged(store, true); // upstream unavailable
        store.setBlocked("p", "T-2", "human hold", "human");
        rejectUnchanged(store, true);
        store.clearBlocked("p", "T-2", "human");
        store.update("p", "T-2", Map.of("status", "in-progress"));
        rejectUnchanged(store, true);
        assertThrows(TaskStore.NotFound.class,
                () -> store.prepareAutoDispatch("p", "missing", true, "fleet"));
    }

    @Test(timeout = 20000)
    public void upstreamEditInAnotherProcessWinsBeforeReadinessIsEvaluated() throws Exception {
        TaskStore store = fixture();
        assertEquals(StageReadiness.Kind.STALE,
                StageReadiness.evaluate(store.list("p", null, null, null, null)).get("T-2").kind());
        String before = contents(store);
        String classpath = java.util.stream.Stream.of(UpstreamEditor.class, TaskStore.class, com.google.gson.Gson.class)
                .map(type -> {
                    try {
                        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).distinct().collect(java.util.stream.Collectors.joining(File.pathSeparator));
        Process peer = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString(), "-cp", classpath,
                UpstreamEditor.class.getName(), store.root().toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertEquals("peer holds the project OS lock", 'L', peer.getInputStream().read());
            CountDownLatch attempted = new CountDownLatch(1);
            var preparation = pool.submit(() -> {
                attempted.countDown();
                return store.prepareAutoDispatch("p", "T-2", true, "fleet");
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertThrows("preparation must wait for the project transaction",
                    java.util.concurrent.TimeoutException.class, () -> preparation.get(150, TimeUnit.MILLISECONDS));
            peer.getOutputStream().write('!');
            peer.getOutputStream().flush();
            assertTrue(peer.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, peer.exitValue());
            var error = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> preparation.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof TaskStore.Invalid);
            assertTrue(error.getCause().getMessage().contains("WAIT_UPSTREAM"));
            assertEquals("no stale-snapshot reopening or partial audit write", before, contents(store));
        } finally {
            peer.destroyForcibly();
            assertTrue(peer.waitFor(5, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void rejectUnchanged(TaskStore store, boolean includeStale) throws Exception {
        String before = contents(store);
        assertThrows(TaskStore.Invalid.class,
                () -> store.prepareAutoDispatch("p", "T-2", includeStale, "fleet"));
        assertEquals(before, contents(store));
    }

    private static String contents(TaskStore store) throws Exception {
        return Files.readString(store.root().resolve("p/T-2.md"));
    }

    private TaskStore fixture() throws Exception {
        Path root = tmp.newFolder().toPath();
        TaskStore store = new TaskStore(root);
        Task upstream = store.create("p", TaskStore.CreateSpec.of("upstream"));
        Task child = store.create("p", TaskStore.CreateSpec.of("child"));
        // Explicit fixture ids/timestamps avoid timing-dependent staleness.
        Files.delete(root.resolve("p/" + upstream.id + ".md"));
        Files.delete(root.resolve("p/" + child.id + ".md"));
        upstream.id = "T-1";
        upstream.stage = "requirements";
        upstream.status = "done";
        upstream.updatedAt = Instant.parse("2025-02-01T00:00:00Z");
        child.id = "T-2";
        child.stage = "system";
        child.status = "done";
        child.sprint = "S-1";
        child.epic = upstream.id;
        child.assignee = "old-worker";
        child.updatedAt = Instant.parse("2025-01-01T00:00:00Z");
        Files.writeString(root.resolve("p/T-1.md"), TaskFileCodec.write(upstream));
        Files.writeString(root.resolve("p/T-2.md"), TaskFileCodec.write(child));
        return store;
    }

    /** Separate JVM acting as an upstream transaction, with an explicit lock barrier. */
    public static final class UpstreamEditor {
        public static void main(String[] args) throws Exception {
            Path project = Path.of(args[0]).resolve("p");
            try (var channel = FileChannel.open(project.resolve(".lock"), StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                System.out.print('L');
                System.out.flush();
                System.in.read();
                Path file = project.resolve("T-1.md");
                Task upstream = TaskFileCodec.read(Files.readString(file));
                upstream.status = "product-backlog";
                upstream.updatedAt = Instant.now();
                Files.writeString(file, TaskFileCodec.write(upstream));
            }
        }
    }
}
