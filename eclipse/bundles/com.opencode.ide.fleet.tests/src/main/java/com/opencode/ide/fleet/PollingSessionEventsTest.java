package com.opencode.ide.fleet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import org.junit.Test;

/**
 * Unit tests for {@link PollingSessionEvents} against the in-memory {@link FakeClient} (no HTTP):
 * idle is detected, polling continues until the status flips, and a never-idle session times out.
 */
public class PollingSessionEventsTest {

  @Test
  public void returnsTrueImmediatelyWhenTheSessionIsIdle() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null);
    client.sessionType = "idle";
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});

    assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));
  }

  @Test
  public void pollsUntilTheSessionTurnsIdle() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null);
    client.sessionType = "busy";
    PollingSessionEvents events =
        new PollingSessionEvents(client, () -> client.sessionType = "idle");

    assertTrue(events.awaitIdle("ses_1", Duration.ofSeconds(5)));
  }

  @Test
  public void timesOutToFalseWhileTheSessionStaysBusy() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null);
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});

    assertFalse(events.awaitIdle("ses_1", Duration.ofMillis(20)));
  }

  @Test
  public void defaultSleeperConstructorDetectsIdleWithoutSleeping() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null);
    client.sessionType = "idle";

    assertTrue(new PollingSessionEvents(client).awaitIdle("ses_1", Duration.ZERO));
  }

  /**
   * Regression (Milestone V, opencode 1.18.23): {@code /session/status} lists BUSY sessions only -
   * a finished session is absent from the map, and absence IS the idle signal. Requiring an
   * explicit idle entry hung every fleet run until its timeout.
   */
  @Test
  public void absentFromTheStatusMapIsIdle() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null);
    client.sessionType = "busy"; // some OTHER session is busy
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});

    assertTrue(
        "a session absent from the status map is idle",
        events.awaitIdle("ses_absent", Duration.ZERO));
  }

  @Test
  public void interruptedThreadReturnsFalseInsteadOfSpinning() throws Exception {
    FakeClient client = new FakeClient();
    client.createSession(null, null); // stays busy
    PollingSessionEvents events = new PollingSessionEvents(client, () -> {});

    Thread.currentThread().interrupt();
    long start = System.nanoTime();
    try {
      assertFalse(
          "interrupted waiter must bail out, not poll to the deadline",
          events.awaitIdle("ses_1", Duration.ofSeconds(30)));
      assertTrue(
          "bail-out must be immediate, not after the timeout",
          System.nanoTime() - start < java.time.Duration.ofSeconds(5).toNanos());
    } finally {
      // consume the restored interrupt flag so JUnit is not affected
      assertTrue(Thread.interrupted());
    }
  }
}
