package com.opencode.ide.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.opencode.ide.client.activity.PermissionRequest;

/**
 * SWT-free decision of which permission asks open the U-014 in-chat
 * permission <b>dialog</b> (as opposed to the always-on banner row): an ask
 * opens at most ONE dialog per request id, and only for the view's CURRENT
 * session — an ask of another session (or of any session while this view
 * has none yet, e.g. before the first message) stays on the banner row,
 * which the Background view complements for every session.
 *
 * <p>View-lifetime state, thread-safe: {@link #offer} is called on the UI
 * thread (from the banner's show path, itself dispatched from the SSE
 * thread by the sink), while the recovery path may surface the same ask
 * again after a reconnect — exactly the duplicate the once-per-id rule
 * absorbs.</p>
 */
public final class ChatPermissionDialogGate {

    /** Permission ids a dialog was already opened for. */
    private final Set<String> offered = ConcurrentHashMap.newKeySet();

    /**
     * @param currentSessionId the session this view is currently showing
     *            ({@code null} before the first message)
     * @param request          the surfaced ask
     * @return {@code true} when THIS ask should open the dialog — the first
     *         time a pending, id-carrying ask of the current session is
     *         offered; {@code false} for any repeat, other session's ask or
     *         unusable request (the banner still shows those)
     */
    public boolean offer(String currentSessionId, PermissionRequest request) {
        if (request == null || !request.pending()) {
            return false;
        }
        String permissionId = request.permissionId();
        if (permissionId == null || permissionId.isBlank()) {
            return false; // cannot be answered without an id
        }
        if (currentSessionId == null || !currentSessionId.equals(request.sessionId())) {
            return false; // the dialog is for the CURRENT chat session only
        }
        return offered.add(permissionId); // false on a repeat: one dialog per id
    }
}
