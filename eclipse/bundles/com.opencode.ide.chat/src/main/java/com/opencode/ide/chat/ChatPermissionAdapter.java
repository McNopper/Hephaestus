package com.opencode.ide.chat;

import java.util.function.Supplier;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Bridges the opencode {@code /api/event} SSE stream into
 * {@link ChatPermissions}: parses each event with the shape-tolerant
 * {@link PermissionEvents} and dispatches {@code permission.asked} /
 * {@code permission.replied} to the registered {@link ChatPermissionSink}.
 * No sink, or a non-permission event — no-op. Registered by
 * {@code ChatSessionController.subscribe()} alongside its delta listener.
 */
public final class ChatPermissionAdapter implements OpencodeEventListener {

    private final Supplier<OpencodeClient> clientProvider;

    /**
     * @param clientProvider supplies the live client to answer asks with —
     *                       called on the SSE thread, may block, may return
     *                       {@code null} when none is available (the ask is
     *                       skipped then)
     */
    public ChatPermissionAdapter(Supplier<OpencodeClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public void onEvent(OpencodeEvent event) {
        PermissionRequest request = PermissionEvents.parse(event);
        if (request == null) {
            return;
        }
        OpencodeClient client = request.pending() ? clientProvider.get() : null;
        ChatPermissions.dispatch(request, client);
    }
}
