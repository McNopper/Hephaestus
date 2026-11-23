package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * T-201: input/output routing over a fake transport (FR-CP-13) - keystrokes reach the stream,
 * output reaches the renderer, and the teardown (socket close + PTY removal) runs exactly once no
 * matter which side ends the link.
 */
public class PtyLinkTest {

  /** Fake transport: records keystrokes and close() calls. */
  private static final class FakeStream implements PtyStream {
    final List<String> sent = new ArrayList<>();
    boolean closed;

    @Override
    public void sendInput(String keystrokes) {
      sent.add(keystrokes);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  /** Fake renderer: collects everything the pane would draw. */
  private static final class Recorder implements Consumer<String> {
    final StringBuilder drawn = new StringBuilder();

    @Override
    public void accept(String text) {
      drawn.append(text);
    }
  }

  @Test
  public void keystrokesReachTheTransportAndEmptyPayloadsAreDropped() {
    PtyLink link = new PtyLink();
    FakeStream stream = new FakeStream();
    link.attach(stream, null);

    link.keystroke("echo hi\r");
    link.keystroke("");
    link.keystroke(null);

    assertEquals(List.of("echo hi\r"), stream.sent);
  }

  @Test
  public void keystrokesBeforeTheStreamIsAttachedAreDroppedNotBuffered() {
    PtyLink link = new PtyLink();
    FakeStream stream = new FakeStream();

    link.keystroke("too early"); // no stream yet - nothing to send it to

    link.attach(stream, null);
    assertEquals(List.of(), stream.sent);
  }

  @Test
  public void outputThatArrivesBeforeTheRendererIsFlushedInOrder() {
    PtyLink link = new PtyLink();
    link.onOutput("banner");
    link.onOutput("prompt>");

    Recorder renderer = new Recorder();
    link.renderer(renderer);
    assertEquals("bannerprompt>", renderer.drawn.toString());

    link.onOutput("typed");
    assertEquals("bannerprompt>typed", renderer.drawn.toString());
  }

  @Test
  public void closingThePaneTearsDownOnceAndDropsLateTraffic() {
    PtyLink link = new PtyLink();
    FakeStream stream = new FakeStream();
    AtomicInteger ends = new AtomicInteger();
    link.attach(stream, ends::incrementAndGet);
    Recorder renderer = new Recorder();
    link.renderer(renderer);

    link.close();
    assertTrue("the socket closes with the pane", stream.closed);
    assertEquals(1, ends.get());

    link.keystroke("too late"); // dropped after teardown, never thrown
    assertEquals(List.of(), stream.sent);
    link.close(); // idempotent
    assertEquals(1, ends.get());
    link.onOutput("stray frame"); // dropped after teardown
    assertEquals("", renderer.drawn.toString());
  }

  @Test
  public void serverCloseNoticesTheUserThenTearsDownExactlyOnce() {
    PtyLink link = new PtyLink();
    FakeStream stream = new FakeStream();
    AtomicInteger ends = new AtomicInteger();
    link.attach(stream, ends::incrementAndGet);
    Recorder renderer = new Recorder();
    link.renderer(renderer);

    link.onOutput("last words");
    link.onClosed(4404, "session exited");

    assertEquals(
        "last words\n[pty stream closed (4404): session exited]\n", renderer.drawn.toString());
    assertTrue(stream.closed);
    assertEquals(1, ends.get());
    link.close(); // the pane's later dispose must not run teardown twice
    assertEquals(1, ends.get());
  }

  @Test
  public void transportErrorsSurfaceToThePaneAndEndTheLink() {
    PtyLink link = new PtyLink();
    FakeStream stream = new FakeStream();
    AtomicInteger ends = new AtomicInteger();
    link.attach(stream, ends::incrementAndGet);
    Recorder renderer = new Recorder();
    link.renderer(renderer);

    link.onError(new IllegalStateException("boom"));

    assertTrue(renderer.drawn.toString().contains("pty stream error: boom"));
    assertTrue(link.isClosed());
    assertTrue(stream.closed);
    assertEquals(1, ends.get());
  }

  @Test
  public void aConnectThatLosesTheRaceStillClosesTheStreamAndRunsTeardown() {
    PtyLink link = new PtyLink();
    link.close(); // the link ended while the connect was still in flight

    FakeStream stream = new FakeStream();
    AtomicInteger ends = new AtomicInteger();
    link.attach(stream, ends::incrementAndGet);

    assertTrue(stream.closed);
    assertEquals(1, ends.get());
  }
}
