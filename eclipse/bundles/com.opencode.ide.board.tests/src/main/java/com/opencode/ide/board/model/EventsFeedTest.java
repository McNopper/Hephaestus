package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.fleet.GlobalEventsAggregator.ObservedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;

/**
 * Unit tests for the SWT-free {@link EventsFeed}: row formatting ({@code HH:mm:ss · connection ·
 * type}) over remembered arrival times with the clock injected, the unknown stamp for unwitnessed
 * arrivals and the bounded arrival window's eviction, null tolerance, tooltip truncation, and the
 * liveness badge wording.
 */
public class EventsFeedTest {

  private static ObservedEvent event(
      String id, String connection, String type, String propertiesJson) {
    JsonObject properties =
        propertiesJson == null ? null : JsonParser.parseString(propertiesJson).getAsJsonObject();
    return new ObservedEvent(connection, id, type, properties);
  }

  @Test
  public void formatRendersStampConnectionType() {
    EventsFeed feed =
        new EventsFeed(Clock.fixed(Instant.ofEpochMilli(1_700_000_123_456L), ZoneOffset.UTC));
    ObservedEvent event = event("e1", "connA", "session.updated", "{\"detail\":\"d\"}");
    feed.remember(event);
    assertEquals("22:15:23 · connA · session.updated", feed.format(event));
  }

  @Test
  public void formatStampsUnwitnessedArrivalsAsUnknown() {
    EventsFeed feed = new EventsFeed(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    assertEquals(
        "--:--:-- · connA · session.idle",
        feed.format(event("never-seen", "connA", "session.idle", "{}")));
  }

  @Test
  public void formatToleratesNullEventAndFields() {
    EventsFeed feed = new EventsFeed(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    assertEquals("", feed.format(null));
    assertEquals("--:--:-- ·  · ", feed.format(event("x", null, null, null)));
    feed.remember(null);
    feed.remember(new ObservedEvent("c", null, "t", null));
  }

  @Test
  public void arrivalWindowEvictsOldestFirst() {
    EventsFeed feed = new EventsFeed(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2);
    ObservedEvent first = event("e1", "c", "t", "{}");
    ObservedEvent second = event("e2", "c", "t", "{}");
    ObservedEvent third = event("e3", "c", "t", "{}");
    feed.remember(first);
    feed.remember(second);
    feed.remember(third);
    assertTrue(
        "the eldest arrival fell out of the window", feed.format(first).startsWith("--:--:--"));
    assertFalse(feed.format(second).startsWith("--:--:--"));
    assertFalse(feed.format(third).startsWith("--:--:--"));
  }

  @Test
  public void tooltipIsThePropertiesJsonTruncated() {
    assertEquals("", EventsFeed.tooltip(null));
    assertEquals("", EventsFeed.tooltip(event("e", "c", "t", null)));
    String small = "{\"a\":\"b\"}";
    assertEquals(small, EventsFeed.tooltip(event("e", "c", "t", small)));
    String big = "{\"a\":\"" + "x".repeat(600) + "\"}";
    String tooltip = EventsFeed.tooltip(event("e", "c", "t", big));
    assertEquals(EventsFeed.TOOLTIP_MAX + 1, tooltip.length());
    assertTrue(tooltip.startsWith("{\"a\":\"xxx"));
    assertTrue(tooltip.endsWith("\u2026"));
  }

  @Test
  public void livenessFormatsCountsAndFailures() {
    assertEquals("", EventsFeed.liveness(0, 0));
    assertEquals("1 connection", EventsFeed.liveness(1, 0));
    assertEquals("3 connections", EventsFeed.liveness(3, 0));
    assertEquals("2 connections (1 failed)", EventsFeed.liveness(2, 1));
    assertEquals("0 connections (2 failed)", EventsFeed.liveness(0, 2));
  }
}
