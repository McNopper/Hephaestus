package com.opencode.ide.git.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** The shared git-run pipeline and termination, used by worktree and store commands. */
final class GitProcesses {
    private GitProcesses() { }

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
     */
    static Result await(Process process, String[] args, Duration timeout) {
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readUtf8(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
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
        return new Result(process.exitValue(), join(stdout), join(stderr), null, null);
    }

    /** A finished run (exitCode/stdout/stderr) or a failure (failure/cause). */
    record Result(Integer exitCode, String stdout, String stderr, String failure, Throwable cause) {
        boolean failed() {
            return failure != null;
        }
    }

    private static String join(CompletableFuture<String> future) {
        try {
            return future.get(com.opencode.ide.git.GitTuning.OUTPUT_DRAIN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readUtf8(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
