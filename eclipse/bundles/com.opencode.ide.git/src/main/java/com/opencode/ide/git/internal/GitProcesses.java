package com.opencode.ide.git.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The shared git-run pipeline and termination, used by worktree and store commands. */
final class GitProcesses {
    private GitProcesses() { }

    /**
     * Drains run on a private CACHED pool with named threads. Cached, not
     * fixed: each awaiting command holds one thread per stream, so a fixed
     * 2-thread pool supports exactly ONE git process - any overlap queued
     * drains past the drain wait and a successful command read as empty
     * output (2026-09-23 review finding: the incident this exists to fix).
     * Idle threads reap; drains are cheap blocked reads.
     */
    private static final AtomicInteger DRAIN_SEQ = new AtomicInteger();
    private static final ExecutorService DRAINS =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "git-drain-" + DRAIN_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    /** Wait before releasing RepoGate. Git locks carry no owner identity, so
     * never delete them or abort an unknown merge as automatic cleanup. */
    static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The one run pipeline (2026-09-23 CPD finding: GitStore and
     * GitWorktreeManager copy-pasted it): both streams drained while the
     * process runs, a bounded wait, forced termination on interrupt or
     * timeout. A failure is a VALUE here - each caller maps it to its own
     * error style (error output vs exception).
     *
     * <p>A LOST DRAIN is loud, never silent: {@code join} timing out or a
     * stream read failing turns the result into a failure
     * ({@code "output drain lost for git ..."}), so callers can retry or
     * error out instead of manufacturing {@code NOT_A_REPO} from an empty
     * stdout (the 2026-09-23 incident).</p>
     */
    static Result await(Process process, String[] args, Duration timeout) {
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readUtf8(process.getInputStream()), DRAINS);
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readUtf8(process.getErrorStream()), DRAINS);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return new Result(null, "", "", "git " + Arrays.toString(args) + " timed out after " + timeout, null);
            }
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            return new Result(null, "", "", "interrupted while waiting for git " + Arrays.toString(args), e);
        }
        String out = join(stdout, args);
        String err = join(stderr, args);
        if (out == null || err == null) {
            return new Result(null, out == null ? "" : out, err == null ? "" : err,
                    "output drain lost for git " + Arrays.toString(args), null);
        }
        return new Result(process.exitValue(), out, err, null, null);
    }

    /** A finished run (exitCode/stdout/stderr) or a failure (failure/cause). */
    record Result(Integer exitCode, String stdout, String stderr, String failure, Throwable cause) {
        boolean failed() {
            return failure != null;
        }
    }

    /** @return the drained text, or {@code null} when the drain was lost (never silent). */
    private static String join(CompletableFuture<String> future, String[] args) {
        try {
            return future.get(com.opencode.ide.git.GitTuning.OUTPUT_DRAIN_WAIT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return the stream text; a read failure returns a marker the drain check catches. */
    private static String readUtf8(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
