package com.opencode.ide.chat.internal;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.core.OpencodeConnection;

/**
 * The server surface {@link ChatSessionController} needs: the HTTP client plus
 * live-event ({@code /api/event} SSE) listener registration. The view supplies
 * an adapter over the {@link OpencodeConnection} singleton; tests supply fakes.
 */
public interface ChatServerConnection {

    /**
     * @return the current client; may block (spawn mode) - never call from the
     *         UI thread.
     */
    OpencodeClient getClient() throws OpencodeException;

    /**
     * The working directory v2's fs/vcs endpoints should be scoped to (they
     * resolve against the SERVER's cwd — the user's home on the shared
     * service). {@code null} = unscoped; test fakes default to that.
     */
    default String workingDirectory() {
        return null;
    }

    /** Registers for live opencode server events (delivered on a background thread). */
    void addEventListener(OpencodeEventListener listener);

    /** Unregisters a previously-added listener. */
    void removeEventListener(OpencodeEventListener listener);
}
