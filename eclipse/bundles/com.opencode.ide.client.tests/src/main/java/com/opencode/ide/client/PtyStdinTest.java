package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * T-201: keystroke to wire payload (FR-CP-13). The byte rules below are the ones the 2026-11-23
 * live probe round-tripped on opencode 2.4.0 (raw text frames, CR submits a line).
 */
public class PtyStdinTest {

  // ---------------- encode: LF/CRLF -> CR, everything else verbatim ----------------

  @Test
  public void newlinesCollapseToCarriageReturn() {
    assertEquals("echo hi\r", PtyStdin.encode("echo hi\n"));
    assertEquals("echo hi\r", PtyStdin.encode("echo hi\r\n"));
    assertEquals("a\rb", PtyStdin.encode("a\rb")); // a lone CR is already the wire form
    assertEquals("one\rtwo\rthree", PtyStdin.encode("one\ntwo\nthree"));
  }

  @Test
  public void multiByteAndNullishTextPassThrough() {
    assertEquals("", PtyStdin.encode(null));
    assertEquals("", PtyStdin.encode(""));
    assertEquals("passthrough", PtyStdin.encode("passthrough"));
    assertEquals("héllo wörld\r", PtyStdin.encode("héllo wörld\n"));
  }

  @Test
  public void encodeIsIdempotent() {
    String once = PtyStdin.encode("a\nb\r\nc");
    assertEquals(once, PtyStdin.encode(once));
    assertEquals("echo hi\r", PtyStdin.encode(PtyStdin.encode("echo hi\r\n")));
  }

  // ---------------- payload: one key event -> one frame ----------------

  @Test
  public void typingMapsCharacterByCharacter() {
    assertEquals("a", PtyStdin.payload("a", 'a'));
    assertEquals("a", PtyStdin.payload(null, 'a')); // a source that only carries the character
    assertEquals("\t", PtyStdin.payload(null, '\t'));
    assertEquals("\r", PtyStdin.payload(null, '\n')); // Enter as LF
    assertEquals("\r", PtyStdin.payload("\r\n", '\r')); // Enter as the platform delimiter
  }

  @Test
  public void functionKeysHaveNoRawEquivalentAndAreDropped() {
    assertEquals("", PtyStdin.payload(null, (char) 0)); // arrow/Home/PgUp carry char 0
    assertEquals("", PtyStdin.payload("", (char) 0));
  }

  @Test
  public void pasteTextWinsOverTheKeyCharacter() {
    assertEquals("paste\rline", PtyStdin.payload("paste\nline", (char) 0));
    assertEquals("git status\r", PtyStdin.payload("git status\n", 'x'));
  }

  @Test
  public void ctrlOnlyForwardsTheInterruptTrio() {
    // both SWT shapes of Ctrl+C: the control code (0x03) and the letter
    assertEquals(String.valueOf((char) 3), PtyStdin.payload(null, (char) 3, true));
    assertEquals(String.valueOf((char) 3), PtyStdin.payload(null, 'c', true));
    assertEquals(String.valueOf((char) 4), PtyStdin.payload(null, 'D', true));
    assertEquals(String.valueOf((char) 26), PtyStdin.payload(null, 'z', true));
    // reserved local bindings stay out of stdin (paste reaches the PTY via its own text)
    assertEquals("", PtyStdin.payload(null, 'v', true));
    assertEquals("", PtyStdin.payload(null, 'x', true));
    assertEquals("", PtyStdin.payload(null, 'a', true));
    // without Ctrl the plain mapping applies again
    assertEquals("c", PtyStdin.payload(null, 'c', false));
  }

  @Test
  public void pasteTextIsNotSwallowedByAHeldCtrl() {
    // Ctrl+V's verify event carries the clipboard text - it must still reach stdin
    assertEquals("paste\r", PtyStdin.payload("paste\n", 'v', true));
  }
}
