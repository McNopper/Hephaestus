package com.opencode.ide.client.internal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * U-066 URL-shape contract for the session list: the plain {@code directory} parameter (the bracket
 * form {@code location[directory]} is silently ignored server-side - live-probed on 2026-10-11,
 * service 2.0.21), backslash-escaped per {@link java.net.URLEncoder} rules, the optional {@code
 * limit} (service default page = 50; the machine-wide probe returned all 211 sessions at 500), and
 * byte-identical preservation of the historical no-limit form.
 */
public class SessionListPathTest {

  @Test
  public void directoryOnlyKeepsTheHistoricalShape() {
    assertEquals(
        "/session?directory=C%3A%5CDev%20Box",
        HttpOpencodeClient.sessionListPath("C:\\Dev Box", 0));
  }

  @Test
  public void limitOnlyAppendsTheParameter() {
    assertEquals("/session?limit=500", HttpOpencodeClient.sessionListPath(null, 500));
  }

  @Test
  public void directoryAndLimitCombine() {
    assertEquals(
        "/session?directory=D%3A%5Crepo&limit=10",
        HttpOpencodeClient.sessionListPath("D:\\repo", 10));
  }

  @Test
  public void blankDirectoryIsTreatedAsMissing() {
    assertEquals("/session?limit=10", HttpOpencodeClient.sessionListPath("  ", 10));
    assertEquals("/session", HttpOpencodeClient.sessionListPath("", 0));
  }

  @Test
  public void nonPositiveLimitNeverAppendsTheParameter() {
    assertEquals("/session", HttpOpencodeClient.sessionListPath(null, 0));
    assertEquals("/session", HttpOpencodeClient.sessionListPath(null, -1));
    assertEquals("/session?directory=C%3A%5Cr", HttpOpencodeClient.sessionListPath("C:\\r", -5));
  }
}
