package com.opencode.ide.client;

import java.time.Duration;

/**
 * The HTTP client's knob table - every timeout and truncation limit the
 * client uses lives here (the same discipline as the fleet's
 * {@code FleetTuning}; bundle-local because the client cannot depend on the
 * fleet). Change a knob here and nowhere else.
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class ClientTuning {

    /** HttpClient connect timeout for every request. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default per-call REST timeout (everything except the prompt POST). */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Default prompt-POST timeout (interactive callers; the fleet passes its whole budget). */
    public static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(5);

    /** Readiness-probe client connect timeout (spawn path). */
    public static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** The /global/health readiness probe timeout (spawn path). */
    public static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** The /agent readiness probe timeout (spawn path - the data layer must be up too). */
    public static final Duration AGENT_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Readiness re-probe interval while waiting for a spawned server. */
    public static final Duration PROBE_INTERVAL = Duration.ofMillis(500);

    /** How long to wait after destroyForcibly before giving up on the child. */
    public static final Duration DESTROY_WAIT = Duration.ofSeconds(3);

    /** SSE reconnect backoff base (doubles on consecutive failures). */
    public static final Duration SSE_BACKOFF_BASE = Duration.ofSeconds(1);

    /** SSE reconnect backoff ceiling. */
    public static final Duration SSE_BACKOFF_MAX = Duration.ofSeconds(30);

    /** A stable SSE connection must hold this long before the connection-failed marker clears. */
    public static final Duration SSE_STABLE_CONNECTION = Duration.ofSeconds(10);

    /** Max characters of an error/log snippet kept in exception messages and tool output. */
    public static final int SNIPPET_MAX = 500;

    /** Min characters before a snippet is truncated (short messages pass through). */
    public static final int SNIPPET_MIN = 120;

    private ClientTuning() {
    }
}
