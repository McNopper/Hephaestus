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
            "GIT_COMMAND_TIMEOUT_MS", Duration.ofSeconds(60));

    /** The merge timeout. Env: GIT_MERGE_TIMEOUT_MS. */
    public static final Duration MERGE_TIMEOUT = duration(
            "GIT_MERGE_TIMEOUT_MS", Duration.ofMinutes(10));

    /** Store-sync per-command timeout. Env: GIT_SYNC_TIMEOUT_MS. */
    public static final Duration SYNC_TIMEOUT = duration(
            "GIT_SYNC_TIMEOUT_MS", Duration.ofSeconds(60));

    /** How long to wait for a git process's stdout/stderr drain. Env: GIT_DRAIN_WAIT_MS. */
    public static final Duration OUTPUT_DRAIN_WAIT = duration(
            "GIT_DRAIN_WAIT_MS", Duration.ofSeconds(5));

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
