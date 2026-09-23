package com.opencode.ide.board.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.WatchEvent.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Watches {@code <root>/<project>} for task-store changes and fires the
 * listener (debounced ~300 ms). Combines a {@link WatchService}
 * (create/delete/modify) with a 2 s poll fallback keyed on a fingerprint of
 * the {@code *.md} + {@code _meta.json} files, so missed watch events and
 * directories that appear only later are still picked up. The fingerprint
 * covers each file's <b>name, mtime, size AND content checksum</b> (B-002):
 * peer processes replace files (atomic tmp-rename, git checkout/rebase) and
 * a rewrite that lands within the same millisecond with the same byte size
 * would otherwise be invisible to an mtime+size fingerprint. The store's
 * atomic tmp-rename writes are tolerated: entries whose name starts with
 * {@code .} (temp files, {@code .lock}) are ignored. All SWT-free; the
 * listener runs on the watcher's daemon thread.
 */
public final class TaskStoreWatcher {

    private static final long POLL_MILLIS = 2000;
    private static final long DEBOUNCE_MILLIS = 300;
    private static final long FINGERPRINT_UNSET = Long.MIN_VALUE;
    private static final long FINGERPRINT_ERROR = -1;
    private static final long FINGERPRINT_MISSING = 0;

    private final Path dir;
    private final Runnable listener;
    private WatchService service;
    private WatchKey dirKey;
    private java.util.concurrent.Future<?> thread;
    private volatile boolean running;
    private long fingerprint = FINGERPRINT_UNSET;

    public TaskStoreWatcher(Path projectDir, Runnable listener) {
        this.dir = Objects.requireNonNull(projectDir);
        this.listener = Objects.requireNonNull(listener);
    }

    /** Starts watching; the baseline fingerprint is taken synchronously (no initial fire). */
    public synchronized void start() {
        if (thread != null && !thread.isDone()) {
            return;
        }
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            service = null;
        }
        dirKey = null;
        fingerprint = fingerprint();
        running = true;
        thread = com.opencode.ide.client.WorkerPools.serialExecutor(
                "task-store-watcher[" + dir.getFileName() + "]").submit(this::loop);
    }

    /** Stops watching; safe to call more than once. */
    public synchronized void stop() {
        running = false;
        java.util.concurrent.Future<?> t = thread;
        thread = null;
        if (t != null) {
            t.cancel(true);
        }
        if (dirKey != null) {
            dirKey.cancel();
            dirKey = null;
        }
        WatchService s = service;
        service = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed watch service: nothing left to do
            }
        }
    }

    private void loop() {
        while (running) {
            WatchService s = service;
            ensureRegistered(s);
            WatchKey key = null;
            if (s != null) {
                try {
                    key = s.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                    return;
                }
            } else {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
            boolean watchEvent = false;
            if (key != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        watchEvent = true;
                        continue;
                    }
                    Object context = event.context();
                    String name = context == null ? "" : context.toString();
                    if (!name.startsWith(".")) {
                        watchEvent = true;
                    }
                }
                key.reset();
            }
            long current = fingerprint();
            boolean changed = fingerprint != FINGERPRINT_UNSET
                    && fingerprint != FINGERPRINT_ERROR
                    && current != fingerprint;
            if (watchEvent || changed) {
                try {
                    Thread.sleep(DEBOUNCE_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                current = fingerprint();
            }
            fingerprint = current;
            if (running && (watchEvent || changed)) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // a misbehaving listener must not kill the watcher thread
                }
            }
        }
    }

    private void ensureRegistered(WatchService s) {
        if (s == null) {
            return;
        }
        if (dirKey != null && dirKey.isValid()) {
            return;
        }
        if (dirKey != null) {
            dirKey.cancel();
            dirKey = null;
        }
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            dirKey = dir.register(s, new Kind<?>[] {
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE });
        } catch (IOException | java.nio.file.ClosedWatchServiceException e) {
            dirKey = null;
        }
    }

    private long fingerprint() {
        if (!Files.isDirectory(dir)) {
            return FINGERPRINT_MISSING;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".") && (n.endsWith(".md") || "_meta.json".equals(n));
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            return FINGERPRINT_ERROR;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        long h = 17;
        for (Path p : files) {
            h = 31 * h + p.getFileName().toString().hashCode();
            try {
                h = 31 * h + Files.getLastModifiedTime(p).toMillis();
                h = 31 * h + Files.size(p);
                h = 31 * h + contentChecksum(p);
            } catch (IOException e) {
                h = 31 * h + 1;
            }
        }
        return h;
    }

    /**
     * A cheap content checksum (CRC32 over the bytes) — the piece that makes
     * the fingerprint content-sensitive: a peer rewrite that preserves name,
     * size and mtime still changes it. Markdown stores are a few hundred KB
     * total, so re-reading them every poll is negligible; a file vanishing
     * mid-read (peer delete/rename race) reads as a change, which it is.
     */
    private static long contentChecksum(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return crc.getValue();
        } catch (IOException e) {
            return -1; // unreadable right now: hashes as a change, never as a stable state
        }
    }
}
