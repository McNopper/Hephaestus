package com.opencode.ide.ui.attention;

import java.util.Objects;

/**
 * One attention-worthy happening: what happened ({@link AttentionKind}), a
 * human-readable one-liner for the popup, and the session it belongs to.
 *
 * <p>Null-tolerant value: a blank message falls back to the kind's title;
 * {@code sessionId} may be {@code null} for server-level events (the
 * classifier always sets it when the wire event carried one).</p>
 *
 * @param kind     what happened (never {@code null})
 * @param message  display one-liner; blank falls back to {@link AttentionKind#title()}
 * @param sessionId the opencode session id, or {@code null}
 */
public record AttentionEvent(AttentionKind kind, String message, String sessionId) {

    /** Null-safe: kind required, message defaults to the kind title. */
    public AttentionEvent {
        Objects.requireNonNull(kind, "kind");
        if (message == null || message.isBlank()) {
            message = kind.title();
        }
    }
}
