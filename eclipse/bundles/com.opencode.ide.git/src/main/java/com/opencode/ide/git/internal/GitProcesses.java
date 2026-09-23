package com.opencode.ide.git.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** The shared git-run pipeline and termination, used by worktree and store commands. */
final class GitProcesses {
    private GitProcesses() { }

    /**
     * Drains run as JOBS on the shared workers (the ECLIPSE JOB MANAGER -
     * never the ForkJoin common pool and never a pool of ours; the 2026-09-23
     * incident: pooled drains that could not keep up made SUCCESSFUL git
     * commands read as empty output and the sync abort mid-sequence).
     */
    private static ExecutorService drains() {
        return com.opencode.ide.client.WorkerPools.ioExecutor("git-drain");
    }

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
     * stdout (the 2026-09-23 incident). The streams are CLOSED on loss so a
     * parked reader unblocks and its worker returns to the pool.</p>
     */
    static Result await(Process process, String[] args, Duration timeout) {
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readUtf8(process.getInputStream()), drains());
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readUtf8(process.getErrorStream()), drains());
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
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
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

    /** Unblocks a parked drain reader after a lost join (best effort). */
    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort: the reader unblocks either way
        }
    }
}
