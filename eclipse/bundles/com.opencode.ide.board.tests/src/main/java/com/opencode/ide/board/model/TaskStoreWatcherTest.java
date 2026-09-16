package com.opencode.ide.board.model;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Unit tests for {@link TaskStoreWatcher}: it must fire (within ~3 s) when the
 * store writes a ticket through its atomic tmp-rename pattern, and via the
 * poll fallback when the watched project directory appears only after start.
 * The B-002 additions cover PEER writes — another process rewriting files
 * out of band, including the git-checkout shape (same name, same size, same
 * mtime, different content) that a pure mtime+size fingerprint misses, and
 * peer deletions.
 */
public class TaskStoreWatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void firesWhenTaskStoreWrites() throws Exception {
        Path root = tmp.newFolder().toPath();
        TaskStore store = new TaskStore(root);
        store.create("p", TaskStore.CreateSpec.of("seed"));

        CountDownLatch fired = new CountDownLatch(1);
        TaskStoreWatcher watcher = new TaskStoreWatcher(root.resolve("p"), fired::countDown);
        watcher.start();
        try {
            store.create("p", TaskStore.CreateSpec.of("second"));
            assertTrue("watcher should fire within 3s of a TaskStore write",
                    fired.await(3, TimeUnit.SECONDS));
        } finally {
            watcher.stop();
        }
    }

    @Test
    public void firesWhenPeerReplacesContentPreservingSizeAndMtime() throws Exception {
        Path root = tmp.newFolder().toPath();
        TaskStore store = new TaskStore(root);
        Task seed = store.create("p", TaskStore.CreateSpec.of("seed"));
        Path ticket = root.resolve("p").resolve(seed.id + ".md");

        CountDownLatch fired = new CountDownLatch(1);
        TaskStoreWatcher watcher = new TaskStoreWatcher(root.resolve("p"), fired::countDown);
        watcher.start();
        try {
            // the git-checkout shape: a rewrite that keeps name, byte size AND
            // mtime — invisible to the old mtime+size fingerprint, caught by
            // the content checksum (B-002 AC-3)
            byte[] original = Files.readAllBytes(ticket);
            FileTime originalTime = Files.getLastModifiedTime(ticket);
            byte[] replaced = original.clone();
            boolean flipped = false;
            for (int i = 0; i < replaced.length; i++) {
                if (replaced[i] == 'a') {
                    replaced[i] = 'b'; // same length, different content
                    flipped = true;
                    break;
                }
            }
            if (!flipped && replaced.length > 0) {
                replaced[0] = (byte) (replaced[0] == 'x' ? 'y' : 'x');
            }
            Path staged = root.resolve("peer-tmp.md");
            Files.write(staged, replaced);
            Files.setLastModifiedTime(staged, originalTime);
            Files.delete(ticket);
            Files.move(staged, ticket);

            assertTrue("peer replace with identical size+mtime must still fire (content checksum)",
                    fired.await(8, TimeUnit.SECONDS));
        } finally {
            watcher.stop();
        }
    }

    @Test
    public void firesWhenPeerDeletesATicket() throws Exception {
        Path root = tmp.newFolder().toPath();
        TaskStore store = new TaskStore(root);
        Task seed = store.create("p", TaskStore.CreateSpec.of("seed"));
        Path ticket = root.resolve("p").resolve(seed.id + ".md");
        assertTrue(Files.isRegularFile(ticket));

        CountDownLatch fired = new CountDownLatch(1);
        TaskStoreWatcher watcher = new TaskStoreWatcher(root.resolve("p"), fired::countDown);
        watcher.start();
        try {
            Files.delete(ticket);
            assertTrue("peer delete must fire (fingerprint loses the file)",
                    fired.await(8, TimeUnit.SECONDS));
        } finally {
            watcher.stop();
        }
    }

    @Test
    public void firesViaPollWhenProjectDirAppears() throws Exception {
        Path root = tmp.newFolder().toPath().resolve("tasks");

        CountDownLatch fired = new CountDownLatch(1);
        TaskStoreWatcher watcher = new TaskStoreWatcher(root.resolve("p"), fired::countDown);
        watcher.start();
        try {
            new TaskStore(root).create("p", TaskStore.CreateSpec.of("first"));
            assertTrue("watcher should fire via the poll fallback when the dir appears",
                    fired.await(8, TimeUnit.SECONDS));
        } finally {
            watcher.stop();
        }
    }

    @Test
    public void stopIsIdempotentAndRestartable() throws Exception {
        Path root = tmp.newFolder().toPath();
        TaskStore store = new TaskStore(root);
        store.create("p", TaskStore.CreateSpec.of("seed"));

        CountDownLatch fired = new CountDownLatch(1);
        TaskStoreWatcher watcher = new TaskStoreWatcher(root.resolve("p"), fired::countDown);
        watcher.start();
        watcher.stop();
        watcher.stop();

        watcher.start();
        try {
            store.create("p", TaskStore.CreateSpec.of("after restart"));
            assertTrue(fired.await(3, TimeUnit.SECONDS));
        } finally {
            watcher.stop();
        }
    }
}
