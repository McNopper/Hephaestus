package com.opencode.ide.git;

import java.time.Duration;

/**
 * The git layer's knob table (same discipline as the fleet's
 * {@code FleetTuning}). Change a knob here and nowhere else.
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class GitTuning {

    /** Per git-command timeout (every add/commit/status/rev-parse call). */
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /** The merge timeout (a big merge legitimately takes minutes). */
    public static final Duration MERGE_TIMEOUT = Duration.ofMinutes(10);

    /** Store-sync per-command timeout (add/commit/pull/push each). */
    public static final Duration SYNC_TIMEOUT = Duration.ofSeconds(60);

    /** How long to wait for a git process's stdout/stderr drain after exit. */
    public static final Duration OUTPUT_DRAIN_WAIT = Duration.ofSeconds(5);

    /** Max tail characters of git stderr kept in log warnings. */
    public static final int WARN_TAIL = 1000;

    private GitTuning() {
    }
}
