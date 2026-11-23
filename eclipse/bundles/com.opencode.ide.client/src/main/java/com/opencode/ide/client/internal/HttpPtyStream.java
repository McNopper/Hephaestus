package com.opencode.ide.client.internal;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.PtyStdin;
import com.opencode.ide.client.PtyStream;
import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link PtyStream} backed by the JDK's {@link WebSocket} ({@code java.net.http}) - the client's
 * first WebSocket transport, scoped to the PTY connect stream (FR-CP-13 / T-201).
 *
 * <p>Frame contract (live-probed 2026-11-23 on opencode 2.4.0, both directions raw text frames):
 * input frames are encoded by {@link PtyStdin#encode}; output text frames are forwarded as-is; a
 * <b>binary</b> frame whose first byte is {@code 0x00} is the server's control checkpoint ({@code
 * {"cursor":N}} - written as {@code 0x00 + JSON} upstream) and is dropped: there is no emulator on
 * our side to apply it. Other binary frames decode as UTF-8 output.
 *
 * <p>Output serialisation: the JDK allows only ONE outstanding send per socket, so keystrokes go
 * through a small outbox drained one frame per send completion (fast typing must not throw).
 * Internal (non-exported) package - {@code HttpOpencodeClient.connectPty} is the only entry point.
 */
final class HttpPtyStream implements PtyStream, WebSocket.Listener {

  private final PtyStream.Listener listener;

  /** Reassembly buffers - a message never mixes text and binary parts (WS continuation rules). */
  private final StringBuilder text = new StringBuilder();

  private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

  /** Serialised outbound stdin (one send in flight; drained on completion). */
  private final ArrayDeque<String> outbox = new ArrayDeque<>();

  private WebSocket socket;
  private boolean sending;
  private boolean closed;

  HttpPtyStream(PtyStream.Listener listener) {
    this.listener = listener;
  }

  /** Binds the opened socket (after {@code buildAsync} completes; sends start from here). */
  void connected(WebSocket opened) {
    boolean drop;
    synchronized (this) {
      drop = closed;
      if (!drop) {
        socket = opened;
      }
    }
    if (drop) {
      // the link was torn down while the handshake completed - do not leak the socket
      try {
        opened.sendClose(WebSocket.NORMAL_CLOSURE, "");
      } catch (RuntimeException e) {
        ClientLog.warning("pty stream close failed: " + e.getMessage());
      }
    }
  }

  // ---------------- WebSocket.Listener (server -> console) ----------------

  @Override
  public void onOpen(WebSocket webSocket) {
    webSocket.request(1); // demand for the first frame - replay + banner arrive immediately
  }

  @Override
  public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    text.append(data);
    if (last) {
      String chunk = text.toString();
      text.setLength(0);
      listener.onOutput(chunk);
    }
    webSocket.request(1);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    byte[] chunk = new byte[data.remaining()];
    data.get(chunk);
    binary.write(chunk, 0, chunk.length);
    if (last) {
      byte[] message = binary.toByteArray();
      binary.reset();
      if (message.length == 0) {
        // nothing to render
      } else if (message[0] == 0) {
        // control frame: 0x00 + JSON (the {"cursor":N} checkpoint) - dropped, no emulator
      } else {
        listener.onOutput(new String(message, StandardCharsets.UTF_8));
      }
    }
    webSocket.request(1);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    synchronized (this) {
      closed = true;
      outbox.clear();
    }
    listener.onClosed(statusCode, reason);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    synchronized (this) {
      closed = true;
      outbox.clear();
    }
    listener.onError(error);
  }

  // ---------------- PtyStream (console -> stdin) ----------------

  @Override
  public void sendInput(String keystrokes) {
    String payload = PtyStdin.encode(keystrokes);
    if (payload.isEmpty()) {
      return;
    }
    boolean start;
    synchronized (this) {
      if (closed || socket == null) {
        return;
      }
      outbox.add(payload);
      start = !sending;
      sending = true;
    }
    if (start) {
      drain();
    }
  }

  @Override
  public void close() {
    WebSocket current;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      outbox.clear();
      current = socket;
    }
    if (current == null) {
      return;
    }
    try {
      current
          .sendClose(WebSocket.NORMAL_CLOSURE, "")
          .whenComplete(
              (result, error) -> {
                if (error != null) {
                  ClientLog.warning("pty stream close failed: " + error.getMessage());
                }
              });
    } catch (RuntimeException e) {
      ClientLog.warning("pty stream close failed: " + e.getMessage());
    }
  }

  /** Sends the queued frames one at a time (re-entered from each send's completion). */
  private void drain() {
    while (true) {
      String next;
      synchronized (this) {
        if (closed || socket == null) {
          outbox.clear();
          sending = false;
          return;
        }
        next = outbox.poll();
        if (next == null) {
          sending = false;
          return;
        }
      }
      try {
        socket
            .sendText(next, true)
            .whenComplete(
                (result, error) -> {
                  if (error != null) {
                    ClientLog.warning("pty stdin send failed: " + error.getMessage());
                  }
                  drain();
                });
        return; // the completion above resumes the drain
      } catch (RuntimeException e) {
        ClientLog.warning("pty stdin send failed: " + e.getMessage());
        // fall through and try the next queued frame
      }
    }
  }
}
