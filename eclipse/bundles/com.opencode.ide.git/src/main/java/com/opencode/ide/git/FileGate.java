package com.opencode.ide.git;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Reentrant JVM and process exclusion. Lock files are permanent: never unlink them. */
public final class FileGate {
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private FileGate() { }

    public static <T> T with(Path file, Supplier<T> action) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path key = file.toAbsolutePath().getParent().toRealPath().resolve(file.getFileName());
            ReentrantLock local = LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
            local.lockInterruptibly();
            try {
                if (local.getHoldCount() > 1) {
                    return action.get();
                }
                try (var channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        var lock = channel.lock()) {
                    return action.get();
                }
            } finally {
                local.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted acquiring " + file, e);
        } catch (IOException e) {
            throw new IllegalStateException("cannot lock " + file, e);
        }
    }
}
