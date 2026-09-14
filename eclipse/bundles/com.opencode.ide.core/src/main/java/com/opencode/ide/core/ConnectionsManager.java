package com.opencode.ide.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeClients;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ProviderList;

/**
 * Plural connections: the primary {@link OpencodeConnection} singleton (spawn
 * or connect mode, behavior unchanged) plus N <b>remote</b> servers built from
 * the {@link OpencodePreferences} {@code remoteConnections} list.
 *
 * <p>OSGi-agnostic POJO; the core activator registers the {@link #getDefault()}
 * instance as an OSGi service (like {@code OpencodeConnection}). The factories
 * ({@link ClientFactory}/{@link StreamFactory}) and the clock are injectable so
 * tests run without sockets.</p>
 *
 * <p>Lifecycle: each remote owns one SSE event stream used for liveness; the
 * streams are started lazily when the first manager {@link #addListener}
 * registers and stopped when a connection is removed by {@link #refresh()} or
 * on {@link #dispose()}. Observers get immutable snapshots from
 * {@link #connections()}.</p>
 */
public final class ConnectionsManager {

    /** Creates the {@link OpencodeClient} for a remote connection config. */
    @FunctionalInterface
    public interface ClientFactory {
        OpencodeClient create(ConnectionConfig config);
    }

    /** A controllable event stream ({@link OpencodeEventStream} adapted). */
    public interface EventStream {
        void start();

        void stop();

        boolean isConnected();
    }

    /** Creates the liveness stream for a remote connection config. */
    @FunctionalInterface
    public interface StreamFactory {
        EventStream create(ConnectionConfig config, Consumer<Boolean> connectionListener);
    }

    /** Access to the primary connection's client (the singleton in production). */
    @FunctionalInterface
    public interface PrimaryAccess {
        OpencodeClient client() throws com.opencode.ide.client.OpencodeException;
    }

    /** Default client factory: connect-mode HTTP clients. */
    private static final ClientFactory DEFAULT_CLIENTS = OpencodeClients::http;

    /** Default stream factory: the real /event SSE stream with a no-op event sink. */
    private static final StreamFactory DEFAULT_STREAMS = (config, connectionListener) -> {
        OpencodeEventStream real = new OpencodeEventStream(config, event -> {
            // events are not fanned out yet (later roadmap phase); liveness only
        }, connectionListener);
        return new EventStream() {
            @Override
            public void start() {
                real.start();
            }

            @Override
            public void stop() {
                real.stop();
            }

            @Override
            public boolean isConnected() {
                return real.isConnected();
            }
        };
    };

    private static final PrimaryAccess DEFAULT_PRIMARY =
            () -> OpencodeConnection.getInstance().getClient();

    private static volatile ConnectionsManager defaultInstance;

    // Nullable: null in production defers construction (InstanceScope needs the
    // platform's instance data location, which early activation cannot rely on);
    // injected instances (tests) keep eager behavior.
    private OpencodePreferences preferences;
    private final ClientFactory clientFactory;
    private final StreamFactory streamFactory;
    private final ManagedConnection primary;
    private final LongSupplier clock;

    private final List<ManagedConnection> remotes = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean streamsWanted;

    /** Production instance: real preferences, factories and primary singleton. */
    public static ConnectionsManager getDefault() {
        ConnectionsManager local = defaultInstance;
        if (local == null) {
            synchronized (ConnectionsManager.class) {
                local = defaultInstance;
                if (local == null) {
                    local = new ConnectionsManager(null,
                            DEFAULT_CLIENTS, DEFAULT_STREAMS, DEFAULT_PRIMARY,
                            System::currentTimeMillis);
                    defaultInstance = local;
                }
            }
        }
        return local;
    }

    public ConnectionsManager() {
        this(null, DEFAULT_CLIENTS, DEFAULT_STREAMS, DEFAULT_PRIMARY,
                System::currentTimeMillis);
    }

    /**
     * Full-injection constructor (test seam): fake factories and clock keep the
     * manager off the network. A {@code null} primary omits the primary entry;
     * a {@code null} {@code preferences} switches to lazy production preferences.
     */
    public ConnectionsManager(OpencodePreferences preferences, ClientFactory clientFactory,
            StreamFactory streamFactory, PrimaryAccess primary, LongSupplier clock) {
        this.preferences = preferences;
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.primary = primary != null ? ManagedConnection.newPrimary(primary) : null;
        if (preferences != null) {
            rebuild();
        }
    }

    /** Disposes the default instance only if it was ever created (never constructs eagerly). */
    public static void disposeIfCreated() {
        ConnectionsManager local = defaultInstance;
        if (local != null) {
            local.dispose();
        }
    }

    /** @return immutable snapshot: the primary first (when present), then the remotes. */
    public synchronized List<ManagedConnection> connections() {
        preferences(); // materialize production preferences (and initial rebuild) on first use
        List<ManagedConnection> all = new ArrayList<>(remotes.size() + 1);
        if (primary != null) {
            all.add(primary);
        }
        all.addAll(remotes);
        return List.copyOf(all);
    }

