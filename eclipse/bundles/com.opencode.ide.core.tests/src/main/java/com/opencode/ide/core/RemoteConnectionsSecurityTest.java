package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for remote-connection credential handling in {@link OpencodePreferences}: passwords
 * live in the {@link RemoteCredentials} seam (in-memory fake here; production uses Equinox secure
 * storage) and never in the raw instance preference lines.
 */
public class RemoteConnectionsSecurityTest {

  private static final String URL_A = "http://127.0.0.1:4201";
  private static final String URL_B = "http://127.0.0.1:4202";

  private final FakeCredentials credentials = new FakeCredentials();
  private final OpencodePreferences prefs = new OpencodePreferences(credentials);

  @Before
  public void setUp() {
    prefs.setRemoteConnections("");
    credentials.passwords.clear();
  }

  // ---------- round-trip ----------

  @Test
  public void roundTripStoresPasswordInSeamAndUserInRaw() {
    ConnectionConfig withPassword = config(URL_A, "alice", "secret");
    prefs.setRemoteConnectionConfigs(List.of(withPassword));

    assertEquals("secret", credentials.passwords.get(URL_A));
    assertEquals(
        "instance lines keep url|user only", URL_A + "|alice", prefs.getRemoteConnections());
    assertEquals(List.of(withPassword), prefs.getRemoteConnectionConfigs());
  }

  @Test
  public void rawPreferenceNeverContainsPassword() {
    // a '|' inside the password is fine now: it goes through the seam
    prefs.setRemoteConnectionConfigs(List.of(config(URL_A, "alice", "s3cr3t|with|pipes")));

    String raw = prefs.getRemoteConnections();
    assertTrue("password must not leak into the raw preference", !raw.contains("s3cr3t"));
    assertEquals("exactly one '|' separator per line", 1, raw.split("\\|", -1).length - 1);
  }

  @Test
  public void absentPasswordLoadsAsNull() {
    prefs.setRemoteConnectionConfigs(List.of(config(URL_A, "alice", null)));

    ConnectionConfig loaded = prefs.getRemoteConnectionConfigs().get(0);
    assertNull(loaded.password());
    assertEquals("alice", loaded.username());
  }

  // ---------- migration of legacy 3-segment lines ----------

  @Test
  public void migrationMovesLegacyPasswordIntoSeamAndRewritesRaw() {
    prefs.setRemoteConnections(URL_A + "|alice|legacySecret");

    List<ConnectionConfig> loaded = prefs.getRemoteConnectionConfigs();
    assertEquals(
        "legacy password moved into the seam", "legacySecret", credentials.passwords.get(URL_A));
    assertEquals(List.of(config(URL_A, "alice", "legacySecret")), loaded);
    assertEquals(
        "instance line rewritten without the password",
        URL_A + "|alice",
        prefs.getRemoteConnections());
    assertEquals(
        "second read loads through the seam",
        "legacySecret",
        prefs.getRemoteConnectionConfigs().get(0).password());
  }

  @Test
  public void legacyEmptyThirdSegmentMigratesToNoPassword() {
    prefs.setRemoteConnections(URL_A + "|alice|");

    assertEquals(List.of(config(URL_A, "alice", null)), prefs.getRemoteConnectionConfigs());
    assertEquals(URL_A + "|alice", prefs.getRemoteConnections());
    assertNull(credentials.passwords.get(URL_A));
  }

  // ---------- secure-entry lifecycle ----------

  @Test
  public void removingConnectionDropsItsSecureEntry() {
    prefs.setRemoteConnectionConfigs(
        List.of(config(URL_A, null, "pwA"), config(URL_B, null, "pwB")));

    prefs.setRemoteConnectionConfigs(List.of(config(URL_B, null, "pwB")));

    assertNull("stale entry removed", credentials.passwords.get(URL_A));
    assertEquals("kept entry survives", "pwB", credentials.passwords.get(URL_B));
    assertEquals(URL_B, prefs.getRemoteConnections());
  }

  @Test
  public void replacingPasswordWithNullRemovesSecureEntry() {
    prefs.setRemoteConnectionConfigs(List.of(config(URL_A, "alice", "pw")));

    prefs.setRemoteConnectionConfigs(List.of(config(URL_A, "alice", null)));

    assertNull(credentials.passwords.get(URL_A));
    assertEquals(URL_A + "|alice", prefs.getRemoteConnections());
  }

  @Test
  public void clearingAllConfigsClearsSecureEntries() {
    prefs.setRemoteConnectionConfigs(
        List.of(config(URL_A, null, "pwA"), config(URL_B, null, "pwB")));

    prefs.setRemoteConnectionConfigs(List.of());

    assertTrue(credentials.passwords.isEmpty());
    assertTrue(prefs.getRemoteConnections().isEmpty());
  }

