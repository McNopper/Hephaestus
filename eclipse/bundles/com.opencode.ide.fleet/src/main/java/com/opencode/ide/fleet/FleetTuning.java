package com.opencode.ide.fleet;

import java.time.Duration;

/**
 * The engine's knob table - EVERY magic number that tunes fleet behavior
 * lives here and nowhere else (user direction 2026-08-28). Later this
 * becomes configurable (env vars / a settings file); until then changing a
 * knob means changing exactly one line in this class.
 *
 * <p>Scope: numbers the FLEET engine controls. Per-call defaults that live
 * inside lower bundles (the client's 30&nbsp;s HTTP default, the git
 * manager's 3/5/10&nbsp;min timeouts) keep their in-place defaults,
 * but wherever the engine cares, it passes one of THESE values explicitly -
 * so the engine's behavior is fully described by this table.</p>
 */
public final class FleetTuning {

    /** Default per-ticket run budget. Env: FLEET_TICKET_BUDGET_MS. */
    public static final Duration DEFAULT_TICKET_BUDGET = duration(
            "FLEET_TICKET_BUDGET_MS", Duration.ofMinutes(30));

    /** Upper clamp for a per-ticket budget. Env: FLEET_MAX_TICKET_BUDGET_MS. */
    public static final Duration MAX_TICKET_BUDGET = duration(
            "FLEET_MAX_TICKET_BUDGET_MS", Duration.ofHours(24));

    /** Prompt-POST budget for interactive/legacy callers. Env: FLEET_PROMPT_TIMEOUT_MS. */
    public static final Duration INTERACTIVE_PROMPT_TIMEOUT = duration(
            "FLEET_PROMPT_TIMEOUT_MS", Duration.ofMinutes(5));

    /** Spawned-server readiness timeout. Env: FLEET_SERVER_START_MS. */
    public static final Duration SERVER_START_TIMEOUT = duration(
            "FLEET_SERVER_START_MS", Duration.ofSeconds(60));

    /** Idle-poll interval. Env: FLEET_POLL_MS. */
    public static final long STATUS_POLL_MILLIS = integer(
            "FLEET_POLL_MS", 1000);

    /** Session idle+silent for this long is aborted. Env: FLEET_STALL_TIMEOUT_MS. */
    public static final Duration STALL_TIMEOUT = duration(
            "FLEET_STALL_TIMEOUT_MS", Duration.ofMinutes(5));

    /** Close() grace before hard-cancel. Env: FLEET_SHUTDOWN_GRACE_MS. */
    public static final Duration SHUTDOWN_GRACE = duration(
            "FLEET_SHUTDOWN_GRACE_MS", Duration.ofSeconds(30));

    /**
     * B-004: pause after killing a leaked serve before retrying worktree
     * removal - the OS releases a dead process's file handles asynchronously
     * (notably on Windows). Env: FLEET_SERVE_KILL_SETTLE_MS.
     */
    public static final Duration SERVE_KILL_SETTLE = duration(
            "FLEET_SERVE_KILL_SETTLE_MS", Duration.ofMillis(750));

    /**
     * B-008 / rubberduck F-4: the ABSOLUTE wall-clock run cap. The per-ticket
     * budget is progress-aware (a run showing progress is never
     * budget-killed, per B-008's AC) - this cap is the backstop that keeps a
     * permanently-busy zombie session from holding a concurrency slot and
     * burning tokens forever. Env: FLEET_HARD_RUN_CAP_MS.
     */
    public static final Duration HARD_RUN_CAP = duration(
            "FLEET_HARD_RUN_CAP_MS", Duration.ofHours(4));

    /**
     * U-022: recurring-waves cycle period - how often the loop re-checks
     * drain state, NEEDS-HUMAN tickets and wave planning. Env: FLEET_WAVE_LOOP_MS.
     */
    public static final Duration WAVE_LOOP_PERIOD = duration(
            "FLEET_WAVE_LOOP_MS", Duration.ofSeconds(5));

    /**
     * U-022: how often the parked recurring-waves loop repeats its
     * NEEDS-HUMAN summary (the first appearance is immediate). Env: FLEET_WAVE_SUMMARY_MS.
     */
    public static final Duration WAVE_SUMMARY_PERIOD = duration(
            "FLEET_WAVE_SUMMARY_MS", Duration.ofMinutes(5));

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

    private FleetTuning() {
    }
}
