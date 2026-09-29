package com.opencode.ide.chat;

import java.util.Set;
import java.util.function.Supplier;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.activity.PermissionRequest;

/**
 * Reconnect recovery for permission asks (T-004): SSE events are not
 * replayed, so a view that appears (or re-connects) re-reads the pending
 * asks via {@code GET /permission/request} ({@code listPermissionRequests})
 * and surfaces the ones still unanswered - the ask-&gt;answer half is fed by
 * {@link ChatPermissionAdapter}, this is the recovery half.
 *
 * <p>SWT-free seam in the {@link ChatPermissions} family: the view supplies
 * the client/directory/session scope plus a listener that shows the banner
 * (dispatched to the UI thread by the view), so the re-read-and-filter logic
 * is unit-testable without a workbench. Failures degrade without throwing:
 * an unreadable server (older version, not connected) leaves the banner
 * hidden, exactly like a view that opened before any ask was raised.</p>
 */
public final class ChatPermissionRecovery {

    /** Receives every recovered pending ask together with the client that can answer it. */
    public interface Listener {

        void shown(PermissionRequest request, OpencodeClient client);
    }

    private final Supplier<OpencodeClient> clientProvider;
    private final Supplier<String> workingDirectory;
    /**
     * Session scope of the recovering view; {@code null} = surface asks of
     * every session (a fresh view before its first message).
     */
    private final Supplier<String> sessionId;
    /** Permission ids already answered in the view - never re-surfaced. */
    private final Supplier<Set<String>> answeredIds;
    private final Listener listener;

    /**
     * @param clientProvider   supplies the live client; {@code null} = not
     *                         connected (recovery is a no-op then)
     * @param workingDirectory project scope of the re-read ({@code null} = unscoped)
     * @param sessionId        session filter, see {@link #sessionId}
     * @param answeredIds      ids this view already answered (live view state)
     * @param listener         receives each ask to surface
     */
    public ChatPermissionRecovery(Supplier<OpencodeClient> clientProvider,
            Supplier<String> workingDirectory, Supplier<String> sessionId,
            Supplier<Set<String>> answeredIds, Listener listener) {
        this.clientProvider = clientProvider;
        this.workingDirectory = workingDirectory;
        this.sessionId = sessionId;
        this.answeredIds = answeredIds;
        this.listener = listener;
    }

    /**
     * Re-reads the pending asks and surfaces every one that is still pending,
     * carries a usable id, was not answered in this view, and (when scoped)
     * belongs to this view's session - the same filters the live ask path
     * applies. A broken listener is contained so one bad surface call cannot
     * hide the remaining asks; a failing re-read degrades without throwing.
     *
     * @return how many asks were surfaced
     */
    public int recover() {
        int surfaced = 0;
        try {
            OpencodeClient client = clientProvider.get();
            if (client == null) {
                return 0; // not connected - the banner simply stays hidden
            }
            for (PermissionRequest request : client.listPermissionRequests(workingDirectory.get())) {
                if (request == null || !request.pending()) {
                    continue;
                }
                String permissionId = request.permissionId();
                if (permissionId == null || permissionId.isBlank()) {
                    continue; // cannot be answered without an id
                }
                if (answeredIds.get().contains(permissionId)) {
                    continue;
                }
                String scope = sessionId.get();
                if (scope != null && !scope.equals(request.sessionId())) {
                    continue; // this banner = this session; the Background view shows every ask
                }
                deliver(request, client);
                surfaced++;
            }
        } catch (Exception ignored) {
            // older server / not connected - the banner simply stays hidden
        }
        return surfaced;
    }

    /** One contained surface call: a throwing listener never breaks the loop. */
    private void deliver(PermissionRequest request, OpencodeClient client) {
        try {
            listener.shown(request, client);
        } catch (Throwable ignored) {
            // a broken surface must not hide the remaining asks
        }
    }
}
