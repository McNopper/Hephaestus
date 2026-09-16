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

    /** HttpClient connect timeout. Env: CLIENT_CONNECT_TIMEOUT_MS. */
    public static final Duration CONNECT_TIMEOUT = duration(
            "CLIENT_CONNECT_TIMEOUT_MS", Duration.ofSeconds(10));

    /** Default per-call REST timeout. Env: CLIENT_REQUEST_TIMEOUT_MS. */
    public static final Duration REQUEST_TIMEOUT = duration(
            "CLIENT_REQUEST_TIMEOUT_MS", Duration.ofSeconds(30));

    /** Default prompt-POST timeout. Env: CLIENT_PROMPT_TIMEOUT_MS. */
    public static final Duration PROMPT_TIMEOUT = duration(
            "CLIENT_PROMPT_TIMEOUT_MS", Duration.ofMinutes(5));

    /** Readiness-probe connect timeout. Env: CLIENT_PROBE_CONNECT_MS. */
    public static final Duration PROBE_CONNECT_TIMEOUT = duration(
            "CLIENT_PROBE_CONNECT_MS", Duration.ofSeconds(5));

    /** /global/health readiness probe timeout. Env: CLIENT_HEALTH_PROBE_MS. */
    public static final Duration HEALTH_PROBE_TIMEOUT = duration(
            "CLIENT_HEALTH_PROBE_MS", Duration.ofSeconds(3));

    /** /agent readiness probe timeout. Env: CLIENT_AGENT_PROBE_MS. */
    public static final Duration AGENT_PROBE_TIMEOUT = duration(
            "CLIENT_AGENT_PROBE_MS", Duration.ofSeconds(5));

    /** Readiness re-probe interval. Env: CLIENT_PROBE_INTERVAL_MS. */
    public static final Duration PROBE_INTERVAL = duration(
            "CLIENT_PROBE_INTERVAL_MS", Duration.ofMillis(500));

    /** Wait after destroyForcibly. Env: CLIENT_DESTROY_WAIT_MS. */
    public static final Duration DESTROY_WAIT = duration(
            "CLIENT_DESTROY_WAIT_MS", Duration.ofSeconds(3));

    /** SSE HttpClient connect timeout (the stream's own TCP connect budget,
     *  deliberately independent of the REST client's {@link #CONNECT_TIMEOUT}).
     *  Env: CLIENT_SSE_CONNECT_TIMEOUT_MS. */
    public static final Duration SSE_CONNECT_TIMEOUT = duration(
            "CLIENT_SSE_CONNECT_TIMEOUT_MS", Duration.ofSeconds(10));

    /** SSE reconnect backoff base. Env: CLIENT_SSE_BACKOFF_BASE_MS. */
    public static final Duration SSE_BACKOFF_BASE = duration(
            "CLIENT_SSE_BACKOFF_BASE_MS", Duration.ofSeconds(1));

    /** SSE reconnect backoff ceiling. Env: CLIENT_SSE_BACKOFF_MAX_MS. */
    public static final Duration SSE_BACKOFF_MAX = duration(
            "CLIENT_SSE_BACKOFF_MAX_MS", Duration.ofSeconds(30));

    /** SSE stable-connection window. Env: CLIENT_SSE_STABLE_MS. */
    public static final Duration SSE_STABLE_CONNECTION = duration(
            "CLIENT_SSE_STABLE_MS", Duration.ofSeconds(10));

    /** Max characters of an error-body snippet in thrown errors. Env: CLIENT_SNIPPET_MAX. */
    public static final int SNIPPET_MAX = integer(
            "CLIENT_SNIPPET_MAX", 500);

    /** Min characters before truncation - the cap of the short warning-snippet
     *  tier (malformed-body log warnings). Env: CLIENT_SNIPPET_MIN. */
    public static final int SNIPPET_MIN = integer(
            "CLIENT_SNIPPET_MIN", 120);

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

    private ClientTuning() {
    }
}
