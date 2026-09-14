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
 * manager's 60&nbsp;s / 10&nbsp;min timeouts) keep their in-place defaults,
 * but wherever the engine cares, it passes one of THESE values explicitly -
 * so the engine's behavior is fully described by this table.</p>
 */
public final class FleetTuning {

    /** Default per-ticket run budget when the dispatcher sends none. */
    public static final Duration DEFAULT_TICKET_BUDGET = Duration.ofMinutes(30);

    /** Upper clamp for a per-ticket budget (the tool layer enforces this). */
    public static final Duration MAX_TICKET_BUDGET = Duration.ofMinutes(24 * 60);

    /** Prompt-POST budget for interactive/legacy callers (the client's own default mirrors this). */
    public static final Duration INTERACTIVE_PROMPT_TIMEOUT = Duration.ofMinutes(5);

    /** How long the engine waits for a spawned {@code opencode serve} to become ready. */
    public static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(60);

    /** Idle-poll interval for the stream-less completion detection. */
    public static final long STATUS_POLL_MILLIS = 1000;

    /**
     * A running session with no new messages for this long is considered
     * STALLED: the watchdog aborts it ({@code POST /session/:id/abort}) and
     * the job fails cleanly - instead of burning the whole budget on a hang
     * or killing slow-but-healthy workers with a guessed wall clock.
     */
    public static final Duration STALL_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Shutdown grace: how long {@code close()} lets in-flight launches settle
     * (a merge mid-git must not be SIGKILLed - stale index.lock/MERGE_HEAD)
     * before hard-cancelling. Launches are bounded by their ticket budget;
     * this only bounds the WAIT at shutdown.
     */
    public static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private FleetTuning() {
    }
}
