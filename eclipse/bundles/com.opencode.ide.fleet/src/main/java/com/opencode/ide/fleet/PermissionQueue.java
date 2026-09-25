package com.opencode.ide.fleet;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.activity.PermissionRequest.Status;

/**
 * Thread-safe queue of permission requests raised by unattended fleet
 * sessions (ROADMAP H5 item 1): events feed it via
 * {@link #offer(PermissionRequest)} (see {@link FleetPermissionBridge}), the
 * human answers via {@link #answer(String, Response, boolean)}, which drives
 * the injected {@link PermissionResponder}
 * ({@code POST /session/:id/permission/:requestID/reply}). Failures surface as
 * a failed {@link AnswerResult} — answering never throws, and a failed answer
 * keeps the request pending so it can be retried.
 *
 * <p>Entries are deduplicated by permission id: a re-delivered
 * {@code permission.asked} updates the existing entry instead of duplicating
 * it, and a {@code permission.replied} (answered by anyone) drops it from
 * {@link #pending()}. The id is the v2 {@code requestID} the reply endpoint
 * takes. {@link #pending()} returns unanswered requests oldest first. Change
 * notification via {@link Runnable} listeners, invoked on the mutating thread
 * (same contract as {@code ActivityTracker}).</p>
 *
 * <p>Pure Java, no Eclipse/OSGi.</p>
 */
public final class PermissionQueue {

    /**
     * Posts one answer server-side. Mirrors
     * {@link OpencodeClient#respondToPermission(String, String, String, boolean)}
     * so {@code client::respondToPermission} (or {@link #responderOf}) can be
     * used directly; fakes throw {@link OpencodeException} to simulate
     * transport failures.
     */
    @FunctionalInterface
    public interface PermissionResponder {

        /**
         * @param sessionId    the asking session
         * @param permissionId the request id
         * @param response     {@code once}, {@code always} or {@code reject}
         *                     (see {@link Response#wire()})
         * @param remember     persist the decision as a rule
         * @return whether the server accepted the answer
         */
        boolean respond(String sessionId, String permissionId, String response, boolean remember)
                throws OpencodeException;
    }

    /** The three answers the human can give; {@link #wire()} is the server's spelling. */
    public enum Response {
        ONCE("once"), ALWAYS("always"), REJECT("reject");

        private final String wire;

        Response(String wire) {
            this.wire = wire;
        }

        /** @return the wire value for the answer endpoint's body */
        public String wire() {
            return wire;
        }
    }

    /** Outcome of {@link #answer}; {@link #message()} explains every failure. */
    public record AnswerResult(boolean success, String message) {

        static AnswerResult ok(String permissionId, Response response) {
            return new AnswerResult(true, "answered " + permissionId + " (" + response.wire() + ")");
        }

        static AnswerResult fail(String message) {
            return new AnswerResult(false, message);
        }
    }

    /** One queued request; mutable fields are volatile, guarded per-entry for answers. */
    private static final class Entry {

        final long sequence;
        final String sessionId;
        final String permissionId;
        volatile String permission;
        volatile List<String> patterns;
        volatile String title;
        volatile Status status;

        Entry(long sequence, PermissionRequest request) {
            this.sequence = sequence;
            this.sessionId = request.sessionId();
            this.permissionId = request.permissionId();
            this.permission = request.permission();
            this.patterns = request.patterns();
            this.title = request.title();
            this.status = request.status() == null ? Status.PENDING : request.status();
        }

        /** Merges a re-delivered request; @return whether anything changed. */
        boolean update(PermissionRequest request) {
            boolean changed = false;
            Status status = request.status() == null ? this.status : request.status();
            if (status != this.status) {
                this.status = status;
                changed = true;
            }
            if (request.permission() != null && !request.permission().equals(this.permission)) {
                this.permission = request.permission();
                changed = true;
            }
            if (!request.patterns().equals(this.patterns)) {
                this.patterns = request.patterns();
                changed = true;
            }
            if (request.title() != null && !request.title().equals(this.title)) {
                this.title = request.title();
                changed = true;
            }
            return changed;
        }

        PermissionRequest snapshot() {
            return new PermissionRequest(sessionId, permissionId, permission, patterns, title, status);
        }
    }

