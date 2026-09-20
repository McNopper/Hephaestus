package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.fleet.GlobalEventsAggregator.GlobalStream;
import com.opencode.ide.fleet.GlobalEventsAggregator.GlobalStreamFactory;
import com.opencode.ide.fleet.GlobalEventsAggregator.ObservedEvent;

/**
 * Unit tests for {@link GlobalEventsAggregator}: two-connection delivery with
 * connection tags, de-duplication across connections and on re-delivery,
 * directory-aware identity (v2 serves one {@code /api/event} stream for every
 * project), seen window rotation, recent ordering and bounds, listener failure
 * isolation, unsubscribe/close lifecycle, failed subscriptions keeping others
 * alive, the duplicate counter, and a concurrency smoke (parallel pushes from
 * both connections are each counted once).
 */
public class GlobalEventsAggregatorTest {

    /** Opens fake streams and keeps each connection's sink, so tests push events by hand (no SSE). */
    private static final class FakeFactory implements GlobalStreamFactory {

        final Map<String, Consumer<OpencodeEvent>> sinks = new ConcurrentHashMap<>();
        final Map<String, FakeStream> streams = new ConcurrentHashMap<>();
        /** Connection id whose open() throws (simulating a broken subscribe). */
        volatile String failOpenFor;
        /** Connection id whose stream throws from start(). */
        volatile String failStartFor;

        @Override
        public GlobalStream open(String connectionId, OpencodeClient client, Consumer<OpencodeEvent> sink,
                Consumer<Boolean> connectionListener) {
            if (connectionId.equals(failOpenFor)) {
                throw new IllegalStateException("no stream for " + connectionId);
            }
            FakeStream stream = new FakeStream(connectionId.equals(failStartFor));
            sinks.put(connectionId, sink);
            streams.put(connectionId, stream);
            return stream;
        }
    }

    /** Records start/stop; can fail start to prove failure isolation. */
    private static final class FakeStream implements GlobalStream {

        final int[] startCalls = { 0 };
        final int[] stopCalls = { 0 };
        final boolean failStart;

        FakeStream(boolean failStart) {
            this.failStart = failStart;
        }

        @Override
        public void start() {
            startCalls[0]++;
            if (failStart) {
                throw new IllegalStateException("start failed");
            }
        }

        @Override
        public void stop() {
            stopCalls[0]++;
        }
    }

    private static OpencodeEvent event(String type, String detail) {
        return event(type, detail, null);
    }

    /** An event carrying the v2 frame's {@code location.directory} scope. */
    private static OpencodeEvent event(String type, String detail, String directory) {
        JsonObject properties = JsonParser.parseString("{\"detail\":\"" + detail + "\"}").getAsJsonObject();
        return new OpencodeEvent(type, properties, directory);
    }

    private static void push(FakeFactory factory, String connectionId, String detail) {
        factory.sinks.get(connectionId).accept(event("session.renamed", detail));
    }

    private static List<String> details(List<ObservedEvent> events) {
        return events.stream().map(e -> e.properties().get("detail").getAsString()).toList();
    }

    @Test
    public void deliversEventsOfBothConnectionsTaggedWithConnectionId() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        assertTrue(aggregator.subscribe("connA", new FakeClient()));
        assertTrue(aggregator.subscribe("connB", new FakeClient()));

        push(factory, "connA", "e1");
        push(factory, "connB", "e2");