    /** @return the primary entry, or {@code null} for a primary-less manager. */
    public ManagedConnection primaryConnection() {
        return primary;
    }

    /** @return the remote connections parsed from the preferences (invalid entries skipped). */
    public List<ConnectionConfig> getRemoteConnections() {
        return preferences().getRemoteConnectionConfigs();
    }

    /**
     * Rebuilds the remote connections from the preferences and diffs against
     * the current set: streams of removed connections are stopped, added ones
     * are created (and started when streams are wanted). A connection whose
     * URL <b>or credentials</b> changed counts as removed+added. Listeners are
     * fired when something changed. Never performs IO; safe on the UI thread.
     */
    public synchronized void refresh() {
        rebuild();
    }

    private synchronized OpencodePreferences preferences() {
        if (preferences == null) {
            preferences = new OpencodePreferences();
            rebuild(); // the initial population the eager constructor used to perform
        }
        return preferences;
    }

    private synchronized void rebuild() {
        List<ConnectionConfig> desired = preferences().getRemoteConnectionConfigs();
        boolean changed = false;

        var iterator = remotes.iterator();
        while (iterator.hasNext()) {
            ManagedConnection connection = iterator.next();
            if (!desired.contains(connection.config())) {
                connection.stopStream();
                iterator.remove();
                changed = true;
            }
        }
        for (ConnectionConfig config : desired) {
            if (remotes.stream().noneMatch(c -> c.config().equals(config))) {
                ManagedConnection connection = ManagedConnection.newRemote(config,
                        clientFactory, streamFactory);
                if (streamsWanted) {
                    connection.startStream(up -> onConnectionStateChanged(connection, up));
                }
                remotes.add(connection);
                changed = true;
            }
        }
        if (changed) {
            fireListeners();
        }
    }

    /**
     * Registers a change listener: fired when connections are added/removed by
     * {@link #refresh()} and when any remote's connect state changes. The first
     * listener starts the remote liveness streams. Called from arbitrary
     * threads - hop to the UI thread where needed.
     */
    public void addListener(Runnable listener) {
        if (listener == null || !listeners.addIfAbsent(listener)) {
            return;
        }
        // Streams are started UNDER the monitor: start() is non-blocking (spawns
        // a daemon thread), and holding the monitor prevents a concurrent
        // rebuild() from removing a connection between the snapshot and the
        // start — which would leave an unstoppable stream behind (resurrecting
        // a removed connection's reconnect loop forever).
        synchronized (this) {
            if (streamsWanted) {
                return;
            }
            streamsWanted = true;
            for (ManagedConnection connection : remotes) {
                connection.startStream(up -> onConnectionStateChanged(connection, up));
            }
        }
    }

    /** Unregisters a listener; the liveness streams keep running. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * {@code GET /agent} for the given connection, cached with a 30s TTL and
     * invalidated when the connection's SSE stream drops.
     */
    public List<Agent> agents(ManagedConnection connection) throws com.opencode.ide.client.OpencodeException {
        requireManaged(connection);
        ManagedConnection.AgentsProvidersCache cache = connection.cache();
        long now = clock.getAsLong();
        List<Agent> cached = cache.agents(now);
        if (cached == null) {
            cached = connection.client().getAgents();
            cache.putAgents(cached == null ? List.of() : cached, clock.getAsLong());
        }
        return cached;
    }

    /**
     * {@code GET /config/providers} for the given connection, cached with a
     * 30s TTL and invalidated when the connection's SSE stream drops.
     */
    public ProviderList providers(ManagedConnection connection) throws com.opencode.ide.client.OpencodeException {
        requireManaged(connection);
        ManagedConnection.AgentsProvidersCache cache = connection.cache();
        long now = clock.getAsLong();
        ProviderList cached = cache.providers(now);
        if (cached == null) {
            cached = connection.client().getProviders();
            cache.putProviders(cached, clock.getAsLong());
        }
        return cached;
    }

    /** Stops all remote liveness streams, clears the list and resets the lifecycle state. */
    public synchronized void dispose() {
        for (ManagedConnection connection : remotes) {
            connection.stopStream();
        }
        remotes.clear();
        // reset lifecycle state so a later re-use (or a fresh getDefault() after an
        // OSGi stop/start cycle) starts streams again instead of early-returning
        streamsWanted = false;
        listeners.clear();
        if (defaultInstance == this) {
            defaultInstance = null;
        }
    }

    private synchronized void requireManaged(ManagedConnection connection) {
        if (connection == null
                || (connection != primary && !remotes.contains(connection))) {
            throw new IllegalArgumentException("connection is not managed by this manager");
        }
    }

    private void onConnectionStateChanged(ManagedConnection connection, boolean up) {
        connection.setConnected(up);
        connection.cache().invalidate(); // data may be stale after an outage
        fireListeners();
    }

    private void fireListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                ClientLog.warning("connections manager listener failed: " + e.getMessage());
            }
        }
    }
}
