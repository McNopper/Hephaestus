package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.activity.PermissionRequest.Status;
import com.opencode.ide.fleet.PermissionQueue.AnswerResult;
import com.opencode.ide.fleet.PermissionQueue.PermissionResponder;

/**
 * Unit tests for {@link PermissionQueue}: dedup by permission id, oldest-first
 * ordering, answered-by-event transitions, answer semantics against a fake
 * responder (success / server false / transport failure / unknown id /
 * double answer), session removal, listener notification, and a concurrency
 * smoke (parallel offers and racing answers resolve to exactly one server
 * call per request).
 */
public class PermissionQueueTest {

    /** Recording fake of the responder seam. */
    private static final class FakeResponder implements PermissionResponder {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean accept = true;
        /** When set, respond throws (simulating a transport failure). */
        volatile RuntimeException failure;

        @Override
        public boolean respond(String sessionId, String permissionId, String response, boolean remember)
                throws OpencodeException {
            calls.add(sessionId + "|" + permissionId + "|" + response + "|" + remember);
            if (failure != null) {
                throw failure;
            }
            return accept;
        }
    }

    private static PermissionRequest asked(String sessionId, String permissionId, String title) {
        return new PermissionRequest(sessionId, permissionId, "bash", List.of("git push"), title,
                Status.PENDING);
    }

    private static PermissionRequest replied(String sessionId, String permissionId) {
        return new PermissionRequest(sessionId, permissionId, null, List.of(), null, Status.ANSWERED);
    }

    @Test
    public void reconcileAddsMissedAsksAndNeverDropsQueuedOnes() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", "first"));

        // the service list carries an ask the event stream MISSED plus a
        // re-delivery of the one already held
        int gained = queue.reconcile(List.of(
                asked("ses_1", "per_1", "first"),
                asked("ses_2", "per_2", "missed by the stream")));

        assertEquals("only the unknown ask is gained", 1, gained);
        assertEquals("both asks are pending", 2, queue.pendingCount());

