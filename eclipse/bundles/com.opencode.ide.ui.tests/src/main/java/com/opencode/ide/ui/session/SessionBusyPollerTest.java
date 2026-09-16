package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Unit tests for {@link SessionBusyPoller}: the SWT-free poller behind the
 * Server view's live busy icons. No SWT, no Display — the client is a fake,
 * the pure mapping helpers get plain maps, and every started poller is
 * disposed and joined with a timeout so no thread leaks between tests.
 */
public class SessionBusyPollerTest {

    /** Fast enough for tests, slow enough to never busy-spin. */
    private static final long TEST_INTERVAL_MILLIS = 15L;

    /** Generous CI-safe bound for "this should have happened by now". */
    private static final long AWAIT_MILLIS = 5_000L;

    // ---------- pure mapping: toBusySet ----------

    @Test
    public void toBusySetKeepsBusyAndRetryAndDropsEverythingElse() {
        Map<String, SessionStatus> statuses = new LinkedHashMap<>();
        statuses.put("ses_busy", new SessionStatus("busy"));
        statuses.put("ses_retry", new SessionStatus("retry"));
        statuses.put("ses_idle", new SessionStatus("idle"));
        statuses.put("ses_null", null);
        statuses.put("ses_weird", new SessionStatus("BACKING_OFF"));

        assertEquals(Set.of("ses_busy", "ses_retry"), SessionBusyPoller.toBusySet(statuses));
    }

    @Test
    public void toBusySetToleratesNullAndEmptyMaps() {
        assertEquals(Set.of(), SessionBusyPoller.toBusySet(null));
        assertEquals(Set.of(), SessionBusyPoller.toBusySet(Map.of()));
    }

    // ---------- pure mapping: mergeInto ----------

    @Test
    public void mergeIntoAddsBusyEntriesAndPreservesRetryMarkers() {
        Map<String, SessionStatus> statuses = new LinkedHashMap<>();
        statuses.put("ses_retry", new SessionStatus("retry"));

        assertTrue(SessionBusyPoller.mergeInto(statuses, Set.of("ses_retry", "ses_new")));

        // the polled set has no type detail, so the SSE-written retry marker survives
        assertEquals("retry", statuses.get("ses_retry").type());
        assertEquals("busy", statuses.get("ses_new").type());
    }

    @Test
    public void mergeIntoClearsStaleBusyEntriesAndKeepsExplicitIdle() {
        Map<String, SessionStatus> statuses = new LinkedHashMap<>();
        statuses.put("ses_a", new SessionStatus("busy"));
        statuses.put("ses_b", new SessionStatus("idle"));
        statuses.put("ses_c", new SessionStatus("retry"));

        assertTrue(SessionBusyPoller.mergeInto(statuses, Set.of()));

        assertFalse(statuses.containsKey("ses_a"));   // absent = idle since opencode 1.18.23
        assertFalse(statuses.containsKey("ses_c"));
        assertEquals("idle", statuses.get("ses_b").type());
    }

    @Test
    public void mergeIntoWithoutChangeReportsFalse() {
        Map<String, SessionStatus> statuses = new LinkedHashMap<>();
        statuses.put("ses_a", new SessionStatus("busy"));

        assertFalse(SessionBusyPoller.mergeInto(statuses, Set.of("ses_a")));

        assertEquals(Map.of("ses_a", new SessionStatus("busy")), statuses);
    }

    @Test
    public void mergeIntoToleratesNullArguments() {
        assertFalse(SessionBusyPoller.mergeInto(null, Set.of("ses_a")));

        Map<String, SessionStatus> statuses = new LinkedHashMap<>();
        statuses.put("ses_a", new SessionStatus("busy"));
        assertTrue(SessionBusyPoller.mergeInto(statuses, null));
        assertTrue(statuses.isEmpty());
    }

    // ---------- pure mapping: iconKey ----------

    @Test
    public void iconKeyMapsBusyToTheBusyIconAndIdleToTheIdleIcon() {
        assertEquals("icons/session-busy.png", SessionBusyPoller.iconKey(true));
        assertEquals("icons/session.png", SessionBusyPoller.iconKey(false));
        assertNotEquals(SessionBusyPoller.iconKey(true), SessionBusyPoller.iconKey(false));
    }

    // ---------- polling behaviour (fake client, short interval) ----------

    @Test
    public void pollDeliversTheClientBusySet() throws Exception {
        FakeClient client = new FakeClient();
        client.statuses = Map.of("ses_busy", new SessionStatus("busy"),
                "ses_retry", new SessionStatus("retry"));
        RecordingListener listener = new RecordingListener();
        SessionBusyPoller poller = new SessionBusyPoller(client, TEST_INTERVAL_MILLIS, null);
        poller.addListener(listener);

        try {
            poller.start();
            listener.awaitCount(1);

            assertEquals(Set.of("ses_busy", "ses_retry"), listener.received.get(0));
        } finally {
            disposeQuietly(poller);
        }
    }

