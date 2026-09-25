package com.opencode.ide.fleet;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Event-to-queue bridge for the fleet's {@link PermissionQueue}: feed it the
 * opencode {@code /api/event} SSE stream ({@link #onEvent}, e.g. via
 * {@link #subscribe(SseSessionEvents.Subscriber)} on the owner-run stream),
 * and it enqueues {@code permission.asked} events of the fleet's own sessions
 * while dropping them again on {@code permission.replied} or
 * {@code session.deleted}.
 *
 * <p><b>Why the runner-level session-created callback:</b> the fleet's
 * synchronous client call queues {@code POST /session/:id/prompt} and polls
 * until the agent's final reply. An unattended session can ask for permission
 * while that call is waiting. The session must therefore be watched
 * from the moment it is created (before the prompt is sent), not after
 * {@code submit} returns. Wire the {@link FleetRunner}'s
 * {@code onSessionCreated} callback to {@link #sessionStarted(String)}
 * (e.g. {@code new FleetRunner(client, worktrees, bridge::sessionStarted)});
 * every session the runner creates is then registered with this bridge.
 * {@link TaskFleet} calls {@link #sessionEnded(String)} when the job leaves
 * the launch (completed, aborted, failed) so pending entries are dropped.</p>
 *
 * <p><b>v2 payloads:</b> the only field this class reads itself is
 * {@code sessionID}, which v2 kept on {@code session.deleted} (and on every
 * other session event). Everything else — the permission id, category and
 * patterns — is resolved by the shape-tolerant
 * {@link PermissionEvents#parse(OpencodeEvent)} in the client bundle, which
 * stays the single place that knows the wire shape.</p>
 *
 * <p>Never throws on any event; foreign sessions are ignored. Pure Java, no
 * Eclipse/OSGi.</p>
 */
public final class FleetPermissionBridge {

    private final PermissionQueue queue;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    /** @param queue where requests of watched sessions are enqueued */
    public FleetPermissionBridge(PermissionQueue queue) {
        this.queue = queue;
    }

    /**
     * The bridge is a live view of the engine's permission queue; the
     * watchdog pauses its stall clock while asks are pending (a session
     * waiting for a human answer is WAITING, not hung - review finding).
     */
    public int pendingCount() {
        return queue.pendingCount();
    }

    /**
     * Delegates the REST safety net (slice 3, 2026-09-25): asks the event
     * stream missed reach the queue from the service's own list - the list is
     * a floor, events stay the fast path.
     */
    public int reconcile(java.util.List<com.opencode.ide.client.activity.PermissionRequest> requests) {
        return queue.reconcile(requests);
    }

    /**
     * Marks a session as fleet-watched: its permission requests are enqueued.
     * Idempotent; the direct target of the {@link FleetRunner}'s
     * session-created callback (see class javadoc), invoked before the
     * session's first prompt is sent.
     */
    public void sessionStarted(String sessionId) {
        if (sessionId != null) {
            sessions.add(sessionId);
        }
    }

    /**
     * The session ended (job completed/aborted/failed or the session was
     * deleted): stop watching and drop its pending requests.
     */
    public void sessionEnded(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
        queue.remove(sessionId);
    }

    /**
     * Feeds one SSE event (any type). {@code session.deleted} ends a watched
     * session; {@code permission.asked}/{@code permission.replied} of watched
     * sessions are forwarded to the queue. Unknown sessions, foreign event
     * types and malformed payloads are ignored — this method never throws.
     *
     * <p>The client-owned {@link PermissionEvents#parse(OpencodeEvent)} resolves
     * the request id from {@code id} on asks and {@code requestID} on replies.
     * This bridge only reads {@code sessionID} itself for session deletion.</p>
     */
    public void onEvent(OpencodeEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        if ("session.deleted".equals(event.type())) {
            // v2 keeps the name and the flat {sessionID} payload
            String sessionId = event.string("sessionID");
            if (sessionId != null && sessions.contains(sessionId)) {
                sessionEnded(sessionId);
            }
            return;
        }
        PermissionRequest request = PermissionEvents.parse(event);
        if (request == null || !sessions.contains(request.sessionId())) {
            return;
        }
        queue.offer(request);
    }

    /**
     * Subscribes this bridge to an owner-run event stream (the same
     * {@link SseSessionEvents.Subscriber} seam the SSE completion detection
     * uses); the returned handle unsubscribes.
     */
    public Runnable subscribe(SseSessionEvents.Subscriber subscriber) {
        return subscriber.subscribe(this::onEvent);
    }
}
