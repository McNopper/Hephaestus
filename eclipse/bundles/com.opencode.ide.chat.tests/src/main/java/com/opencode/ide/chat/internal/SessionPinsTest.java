package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for the CP-05 (FR-CP-05 AC2) pin marker - the host-side set the in-chat switcher lists
 * first. The live v2 API has no pin verb/field (probed 2026-11-17), so the marker is a pure client
 * notion: parse/format round-trip, toggle semantics and the null/blank edges.
 */
public class SessionPinsTest {

  @Test
  public void parseToleratesNullBlankAndWhitespace() {
    assertTrue(SessionPins.parse(null).isEmpty());
    assertTrue(SessionPins.parse("").isEmpty());
    assertTrue(SessionPins.parse("  ").isEmpty());
    assertTrue(SessionPins.parse("a,, ,b").toString().contains("a"));
  }

  @Test
  public void roundTripKeepsTheSameIdsAndFormatIsStable() {
    SessionPins.parse("ses_2,ses_1");
    assertEquals("ses_1,ses_2", SessionPins.format(java.util.Set.of("ses_2", "ses_1")));
    assertEquals("", SessionPins.format(java.util.Set.of()));
    assertEquals("", SessionPins.format(null));
  }

  @Test
  public void pinAddsUnpinRemovesAndBothAreIdempotent() {
    Set<String> pinned = SessionPins.with(Set.of(), "ses_1", true);
    assertTrue(SessionPins.contains(pinned, "ses_1"));
    assertEquals(Set.of("ses_1"), SessionPins.with(pinned, "ses_1", true));

    Set<String> unpinned = SessionPins.with(pinned, "ses_1", false);
    assertFalse(SessionPins.contains(unpinned, "ses_1"));
    assertTrue(SessionPins.with(unpinned, "ses_1", false).isEmpty());
  }

  @Test
  public void blankOrNullIdsNeverEnterTheSet() {
    Set<String> pinned = SessionPins.with(Set.of(), null, true);
    assertTrue(pinned.isEmpty());
    assertTrue(SessionPins.with(Set.of(), "  ", true).isEmpty());
    assertFalse(SessionPins.contains(null, "ses_1"));
    assertFalse(SessionPins.contains(Set.of("ses_1"), null));
  }
}
