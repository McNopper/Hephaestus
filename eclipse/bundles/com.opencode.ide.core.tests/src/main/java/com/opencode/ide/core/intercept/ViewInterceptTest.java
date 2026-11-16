package com.opencode.ide.core.intercept;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

/** T-144: the view-content interceptor's recorder (format, flattening, truncation, off-state). */
public class ViewInterceptTest {

  private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");

  @Test
  public void formatLineIsTimestampedAndSingleLine() {
    assertEquals(
        "2026-11-16T12:00:00Z [status] Session ses_1",
        ViewIntercept.formatLine(AT, "status", "Session ses_1"));
    assertEquals(
        "embedded newlines flatten",
        "2026-11-16T12:00:00Z [assistant] line one line two  keep",
        ViewIntercept.formatLine(AT, "assistant", "line one\nline two\r keep"));
    assertEquals("2026-11-16T12:00:00Z [goal] (null)", ViewIntercept.formatLine(AT, "goal", null));
  }

  @Test
  public void truncateKeepsShortContentAndTagsLongContent() {
    assertEquals("short", ViewIntercept.truncate("short", 400));
    assertEquals(null, ViewIntercept.truncate(null, 400));
    String longText = "x".repeat(500);
    String out = ViewIntercept.truncate(longText, 400);
    assertTrue("keeps the head", out.startsWith("x".repeat(400)));
    assertTrue("carries the total", out.endsWith("(500 chars total)"));
    assertFalse("never exceeds max + tail", out.length() > 400 + 30);
  }

  @Test
  public void isOffByDefaultAndRecordIsANoOp() {
    // the test JVM carries no -Dopencode.viewIntercept: the recorder must be inert
    assertFalse("interception is opt-in", ViewIntercept.isOn());
    ViewIntercept.record("status", "must not throw or write");
    ViewIntercept.recordTruncated("assistant", "y".repeat(1000), 400);
  }
}
