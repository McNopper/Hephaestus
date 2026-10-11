package com.opencode.ide.core;

import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ProviderList;
import java.util.List;
import java.util.Objects;

/**
 * One entry of the {@link ConnectionsManager}: either the <b>primary</b> connection (a thin wrapper
 * delegating to the {@link OpencodeConnection} singleton - spawn or connect mode, unchanged
 * behavior) or one <b>remote</b> connection (connect mode, client built lazily via {@link
 * com.opencode.ide.client.OpencodeClients#http}, one optional SSE event stream used for liveness).
 *
 * <p>Instances are created by {@link ConnectionsManager}; the state-mutating methods are
 * package-private.
 */
public final class ManagedConnection {

  private static final String PRIMARY_ID = "primary";

  private final String id;
  private final String label;
  private final boolean primary;
  private final ConnectionConfig config;
  private final ConnectionsManager.PrimaryAccess primaryAccess;
  private final ConnectionsManager.ClientFactory clientFactory;
  private final ConnectionsManager.StreamFactory streamFactory;
  private final AgentsProvidersCache cache = new AgentsProvidersCache();

  private volatile OpencodeClient client;
  private volatile ConnectionsManager.EventStream stream;
  private volatile boolean connected;

  private ManagedConnection(
      String id,
      String label,
      boolean primary,
      ConnectionConfig config,
      ConnectionsManager.PrimaryAccess primaryAccess,
      ConnectionsManager.ClientFactory clientFactory,
      ConnectionsManager.StreamFactory streamFactory) {
    this.id = id;
    this.label = label;
    this.primary = primary;
    this.config = config;
    this.primaryAccess = primaryAccess;
    this.clientFactory = clientFactory;
    this.streamFactory = streamFactory;
  }

  static ManagedConnection newPrimary(ConnectionsManager.PrimaryAccess access) {
    return new ManagedConnection(
        PRIMARY_ID, "primary", true, null, Objects.requireNonNull(access), null, null);
  }

  static ManagedConnection newRemote(
      ConnectionConfig config,
      ConnectionsManager.ClientFactory clientFactory,
      ConnectionsManager.StreamFactory streamFactory) {
    Objects.requireNonNull(config, "config");
    return new ManagedConnection(
        config.baseUrl().toString(),
        config.displayName(),
        false,
        config,
        null,
        clientFactory,
        streamFactory);
  }

  /**
   * @return the stable key of this connection: the remote's URL, or {@code "primary"}.
   */
  public String id() {
    return id;
  }

  /**
   * @return a short display label (host:port for remotes).
   */
  public String label() {
    return label;
  }

  /**
   * @return {@code true} for the primary {@link OpencodeConnection} delegate.
   */
  public boolean primary() {
    return primary;
  }

  /**
   * @return the remote connection parameters, or {@code null} for the primary.
   */
  public ConnectionConfig config() {
    return config;
  }

  /**
   * @return the client of this connection, creating it lazily. For the primary this delegates to
   *     {@link OpencodeConnection#getClient()} (may block / spawn); for remotes it is a plain
   *     connect-mode client. May throw {@link com.opencode.ide.client.OpencodeException} (primary
   *     only) - never call from the UI thread for the primary.
   */
  public OpencodeClient client() throws com.opencode.ide.client.OpencodeException {
    if (primary) {
      return primaryAccess.client();
    }
    OpencodeClient local = client;
    if (local == null) {
      synchronized (this) {
        local = client;
        if (local == null) {
          local = clientFactory.create(config);
          client = local;
        }
      }
    }
    return local;
  }

  /**
   * @return whether this connection's SSE stream is currently live. For remotes this is driven by
   *     the stream's connection listener; for the primary liveness is not tracked here ({@code
   *     true}).
   */
  public boolean connected() {
    return primary || connected;
  }

  AgentsProvidersCache cache() {
    return cache;
  }

  boolean hasStream() {
    return stream != null;
  }

  /** Starts the liveness stream (idempotent); remote connections only. */
  void startStream(java.util.function.Consumer<Boolean> connectionListener) {
    if (primary) {
      return;
    }
    if (stream == null) {
      synchronized (this) {
        if (stream == null) {
          ConnectionsManager.EventStream created =
              streamFactory.create(config, up -> connectionListener.accept(up));
          stream = created;
        }
      }
    }
    stream.start();
  }

  /** Stops the liveness stream and drops it; remote connections only. */
  void stopStream() {
    ConnectionsManager.EventStream current = stream;
    stream = null;
    if (current != null) {
      current.stop();
    }
    setConnected(false);
  }

  void setConnected(boolean value) {
    connected = value;
  }

  /**
   * Small bounded cache for {@code GET /agent} and {@code GET /config/providers} per connection: a
   * 30&nbsp;second TTL, and invalidated whenever the SSE connection drops (data may be stale after
   * an outage). Exposed through {@link ConnectionsManager#agents(ManagedConnection)} / {@link
   * ConnectionsManager#providers(ManagedConnection)} so views do not re-fetch on every refresh.
   */
  static final class AgentsProvidersCache {

    private static final long TTL_MILLIS = 30_000L;
    private static final long NEVER = Long.MIN_VALUE;

    private List<Agent> agents;
    private long agentsAt = NEVER;
    private ProviderList providers;
    private long providersAt = NEVER;

    List<Agent> agents(long now) {
      return valid(agentsAt, now) ? agents : null;
    }

    void putAgents(List<Agent> value, long now) {
      agents = value;
      agentsAt = now;
    }

    ProviderList providers(long now) {
      return valid(providersAt, now) ? providers : null;
    }

    void putProviders(ProviderList value, long now) {
      providers = value;
      providersAt = now;
    }

    void invalidate() {
      agentsAt = NEVER;
      providersAt = NEVER;
    }

    private static boolean valid(long fetchedAt, long now) {
      return fetchedAt != NEVER && now - fetchedAt < TTL_MILLIS;
    }
  }
}
