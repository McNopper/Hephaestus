package com.opencode.ide.git;

import java.time.Duration;

/**
 * The git layer's knob table (same discipline as the fleet's
 * {@code FleetTuning}). Change a knob here and nowhere else.
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class GitTuning {

    /** Per git-command timeout. Env: GIT_COMMAND_TIMEOUT_MS. */
    public static final Duration COMMAND_TIMEOUT = duration(
            "GIT_COMMAND_TIMEOUT_MS", Duration.ofMinutes(3));

    /** The merge timeout. Env: GIT_MERGE_TIMEOUT_MS. */
    public static final Duration MERGE_TIMEOUT = duration(
            "GIT_MERGE_TIMEOUT_MS", Duration.ofMinutes(10));

    /**
     * Store-sync per-command timeout. Env: GIT_SYNC_TIMEOUT_MS.
     *
     * <p>Minutes, not seconds: on a loaded machine without the Defender
     * exclusions (T-006) git commands legitimately run ~90s apart (measured
     * 2026-09-23) - and killing git MID-SEQUENCE is worse than waiting: a
     * killed {@code add} strands the store staged-but-uncommitted and a
     * killed {@code commit} silently loses the bookkeeping.</p>
     */
    public static final Duration SYNC_TIMEOUT = duration(
            "GIT_SYNC_TIMEOUT_MS", Duration.ofMinutes(5));

    /**
     * How long to wait for a git process's stdout/stderr drain AFTER the
     * process exited. Env: GIT_DRAIN_WAIT_MS. Generous on purpose
     * (2026-09-23: 5&nbsp;s was blown under machine load - the drain thread
     * simply was not scheduled in time, the result read as "output drain
     * lost", and the sync aborted MID-SEQUENCE with the store stranded
     * staged-but-uncommitted). Still bounded: a descendant holding the pipe
     * open cannot wedge git forever - and the loss stays LOUD in
     * GitProcesses, never a silent empty output.
     */
    public static final Duration OUTPUT_DRAIN_WAIT = duration(
            "GIT_DRAIN_WAIT_MS", Duration.ofSeconds(60));

    /** Max tail characters of git stderr kept in log warnings. Env: GIT_WARN_TAIL. */
    public static final int WARN_TAIL = integer(
            "GIT_WARN_TAIL", 1000);

    private static Duration duration(String envVar, Duration fallback) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) { return fallback; }
        try {
            long millis = Long.parseLong(value.trim());
            return millis > 0 ? Duration.ofMillis(millis) : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }

    private static int integer(String envVar, int fallback) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) { return fallback; }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }

    private GitTuning() {
    }
}
