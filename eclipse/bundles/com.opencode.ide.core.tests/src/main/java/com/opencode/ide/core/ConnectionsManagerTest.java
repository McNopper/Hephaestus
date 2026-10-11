package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit test for {@link ConnectionsManager} with fake factories and a fake clock: no HTTP, no
 * sockets, no OSGi services. Preferences use the real {@link OpencodePreferences} (the test
 * runtime's instance scope).
 */
public class ConnectionsManagerTest {

  private static final ConnectionConfig ONE = config("http://127.0.0.1:4101");
  private static final ConnectionConfig TWO = config("http://127.0.0.1:4102");

  private final FakeCredentials credentials = new FakeCredentials();
  private final OpencodePreferences prefs = new OpencodePreferences(credentials);
  private final AtomicLong clock = new AtomicLong(1_000_000);
  private final FakeClientFactory clients = new FakeClientFactory();
  private final FakeStreamFactory streams = new FakeStreamFactory();

  @Before
  public void setUp() {
    prefs.setRemoteConnections("");
  }

  private ConnectionsManager newManager() {
    return new ConnectionsManager(prefs, clients, streams, () -> new FakeClient(), clock::get);
  }

  // ---------- construction from preferences ----------

  @Test
  public void remotesBuiltFromPreferencesWithPrimaryFirst() {
    prefs.setRemoteConnectionConfigs(List.of(ONE, TWO));
    ConnectionsManager manager = newManager();

    List<ManagedConnection> connections = manager.connections();
    assertEquals(3, connections.size());
    ManagedConnection primary = connections.get(0);
    assertTrue(primary.primary());
    assertEquals("primary", primary.id());
    assertNull(primary.config());
    assertTrue(primary.connected());
    assertEquals(ONE.baseUrl().toString(), connections.get(1).id());
    assertEquals(TWO.baseUrl().toString(), connections.get(2).id());
    assertEquals("connections() must return equal snapshots", manager.connections(), connections);
    assertFalse(
        "remotes start disconnected until their stream reports liveness",
        connections.get(1).connected());
  }

  @Test
  public void remoteCarriesLabelAndConfig() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();

    ManagedConnection remote = manager.connections().get(1);
    assertFalse(remote.primary());
    assertEquals(ONE, remote.config());
    assertEquals("127.0.0.1:4101", remote.label());
    assertFalse(remote.connected());
  }

  @Test
  public void nullPrimaryOmitsPrimaryEntry() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = new ConnectionsManager(prefs, clients, streams, null, clock::get);

    List<ManagedConnection> connections = manager.connections();
    assertEquals(1, connections.size());
    assertFalse(connections.get(0).primary());
    assertNull(manager.primaryConnection());
  }

  @Test
  public void invalidEntriesAreSkippedWithWarning() {
    RecordingLog log = new RecordingLog();
    ClientLog previous = ClientLog.BACKEND.get();
    ClientLog.install(log);
    try {
      prefs.setRemoteConnections(
          "http://127.0.0.1:4101\n\n   \nnot-a-url\nftp://x/y\nhttp://127.0.0.1:4102");
      ConnectionsManager manager = newManager();
      assertEquals(
          "valid entries survive, invalid skipped", 2, manager.getRemoteConnections().size());
      assertTrue(
          "invalid entries must log warnings, got: " + log.messages, log.messages.size() >= 2);
    } finally {
      ClientLog.install(previous);
    }
  }

  // ---------- refresh diff ----------

  @Test
  public void refreshStopsStreamOfRemovedConnectionAndFiresListener() {
    prefs.setRemoteConnectionConfigs(List.of(ONE, TWO));
    ConnectionsManager manager = newManager();
    manager.addListener(() -> {}); // make streams wanted; both streams are created+started
    FakeStream removed = streams.lastFor(ONE.baseUrl().toString());
    assertNotNull(removed);

    AtomicInteger notifications = new AtomicInteger();
    manager.addListener(notifications::incrementAndGet);
    prefs.setRemoteConnectionConfigs(List.of(TWO));
    manager.refresh();

    assertTrue("removed connection's stream must be stopped", removed.stopped);
    assertEquals(List.of(TWO.baseUrl().toString()), remoteIds(manager));
    assertEquals("listener fired for the removal", 1, notifications.get());
  }

  @Test
  public void refreshStartsAddedConnectionOnlyWhenStreamsWanted() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();

    prefs.setRemoteConnectionConfigs(List.of(ONE, TWO));
    manager.refresh();
    assertNull(
        "no listener yet -> no stream created for the new connection",
        streams.lastFor(TWO.baseUrl().toString()));

    manager.addListener(() -> {});
    assertNotNull(
        "first listener starts streams for all remotes", streams.lastFor(ONE.baseUrl().toString()));
    assertNotNull(streams.lastFor(TWO.baseUrl().toString()));
    assertTrue(streams.lastFor(TWO.baseUrl().toString()).started);

    prefs.setRemoteConnectionConfigs(List.of(ONE));
    manager.refresh();
    assertTrue(
        "removed again -> stream stopped", streams.lastFor(TWO.baseUrl().toString()).stopped);
  }

  @Test
  public void changedCredentialsRebuildTheConnection() throws Exception {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    ManagedConnection remote = manager.connections().get(1);
    manager.agents(remote); // force the lazy client creation
    OpencodeClient firstClient = clients.created.get(0);

    ConnectionConfig changed = new ConnectionConfig(ONE.baseUrl(), "user", "pass");
    prefs.setRemoteConnectionConfigs(List.of(changed));
    manager.refresh();

    assertEquals(List.of(changed.baseUrl().toString()), remoteIds(manager));
    manager.agents(manager.connections().get(1)); // force the rebuilt connection's lazy client
    OpencodeClient secondClient = clients.created.get(1);
    assertFalse("credential change must rebuild the client", firstClient == secondClient);
  }

  @Test
  public void refreshWithoutChangeDoesNotFireListeners() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    AtomicInteger notifications = new AtomicInteger();
    manager.addListener(notifications::incrementAndGet);

    manager.refresh();

    assertEquals("no diff -> no notification", 0, notifications.get());
  }

  // ---------- streams + liveness ----------

  @Test
  public void connectStateChangeInvalidatesCacheAndFiresListener() throws Exception {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    AtomicInteger notifications = new AtomicInteger();
    manager.addListener(notifications::incrementAndGet);
    ManagedConnection remote = manager.connections().get(1);

    manager.agents(remote); // force the lazy client creation
    FakeClient client = clients.created.get(0);
    assertEquals(1, client.agentsCalls.get());

    FakeStream stream = streams.lastFor(ONE.baseUrl().toString());
    stream.simulate(true);
    assertTrue(remote.connected());
    stream.simulate(false);
    assertFalse(remote.connected());

    manager.agents(remote);
    assertEquals("cache must be invalidated on the disconnect", 2, client.agentsCalls.get());
    assertTrue("connect-state changes must fire listeners", notifications.get() >= 2);
  }

  @Test
  public void removeListenerKeepsStreamsRunning() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    Runnable listener = () -> {};
    manager.addListener(listener);
    FakeStream stream = streams.lastFor(ONE.baseUrl().toString());

    manager.removeListener(listener);

    assertNotNull(stream);
    assertFalse("streams keep running after the last listener is removed", stream.stopped);
  }

  @Test
  public void disposeStopsAllStreams() {
    prefs.setRemoteConnectionConfigs(List.of(ONE, TWO));
    ConnectionsManager manager = newManager();
    manager.addListener(() -> {});
    List<FakeStream> all = new ArrayList<>(streams.streamsByUrl.values());
    assertEquals(2, all.size());

    manager.dispose();

    assertTrue("dispose drops all remotes", remoteIds(manager).isEmpty());
    for (FakeStream stream : all) {
      assertTrue("dispose must stop every stream", stream.stopped);
    }
  }

  // ---------- agents/providers cache ----------

  @Test
  public void agentsAndProvidersCachedWithinTtl() throws Exception {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    ManagedConnection remote = manager.connections().get(1);

    manager.agents(remote); // force the lazy client creation
    FakeClient client = clients.created.get(0);

    manager.agents(remote);
    assertEquals("second call within TTL must not re-fetch", 1, client.agentsCalls.get());

    ProviderList first = manager.providers(remote);
    manager.providers(remote);
    assertEquals(1, client.providersCalls.get());
    assertSame(first, manager.providers(remote));
  }

  @Test
  public void agentsRefetchedAfterTtl() throws Exception {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    ManagedConnection remote = manager.connections().get(1);

    manager.agents(remote); // force the lazy client creation
    FakeClient client = clients.created.get(0);

    clock.addAndGet(30_001);
    manager.agents(remote);

    assertEquals("after the TTL the cache must re-fetch", 2, client.agentsCalls.get());
  }

  @Test
  public void primaryDelegatesToPrimaryAccess() throws Exception {
    FakeClient primaryClient = new FakeClient();
    ConnectionsManager manager =
        new ConnectionsManager(prefs, clients, streams, () -> primaryClient, clock::get);
    ManagedConnection primary = manager.primaryConnection();

    assertSame(primaryClient, primary.client());
    assertTrue(primary.primary());
    assertTrue(primary.connected());
    assertEquals(List.of(), manager.agents(primary));
    assertEquals("primary agents also run through the cache", 1, primaryClient.agentsCalls.get());
  }

  // ---------- helpers ----------

  private static List<String> remoteIds(ConnectionsManager manager) {
    return manager.connections().stream()
        .filter(c -> !c.primary())
        .map(ManagedConnection::id)
        .toList();
  }

  private static ConnectionConfig config(String url) {
    return new ConnectionConfig(java.net.URI.create(url), null, null);
  }

  // ---------- lifecycle regression tests (stream resurrection + dispose reset) ----------

  @Test
  public void addListenerAfterRemovalStartsNoStream() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();

    // the connection is removed BEFORE any listener ever started streams
    prefs.setRemoteConnectionConfigs(List.of());
    manager.refresh();
    assertTrue(manager.connections().stream().noneMatch(c -> !c.primary()));

    // must not resurrect a stream for the removed connection (and not throw)
    manager.addListener(() -> {});
    assertTrue(manager.connections().stream().noneMatch(c -> !c.primary()));
  }

  @Test
  public void disposeResetsLifecycleSoStreamsRestartOnReAdd() {
    prefs.setRemoteConnectionConfigs(List.of(ONE));
    ConnectionsManager manager = newManager();
    manager.addListener(() -> {});
    FakeStream first = streams.lastFor(ONE.baseUrl().toString());
    assertTrue(first.started);

    manager.dispose();

    prefs.setRemoteConnectionConfigs(List.of(TWO));
    manager.refresh();
    // streamsWanted was reset by dispose: the next listener must start the new stream
    manager.addListener(() -> {});
    FakeStream second = streams.lastFor(TWO.baseUrl().toString());
    assertTrue("stream must start again after dispose reset the lifecycle state", second.started);
  }

  // ---------- fakes ----------

  private static final class FakeClient implements OpencodeClient {
    final AtomicInteger agentsCalls = new AtomicInteger();
    final AtomicInteger providersCalls = new AtomicInteger();

    @Override
    public HealthStatus getHealth() {
      return new HealthStatus(true, "test");
    }

    @Override
    public List<Agent> getAgents() {
      agentsCalls.incrementAndGet();
      return List.of();
    }

    @Override
    public ProviderList getProviders() {
      providersCalls.incrementAndGet();
      return new ProviderList(List.of(), Map.of());
    }

    @Override
    public ConfigInfo getConfig() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Session> getSessions() {
      return List.of();
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
      return Map.of();
    }

    @Override
    public Session createSession(String title, Path directory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) {
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

  private static final class FakeClientFactory implements ConnectionsManager.ClientFactory {
    final List<FakeClient> created = new ArrayList<>();

    @Override
    public OpencodeClient create(ConnectionConfig config) {
      FakeClient client = new FakeClient();
      created.add(client);
      return client;
    }
  }

  private static final class FakeStream implements ConnectionsManager.EventStream {
    final Consumer<Boolean> listener;
    boolean started;
    boolean stopped;

    FakeStream(Consumer<Boolean> listener) {
      this.listener = listener;
    }

    @Override
    public void start() {
      started = true;
    }

    @Override
    public void stop() {
      stopped = true;
    }

    @Override
    public boolean isConnected() {
      return started && !stopped;
    }

    void simulate(boolean up) {
      listener.accept(up);
    }
  }

  private static final class FakeStreamFactory implements ConnectionsManager.StreamFactory {
    final Map<String, FakeStream> streamsByUrl = new java.util.LinkedHashMap<>();

    @Override
    public FakeStream create(ConnectionConfig config, Consumer<Boolean> connectionListener) {
      FakeStream stream = new FakeStream(connectionListener);
      streamsByUrl.put(config.baseUrl().toString(), stream);
      return stream;
    }

    FakeStream lastFor(String url) {
      return streamsByUrl.get(url);
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

  /** In-memory {@link RemoteCredentials} so these tests never touch secure storage. */
  private static final class FakeCredentials implements RemoteCredentials {
    final Map<String, String> passwords = new java.util.LinkedHashMap<>();

    @Override
    public String loadPassword(String url) {
      return passwords.get(url);
    }

    @Override
    public void storePassword(String url, String password) {
      passwords.put(url, password);
    }

    @Override
    public void removePassword(String url) {
      passwords.remove(url);
    }

    @Override
    public void removeAll() {
      passwords.clear();
    }
  }
}
