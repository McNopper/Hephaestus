package com.opencode.ide.ui.session;

import com.opencode.ide.client.model.OpencodeEvent;

/**
 * SWT-free predicate deciding whether one {@link OpencodeEvent} from the
 * {@code /event} SSE stream is relevant for the Session Details view of one
 * session: the event type must change the transcript/history, and the event
 * must belong to THIS session id.
 *
 * <p>Tolerates malformed events: {@code null} event, {@code null} type,
 * missing properties, or a session id that cannot be located all yield
 * {@code false} (never an exception, never a refresh of unrelated views).</p>
 */
public final class SessionEventFilter {

    private SessionEventFilter() {
    }

    /**
     * @param sessionId the view's session id (the secondary id it was opened with)
     * @param event     the live server event (may be {@code null}/malformed)
     * @return {@code true} when the event changes this session's history and a
     *         (debounced) reload is warranted
     */
    public static boolean shouldRefreshFor(String sessionId, OpencodeEvent event) {
        if (sessionId == null || sessionId.isBlank() || event == null) {
            return false;
        }
        if (event.type() == null) {
            return false;
        }
        return switch (event.type()) {
            // v2 split v1's two coarse events into named ones. Turn-is-over
            // signals (v1 session.idle / message.updated): session.idle plus the
            // three session.execution.* outcomes. Message content settled (v1
            // message.updated): session.text.ended. Streaming progress (v1
            // message.part.updated): the text/reasoning deltas and the tool
            // lifecycle - these arrive in bursts, so callers must coalesce.
            // Title/metadata (v1 session.updated, gone): session.renamed.
            case "session.idle",
                    "session.execution.succeeded", "session.execution.failed",
                    "session.execution.interrupted",
                    "session.renamed",
                    "session.text.ended",
                    "session.text.delta", "session.reasoning.delta",
                    "session.tool.called", "session.tool.input.started",
                    "session.tool.progress", "session.tool.success", "session.tool.failed" ->
                sessionId.equals(sessionIdOf(event));
            default -> false;
        };
    }

    /**
     * @return the session id an event belongs to. Every v2 session-scoped
     *         event carries it as a flat top-level {@code sessionID} — v1's
     *         nested {@code part.sessionID} / {@code info.id} fallbacks are
     *         gone with the events that used them. {@code null} when absent or
     *         not a string.
     */
    private static String sessionIdOf(OpencodeEvent event) {
        return event.string("sessionID");
    }
}
