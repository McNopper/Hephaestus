package com.opencode.ide.ui.attention;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * SWT-free, Eclipse-free classification of opencode {@code /event} stream
 * events into {@link AttentionEvent}s — the pure heart of U-047's attention
 * parity. Feed every event through {@link #apply}; {@code null} means
 * "nothing attention-worthy happened".
 *
 * <p>Mapping (canonical v2 type names verified against
 * {@link PermissionEvents} and the fleet's {@code SseSessionEvents}):</p>
 * <ul>
 *   <li>{@code permission.asked} → {@link AttentionKind#PERMISSION_ASK},
 *   message from {@link PermissionRequest#display()} (category + command).</li>
 *   <li>{@code session.status} {@code busy}/{@code retry} → remembered in a
 *   busy set (no notification — the TUI parity notifies on the END of work,
 *   not its start).</li>
 *   <li>{@code session.idle}, {@code session.status} with a non-busy type, or
 *   {@code session.execution.succeeded} <b>after being busy</b> →
 *   {@link AttentionKind#SESSION_COMPLETED}. The busy-set memory is what
 *   keeps noise out: an idle event for a session we never saw working is
 *   ignored (the same discipline as {@code ActivityTracker#applyIdle}).</li>
 *   <li>{@code session.execution.failed} / {@code session.execution.interrupted}
 *   <b>after being busy</b> → {@link AttentionKind#SESSION_ERROR}. These are
 *   the v2 session error types; there is no separate {@code session.error}
 *   event on the wire.</li>
 *   <li>{@code session.deleted} → busy set entry dropped silently (a deleted
 *   session is gone, not finished — notifying would be noise).</li>
 *   <li>{@link AttentionKind#QUESTION}: enum supported, NOT mapped — see
 *   {@link AttentionKind} javadoc. TODO(U-047): wire once a form-ask SSE
 *   event type is confirmed on the wire.</li>
 * </ul>
 *
 * <p>Stateful but thread-safe: the busy set is a concurrent set (events
 * arrive on the SSE reader thread; {@link #apply} may also be called from
 * tests). Busy memory is per-instance — one classifier per event source.
 * The event {@link OpencodeEvent#directory()} is deliberately NOT filtered:
 * v2's single stream carries every session the server processes (including
 * other projects' worktree sessions and TUI-driven runs on the attached
 * shared service), and attention parity means "the human's agent needs
 * them", not "the current Eclipse project's agent". Views that need
 * directory scoping filter downstream (like the Server view does).</p>
 */
public final class AttentionClassifier {

    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    /**
     * Classifies one event.
     *
     * @return the {@link AttentionEvent} to surface, or {@code null} when the
     *         event is not attention-worthy (the common case)
     */
    public AttentionEvent apply(OpencodeEvent event) {
        if (event == null || event.type() == null) {
            return null;
        }
        return switch (event.type()) {
            case PermissionEvents.ASKED -> permissionAsk(event);
            case "session.status" -> status(event);
            case "session.idle", "session.execution.succeeded" ->
                completedIfBusy(sessionId(event), "finished");
            case "session.execution.failed" -> failedIfBusy(sessionId(event), "failed");
            case "session.execution.interrupted" -> failedIfBusy(sessionId(event), "was interrupted");
            case "session.deleted" -> {
                // silent cleanup: a deleted session is gone, not finished
                String sid = sessionId(event);
                if (sid != null) {
                    busy.remove(sid);
                }
                yield null;
            }
            default -> null;
        };
    }

    /** @return whether this session is currently remembered as working. */
    public boolean isBusy(String sessionId) {
        return sessionId != null && busy.contains(sessionId);
    }

    /** @return an immutable snapshot of the sessions currently working. */
    public Set<String> busySessions() {
        return Set.copyOf(busy);
    }

    // ---------- classification branches ----------

    /** {@code permission.asked}: canonical parsing + display via the client's model. */
    private AttentionEvent permissionAsk(OpencodeEvent event) {
        PermissionRequest request = PermissionEvents.parse(event);
        if (request == null || !request.pending()) {
            // null = missing ids (nothing actionable); ANSWERED = a replied event
            return null;
        }
        return new AttentionEvent(AttentionKind.PERMISSION_ASK, request.display(), request.sessionId());
    }

    /**
     * {@code session.status}: busy/retry feeds the busy-set memory; any other
     * typed status (v2: {@code idle}) completes a remembered-busy session.
     * A status event without type detail leaves all state untouched.
     */
    private AttentionEvent status(OpencodeEvent event) {
        String sid = sessionId(event);
        if (sid == null) {
            return null;
        }
        String type = event.string("status");
        if (type == null) {
            type = event.at("status.type");
        }
        if (type == null) {
            return null;
        }
        if ("busy".equalsIgnoreCase(type) || "retry".equalsIgnoreCase(type)) {
            busy.add(sid);
            return null;
        }
        return completedIfBusy(sid, "finished");
    }

    /** Turn-end: only a session remembered busy yields a completion event. */
    private AttentionEvent completedIfBusy(String sessionId, String outcome) {
        if (sessionId == null || !busy.remove(sessionId)) {
            return null;
        }
        return new AttentionEvent(AttentionKind.SESSION_COMPLETED,
                "Session " + sessionId + " " + outcome, sessionId);
    }

    /** Error-end: the failed/interrupted flavor of a turn end. */
    private AttentionEvent failedIfBusy(String sessionId, String outcome) {
        if (sessionId == null || !busy.remove(sessionId)) {
            return null;
        }
        return new AttentionEvent(AttentionKind.SESSION_ERROR,
                "Session " + sessionId + " " + outcome, sessionId);
    }

    /** The session id of a v2 session event (flat {@code sessionID}). */
    private static String sessionId(OpencodeEvent event) {
        return event.string("sessionID");
    }
}
