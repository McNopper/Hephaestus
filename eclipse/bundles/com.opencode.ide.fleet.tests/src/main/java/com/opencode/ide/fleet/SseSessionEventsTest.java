package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Unit tests for {@link SseSessionEvents} against a fake
 * {@link SseSessionEvents.Subscriber} (no HTTP, no real stream): matching
 * {@code session.idle}/{@code session.deleted} and the v2
 * {@code session.execution.*} outcomes complete the wait, foreign
 * sessions are ignored, timeouts return false, every wait unsubscribes
 * (completion and timeout), stream drops trigger the one-shot fallback poll,
 * and concurrent waits for different sessions coexist.
 */
public class SseSessionEventsTest {

    private static OpencodeEvent event(String type, String sessionId) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sessionID", sessionId);
        return new OpencodeEvent(type, properties);
    }

    /** Fake registration point: records subscriptions, can deliver events. */
    private static final class FakeSubscriber implements SseSessionEvents.Subscriber {
        final List<Consumer<OpencodeEvent>> listeners = new CopyOnWriteArrayList<>();
        int subscriptions;
        int unsubscriptions;
        /** Optional event delivered to a listener right after it subscribes. */
        OpencodeEvent autoEvent;
        /** Optional hook, invoked inside subscribe() before autoEvent. */
        Runnable onSubscribed;

        @Override
        public Runnable subscribe(Consumer<OpencodeEvent> listener) {
            listeners.add(listener);
            subscriptions++;
            if (onSubscribed != null) {
                onSubscribed.run();
            }
            if (autoEvent != null) {
                listener.accept(autoEvent);
            }
            return () -> {
                listeners.remove(listener);
                unsubscriptions++;
            };
        }

        void emit(OpencodeEvent event) {
            for (Consumer<OpencodeEvent> listener : listeners) {
                listener.accept(event);
            }
        }
    }

    @Test
    public void completesOnAMatchingIdleEventAndUnsubscribes() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        subscriber.autoEvent = event("session.idle", "ses_1");
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));

        assertEquals(1, subscriber.subscriptions);
        assertEquals("unsubscribed after completion", 1, subscriber.unsubscriptions);
        assertTrue(subscriber.listeners.isEmpty());
    }

    @Test
    public void acceptsSessionDeletedForTheAwaitedSession() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        subscriber.autoEvent = event("session.deleted", "ses_1");
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));
    }

    @Test
    public void acceptsTheV2ExecutionOutcomesAsTerminal() throws Exception {
        // v2 ends a turn with session.execution.succeeded/failed/interrupted;
        // the session.idle that used to follow may never be seen (relay gap,
        // reconnect), so each outcome must end the wait on its own
        for (String type : List.of("session.execution.succeeded", "session.execution.failed",
                "session.execution.interrupted")) {
            FakeSubscriber subscriber = new FakeSubscriber();
            subscriber.autoEvent = event(type, "ses_1");
            SseSessionEvents events = new SseSessionEvents(subscriber);

            assertTrue(type + " must complete the wait",
                    events.awaitIdle("ses_1", Duration.ofSeconds(5)));
        }
    }

    @Test
    public void executionOutcomeOfAnotherSessionIsIgnored() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        subscriber.autoEvent = event("session.execution.succeeded", "ses_other");
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(150)));
    }

    @Test
    public void nonTerminalV2EventsDoNotCompleteTheWait() throws Exception {
        // the stream carries plenty of same-session traffic before the end:
        // deltas, tool calls and status updates must not be read as "done"
        FakeSubscriber subscriber = new FakeSubscriber();
        subscriber.autoEvent = event("session.text.delta", "ses_1");
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(150)));
    }

    @Test
    public void parksUntilTheMatchingIdleEventArrives() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        CountDownLatch subscribed = new CountDownLatch(1);
        subscriber.onSubscribed = subscribed::countDown;
        SseSessionEvents events = new SseSessionEvents(subscriber);

        Thread emitter = new Thread(() -> {
            try {
                subscribed.await(5, TimeUnit.SECONDS);
                Thread.sleep(50); // let awaitIdle park before the event lands
                subscriber.emit(event("session.idle", "ses_1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        emitter.start();

        assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));
        emitter.join(5000);
    }

    @Test
    public void ignoresIdleEventsForOtherSessionsAndTimesOut() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        subscriber.autoEvent = event("session.idle", "ses_other");
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(150)));

        assertEquals("unsubscribed after timeout", 1, subscriber.unsubscriptions);
    }

    @Test
    public void timesOutToFalseWhenNoEventArrives() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        SseSessionEvents events = new SseSessionEvents(subscriber);

        assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(100)));

        assertEquals(1, subscriber.subscriptions);
        assertEquals("unsubscribed after timeout", 1, subscriber.unsubscriptions);
        assertTrue(subscriber.listeners.isEmpty());
    }

    @Test
    public void streamDropFallsBackToOneStatusPoll() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        CountDownLatch subscribed = new CountDownLatch(1);
        subscriber.onSubscribed = subscribed::countDown;
        FakeClient client = new FakeClient();
        client.createSession(null, null);
        client.sessionType = "idle";
        SseSessionEvents events = new SseSessionEvents(subscriber, client);

        Thread dropper = new Thread(() -> {
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                events.connectionListener().accept(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        dropper.start();

        assertTrue("idle missed in the stream gap is rescued by one poll",
                events.awaitIdle("ses_1", Duration.ofSeconds(5)));
        dropper.join(5000);
    }

    @Test
    public void streamDropWithBusyClientKeepsWaitingForTheIdleEvent() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        CountDownLatch subscribed = new CountDownLatch(1);
        subscriber.onSubscribed = subscribed::countDown;
        FakeClient client = new FakeClient();
        client.createSession(null, null);
        SseSessionEvents events = new SseSessionEvents(subscriber, client);

        Thread actor = new Thread(() -> {
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                events.connectionListener().accept(false);
                Thread.sleep(50);
                subscriber.emit(event("session.idle", "ses_1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        actor.start();

        assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));
        actor.join(5000);
    }

    @Test
    public void streamDropWithoutClientKeepsWaitingUntilTimeout() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        CountDownLatch subscribed = new CountDownLatch(1);
        subscriber.onSubscribed = subscribed::countDown;
        SseSessionEvents events = new SseSessionEvents(subscriber);

        Thread dropper = new Thread(() -> {
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                events.connectionListener().accept(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        dropper.start();

        assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(150)));
        dropper.join(5000);
    }

    @Test
    public void concurrentWaitersForDifferentSessionsEachComplete() throws Exception {
        FakeSubscriber subscriber = new FakeSubscriber();
        CountDownLatch bothSubscribed = new CountDownLatch(2);
        subscriber.onSubscribed = bothSubscribed::countDown;
        SseSessionEvents events = new SseSessionEvents(subscriber);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> events.awaitIdle("ses_1", Duration.ofSeconds(5)));
            Future<Boolean> second = pool.submit(() -> events.awaitIdle("ses_2", Duration.ofSeconds(5)));
            assertTrue(bothSubscribed.await(5, TimeUnit.SECONDS));

            subscriber.emit(event("session.idle", "ses_1"));
            subscriber.emit(event("session.idle", "ses_2"));

            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
