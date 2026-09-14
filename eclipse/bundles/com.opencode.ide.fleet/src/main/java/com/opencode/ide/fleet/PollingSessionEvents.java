package com.opencode.ide.fleet;

import java.time.Duration;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Idle detection over the REST surface: polls
 * {@code GET /session/status} until the session reports {@code idle} - the
 * same loop {@link FleetRunner} uses internally. This is the
 * (stream-less) completion check and also serves {@link SseSessionEvents}
 * as its one-shot fallback check on stream drops ({@code awaitIdle} with a
 * zero timeout performs exactly one status check).
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class PollingSessionEvents {

    private static final long DEFAULT_POLL_MILLIS = FleetTuning.STATUS_POLL_MILLIS;

    private final OpencodeClient client;
    private final Runnable sleeper;

    public PollingSessionEvents(OpencodeClient client) {
        this(client, PollingSessionEvents::sleepPollInterval);
    }

    /**
     * @param sleeper invoked between status polls; inject a no-op to make
     *                {@link #awaitIdle} run instantly in tests
     */
    public PollingSessionEvents(OpencodeClient client, Runnable sleeper) {
        this.client = client;
        this.sleeper = sleeper;
    }

    /**
     * Blocks until the session reports idle.
     *
     * @param sessionId the opencode session to watch
     * @param timeout   how long to wait
     * @return {@code true} when the session went idle within the timeout,
     *         {@code false} on timeout
     * @throws OpencodeException when the underlying transport fails
     */
    public boolean awaitIdle(String sessionId, Duration timeout) throws OpencodeException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!isIdle(sessionId)) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                // the sleeper restored the interrupt flag: honor cancellation
                // instead of spinning without sleep until the deadline
                return false;
            }
            sleeper.run();
        }
        return true;
    }

    /**
     * Idle when the status map reports {@code idle} for the session — or when
     * the session is ABSENT from the map: since opencode 1.18.23 the endpoint
     * lists busy sessions only, so absence IS the idle signal (requiring an
     * explicit idle entry hung every run until its timeout; found live in
     * Milestone V). Both spellings accepted — present-idle and absent.
     */
    private boolean isIdle(String sessionId) throws OpencodeException {
        SessionStatus status = client.getSessionStatus().get(sessionId);
        return status == null || "idle".equals(status.type());
    }

    private static void sleepPollInterval() {
        try {
            Thread.sleep(DEFAULT_POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
