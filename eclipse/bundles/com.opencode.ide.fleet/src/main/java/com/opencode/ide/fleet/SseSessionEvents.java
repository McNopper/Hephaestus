package com.opencode.ide.fleet;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Idle detection driven by the opencode {@code /api/event} SSE stream instead
 * of polling. This class does NOT own a stream: an owner that already runs one
 * {@code OpencodeEventStream} (e.g. the Eclipse core layer) exposes a
 * {@link Subscriber} and registers this instance as one of its listeners.
 * Events arrive on the owner's SSE reader thread; {@link #awaitIdle} only
 * parks on a monitor (no thread per wait, no busy-wait) and may be called
 * concurrently for different sessions.
 *
 * <p>Stream drops must be wired in too: hand {@link #connectionListener()} to
 * the owning stream as its connection listener. On a drop ({@code false}) each
 * waiter performs exactly ONE fallback status poll
 * ({@link PollingSessionEvents}-style) when a client was provided - rescuing
 * completions whose {@code session.idle} event was lost in the gap - and
 * otherwise (no client, or still busy) keeps waiting until the timeout, since
 * the stream reconnects and resumes events on its own.</p>
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class SseSessionEvents {

    /**
     * The registration point of an owner-run event stream: given a listener,
     * feed it every {@link OpencodeEvent} and return a handle that removes it
     * again. Owners typically keep a copy-on-write list of listeners and fan
     * out to them from their single stream sink, so the fleet becomes just
     * one subscriber among several.
     */
    /** Registers a listener with the owner-run SSE stream; the returned handle unsubscribes. */
    @FunctionalInterface
    public interface Subscriber {

        /**
         * @param listener invoked for every event, on the stream's reader
         *                 thread
         * @return unsubscribe handle; running it stops event delivery to the
         *         listener
         */
        Runnable subscribe(Consumer<OpencodeEvent> listener);
    }

    private enum Signal { IDLE, DROPPED, TIMEOUT }

    /**
     * The event types that end a wait. {@code session.idle} and
     * {@code session.deleted} are the historical two; v2 additionally ends a
     * turn with {@code session.execution.succeeded} /
     * {@code .failed} / {@code .interrupted} (all carrying {@code sessionID}),
     * so a finished run is still detected when the {@code session.idle} that
     * would have followed is missed - dropped in a relay gap, or swallowed by
     * a reconnect. Treating them as terminal is deliberately optimistic: the
     * fleet's next step re-reads the session state anyway, and a wait that
     * ends one event early costs far less than one that never ends.
     */
    private static final Set<String> TERMINAL_TYPES = Set.of(
            "session.idle",
            "session.deleted",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted");

    private final Subscriber subscriber;
    private final PollingSessionEvents fallback;
    private final Clock clock;
    private final Object lock = new Object();
    private final Set<Wait> active = ConcurrentHashMap.newKeySet();

    /**
     * @param subscriber the owner-run SSE stream's registration point (no
     *                   fallback status checks on stream drops)
     */
    public SseSessionEvents(Subscriber subscriber) {
        this(subscriber, null);
    }

    /**
     * @param subscriber     the owner-run SSE stream's registration point
     * @param fallbackClient used for exactly ONE status poll when the stream
     *                       drops while waiting (may be {@code null} = keep
     *                       waiting until the timeout)
     */
    public SseSessionEvents(Subscriber subscriber, OpencodeClient fallbackClient) {
        this(subscriber, fallbackClient, Clock.systemDefaultZone());
    }

    /**
     * @param subscriber     the owner-run SSE stream's registration point
     * @param fallbackClient used for exactly ONE status poll when the stream
     *                       drops while waiting (may be {@code null})
     * @param clock          time source for deadline evaluation (injectable
     *                       for tests)
     */
    public SseSessionEvents(Subscriber subscriber, OpencodeClient fallbackClient, Clock clock) {
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        this.fallback = fallbackClient == null ? null : new PollingSessionEvents(fallbackClient);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @return a connection-state sink to hand to the owning stream as its
     *         connection listener; {@code false} marks a drop, {@code true} a
     *         (re)established connection (no action - events simply resume)
     */
    public Consumer<Boolean> connectionListener() {
        return this::onConnectionChange;
    }

    /**
     * Blocks until the session is (again) idle, as signalled by one of the
     * stream's terminal events ({@code session.idle},
     * {@code session.deleted}, or a v2 {@code session.execution.*} outcome -
     * see {@link #TERMINAL_TYPES}).
     *
     * @param sessionId the opencode session to watch
     * @param timeout   how long to wait
     * @return {@code true} when the session went idle (was deleted, or ended
     *         its run) within the timeout, {@code false} on timeout
     * @throws OpencodeException when interrupted or the transport fails
     */
    public boolean awaitIdle(String sessionId, Duration timeout) throws OpencodeException {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(timeout, "timeout");
        Wait wait = new Wait(sessionId);
        active.add(wait);
        Runnable unsubscribe = null;
        try {
            unsubscribe = subscriber.subscribe(wait::onEvent);
            long deadline = clock.millis() + timeout.toMillis();
            while (true) {
                Signal signal = wait.waitFor(deadline);
                if (signal == Signal.IDLE) {
                    return true;
                }
                if (signal == Signal.TIMEOUT) {
                    return false;
                }
                // DROPPED: one status poll rescues completions missed in the gap
                if (fallback != null && fallback.awaitIdle(sessionId, Duration.ZERO)) {
                    return true;
                }
                // still busy (or no client): keep waiting until the deadline
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // distinguish interruption from a real timeout in the failure detail
            throw new OpencodeException("interrupted while awaiting session.idle for " + sessionId);
        } finally {
            active.remove(wait);
            if (unsubscribe != null) {
                unsubscribe.run();
            }
        }
    }

    private void onConnectionChange(boolean connected) {
        if (connected) {
            return;
        }
        for (Wait wait : active) {
            wait.onDrop();
        }
    }

    /** One wait; its flags are guarded by the shared monitor. */
    private final class Wait {

        private final String sessionId;
        private boolean idle;
        private boolean dropped;

        Wait(String sessionId) {
            this.sessionId = sessionId;
        }

        void onEvent(OpencodeEvent event) {
            if (idle || !matches(event)) {
                return;
            }
            synchronized (lock) {
                idle = true;
                lock.notifyAll();
            }
        }

        void onDrop() {
            synchronized (lock) {
                dropped = true;
                lock.notifyAll();
            }
        }

        private boolean matches(OpencodeEvent event) {
            String type = event.type();
            // null-safe on purpose: a malformed frame must not kill the wait
            if (type == null || !TERMINAL_TYPES.contains(type)) {
                return false;
            }
            return sessionId.equals(event.string("sessionID"));
        }

        private Signal waitFor(long deadlineMillis) throws InterruptedException {
            synchronized (lock) {
                while (!idle && !dropped) {
                    long remaining = deadlineMillis - clock.millis();
                    if (remaining <= 0) {
                        return Signal.TIMEOUT;
                    }
                    lock.wait(remaining);
                }
                if (idle) {
                    return Signal.IDLE;
                }
                dropped = false;
                return Signal.DROPPED;
            }
        }
    }
}