    @Test
    public void busySetShrinksWhenASessionGoesIdle() throws Exception {
        FakeClient client = new FakeClient();
        client.statuses = Map.of("ses_a", new SessionStatus("busy"), "ses_b", new SessionStatus("busy"));
        RecordingListener listener = new RecordingListener();
        SessionBusyPoller poller = new SessionBusyPoller(client, TEST_INTERVAL_MILLIS, null);
        poller.addListener(listener);

        try {
            poller.start();
            listener.awaitCount(1);
            // opencode 1.18.23+: ses_a finishing = it vanishes from the status map
            client.statuses = Map.of("ses_b", new SessionStatus("busy"));

            listener.awaitSet(Set.of("ses_b"));
        } finally {
            disposeQuietly(poller);
        }
    }

    @Test
    public void clientFailureDoesNotKillThePollerAndLogsOnlyTheTransition() throws Exception {
        FakeClient client = new FakeClient();
        client.failFirstPolls = 2;
        client.statuses = Map.of("ses_a", new SessionStatus("busy"));
        List<Exception> logged = new CopyOnWriteArrayList<>();
        RecordingListener listener = new RecordingListener();
        SessionBusyPoller poller = new SessionBusyPoller(client, TEST_INTERVAL_MILLIS, logged::add);
        poller.addListener(listener);

        try {
            poller.start();
            listener.awaitCount(1);   // delivered once a poll finally succeeds

            assertEquals(1, logged.size());   // two failures, one transition: no log flooding
            assertEquals("poll boom", logged.get(0).getMessage());
        } finally {
            disposeQuietly(poller);
        }
    }

    @Test
    public void disposeStopsPollingAndTerminatesTheThread() throws Exception {
        FakeClient client = new FakeClient();
        client.statuses = Map.of("ses_a", new SessionStatus("busy"));
        RecordingListener listener = new RecordingListener();
        SessionBusyPoller poller = new SessionBusyPoller(client, TEST_INTERVAL_MILLIS, null);
        poller.addListener(listener);
        poller.start();
        listener.awaitCount(1);

        poller.dispose();
        poller.dispose();   // idempotent

        assertTrue(poller.isDisposed());
        assertTrue("poller thread did not terminate", poller.awaitTermination(AWAIT_MILLIS));
        int callbacks = listener.received.size();   // stable now: the worker is dead
        Thread.sleep(5 * TEST_INTERVAL_MILLIS);   // it would have polled several more times
        assertEquals(callbacks, listener.received.size());
    }

    @Test
    public void disposeBeforeStartPreventsAnyPolling() throws Exception {
        FakeClient client = new FakeClient();
        client.statuses = Map.of("ses_a", new SessionStatus("busy"));
        RecordingListener listener = new RecordingListener();
        SessionBusyPoller poller = new SessionBusyPoller(client, TEST_INTERVAL_MILLIS, null);
        poller.addListener(listener);

        poller.dispose();
        poller.start();
        assertTrue(poller.awaitTermination(AWAIT_MILLIS));

        Thread.sleep(5 * TEST_INTERVAL_MILLIS);
        assertTrue(listener.received.isEmpty());
        assertEquals(0, client.polls.get());
    }

    @Test
    public void zeroIntervalIsRejected() {
        FakeClient client = new FakeClient();

        assertThrows(IllegalArgumentException.class, () -> new SessionBusyPoller(client, 0, null));
    }

    // ---------- helpers ----------

    /** Records every delivered busy set; the await* helpers bound every wait. */
    private static final class RecordingListener implements SessionBusyPoller.Listener {
        final List<Set<String>> received = new CopyOnWriteArrayList<>();

        @Override
        public void busySessions(Set<String> busySessionIds) {
            received.add(busySessionIds);
        }

        void awaitCount(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
            while (received.size() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertTrue("expected at least " + count + " callbacks, got " + received.size(),
                    received.size() >= count);
        }

        void awaitSet(Set<String> expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                for (Set<String> set : received) {
                    if (expected.equals(set)) {
                        return;
                    }
                }
                Thread.sleep(5);
            }
            fail("never received busy set " + expected + "; got " + received);
        }
    }

    private static void disposeQuietly(SessionBusyPoller poller) {
        poller.dispose();
        assertTrue("poller thread did not terminate", poller.awaitTermination(AWAIT_MILLIS));
    }

    /** Fake {@link OpencodeClient}: only {@code getSessionStatus} works; it can fail the first N polls. */
    private static final class FakeClient implements OpencodeClient {
        volatile Map<String, SessionStatus> statuses = Map.of();
        int failFirstPolls;
        final AtomicInteger polls = new AtomicInteger();

        @Override
        public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
            if (polls.incrementAndGet() <= failFirstPolls) {
                throw new OpencodeException("poll boom");
            }
            return statuses;
        }

        @Override
        public HealthStatus getHealth() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Agent> getAgents() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProviderList getProviders() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigInfo getConfig() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Session> getSessions() throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session createSession(String title, Path directory) throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerMcp(String name, com.opencode.ide.client.McpServerConfig config)
                throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatEntry sendMessage(com.opencode.ide.client.ChatRequest request) throws OpencodeException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra)
                throws OpencodeException {
            throw new UnsupportedOperationException();
        }
    }
}
