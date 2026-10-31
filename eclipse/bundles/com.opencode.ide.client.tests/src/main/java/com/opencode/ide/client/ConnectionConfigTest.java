package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import org.junit.Test;

/** Unit tests for the {@link ConnectionConfig} record's derived helpers. */
public class ConnectionConfigTest {

  @Test
  public void noPasswordMeansNoAuth() {
    ConnectionConfig config =
        new ConnectionConfig(URI.create("http://127.0.0.1:4096"), "opencode", null);
    assertFalse(config.hasAuth());
  }

  @Test
  public void passwordEnablesAuth() {
    ConnectionConfig config =
        new ConnectionConfig(URI.create("http://127.0.0.1:4096"), null, "secret");
    assertTrue(config.hasAuth());
  }

  @Test
  public void emptyPasswordIsNoAuth() {
    ConnectionConfig config = new ConnectionConfig(URI.create("http://127.0.0.1:4096"), "u", "");
    assertFalse(config.hasAuth());
  }

  @Test
  public void displayNameShowsHostAndPort() {
    ConnectionConfig config = new ConnectionConfig(URI.create("http://127.0.0.1:4096"), null, null);
    assertEquals("127.0.0.1:4096", config.displayName());
  }

  @Test
  public void displayNameOmitsMissingPort() {
    ConnectionConfig config = new ConnectionConfig(URI.create("http://localhost"), null, null);
    assertEquals("localhost", config.displayName());
  }

  @Test
  public void trailingSlashIsNormalizedAway() {
    ConnectionConfig config =
        new ConnectionConfig(URI.create("http://127.0.0.1:4096/"), null, null);
    assertEquals("http://127.0.0.1:4096", config.baseUrl().toString());
    assertEquals("127.0.0.1:4096", config.displayName());
  }

  @Test
  public void httpsWithoutPortIsAccepted() {
    ConnectionConfig config =
        new ConnectionConfig(URI.create("https://opencode.example.com"), null, null);
    assertEquals("opencode.example.com", config.displayName());
  }

  @Test
  public void rejectsMissingScheme() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectionConfig(URI.create("//127.0.0.1:4096"), null, null));
    assertTrue(
        "message should name the input: " + e.getMessage(),
        e.getMessage().contains("//127.0.0.1:4096"));
    assertTrue(
        "message should name the expectation: " + e.getMessage(),
        e.getMessage().contains("http://host[:port]"));
  }

  @Test
  public void rejectsNonHttpScheme() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectionConfig(URI.create("ftp://127.0.0.1:4096"), null, null));
    assertTrue(
        "message should name the bad scheme: " + e.getMessage(), e.getMessage().contains("ftp"));
  }

  @Test
  public void rejectsBlankHost() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionConfig(URI.create("http://"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionConfig(URI.create("http:///"), null, null));
  }

  @Test
  public void rejectsPath() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectionConfig(URI.create("http://127.0.0.1:4096/api"), null, null));
    assertTrue("message should name the path: " + e.getMessage(), e.getMessage().contains("/api"));
  }

  @Test
  public void rejectsQuery() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionConfig(URI.create("http://127.0.0.1:4096?x=1"), null, null));
  }

  @Test
  public void rejectsFragment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConnectionConfig(URI.create("http://127.0.0.1:4096#frag"), null, null));
  }

  @Test
  public void rejectsUserinfo() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new ConnectionConfig(URI.create("http://user:pw@127.0.0.1:4096"), null, null));
    assertTrue(
        "message should name the input: " + e.getMessage(),
        e.getMessage().contains("user:pw@127.0.0.1:4096"));
  }
}
