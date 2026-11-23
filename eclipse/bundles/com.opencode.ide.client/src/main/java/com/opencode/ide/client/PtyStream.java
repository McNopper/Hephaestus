package com.opencode.ide.client;

/**
 * A connected interactive PTY stream (T-201): {@code GET /api/pty/{ptyID}/connect} as a WebSocket
 * (x-websocket:true, "streaming PTY output and accepting terminal input").
 *
 * <p>Both directions are raw text frames - no envelope: output arrives through {@link Listener},
 * {@link #sendInput} writes keystrokes as stdin. The PTY process itself outlives the socket and is
 * terminated with {@code DELETE /api/pty/{id}} (the recipe T-201 live-proved on 2.4.0: create -
 * connect - text frames both ways - delete). There is no REST stdin route (the PUT route is
 * title/resize only), which is exactly why this transport exists (FR-CP-13).
 */
public interface PtyStream {

  /**
   * The far side of the stream. Callbacks arrive on the transport thread (never the UI thread):
   * implementations must not block and must hop to their display themselves.
   */
  interface Listener {

    /** One (possibly fragmented, already reassembled) chunk of raw PTY output. */
    void onOutput(String text);

    /** The server closed the socket (normal exit, code 4404 when the PTY went away, ...). */
    void onClosed(int statusCode, String reason);

    /** The transport failed (never reported as a clean close). */
    void onError(Throwable error);
  }

  /**
   * Sends keystrokes to the PTY's stdin as one text frame (encoded via {@link PtyStdin#encode};
   * empty or post-close payloads are dropped, never thrown).
   */
  void sendInput(String keystrokes);

  /** Closes the WebSocket politely. Safe to call repeatedly; the PTY lives on until removed. */
  void close();
}
