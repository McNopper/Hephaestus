package com.opencode.ide.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.junit.Test;

/**
 * Unit test for {@link McpRegistration} using a fake {@link OpencodeClient} and a recording {@link
 * ClientLog} sink. No HTTP, no OSGi.
 */
public class McpRegistrationTest {

  /** Records every registerMcp call; fails the first N attempts. */
  private static final class FakeOpencodeClient implements OpencodeClient {
    final List<String> names = new ArrayList<>();
    final List<McpServerConfig> configs = new ArrayList<>();
    int failuresRemaining;

    FakeOpencodeClient(int failuresRemaining) {
      this.failuresRemaining = failuresRemaining;
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) throws OpencodeException {
      if (failuresRemaining > 0) {
        failuresRemaining--;
        throw new OpencodeException("simulated registration failure");
      }
      names.add(name);
      configs.add(config);
    }

    @Override
    public HealthStatus getHealth() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Agent> getAgents() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProviderList getProviders() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ConfigInfo getConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Session> getSessions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Session createSession(String title, Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) {
      throw new UnsupportedOperationException();
    }
  }

  /** ClientLog sink that records entries for assertions. */
  private static final class RecordingLog implements ClientLog {
    final List<String> messages = new ArrayList<>();

    @Override
    public void log(Level level, String message, Throwable cause) {
      messages.add(level + " " + message + " " + cause);
    }
  }

  /** URL shaped like McpInfo.getEndpointUrl(). */
  private static final String URL = "http://127.0.0.1:4711/mcp";

  @Test
  public void registersEclipseBuildWithEndpointUrl() {
    FakeOpencodeClient client = new FakeOpencodeClient(0);
    RecordingLog log = new RecordingLog();
    McpRegistration registration = new McpRegistration(log);

    registration.registerIfNeeded(client, URL);

    assertEquals(List.of(McpRegistration.SERVER_NAME), client.names);
    assertEquals(1, client.configs.size());
    McpServerConfig config = client.configs.get(0);
    assertEquals(URL, config.url());
    assertTrue("registration must be enabled", Boolean.TRUE.equals(config.enabled()));
    assertEquals(McpServerConfig.enabled(URL), config);
    assertTrue(log.messages.isEmpty());
  }

  @Test
  public void sameClientAndUrlRegistersOnlyOnce() {
    FakeOpencodeClient client = new FakeOpencodeClient(0);
    McpRegistration registration = new McpRegistration(new RecordingLog());

    registration.registerIfNeeded(client, URL);
    registration.registerIfNeeded(client, URL);

    assertEquals(1, client.names.size());
  }

  @Test
  public void changedUrlRegistersAgain() {
    FakeOpencodeClient client = new FakeOpencodeClient(0);
    McpRegistration registration = new McpRegistration(new RecordingLog());
    String otherUrl = "http://127.0.0.1:9999/mcp";

    registration.registerIfNeeded(client, URL);
    registration.registerIfNeeded(client, otherUrl);

    assertEquals(2, client.names.size());
    assertEquals(otherUrl, client.configs.get(1).url());
  }

  @Test
  public void newClientRegistersAgain() {
    FakeOpencodeClient first = new FakeOpencodeClient(0);
    FakeOpencodeClient second = new FakeOpencodeClient(0);
    McpRegistration registration = new McpRegistration(new RecordingLog());

    registration.registerIfNeeded(first, URL);
    registration.registerIfNeeded(second, URL);

    assertEquals(1, first.names.size());
    assertEquals(1, second.names.size());
    assertSame(URL, second.configs.get(0).url());
  }

  @Test
  public void failureIsLoggedAndLaterAttemptSucceeds() {
    FakeOpencodeClient client = new FakeOpencodeClient(1);
    RecordingLog log = new RecordingLog();
    McpRegistration registration = new McpRegistration(log);

    registration.registerIfNeeded(client, URL);

    assertEquals("failed attempt must not register", 0, client.names.size());
    assertEquals(1, log.messages.size());
    assertTrue(
        "log entry must name the server: " + log.messages.get(0),
        log.messages.get(0).contains("eclipse-build"));

    registration.registerIfNeeded(client, URL);

    assertEquals("retry after failure must register", 1, client.names.size());
    assertEquals("successful retry must not log again", 1, log.messages.size());
  }

  @Test
  public void nullClientOrNullUrlIsNoop() {
    FakeOpencodeClient client = new FakeOpencodeClient(0);
    McpRegistration registration = new McpRegistration(new RecordingLog());

    registration.registerIfNeeded(null, URL);
    registration.registerIfNeeded(client, null);
    registration.registerIfNeeded(client, "");

    assertEquals(0, client.names.size());
  }
}
