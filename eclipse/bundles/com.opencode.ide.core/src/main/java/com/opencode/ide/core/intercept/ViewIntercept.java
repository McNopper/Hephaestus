package com.opencode.ide.core.intercept;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * T-144: in-plugin view-content interception for GUI testing (owner test design 2026-11-16 - "test
 * by launching Eclipse minimized and intercepting the content passed to the views messages, inside
 * Eclipse").
 *
 * <p>Armed ONLY by system properties - when {@code -Dopencode.viewIntercept=true} is absent every
 * {@link #record} call returns immediately (one boolean read, no allocation, no file): production
 * runs are zero-cost. The file defaults to {@code opencode.viewInterceptFile}, else the working
 * directory's {@code view-intercept.log}.
 *
 * <p>Lines: {@code <ISO-8601> [channel] content} with embedded newlines flattened - one record per
 * line, best-effort (a failed write never disturbs the view under test; the failure is printed once
 * to stderr).
 */
public final class ViewIntercept {

  private static final boolean ON = Boolean.getBoolean("opencode.viewIntercept");
  private static volatile Path file;
  private static boolean writeFailed;

  private ViewIntercept() {}

  /** Whether interception is armed (the {@code opencode.viewIntercept} system property). */
  public static boolean isOn() {
    return ON;
  }

  /**
   * Records one view-bound piece of content. No-op unless armed; best-effort and thread-safe (one
   * atomic append per record).
   *
   * @param channel the view-data channel (e.g. {@code status}, {@code commands}, {@code goal},
   *     {@code render}, {@code assistant}, {@code session-details})
   * @param content what the view received; {@code null} renders as {@code (null)}
   */
  public static void record(String channel, String content) {
    if (!ON) {
      return;
    }
    byte[] line =
        (formatLine(Instant.now(), channel, content) + System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
    try {
      Files.write(file(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException | RuntimeException e) {
      if (!writeFailed) {
        writeFailed = true; // report once, never throw into the view
        System.err.println("[view-intercept] cannot write " + file + ": " + e);
      }
    }
  }

  /** Records a string truncated to {@code max} chars (assistant bodies etc.). */
  public static void recordTruncated(String channel, String content, int max) {
    if (!ON) {
      return;
    }
    record(channel, truncate(content, max));
  }

  /** One record line: {@code <at> [channel] content} with newlines flattened. */
  public static String formatLine(Instant at, String channel, String content) {
    return at
        + " ["
        + channel
        + "] "
        + (content == null ? "(null)" : content.replace('\n', ' ').replace('\r', ' '));
  }

  /** {@code content} shortened to {@code max} chars with a total-length tail; null-safe. */
  public static String truncate(String content, int max) {
    if (content == null || content.length() <= max) {
      return content;
    }
    return content.substring(0, max) + " …(" + content.length() + " chars total)";
  }

  private static Path file() {
    Path f = file;
    if (f == null) {
      String explicit = System.getProperty("opencode.viewInterceptFile");
      f =
          Path.of(
              explicit == null || explicit.isBlank()
                  ? System.getProperty("user.dir", ".") + "/view-intercept.log"
                  : explicit);
      file = f;
    }
    return f;
  }
}
