package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Unit tests for {@link FleetPermissionBridge} against fakes (no HTTP, no
 * real stream): only watched sessions' {@code permission.asked} events are
 * enqueued, {@code permission.replied} clears them, {@code session.deleted}
 * ends a watched session, sessionEnded unwatches, malformed events never
 * throw, plus the subscriber wiring.
 */
public class FleetPermissionBridgeTest {

    /**
     * A v2 {@code permission.asked}:
     * {@code {sessionID, action, resources, save, metadata, source}} — the
     * identity lives in {@code source.id}.
     *
     * <p>TODO(v2): the top-level {@code id} is a compatibility alias, not
     * something a 2.0.x server sends. Client-owned
     * {@code PermissionEvents.parse} still resolves identity from the v1
     * top-level {@code id}/{@code permission}/{@code patterns}; drop the three
     * aliased members here once it reads {@code source.id}/{@code action}/
     * {@code resources} (see {@link FleetPermissionBridge#onEvent}).</p>
     */
    private static OpencodeEvent asked(String sessionId, String permissionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        properties.addProperty("action", "bash");
        properties.add("resources", com.google.gson.JsonParser.parseString("[\"git push\"]"));
        properties.addProperty("save", false);
        properties.add("metadata", com.google.gson.JsonParser.parseString(
                "{\"command\":\"git push\"}"));
        JsonObject source = new JsonObject();
        source.addProperty("type", "tool");
        source.addProperty("messageID", "msg_1");
        source.addProperty("id", permissionId);
        properties.add("source", source);
        // compatibility aliases for the still-v1 client parser (see javadoc)
        properties.addProperty("id", permissionId);
        properties.addProperty("permission", "bash");
        properties.add("patterns", com.google.gson.JsonParser.parseString("[\"git push\"]"));
        return new OpencodeEvent("permission.asked", properties);
    }

    /** A v2 {@code permission.replied}: {@code {sessionID, requestID, reply}}. */
    private static OpencodeEvent replied(String sessionId, String permissionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        properties.addProperty("requestID", permissionId);
        properties.addProperty("reply", "once");
        return new OpencodeEvent("permission.replied", properties);
    }

    private static OpencodeEvent sessionEvent(String type, String sessionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        return new OpencodeEvent(type, properties);
    }

    /** A v2 {@code session.status}: the status is an OBJECT now, not a string. */
    private static OpencodeEvent statusEvent(String sessionId, String statusType) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        JsonObject status = new JsonObject();
        status.addProperty("type", statusType);
        properties.add("status", status);
        return new OpencodeEvent("session.status", properties);
    }

    /** Fake registration point (mirrors SseSessionEventsTest). */
    private static final class FakeSubscriber implements SseSessionEvents.Subscriber {

        final List<Consumer<OpencodeEvent>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public Runnable subscribe(Consumer<OpencodeEvent> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        void emit(OpencodeEvent event) {
            for (Consumer<OpencodeEvent> listener : listeners) {
                listener.accept(event);
            }
        }
    }

    @Test
    public void askedOfWatchedSessionIsEnqueued() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);

        bridge.sessionStarted("ses_1");
        bridge.onEvent(asked("ses_1", "per_1"));

        assertEquals(List.of("per_1"), ids(queue));
    }

    @Test
    public void askedOfUnwatchedSessionIsIgnored() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);

        bridge.onEvent(asked("ses_other", "per_1"));

        assertTrue(queue.pending().isEmpty());
    }

    @Test
    public void repliedEventClearsThePendingRequest() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        bridge.sessionStarted("ses_1");
        bridge.onEvent(asked("ses_1", "per_1"));

        bridge.onEvent(replied("ses_1", "per_1"));

        assertTrue(queue.pending().isEmpty());
    }

    @Test
    public void sessionDeletedEndsWatchedSessionAndDropsItsRequests() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        bridge.sessionStarted("ses_1");

        bridge.onEvent(asked("ses_1", "per_1"));
        bridge.onEvent(sessionEvent("session.deleted", "ses_1"));

        assertTrue("deleted session's requests are dropped", queue.pending().isEmpty());
        bridge.onEvent(asked("ses_1", "per_2"));
        assertTrue("and it is no longer watched", queue.pending().isEmpty());
    }

    @Test
    public void sessionDeletedOfForeignSessionChangesNothing() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        bridge.sessionStarted("ses_1");
        bridge.onEvent(asked("ses_1", "per_1"));

        bridge.onEvent(sessionEvent("session.deleted", "ses_other"));

        assertEquals(1, queue.pendingCount());
    }

    @Test
    public void sessionEndedDropsPendingAndStopsWatching() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        bridge.sessionStarted("ses_1");
        bridge.onEvent(asked("ses_1", "per_1"));

        bridge.sessionEnded("ses_1");

        assertTrue(queue.pending().isEmpty());
        bridge.onEvent(asked("ses_1", "per_2"));
        assertTrue(queue.pending().isEmpty());
    }

    @Test
    public void foreignAndMalformedEventsNeverThrow() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        bridge.sessionStarted("ses_1");

        bridge.onEvent(null);
        bridge.onEvent(new OpencodeEvent(null, new JsonObject()));
        bridge.onEvent(sessionEvent("session.idle", "ses_1"));
        bridge.onEvent(statusEvent("ses_1", "busy"));
        bridge.onEvent(sessionEvent("session.execution.succeeded", "ses_1"));
        // v2 dropped todo.updated; usage updates are the new chatter to ignore
        bridge.onEvent(new OpencodeEvent("session.usage.updated", new JsonObject()));
        bridge.onEvent(asked("ses_1", null)); // parse yields null (no id)
        bridge.onEvent(new OpencodeEvent("permission.asked", new JsonObject())); // empty payload
        bridge.sessionStarted(null);
        bridge.sessionEnded(null);

        assertTrue(queue.pending().isEmpty());
    }

    @Test
    public void subscribeFeedsBridgeEventsFromTheStream() {
        PermissionQueue queue = new PermissionQueue(null);
        FleetPermissionBridge bridge = new FleetPermissionBridge(queue);
        FakeSubscriber subscriber = new FakeSubscriber();

        Runnable unsubscribe = bridge.subscribe(subscriber);

        bridge.sessionStarted("ses_1");
        subscriber.emit(asked("ses_1", "per_1"));
        assertEquals(1, queue.pendingCount());

        unsubscribe.run();
        subscriber.emit(asked("ses_1", "per_2"));
        assertEquals("unsubscribed: later events do not arrive", 1, queue.pendingCount());
    }

    private static List<String> ids(PermissionQueue queue) {
        return queue.pending().stream()
                .map(request -> request.permissionId())
                .toList();
    }
}
