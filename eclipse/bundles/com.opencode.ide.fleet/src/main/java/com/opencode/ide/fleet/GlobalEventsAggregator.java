package com.opencode.ide.fleet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * One merged, de-duplicated feed over the event streams of several opencode
 * connections (ROADMAP H5 item 9, "Fleet global-event consumption").
 * Each {@link #subscribe}d connection runs one {@code GET /api/event}
 * stream; every event is tagged with its connection id and pushed through a
 * single {@link #addListener listener} fan-out plus the {@link #recent(int)}
 * ring buffer — the two things a Fleet view needs (no view wiring yet).
 *
 * <p><b>Scope is the directory, not the connection:</b> v2 serves ONE event
 * stream at {@code /api/event} for every directory — the v1 split into a
 * per-project {@code /event} and an all-projects {@code /global/event} is
 * gone, and so is the assumption that a stream is already project-scoped.
 * A connection id therefore identifies the SERVER an event arrived from, not
 * the project it belongs to; the project/worktree is
 * {@link ObservedEvent#directory()}, taken from the frame's
 * {@code location.directory}. Consumers that want one project filter on it.</p>
 *
 * <p><b>De-duplication:</b> servers relay overlapping events and streams
 * re-deliver after reconnects, so every event id passes a bounded seen window
 * ({@link #DEFAULT_SEEN_CAPACITY} newest ids, FIFO eviction — deterministic
 * and testable); a seen id is dropped and counted in
 * {@link #droppedDuplicates()}. The client unwraps the v2 frame
 * ({@code {id, created, type, location, data}}) down to {@link OpencodeEvent}
 * (type, properties and directory), so the wire event id never reaches this
 * bundle — identity is the deterministic content id of
 * {@code (directory, type, properties)} instead, which identical relays of one
 * logical event share. The directory belongs in the key because a single
 * stream now carries every directory: two worktrees emitting the same payload
 * (an empty {@code server.connected}, say) are two events, not one. Relays
 * that reorder JSON keys would defeat it; the bounded window limits the
 * damage.</p>
 *
 * <p><b>Robustness:</b> events arrive on the streams' reader threads; no
 * method ever throws to a caller. A subscription whose stream cannot be
 * opened or started is logged, its connection id marked in
 * {@link #failedConnections()} — the other connections keep flowing. A
 * throwing listener is logged and skipped, never killing delivery.</p>
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class GlobalEventsAggregator {

    /** Seen-id window size: the newest 512 event ids count as "seen". */
    public static final int DEFAULT_SEEN_CAPACITY = 512;

    /** Ring buffer size behind {@link #recent(int)}. */
    public static final int DEFAULT_RECENT_CAPACITY = 256;

    /**
     * One opened {@code /api/event} stream (the lifecycle seam — the real
     * {@link OpencodeEventStream} is final, so like the core bundle's
     * {@code ConnectionsManager.EventStream} it is adapted behind this small
     * interface, and tests open controllable fakes instead).
     */
    public interface GlobalStream {

        /** Starts delivering to the sink the factory received. */
        void start();

        /** Stops delivery and releases the stream's connection and thread. */
        void stop();
    }

    /**
     * Opens one event stream per subscription; the sink receives that
     * connection's events on the stream's reader thread, the connection
     * listener its liveness ({@code true} live, {@code false} dropped — the
     * aggregator passes {@code null}: nobody consumes liveness yet, streams
     * reconnect themselves).
     */
    @FunctionalInterface
    public interface GlobalStreamFactory {

        /**
         * @param connectionId      which subscription this stream belongs to
         * @param client            whose {@code /api/event} to read (every
         *                          directory of that server, v2 has one stream)
         * @param sink              receives every event (never throws into it)
         * @param connectionListener optional liveness consumer (may be {@code null})
         * @return the opened, not-yet-started stream ({@code null} = failure)
         */
        GlobalStream open(String connectionId, OpencodeClient client, Consumer<OpencodeEvent> sink,
                Consumer<Boolean> connectionListener);
    }

    /**
     * One delivered event, tagged with its connection. {@link #id()} is the
     * aggregator's content id (see class javadoc) — the key de-duplication
     * ran on; {@link #properties()} is the event's properties object as
     * parsed by the client; {@link #directory()} is the project/worktree the
     * event belongs to ({@code null} when the server sent no location), which
     * is what scopes an event now that one stream carries every directory.
     */
    public record ObservedEvent(String connectionId, String id, String type, JsonObject properties,
            String directory) {

        /** An event with no location (test fixtures and location-less frames). */
        public ObservedEvent(String connectionId, String id, String type, JsonObject properties) {
            this(connectionId, id, type, properties, null);
        }
    }

    private static final Logger LOG = Logger.getLogger(GlobalEventsAggregator.class.getName());
    private static final Gson GSON = new Gson();

    /** Default factory: the client's own {@code /api/event} SSE stream. */
    private static final GlobalStreamFactory DEFAULT_STREAMS = (connectionId, client, sink, listener) -> {
        OpencodeEventStream real = client.getGlobalEvents(sink, listener);
        return new GlobalStream() {
            @Override
            public void start() {
                real.start();
            }

            @Override
            public void stop() {
                real.stop();
            }
        };
    };

    private final GlobalStreamFactory streamFactory;
    private final int seenCapacity;
    private final int recentCapacity;

    private final Map<String, GlobalStream> streams = new ConcurrentHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final List<Consumer<ObservedEvent>> listeners = new CopyOnWriteArrayList<>();
    /** Guards {@link #seen} and {@link #recent} — one lock keeps admission and history consistent. */
    private final Object lock = new Object();
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > seenCapacity;
        }
    };
    private final Deque<ObservedEvent> recent = new ArrayDeque<>();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    /** Production aggregator: default capacities and the real SSE stream factory. */
    public GlobalEventsAggregator() {
        this(DEFAULT_SEEN_CAPACITY, DEFAULT_RECENT_CAPACITY, DEFAULT_STREAMS);
    }

    /**
     * Full-injection constructor (test seam): small capacities make the seen
     * window's eviction and the ring buffer's bound observable, a fake
     * factory keeps tests off the network.
     *
     * @param seenCapacity   how many newest event ids count as seen (&ge; 1)
     * @param recentCapacity ring buffer size behind {@link #recent(int)} (&ge; 1)
     * @param streamFactory  opens one stream per subscription
     */
    public GlobalEventsAggregator(int seenCapacity, int recentCapacity, GlobalStreamFactory streamFactory) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.seenCapacity = Math.max(1, seenCapacity);
        this.recentCapacity = Math.max(1, recentCapacity);
    }

    /**
     * Subscribes to the connection's event stream (a re-subscribe stops and
     * replaces its previous stream). The stream is server-wide, not
     * project-scoped — v2 has a single {@code /api/event} — so scoping stays
     * with the consumer via {@link ObservedEvent#directory()}. Never throws:
     * a refused or failing subscription is logged, the connection id marked in
     * {@link #failedConnections()}, and {@code false} returned — the other
     * connections keep flowing.
     *
     * @return {@code true} when the stream is subscribed and started
     */
    public boolean subscribe(String connectionId, OpencodeClient client) {
        if (connectionId == null || connectionId.isBlank() || client == null || closed) {
            return false;
        }
        GlobalStream stream;
        try {
            stream = streamFactory.open(connectionId, client, event -> onEvent(connectionId, event), null);
        } catch (RuntimeException e) {
            return failSubscription(connectionId, "opening the stream failed: " + e.getMessage());
        }
        if (stream == null) {
            return failSubscription(connectionId, "no stream opened");
        }
        GlobalStream previous = streams.put(connectionId, stream);
        try {
            stream.start();
        } catch (RuntimeException e) {
            streams.remove(connectionId, stream);
            if (previous != null && previous != stream) {
                streams.putIfAbsent(connectionId, previous);
            }
            return failSubscription(connectionId, "starting the stream failed: " + e.getMessage());
        }
        if (previous != null && previous != stream) {
            stopQuietly(previous);
        }
        if (closed) {
            // close() raced this subscribe: nobody else would stop this stream
            unsubscribe(connectionId);
            return false;
        }
        failed.remove(connectionId);
        return true;
    }

    /**
     * Ends a subscription and stops its stream; idempotent, and it clears a
     * failed marker of that id (the connection is deliberately gone).
     */
    public void unsubscribe(String connectionId) {
        if (connectionId == null) {
            return;
        }
        GlobalStream stream = streams.remove(connectionId);
        if (stream != null) {
            stopQuietly(stream);
        }
        failed.remove(connectionId);
    }

    /**
     * Ends every subscription and refuses new ones; idempotent. History
     * ({@link #recent(int)}) and the counters stay readable afterwards.
     */
    public void close() {
        closed = true;
        for (String connectionId : streams.keySet()) {
            unsubscribe(connectionId);
        }
    }

    /** Registers an event listener (invoked on the delivering stream's reader thread). */
    public void addListener(Consumer<ObservedEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<ObservedEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * The newest retained events, oldest first (copy, never null):
     * {@code n <= 0} yields an empty list, an {@code n} beyond the buffer
     * yields everything retained.
     */
    public List<ObservedEvent> recent(int n) {
        if (n <= 0) {
            return List.of();
        }
        synchronized (lock) {
            int count = Math.min(n, recent.size());
            List<ObservedEvent> out = new ArrayList<>(count);
            Iterator<ObservedEvent> newest = recent.descendingIterator();
            while (out.size() < count && newest.hasNext()) {
                out.add(newest.next());
            }
            Collections.reverse(out);
            return out;
        }
    }

    /** @return the ids of currently subscribed connections (copy) */
    public Set<String> connections() {
        return Set.copyOf(streams.keySet());
    }

    /** @return the ids whose subscription failed and was not retried since (copy) */
    public Set<String> failedConnections() {
        return Set.copyOf(failed);
    }

    /** @return how many events were delivered (fresh ones, post de-duplication) */
    public int deliveredCount() {
        return (int) delivered.get();
    }

    /** @return how many deliveries were dropped as duplicates (observability) */
    public int droppedDuplicates() {
        return (int) dropped.get();
    }

    /**
     * One event from one subscribed stream (its reader thread). Never throws
     * — a throwing sink would tear down the stream's read loop — and events
     * of connections without a live subscription are ignored (an unsubscribed
     * stream may still have one delivery in flight).
     */
    private void onEvent(String connectionId, OpencodeEvent event) {
        if (event == null || closed || !streams.containsKey(connectionId)) {
            return;
        }
        try {
            ObservedEvent observed = new ObservedEvent(connectionId, contentId(event), event.type(),
                    event.properties(), event.directory());
            boolean fresh;
            synchronized (lock) {
                fresh = seen.put(observed.id(), Boolean.TRUE) == null;
                if (fresh) {
                    recent.addLast(observed);
                    if (recent.size() > recentCapacity) {
                        recent.removeFirst();
                    }
                }
            }
            if (!fresh) {
                dropped.incrementAndGet();
                return;
            }
            delivered.incrementAndGet();
            for (Consumer<ObservedEvent> listener : listeners) {
                try {
                    listener.accept(observed);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "global event listener of " + connectionId + " failed: " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "aggregating a global event of " + connectionId + " failed: " + e.getMessage());
        }
    }

    /**
     * Deterministic content id of one event: the v2 frame's own id is
     * unwrapped away by the client ({@link OpencodeEvent} carries type,
     * properties and directory only), so identity is derived from exactly
     * those. The directory is part of the key because the single
     * {@code /api/event} stream carries every project — without it, equal
     * payloads from two worktrees would collapse into one.
     */
    private static String contentId(OpencodeEvent event) {
        String type = event.type() == null ? "" : event.type();
        String directory = event.directory() == null ? "" : event.directory();
        String properties = GSON.toJson(event.properties());
        return type + "#" + Integer.toHexString(
                (type.hashCode() * 31 + properties.hashCode()) * 31 + directory.hashCode());
    }

    private boolean failSubscription(String connectionId, String message) {
        failed.add(connectionId);
        LOG.log(Level.WARNING, "global events " + connectionId + ": " + message);
        return false;
    }

    private static void stopQuietly(GlobalStream stream) {
        try {
            stream.stop();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "stopping a global event stream failed: " + e.getMessage());
        }
    }
}
