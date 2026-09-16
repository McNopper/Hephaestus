package com.opencode.ide.board.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.opencode.ide.git.GitLocator;
import com.opencode.ide.git.GitTuning;

/**
 * Minimal git CLI access for the Fleet view ("Open diff"), using the same
 * process pattern as the git bundle's worktree manager: {@code git -C <dir>}
 * with UTF-8 output capture, asynchronous stream reads and a timeout with
 * {@code destroyForcibly()}. The timeout is the git bundle's canonical knob
 * ({@link GitTuning#COMMAND_TIMEOUT} — env-overridable via
 * {@code GIT_COMMAND_TIMEOUT_MS}); G-004: no parallel magic numbers. The
 * binary is resolved through the git bundle's {@link GitLocator} (PATH first,
 * then Windows fallback probes — same discovery as every other git call in
 * the product; the internal package is x-friends-exported to this bundle).
 * The {@link #run} seam is generic (any command) so the failure modes —
 * missing binary, timeout, non-zero exit — are unit-testable without going
 * through git diff every time.
 */
public final class GitCli {

    /**
     * The per-command timeout, delegated to the git bundle's knob table so
     * every git invocation in the product shares one tunable value.
     */
    private static final Duration TIMEOUT = GitTuning.COMMAND_TIMEOUT;

    private GitCli() {
    }

    /**
     * Returns {@code git -C <repoRoot> diff HEAD..opencode/<taskId>}
     * ({@code HEAD} is the main worktree's branch — the "main" side of the
     * diff).
     *
     * @throws IllegalStateException on a missing git, timeout or non-zero exit
     */
    public static String diff(Path repoRoot, String taskId) {
        List<String> command = List.of(GitLocator.resolve().command().toString(),
                "-C", repoRoot.toString(),
                "diff", "HEAD.." + com.opencode.ide.git.FleetGit.branchFor(taskId));
        return run(command, TIMEOUT);
    }

    /**
     * Runs a command to completion with UTF-8 stdout/stderr capture
     * (asynchronous stream reads — no pipe deadlock) and a hard timeout
     * enforced via {@code destroyForcibly()}.
     *
     * @throws IllegalStateException on a missing binary ("Failed to start"),
     *                               a timeout ("timed out") or a non-zero exit
     *                               ("failed (exit n)")
     */
    public static String run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start " + command.get(0) + ": " + e.getMessage(), e);
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readUtf8(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readUtf8(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + command.get(0), e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(command.get(0) + " timed out after " + timeout);
        }
        String out = join(stdout);
        String err = join(stderr);
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    command.get(0) + " failed (exit " + process.exitValue() + "): " + err.trim());
        }
        return out + (err.isBlank() ? "" : "\n--- stderr ---\n" + err);
    }

    /**
     * Waits for one stream-drain future with the shared drain budget
     * ({@link GitTuning#OUTPUT_DRAIN_WAIT}, same 5 s default as the git
     * bundle's own join calls — G-004 symmetry: one drain knob everywhere).
     */
    private static String join(CompletableFuture<String> future) {
        try {
            return future.get(GitTuning.OUTPUT_DRAIN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
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