  @Test
  public void rawClearLeavesSecureEntriesUntouched() {
    prefs.setRemoteConnectionConfigs(List.of(config(URL_A, null, "pwA")));

    prefs.setRemoteConnections("");

    assertTrue(prefs.getRemoteConnections().isEmpty());
    assertEquals(
        "raw clear only wipes the instance lines (documented semantics)",
        "pwA",
        credentials.passwords.get(URL_A));
  }

  // ---------- validation ----------

  @Test
  public void parseSkipsPipeCorruptedEntriesWithWarning() {
    ClientLog previous = ClientLog.BACKEND.get();
    RecordingLog log = installRecordingLog();
    try {
      // a '|' inside the user produced 4 segments: this can never round-trip
      prefs.setRemoteConnections(URL_A + "|a|b|c");

      assertTrue(prefs.getRemoteConnectionConfigs().isEmpty());
      assertEquals(
          "nothing valid to migrate: raw stays untouched",
          URL_A + "|a|b|c",
          prefs.getRemoteConnections());
      assertTrue(
          "corrupt entries must log a warning, got: " + log.messages,
          log.messages.stream().anyMatch(m -> m.contains("skipped invalid remote connection")));
    } finally {
      ClientLog.install(previous);
    }
  }

  @Test
  public void storeRefusesEntriesThatCannotRoundTrip() {
    ClientLog previous = ClientLog.BACKEND.get();
    RecordingLog log = installRecordingLog();
    try {
      prefs.setRemoteConnectionConfigs(List.of(config(URL_A, "a|b", "pw")));
      assertTrue("user containing '|' refused", prefs.getRemoteConnections().isEmpty());
      assertNull(credentials.passwords.get(URL_A));

      prefs.setRemoteConnectionConfigs(List.of(config(URL_B, "bob", "line\nbreak")));
      assertTrue(
          "password containing a line break refused", prefs.getRemoteConnections().isEmpty());
      assertNull(credentials.passwords.get(URL_B));

      assertTrue(
          "refusals must be logged, got: " + log.messages,
          log.messages.stream().filter(m -> m.contains("refused remote connection")).count() >= 2);
    } finally {
      ClientLog.install(previous);
    }
  }

  // ---------- graceful degradation ----------

  @Test
  public void secureStorageFailuresDegradeToWarningsWithoutThrowing() {
    credentials.fail = true;
    ClientLog previous = ClientLog.BACKEND.get();
    RecordingLog log = installRecordingLog();
    try {
      // load fails -> password reads as null, the entry still loads
      prefs.setRemoteConnections(URL_A + "|alice");
      List<ConnectionConfig> loaded = prefs.getRemoteConnectionConfigs();
      assertEquals(1, loaded.size());
      assertNull(loaded.get(0).password());

      // store fails -> the user line is still written, nothing throws
      prefs.setRemoteConnectionConfigs(List.of(config(URL_B, "bob", "pw")));
      assertEquals(URL_B + "|bob", prefs.getRemoteConnections());

      // migration with a failing seam keeps the legacy line for a later retry
      prefs.setRemoteConnections(URL_A + "|alice|legacy");
      List<ConnectionConfig> migrated = prefs.getRemoteConnectionConfigs();
      assertEquals("legacy", migrated.get(0).password());
      assertTrue(
          "legacy line must stay when secure storage is unavailable",
          prefs.getRemoteConnections().contains("|legacy"));

      assertTrue("degradation must be logged, got: " + log.messages, log.messages.size() >= 2);
    } finally {
      ClientLog.install(previous);
    }
  }

  // ---------- helpers ----------

  private static ConnectionConfig config(String url, String user, String password) {
    return new ConnectionConfig(java.net.URI.create(url), user, password);
  }

  private static RecordingLog installRecordingLog() {
    RecordingLog log = new RecordingLog();
    ClientLog.install(log);
    return log;
  }

  // ---------- fakes ----------

  /** In-memory seam fake; {@code fail = true} makes every operation throw. */
  private static final class FakeCredentials implements RemoteCredentials {
    final Map<String, String> passwords = new java.util.LinkedHashMap<>();
    boolean fail;

    private void maybeFail() {
      if (fail) {
        throw new IllegalStateException("secure storage unavailable (test)");
      }
    }

    @Override
    public String loadPassword(String url) {
      maybeFail();
      return passwords.get(url);
    }

    @Override
    public void storePassword(String url, String password) {
      maybeFail();
      passwords.put(url, password);
    }

    @Override
    public void removePassword(String url) {
      maybeFail();
      passwords.remove(url);
    }

    @Override
    public void removeAll() {
      maybeFail();
      passwords.clear();
    }
  }

  /** ClientLog sink that records entries for assertions. */
  private static final class RecordingLog implements ClientLog {
    final List<String> messages = new ArrayList<>();

    @Override
    public void log(Level level, String message, Throwable cause) {
      messages.add(level + " " + message);
    }
  }
}
