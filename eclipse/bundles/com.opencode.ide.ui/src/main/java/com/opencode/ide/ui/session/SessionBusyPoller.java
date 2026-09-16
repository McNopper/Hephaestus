package com.opencode.ide.ui.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Polls {@code GET /session/status} on a background thread and notifies
 * listeners with the set of session ids whose agent is currently working
 * ({@code busy} or {@code retry}). This keeps the Server view's busy icons
 * live wherever the SSE {@code /event} stream cannot: remote connections
 * (the view subscribes to events for the primary only) and dropped or
 * missed events on any connection — every poll re-asserts the complete
 * busy state, and since opencode 1.18.23 the endpoint lists BUSY sessions
 * only (an absent id is idle), so the polled set is the whole truth. Fleet
 * worker sessions are covered by construction: they are ordinary sessions
 * of their server and appear in the same map.
 *
 * <p>SWT-free and JFace-free on purpose so it is unit-testable without a
 * {@code Display} (see {@code SessionBusyPollerTest} in
 * {@code com.opencode.ide.ui.tests}). Listeners and the error consumer run
 * on the poller thread and must hop to the UI thread themselves — the
 * Server view does so with {@code Display#asyncExec}, exactly like its SSE
 * listeners.</p>
 *
 * <p>Lifecycle: {@link #start()} polls once immediately and then every
 * {@code intervalMillis}; {@link #dispose()} stops the poller (idempotent;
 * a disposed poller cannot be restarted — create a new one instead). A
 * failed poll never throws and never clears the last delivered set (no
 * icon flicker on transient errors); it is reported to the error consumer
 * only on the transition into failure, so a server that stays down logs
 * one line rather than one per poll.</p>
 */
public final class SessionBusyPoller {

    /**
     * Default poll interval: two seconds. A busy marker appearing within one
     * poll feels live to a human watching the tree, while the request itself
     * is a trivial GET per open view.
     */
    public static final long DEFAULT_INTERVAL_MILLIS = 2000L;

    /**
     * The idle session-row icon key (bundle-relative path). Canonical source
     * of truth — {@code UiActivator#ICON_SESSION} aliases this constant for
     * its remaining call sites.
     */
    public static final String ICON_SESSION = "icons/session.png";

    /**
     * The busy session-row icon key (the distinct orange bubble). Canonical
     * source of truth — {@code UiActivator#ICON_SESSION_BUSY} aliases this
     * constant for its remaining call sites.
     */
    public static final String ICON_SESSION_BUSY = "icons/session-busy.png";

    /**
     * Receives the polled busy set. Called on the poller thread after every
     * successful poll — unchanged sets are re-delivered on purpose, so
     * freshly (re)loaded UI state can reconcile — always with an immutable
     * set ({@code null} is never passed).
     */
    @FunctionalInterface
    public interface Listener {

        /** @param busySessionIds ids of the sessions currently working */
        void busySessions(Set<String> busySessionIds);
    }

    private final OpencodeClient client;
    private final long intervalMillis;
    private final Consumer<Exception> errorConsumer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();

    private Thread worker;
    private volatile boolean disposed;
    private boolean lastPollFailed;   // worker-thread confined: failure-transition detection

    /**
     * @param client        the server to poll (one poller per client)
     * @param intervalMillis delay between polls; must be &gt; 0 (tests use a
     *                      few milliseconds)
     * @param errorConsumer receives poll failures, on the transition into
     *                      failure only; {@code null} disables reporting
     * @throws IllegalArgumentException when {@code intervalMillis} &lt;= 0
     */
    public SessionBusyPoller(OpencodeClient client, long intervalMillis, Consumer<Exception> errorConsumer) {
        this.client = Objects.requireNonNull(client, "client");
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be > 0: " + intervalMillis);
        }
        this.intervalMillis = intervalMillis;
        this.errorConsumer = errorConsumer;
    }

    /** Registers a listener ({@code null} is ignored); called on the poller thread. */
    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Removes a listener ({@code null} is ignored). */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Starts polling on a daemon thread ({@code opencode-session-busy-poller});
     * the first poll runs immediately so the busy state shows up as soon as
     * the view opens. No-op when already started or disposed.
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (disposed || worker != null) {
                return;
            }
            worker = new Thread(this::run, "opencode-session-busy-poller");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** @return whether {@link #dispose()} has been called. */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Stops polling: prevents further polls and listener callbacks, drops
     * all listeners and interrupts an in-flight poll or sleep. Idempotent;
     * the poller cannot be restarted afterwards (create a new one). Does
     * NOT join the worker — a blocked HTTP call may take seconds and the UI
     * thread must not wait for it; tests use {@link #awaitTermination(long)}
     * to prove the thread still ends.
     */
    public void dispose() {
        Thread thread;
        synchronized (lifecycleLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            thread = worker;
        }
        listeners.clear();
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Waits for the worker thread to finish (test support — the UI never
     * blocks on this; it proves dispose leaves no leaked thread).
     *
     * @return whether the worker terminated within the timeout
     */
    public boolean awaitTermination(long timeoutMillis) {
        Thread thread;
        synchronized (lifecycleLock) {
            thread = worker;
        }
        if (thread == null) {
            return true;
        }
        try {
            thread.join(timeoutMillis);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !thread.isAlive();
        }
    }

    // ---------- worker loop ----------

    /** Poll → deliver → sleep, until disposed; survives client errors. */
    private void run() {
        while (!disposed) {
            try {
                Set<String> busy = toBusySet(client.getSessionStatus());
                lastPollFailed = false;
                if (!disposed) {
                    for (Listener listener : listeners) {
                        listener.busySessions(busy);
                    }
                }
            } catch (Exception e) {
                if (!lastPollFailed) {
                    lastPollFailed = true;
                    reportSafely(e);
                }
            }
            if (!sleep()) {
                return;   // interrupted = disposed (only dispose() interrupts)
            }
        }
    }

    /** Interval sleep; {@code false} when interrupted (i.e. disposed). */
    private boolean sleep() {
        try {
            Thread.sleep(intervalMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A throwing error consumer must never kill the polling loop. */
    private void reportSafely(Exception error) {
        if (errorConsumer == null) {
            return;
        }
        try {
            errorConsumer.accept(error);
        } catch (RuntimeException ignored) {
            // logging is best-effort
        }
    }

    // ---------- pure mapping logic (shared with the view; unit-tested) ----------

    /**
     * Projects a raw {@code /session/status} map onto the set of working
     * session ids: keeps entries typed {@code busy} or {@code retry}
     * (case-insensitive, mirroring {@code ServerLabels#isBusy} and the
     * client's {@code ActivityTracker}); {@code idle} entries and — per the
     * opencode 1.18.23 wire contract — absent ids mean idle and simply do
     * not appear.
     *
     * @return an immutable set; the empty set for {@code null}/empty input
     */
    public static Set<String> toBusySet(Map<String, SessionStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return Set.of();
        }
        Set<String> busy = new HashSet<>();
        for (Map.Entry<String, SessionStatus> entry : statuses.entrySet()) {
            if (entry.getKey() != null && isBusyType(entry.getValue())) {
                busy.add(entry.getKey());
            }
        }
        return Set.copyOf(busy);
    }

    /**
     * Reconciles a polled busy set into a live status map (the Server view's
     * per-node {@code statuses}): adds a {@code busy} entry for newly
     * working sessions, preserves an existing {@code retry} marker (the
     * polled set carries no type detail) and removes entries that are
     * busy-typed but no longer in the polled set — absent means idle since
     * opencode 1.18.23. Entries the poll has no opinion about (e.g. an
     * explicit {@code idle} written by an SSE event) are left untouched, so
     * the SSE stream stays the more granular source and polling only
     * backstops it.
     *
     * @return whether anything changed (drives the coalesced viewer refresh)
     */
    public static boolean mergeInto(Map<String, SessionStatus> statuses, Set<String> busySessionIds) {
        if (statuses == null) {
            return false;
        }
        boolean changed = false;
        Set<String> busy = busySessionIds == null ? Set.of() : busySessionIds;
        for (String id : busy) {
            if (id != null && !isBusyType(statuses.get(id))) {
                statuses.put(id, new SessionStatus("busy"));
                changed = true;
            }
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, SessionStatus> entry : statuses.entrySet()) {
            if (isBusyType(entry.getValue()) && !busy.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (String id : stale) {
            statuses.remove(id);
            changed = true;
        }
        return changed;
    }

    /**
     * The session-row icon key for a busy state — the testable busy/idle
     * branch of the Server view's icon assignment (the view folds its other
     * live signals — activity label, tracker snapshot — into the boolean
     * first; both states map to the same two icons).
     */
    public static String iconKey(boolean sessionBusy) {
        return sessionBusy ? ICON_SESSION_BUSY : ICON_SESSION;
    }

    /** Whether the status is typed {@code busy} or {@code retry}. */
    private static boolean isBusyType(SessionStatus status) {
        return status != null && ("busy".equalsIgnoreCase(status.type())
                || "retry".equalsIgnoreCase(status.type()));
    }
}