        // a PARTIAL list never drops what the queue already holds
        assertEquals(0, queue.reconcile(List.of()));
        assertEquals("the list is a floor, not a replacement", 2, queue.pendingCount());
    }

    @Test
    public void pendingReturnsUnansweredOldestFirstAcrossSessions() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", null));
        queue.offer(asked("ses_2", "per_2", null));
        queue.offer(asked("ses_1", "per_3", null));

        List<PermissionRequest> pending = queue.pending();

        assertEquals(3, pending.size());
        assertEquals(List.of("per_1", "per_2", "per_3"),
                pending.stream().map(PermissionRequest::permissionId).toList());
        assertEquals(3, queue.pendingCount());
    }

    @Test
    public void duplicateOfferUpdatesInPlaceWithoutRefiring() {
        PermissionQueue queue = new PermissionQueue(null);
        AtomicInteger notifications = new AtomicInteger();
        queue.addListener(notifications::incrementAndGet);

        assertTrue(queue.offer(asked("ses_1", "per_1", null)));
        assertFalse("identical re-delivery changes nothing", queue.offer(asked("ses_1", "per_1", null)));

        assertEquals(1, queue.pendingCount());
        assertEquals(1, notifications.get());

        assertTrue("a title refresh is a change", queue.offer(asked("ses_1", "per_1", "git push --force")));
        assertEquals(1, queue.pendingCount());
        assertEquals("git push --force", queue.pending().get(0).title());
        assertEquals(2, notifications.get());
    }

    @Test
    public void repliedEventDropsRequestFromPending() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", null));
        queue.offer(asked("ses_1", "per_2", null));

        assertTrue(queue.offer(replied("ses_1", "per_1")));

        assertEquals(List.of("per_2"), ids(queue.pending()));
    }

    @Test
    public void answeredByEventCanBeAskedAgain() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", null));
        queue.offer(replied("ses_1", "per_1"));

        // server re-asking with the same id (e.g. after a correction) reopens it
        queue.offer(asked("ses_1", "per_1", null));

        assertEquals(1, queue.pendingCount());
    }

    @Test
    public void answerOncePostsWireValuesAndMarksAnswered() {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));
        AtomicInteger notifications = new AtomicInteger();
        queue.addListener(notifications::incrementAndGet);

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ONCE, false);

        assertTrue(result.toString(), result.success());
        assertEquals(List.of("ses_1|per_1|once|false"), responder.calls);
        assertTrue(queue.pending().isEmpty());
        assertEquals("listener fired on the answer's state change", 1, notifications.get());
    }

    @Test
    public void answerAlwaysPassesRememberThrough() {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ALWAYS, true);

        assertTrue(result.success());
        assertEquals(List.of("ses_1|per_1|always|true"), responder.calls);
    }

    @Test
    public void answerRejectUsesWireSpelling() {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_9", null));

        assertTrue(queue.answer("per_9", PermissionQueue.Response.REJECT, false).success());

        assertEquals(List.of("ses_1|per_9|reject|false"), responder.calls);
    }

    @Test
    public void answerUnknownIdFailsWithoutServerCall() {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);

        AnswerResult result = queue.answer("per_nope", PermissionQueue.Response.ONCE, false);

        assertFalse(result.success());
        assertNotNull(result.message());
        assertTrue(responder.calls.isEmpty());
    }

    @Test
    public void transportFailureKeepsRequestPendingAndRetrySucceeds() {
        FakeResponder responder = new FakeResponder();
        responder.failure = new RuntimeException("server down");
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));

        AnswerResult failed = queue.answer("per_1", PermissionQueue.Response.ONCE, false);
        assertFalse(failed.success());
        assertTrue("stays pending for retry", queue.pendingCount() == 1);

        responder.failure = null;
        assertTrue(queue.answer("per_1", PermissionQueue.Response.ONCE, false).success());
        assertTrue(queue.pending().isEmpty());
        assertEquals("both attempts reached the server", 2, responder.calls.size());
    }

    @Test
    public void opencodeExceptionFromResponderSurfacesAsFailedResult() {
        PermissionResponder responder = (sessionId, permissionId, response, remember) -> {
            throw new OpencodeException("HTTP 500");
        };
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ONCE, false);

        assertFalse(result.success());
        assertTrue(result.message().contains("HTTP 500"));
        assertEquals(1, queue.pendingCount());
    }

    @Test
    public void serverRejectionKeepsRequestPending() {
        FakeResponder responder = new FakeResponder();
        responder.accept = false;
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ONCE, false);

        assertFalse(result.success());
        assertEquals(1, queue.pendingCount());
    }

    @Test
    public void doubleAnswerResolvesToOnceOnly() {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);
        queue.offer(asked("ses_1", "per_1", null));
        assertTrue(queue.answer("per_1", PermissionQueue.Response.ONCE, false).success());

        AnswerResult second = queue.answer("per_1", PermissionQueue.Response.ALWAYS, true);

        assertFalse(second.success());
        assertEquals("already answered", 1, responder.calls.size());
    }

    @Test
    public void answeredByEventThenAnswerFailsAsAlreadyAnswered() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", null));
        queue.offer(replied("ses_1", "per_1")); // answered elsewhere (e.g. TUI)

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ONCE, false);

        assertFalse(result.success());
    }

    @Test
    public void noResponderWiredFailsCleanly() {
        PermissionQueue queue = new PermissionQueue(null);
        queue.offer(asked("ses_1", "per_1", null));

        AnswerResult result = queue.answer("per_1", PermissionQueue.Response.ONCE, false);

        assertFalse(result.success());
        assertEquals(1, queue.pendingCount());
    }

    @Test
    public void removeDropsOnlyTheSessionEntriesAndNotifiesOnChange() {
        PermissionQueue queue = new PermissionQueue(null);
        AtomicInteger notifications = new AtomicInteger();
        queue.addListener(notifications::incrementAndGet);
        queue.offer(asked("ses_1", "per_1", null));
        queue.offer(asked("ses_1", "per_2", null));
        queue.offer(asked("ses_2", "per_3", null));
        assertEquals("each offer is a change", 3, notifications.get());

        assertTrue(queue.remove("ses_1"));

        assertEquals(List.of("per_3"), ids(queue.pending()));
        assertEquals(4, notifications.get());
        assertFalse(queue.remove("ses_1"));
        assertEquals("no notification without a change", 4, notifications.get());
        assertFalse(queue.remove(null));
    }

    @Test
    public void nullAndIdlessOffersAreIgnored() {
        PermissionQueue queue = new PermissionQueue(null);
        assertFalse(queue.offer(null));
        assertFalse(queue.offer(new PermissionRequest(null, "per_1", "bash", List.of(), null, Status.PENDING)));
        assertFalse(queue.offer(new PermissionRequest("ses_1", null, "bash", List.of(), null, Status.PENDING)));
        assertTrue(queue.pending().isEmpty());
        assertEquals(0, queue.pendingCount());
    }

    @Test
    public void invalidAnswerArgumentsFailFast() {
        PermissionQueue queue = new PermissionQueue(null);
        assertFalse(queue.answer(null, PermissionQueue.Response.ONCE, false).success());
        assertFalse(queue.answer("per_1", null, false).success());
    }

    @Test
    public void concurrentOffersDedupAndRacingAnswersResolveOncePerRequest() throws Exception {
        FakeResponder responder = new FakeResponder();
        PermissionQueue queue = new PermissionQueue(responder);
        int ids = 60;
        List<String> permissionIds = new ArrayList<>();
        for (int i = 0; i < ids; i++) {
            permissionIds.add("per_" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // Phase 1: 4 threads offer every request twice (duplicates must collapse)
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> offers = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int offset = t % 2; // two threads race per half
                offers.add(pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = offset; i < ids; i += 2) {
                        queue.offer(asked("ses_" + (i % 3), "per_" + i, null));
                        queue.offer(asked("ses_" + (i % 3), "per_" + i, null));
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> offer : offers) {
                offer.get(10, TimeUnit.SECONDS);
            }
            assertEquals("duplicates collapsed", ids, queue.pendingCount());

            // Phase 2: 4 threads race to answer (each id answered by 2 racers)
            List<java.util.concurrent.Future<Integer>> answers = new ArrayList<>();
            CountDownLatch race = new CountDownLatch(1);
            for (int t = 0; t < 4; t++) {
                final int offset = t % 2;
                answers.add(pool.submit(() -> {
                    try {
                        race.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    int successes = 0;
                    for (int i = offset; i < ids; i += 2) {
                        if (queue.answer("per_" + i, PermissionQueue.Response.ONCE, false).success()) {
                            successes++;
                        }
                    }
                    return successes;
                }));
            }
            race.countDown();
            int totalSuccesses = 0;
            for (java.util.concurrent.Future<Integer> answer : answers) {
                totalSuccesses += answer.get(10, TimeUnit.SECONDS);
            }

            assertEquals("exactly one successful answer per request", ids, totalSuccesses);
            assertEquals("the server saw every request exactly once", ids, responder.calls.size());
            assertEquals("nothing left pending", 0, queue.pendingCount());
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> ids(List<PermissionRequest> requests) {
        return requests.stream().map(PermissionRequest::permissionId).toList();
    }
}
