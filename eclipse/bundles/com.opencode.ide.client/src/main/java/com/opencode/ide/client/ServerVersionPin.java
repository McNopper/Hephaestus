package com.opencode.ide.client;

import com.opencode.ide.client.model.HealthStatus;

/**
 * Connect-time server version pin (H-002): the opencode server version this
 * client's endpoints were last cross-checked against
 * ({@link #PINNED_VERSION}). The REST surface is only re-verified by hand,
 * so a server that drifted past the pin may expose changed endpoints nobody
 * noticed — {@link #evaluate(HealthStatus)} turns one connect-time health
 * snapshot into a {@link Verdict} that says so. A mismatch is a warning,
 * never a failed start.
 */
public final class ServerVersionPin {

    /**
     * The opencode version whose endpoint spec this client was cross-checked
     * against (2026-09-20, v2 migration). On a mismatch, rerun the endpoint
     * smoke against the new version's spec, then bump this pin.
     */
    public static final String PINNED_VERSION = "2.0.10";

    private ServerVersionPin() {
    }

    /**
     * Compares one health snapshot's version against
     * {@link #PINNED_VERSION}; never throws — a {@code null} snapshot or a
     * {@code null}/blank version reads as {@link Verdict.Kind#UNKNOWN}. The
     * healthy flag is not part of the verdict; only the version is.
     */
    public static Verdict evaluate(HealthStatus health) {
        String version = health == null ? null : health.version();
        if (version == null || version.isBlank()) {
            return new Verdict(Verdict.Kind.UNKNOWN, null, null);
        }
        String seen = version.strip();
        if (seen.equals(PINNED_VERSION)) {
            return new Verdict(Verdict.Kind.MATCHING, seen, null);
        }
        return new Verdict(Verdict.Kind.MISMATCH, seen,
                "opencode server " + seen + " != pinned " + PINNED_VERSION
                        + " - the endpoint cross-check may have rotted; rerun the endpoint smoke");
    }

    /**
     * One pin comparison: the outcome, the last seen server version it is
     * based on ({@code null} when the server reported none), and the warning
     * line a {@link Verdict.Kind#MISMATCH} should be logged through
     * ({@code null} for every other outcome — MATCHING and UNKNOWN have
     * nothing to say).
     */
    public record Verdict(Kind kind, String serverVersion, String warning) {

        public boolean isMismatch() {
            return kind == Kind.MISMATCH;
        }

        /** The three outcomes of {@link #evaluate(HealthStatus)}. */
        public enum Kind {

            /** The server reported exactly {@value ServerVersionPin#PINNED_VERSION}. */
            MATCHING,

            /** The server reported a different version — the warning applies. */
            MISMATCH,

            /** The server reported no usable version (null or blank). */
            UNKNOWN
        }
    }
}
