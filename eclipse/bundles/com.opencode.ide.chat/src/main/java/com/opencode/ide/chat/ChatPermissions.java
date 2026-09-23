package com.opencode.ide.chat;

import java.util.concurrent.CopyOnWriteArrayList;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.activity.PermissionRequest;

/**
 * Global registry for permission-ask listeners (the multiplexer - IA
 * must-not-do item 1): the chat bundle dispatches parsed permission events
 * here and EVERY registered listener sees them. Hosts register with
 * {@link #addSink(ChatPermissionSink)}/{@link #removeSink(ChatPermissionSink)}
 * (a chat banner, a background view, a queue - any number, coexisting).
 *
 * <p>The legacy single-slot API ({@link #setSink}/{@link #clearSink}) is kept
 * for the fleet's queue and routed through its OWN slot: setting it can never
 * steal another listener's feed (before this class, last-set-wins silently
 * dropped everyone else - the exact failure the must-not order warns
 * about).</p>
 *
 * <p>Thread-safe: registration and dispatch may run on different threads; a
 * throwing listener is contained so the SSE event loop never breaks.</p>
 */
public final class ChatPermissions {

    private static final CopyOnWriteArrayList<ChatPermissionSink> SINKS = new CopyOnWriteArrayList<>();
    private static volatile ChatPermissionSink legacySink;

    private ChatPermissions() {
    }

    /** Adds a coexisting listener (banner, views, queues); never replaces others. */
    public static void addSink(ChatPermissionSink sink) {
        if (sink != null) {
            SINKS.addIfAbsent(sink);
        }
    }

    /** Removes a listener added by {@link #addSink}. */
    public static void removeSink(ChatPermissionSink sink) {
        SINKS.remove(sink);
    }

    /**
     * Legacy single-slot registration (the fleet queue's API): occupies its
     * own slot, {@code null} clears it. Kept source-compatible; new hosts use
     * {@link #addSink}.
     */
    public static void setSink(ChatPermissionSink newSink) {
        legacySink = newSink;
    }

    /** Removes the legacy sink; permission events reach the other listeners again. */
    public static void clearSink() {
        legacySink = null;
    }

    /**
     * Routes one parsed permission event to EVERY registered listener: a
     * pending ask to {@link ChatPermissionSink#asked} (skipped without a
     * client to answer with), an answer to {@link ChatPermissionSink#replied}.
     * Each listener is individually contained - a broken one never breaks the
     * SSE event loop or the others.
     */
    static void dispatch(PermissionRequest request, OpencodeClient client) {
        if (request == null) {
            return;
        }
        ChatPermissionSink legacy = legacySink;
        if (legacy != null) {
            deliver(legacy, request, client);
        }
        for (ChatPermissionSink sink : SINKS) {
            deliver(sink, request, client);
        }
    }

    private static void deliver(ChatPermissionSink sink, PermissionRequest request, OpencodeClient client) {
        try {
            if (request.pending()) {
                if (client != null) {
                    sink.asked(request, client);
                }
            } else {
                sink.replied(request.sessionId(), request.permissionId());
            }
        } catch (Throwable ignored) {
            // a broken listener must not break the SSE event loop
        }
    }
}
