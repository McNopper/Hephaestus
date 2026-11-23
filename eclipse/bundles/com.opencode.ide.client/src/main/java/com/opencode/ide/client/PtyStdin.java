package com.opencode.ide.client;

/**
 * Keystroke to wire payload for the interactive PTY stream (T-201, FR-CP-13).
 *
 * <p>The stream contract this encodes against was live-probed on opencode 2.4.0 (2026-11-23,
 * scripted round trip on a self-created PTY): stdin rides <b>text frames</b> on {@code GET
 * /api/pty/{id}/connect} with no envelope, a line is submitted with {@code CR} (the probe's {@code
 * echo T201_INPUT_OK} followed by a CR was echoed and executed; the reply frames came back as raw
 * VT/ANSI text), and control frames arrive <b>binary with a leading 0x00</b> (the {@code
 * {"cursor":N}} checkpoint - output side, dropped by the stream, not rendered).
 *
 * <p>Pure functions only: no SWT, no sockets - the key source (the console's verify/key listeners)
 * hands over the event fields, this class decides the payload, {@link PtyStream#sendInput} ships
 * it. Unit-tested byte-for-byte in {@code PtyStdinTest}.
 */
public final class PtyStdin {

  /** The interrupt control code (Ctrl+C, 0x03) - forwarded as raw stdin. */
  private static final char INTERRUPT = (char) 3;

  /** The EOF control code (Ctrl+D, 0x04) - forwarded as raw stdin. */
  private static final char EOF = (char) 4;

  /** The suspend control code (Ctrl+Z, 0x1a) - forwarded as raw stdin (EOF on Windows). */
  private static final char SUSPEND = (char) 26;

  private PtyStdin() {}

  /**
   * Normalizes keystroke text to the PTY's stdin wire form: {@code LF} and {@code CRLF} collapse to
   * {@code CR} (Eclipse hands us the platform delimiter, real terminals send CR), everything else -
   * including lone CRs and multi-byte characters - passes through verbatim. Idempotent.
   *
   * @param text the raw keystroke/paste text; {@code null} or empty yields {@code ""} (nothing to
   *     send)
   * @return the payload for one text frame
   */
  public static String encode(String text) {
    if (text == null || text.isEmpty() || text.indexOf('\n') < 0) {
      return text == null ? "" : text;
    }
    StringBuilder payload = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
        continue; // drop the CR of a CRLF pair - the LF below becomes the CR
      }
      payload.append(c == '\n' ? '\r' : c);
    }
    return payload.toString();
  }

  /**
   * The payload of ONE key event of a verify/typing source (a character about to be inserted, or a
   * paste carrying its text): paste/pre-typed text wins whenever it is non-empty, a function/arrow
   * key ({@code char 0}) has no raw equivalent without a terminal emulator and is dropped, anything
   * else is encoded as keystrokes. See {@link #payload(String, char, boolean)} for the Ctrl-aware
   * form (key-down control codes).
   *
   * @param text the text the event would insert ({@code VerifyEvent.text}); may be {@code null}
   * @param key the event's character ({@code VerifyEvent.character} / {@code KeyEvent.character})
   * @return the frame payload, or {@code ""} when the key maps to nothing for the PTY
   */
  public static String payload(String text, char key) {
    return payload(text, key, false);
  }

  /**
   * The payload of one key event, Ctrl-aware. With Ctrl held only the interrupt trio (Ctrl+C/D/Z)
   * maps to stdin - in both SWT shapes of the key (the control char and the letter) - so the
   * console's local bindings (copy, paste, select-all) keep working and paste still reaches the PTY
   * through its own text. Without Ctrl the rules of {@link #payload(String, char)} apply.
   *
   * @param text the text the event would insert; may be {@code null}
   * @param key the event's character
   * @param ctrl whether Ctrl is held
   * @return the frame payload, or {@code ""} when the key maps to nothing for the PTY
   */
  public static String payload(String text, char key, boolean ctrl) {
    if (text != null && !text.isEmpty()) {
      return encode(text); // typing or paste - the inserted text is what the PTY must receive
    }
    if (ctrl) {
      char code = controlCode(key);
      return (code == INTERRUPT || code == EOF || code == SUSPEND) ? String.valueOf(code) : "";
    }
    return key == 0 ? "" : encode(String.valueOf(key));
  }

  /** Ctrl+letter in either SWT shape (control char or letter) to the ASCII control code. */
  private static char controlCode(char key) {
    if (key >= 'a' && key <= 'z') {
      return (char) (key - 'a' + 1);
    }
    if (key >= 'A' && key <= 'Z') {
      return (char) (key - 'A' + 1);
    }
    return key;
  }
}
