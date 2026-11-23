package com.opencode.ide.client.internal;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import org.junit.Test;

/**
 * T-201: the PTY WebSocket URL shape (byte-pinned, like {@code sessionListPath}): the REST resolve
 * rule on the configured base plus the http to ws / https to wss scheme swap.
 */
public class PtyConnectUriTest {

  @Test
  public void httpBaseBecomesWsOnThePtyConnectRoute() {
    assertEquals(
        "ws://127.0.0.1:28894/api/pty/pty_2080dc362001paxhL56SKH61jq/connect",
        HttpOpencodeClient.wsUri(
                URI.create("http://127.0.0.1:28894"), "pty_2080dc362001paxhL56SKH61jq")
            .toString());
  }

  @Test
  public void httpsBaseBecomesWss() {
    assertEquals(
        "wss://opencode.example.com/api/pty/pty_1/connect",
        HttpOpencodeClient.wsUri(URI.create("https://opencode.example.com"), "pty_1").toString());
  }

  @Test
  public void aTrailingSlashDoesNotDoubleUpAndAnOddIdIsPathEncoded() {
    assertEquals(
        "ws://host:1/api/pty/pty_1/connect",
        HttpOpencodeClient.wsUri(URI.create("http://host:1/"), "pty_1").toString());
    assertEquals(
        "ws://host:1/api/pty/a%2Fb/connect",
        HttpOpencodeClient.wsUri(URI.create("http://host:1"), "a/b").toString());
  }

  @Test
  public void anAbsoluteApiPathReplacesTheBasePathLikeTheRestClientDoes() {
    assertEquals(
        "ws://host:1/api/pty/pty_9/connect",
        HttpOpencodeClient.wsUri(URI.create("http://host:1/some/base"), "pty_9").toString());
  }
}