    private final PermissionResponder responder;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequencer = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param responder posts answers server-side; {@code null} leaves the
     *                  queue observational (every answer fails with "no
     *                  responder wired")
     */
    public PermissionQueue(PermissionResponder responder) {
        this.responder = responder;
    }

    /** Adapts an {@link OpencodeClient} to the responder seam. */
    public static PermissionResponder responderOf(OpencodeClient client) {
        return client::respondToPermission;
    }

    /** Registers a change notification (runs on whichever thread mutates the queue). */
    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Registers or updates a request (deduplicated by permission id — an
     * {@code asked} re-delivery refreshes it, a {@code replied} marks it
     * answered). Ignored when the request or its ids are {@code null}.
     *
     * @return whether the queue changed (listeners fired only then)
     */
    public boolean offer(PermissionRequest request) {
        if (request == null || request.sessionId() == null || request.permissionId() == null) {
            return false;
        }
        boolean[] changed = { false };
        entries.compute(request.permissionId(), (id, existing) -> {
            if (existing == null) {
                changed[0] = true;
                return new Entry(sequencer.incrementAndGet(), request);
            }
            changed[0] = existing.update(request);
            return existing;
        });
        if (changed[0]) {
            fire();
        }
        return changed[0];
    }

    /**
     * Safety-net reconciliation with the service's own permission list (v2
     * {@code permission.request.list} - slice 3, 2026-09-25): events are the
     * fast path, this catches asks MISSED by the stream (an event gap, a
     * reconnect). Unknown asks are offered exactly like an {@code asked}
     * event. The list is a FLOOR, never a replacement - nothing already
     * queued is dropped by a partial list; removal rides {@code replied}
     * events.
     *
     * @param requests the service's current request list
     * @return how many asks the queue gained
     */
    public int reconcile(java.util.List<PermissionRequest> requests) {
        if (requests == null) {
            return 0;
        }
        int gained = 0;
        for (PermissionRequest request : requests) {
            if (request != null && !entries.containsKey(request.permissionId()) && offer(request)) {
                gained++;
            }
        }
        return gained;
    }

    /** The unanswered requests, oldest first (copy-on-read, never null). */
    public List<PermissionRequest> pending() {
        return entries.values().stream()
                .filter(entry -> entry.status == Status.PENDING)
                .sorted(Comparator.comparingLong(entry -> entry.sequence))
                .map(Entry::snapshot)
                .toList();
    }

    /** @return how many requests currently await an answer */
    public int pendingCount() {
        return (int) entries.values().stream()
                .filter(entry -> entry.status == Status.PENDING)
                .count();
    }

    /**
     * Answers a pending request through the responder
     * ({@code POST /session/:id/permission/:requestID/reply}). Never throws:
     * transport/server failures return a failed result and keep the request
     * pending (retryable); on success the request is marked answered and
     * dropped from {@link #pending()}.
     *
     * @param permissionId the request id to answer
     * @param response     once / always / reject
     * @param remember     persist the decision (sensible for
     *                     {@link Response#ALWAYS})
     * @return the outcome with a human-readable message
     */
    public AnswerResult answer(String permissionId, Response response, boolean remember) {
        if (permissionId == null || permissionId.isBlank()) {
            return AnswerResult.fail("no permission id given");
        }
        if (response == null) {
            return AnswerResult.fail("no response given for " + permissionId);
        }
        Entry entry = entries.get(permissionId);
        if (entry == null) {
            return AnswerResult.fail("unknown permission request " + permissionId);
        }
        // serialized per request: concurrent answers for the SAME id resolve
        // to one server call and one "already answered" for the losers
        synchronized (entry) {
            if (entry.status != Status.PENDING) {
                return AnswerResult.fail("permission request " + permissionId + " is already answered");
            }
            if (responder == null) {
                return AnswerResult.fail("no responder wired to answer " + permissionId);
            }
            boolean accepted;
            try {
                accepted = responder.respond(entry.sessionId, permissionId, response.wire(), remember);
            } catch (OpencodeException | RuntimeException e) {
                return AnswerResult.fail("answering " + permissionId + " failed: " + e.getMessage());
            }
            if (!accepted) {
                return AnswerResult.fail("server did not accept the answer for " + permissionId);
            }
            entry.status = Status.ANSWERED;
        }
        fire();
        return AnswerResult.ok(permissionId, response);
    }

    /**
     * Drops every entry of a session — call when the fleet job's session ends
     * (completed, aborted or deleted): unanswered asks are void once nobody
     * waits for them.
     *
     * @return whether anything was removed
     */
    public boolean remove(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        boolean removed = entries.values().removeIf(entry -> sessionId.equals(entry.sessionId));
        if (removed) {
            fire();
        }
        return removed;
    }

    private void fire() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
