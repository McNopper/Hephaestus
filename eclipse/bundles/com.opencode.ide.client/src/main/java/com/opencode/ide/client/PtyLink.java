package com.opencode.ide.client;

import java.util.function.Consumer;

/**
 * The live link behind one interactive terminal console (T-201): routes PTY output to a renderer,
 * keystrokes to the transport, and owns the single teardown (WebSocket close + PTY removal).
 *
 * <p>Threading: keystrokes arrive on the UI thread, {@link PtyStream.Listener} callbacks on the
 * transport thread - every shared field is guarded by one lock. Ordering: output that arrives
 * before the console's renderer is attached (the connect replay does exactly that) buffers here and
 * flushes on attach, so the console may be created before or after the connect. Teardown runs
 * exactly once no matter which side ends first (user closes the console, the process exits, the
 * transport dies), and keystrokes after teardown are dropped instead of throwing.
 *
 * <p>SWT-free on purpose: the console/UI layer only ever sees a {@link Consumer} of output text,
 * which keeps the routing testable with a fake transport ({@code PtyLinkTest}).
 */
public final class PtyLink implements PtyStream.Listener {

  private final Object lock = new Object();

  /** Output that arrived before {@link #renderer}; {@code null} once attached (guarded by lock). */
  private StringBuilder pending;

  private Consumer<String> renderer;
  private PtyStream stream;
  private Runnable teardown;
  private boolean closed;

  public PtyLink() {}

  /**
   * Attaches the console's renderer (UI thread) and flushes everything buffered so far in order.
   * Called once, when the console exists.
   */
  public void renderer(Consumer<String> target) {
    String buffered;
    synchronized (lock) {
      this.renderer = target;
      buffered = (pending == null) ? "" : pending.toString();
      pending = null;
    }
    if (!buffered.isEmpty() && target != null) {
      target.accept(buffered);
    }
  }

  /**
   * Binds the connected stream and its teardown ({@code close()} + {@code removePty}). If the link
   * already terminated during the connect race, the stream is closed and the teardown runs now.
   *
   * @param connected the stream returned by {@code connectPty}
   * @param onEnd runs exactly once when the link terminates (may be {@code null})
   */
  public void attach(PtyStream connected, Runnable onEnd) {
    boolean alreadyClosed;
    synchronized (lock) {
      alreadyClosed = closed;
      if (!alreadyClosed) {
        this.stream = connected;
        this.teardown = onEnd;
      }
    }
    if (alreadyClosed) {
      if (connected != null) {
        connected.close();
      }
      if (onEnd != null) {
        onEnd.run();
      }
    }
  }

  /**
   * Routes one payload to the PTY's stdin - dropped when the payload is empty or the link already
   * ended (never throws at the key source).
   *
   * @param payload the already-encoded frame from {@link PtyStdin#payload}
   */
  public void keystroke(String payload) {
    PtyStream target;
    synchronized (lock) {
      if (closed || payload == null || payload.isEmpty() || stream == null) {
        return;
      }
      target = stream;
    }
    target.sendInput(payload);
  }

  /** The transport delivered raw PTY output. */
  @Override
  public void onOutput(String text) {
    sink(text);
  }

  /** The server closed the socket: tell the user, then run the single teardown. */
  @Override
  public void onClosed(int statusCode, String reason) {
    String detail = (reason == null || reason.isBlank()) ? "" : ": " + reason;
    sink("\n[pty stream closed (" + statusCode + ")" + detail + "]\n");
    terminate();
  }

  /** The transport failed: tell the user, then run the single teardown. */
  @Override
  public void onError(Throwable error) {
    sink(
        "\n[pty stream error: "
            + ((error == null || error.getMessage() == null) ? "unknown" : error.getMessage())
            + "]\n");
    terminate();
  }

  /** The console was closed by the user (or the caller decides to end the link). */
  public void close() {
    terminate();
  }

  /**
   * @return whether the link has ended (stream closed, teardown done).
   */
  public boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  /** Ends the link exactly once: closes the stream (if attached) and runs the teardown. */
  private void terminate() {
    PtyStream current;
    Runnable end;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      current = stream;
      end = teardown;
      stream = null;
      teardown = null;
    }
    if (current != null) {
      current.close();
    }
    if (end != null) {
      end.run();
    }
  }

  /** Delivers text to the renderer, buffering until one is attached; dropped after teardown. */
  private void sink(String text) {
    Consumer<String> target;
    synchronized (lock) {
      if (closed) {
        return;
      }
      if (renderer == null) {
        if (pending == null) {
          pending = new StringBuilder();
        }
        pending.append(text);
        return;
      }
      target = renderer;
    }
    target.accept(text);
  }
}