        assertEquals(2, aggregator.deliveredCount());
        assertEquals(List.of("connA", "connB"),
                aggregator.recent(10).stream().map(ObservedEvent::connectionId).toList());
        assertEquals(List.of("e1", "e2"), details(aggregator.recent(10)));
        assertEquals(Set.of("connA", "connB"), aggregator.connections());
        assertTrue(aggregator.failedConnections().isEmpty());
    }

    @Test
    public void sameContentAcrossConnectionsIsDeliveredOnce() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());
        aggregator.subscribe("connB", new FakeClient());

        push(factory, "connA", "e1");
        push(factory, "connB", "e1"); // the same logical event, relayed by both servers

        assertEquals(1, aggregator.deliveredCount());
        assertEquals(1, aggregator.droppedDuplicates());
        ObservedEvent delivered = aggregator.recent(1).get(0);
        assertEquals("connA", delivered.connectionId()); // first arrival wins the tag
        assertEquals("session.renamed", delivered.type());
    }

    @Test
    public void reDeliveryOnTheSameConnectionIsDropped() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());

        push(factory, "connA", "e1");
        push(factory, "connA", "e1"); // replayed after a reconnect

        assertEquals(1, aggregator.deliveredCount());
        assertEquals(1, aggregator.droppedDuplicates());
        assertEquals(1, aggregator.recent(10).size());
    }

    @Test
    public void differentContentOfTheSameTypeIsNotDropped() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());

        push(factory, "connA", "e1");
        push(factory, "connA", "e2");

        assertEquals(2, aggregator.deliveredCount());
        assertEquals(0, aggregator.droppedDuplicates());
    }

    @Test
    public void theEventDirectoryIsCarriedThroughToTheFanOut() {
        // v2 serves ONE /api/event stream for every project, so the connection
        // id says which SERVER an event came from - the project scope is the
        // frame's location.directory, which consumers filter on
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        List<ObservedEvent> received = new CopyOnWriteArrayList<>();
        aggregator.addListener(received::add);
        aggregator.subscribe("connA", new FakeClient());

        factory.sinks.get("connA").accept(event("session.idle", "e1", "C:/wt/ticket-1"));
        factory.sinks.get("connA").accept(event("session.idle", "e2", "C:/wt/ticket-2"));
        factory.sinks.get("connA").accept(event("session.idle", "e3", null));

        assertEquals(List.of("C:/wt/ticket-1", "C:/wt/ticket-2"),
                received.stream().map(ObservedEvent::directory).filter(d -> d != null).toList());
        assertNull("a location-less frame keeps a null directory",
                aggregator.recent(1).get(0).directory());
        // the scoping a consumer does: one project out of the shared stream
        assertEquals(List.of("e1"), details(received.stream()
                .filter(e -> "C:/wt/ticket-1".equals(e.directory())).toList()));
    }

    @Test
    public void equalPayloadsFromDifferentDirectoriesAreTwoEvents() {
        // the single v2 stream carries every worktree: without the directory
        // in the identity, two projects reporting the same payload would
        // collapse into one delivery
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());

        factory.sinks.get("connA").accept(event("server.connected", "e1", "C:/wt/ticket-1"));
        factory.sinks.get("connA").accept(event("server.connected", "e1", "C:/wt/ticket-2"));
        factory.sinks.get("connA").accept(event("server.connected", "e1", "C:/wt/ticket-2"));

        assertEquals(2, aggregator.deliveredCount());
        assertEquals("the re-delivery of the second directory is the only duplicate",
                1, aggregator.droppedDuplicates());
    }

    @Test
    public void evictedIdIsDeliveredAgainAfterWindowRotation() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(4, 16, factory);
        aggregator.subscribe("connA", new FakeClient());

        for (int i = 1; i <= 4; i++) {
            push(factory, "connA", "e" + i);
        }
        for (int i = 5; i <= 8; i++) { // rotates e1..e4 out of the seen window
            push(factory, "connA", "e" + i);
        }
        push(factory, "connA", "e1"); // evicted: no longer known, delivered again

        assertEquals(9, aggregator.deliveredCount());
        push(factory, "connA", "e8"); // still inside the window: dropped
        assertEquals(9, aggregator.deliveredCount());
        assertEquals(1, aggregator.droppedDuplicates());
    }

    @Test
    public void recentReturnsLastNOldestFirstWithinBounds() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());
        for (int i = 1; i <= 5; i++) {
            push(factory, "connA", "e" + i);
        }

        assertEquals(List.of("e3", "e4", "e5"), details(aggregator.recent(3)));
        assertEquals(List.of(), aggregator.recent(0));
        assertEquals(List.of(), aggregator.recent(-2));
        assertEquals(List.of("e1", "e2", "e3", "e4", "e5"), details(aggregator.recent(99)));
    }

    @Test
    public void recentIsBoundedByItsCapacity() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 3, factory);
        aggregator.subscribe("connA", new FakeClient());
        for (int i = 1; i <= 5; i++) {
            push(factory, "connA", "e" + i);
        }

        assertEquals(List.of("e3", "e4", "e5"), details(aggregator.recent(99)));
    }

    @Test
    public void throwingListenerDoesNotStopDeliveryOrOtherListeners() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        List<ObservedEvent> received = new CopyOnWriteArrayList<>();
        aggregator.addListener(event -> {
            throw new IllegalStateException("bad listener");
        });
        aggregator.addListener(received::add);
        aggregator.subscribe("connA", new FakeClient());

        push(factory, "connA", "e1");
        push(factory, "connA", "e2");

        assertEquals(2, received.size());
        assertEquals(2, aggregator.deliveredCount());
        assertEquals(List.of("e1", "e2"), details(aggregator.recent(10)));
    }

    @Test
    public void unsubscribeStopsDeliveryAndStream() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());
        push(factory, "connA", "e1");

        aggregator.unsubscribe("connA");
        push(factory, "connA", "e2"); // the stopped stream's sink no longer delivers

        assertEquals(1, aggregator.deliveredCount());
        assertEquals(List.of("e1"), details(aggregator.recent(10)));
        assertEquals(1, factory.streams.get("connA").stopCalls[0]);
        assertTrue(aggregator.connections().isEmpty());
    }

    @Test
    public void closeIsIdempotentStopsAllAndRefusesNewSubscriptions() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());
        aggregator.subscribe("connB", new FakeClient());

        aggregator.close();
        aggregator.close();

        assertEquals(1, factory.streams.get("connA").stopCalls[0]);
        assertEquals(1, factory.streams.get("connB").stopCalls[0]);
        assertTrue(aggregator.connections().isEmpty());
        assertFalse(aggregator.subscribe("connC", new FakeClient()));
        push(factory, "connA", "e1");
        push(factory, "connB", "e2");
        assertEquals(0, aggregator.deliveredCount());
    }

    @Test
    public void failedOpenMarksConnectionFailedButOthersKeepFlowing() {
        FakeFactory factory = new FakeFactory();
        factory.failOpenFor = "connB";
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);

        assertFalse(aggregator.subscribe("connB", new FakeClient()));
        assertEquals(Set.of("connB"), aggregator.failedConnections());
        assertTrue(aggregator.connections().isEmpty());

        assertTrue(aggregator.subscribe("connA", new FakeClient()));
        push(factory, "connA", "e1");
        assertEquals(1, aggregator.deliveredCount());
    }

    @Test
    public void failedStreamStartMarksConnectionFailed() {
        FakeFactory factory = new FakeFactory();
        factory.failStartFor = "connB";
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);

        assertFalse(aggregator.subscribe("connB", new FakeClient()));
        assertEquals(Set.of("connB"), aggregator.failedConnections());
        assertTrue(aggregator.subscribe("connA", new FakeClient()));
    }

    @Test
    public void reSubscribeReplacesThePreviousStream() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());
        FakeStream first = factory.streams.get("connA");

        assertTrue(aggregator.subscribe("connA", new FakeClient()));

        assertEquals(1, first.stopCalls[0]);
        push(factory, "connA", "e1"); // routed through the new subscription
        assertEquals(1, aggregator.deliveredCount());
    }

    @Test
    public void successfulRetryClearsTheFailedMarker() {
        FakeFactory factory = new FakeFactory();
        factory.failOpenFor = "connA";
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        assertFalse(aggregator.subscribe("connA", new FakeClient()));
        assertEquals(Set.of("connA"), aggregator.failedConnections());

        factory.failOpenFor = null;
        assertTrue(aggregator.subscribe("connA", new FakeClient()));
        assertTrue(aggregator.failedConnections().isEmpty());
    }

    @Test
    public void duplicateCounterAccumulatesPerExtraDelivery() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        aggregator.subscribe("connA", new FakeClient());

        push(factory, "connA", "e1");
        push(factory, "connA", "e1");
        push(factory, "connA", "e1");

        assertEquals(1, aggregator.deliveredCount());
        assertEquals(2, aggregator.droppedDuplicates());
        assertEquals(1, aggregator.recent(10).size());
    }

    @Test
    public void nullAndBlankArgumentsAreRefused() {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(16, 16, factory);
        FakeClient client = new FakeClient();

        assertFalse(aggregator.subscribe(null, client));
        assertFalse(aggregator.subscribe(" ", client));
        assertFalse(aggregator.subscribe("connA", null));

        aggregator.subscribe("connA", client);
        factory.sinks.get("connA").accept(null); // a malformed delivery is ignored
        assertEquals(0, aggregator.deliveredCount());
        aggregator.unsubscribe(null); // must not throw
    }

    @Test
    public void concurrentDeliveriesFromBothConnectionsAreAllCounted() throws Exception {
        FakeFactory factory = new FakeFactory();
        GlobalEventsAggregator aggregator = new GlobalEventsAggregator(512, 64, factory);
        aggregator.subscribe("connA", new FakeClient());
        aggregator.subscribe("connB", new FakeClient());
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        for (String connectionId : List.of("connA", "connB")) {
            Runnable pusher = () -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        factory.sinks.get(connectionId)
                                .accept(event("session.text.delta", connectionId + "-" + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            };
            new Thread(pusher, "push-" + connectionId).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(2 * perThread, aggregator.deliveredCount());
        assertEquals(0, aggregator.droppedDuplicates());
        assertEquals(64, aggregator.recent(1000).size());
    }
}
