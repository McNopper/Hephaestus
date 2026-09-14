package com.opencode.ide.core;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeClients;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.OpencodeServerLauncher;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.core.context.ProjectContext;
import com.opencode.ide.core.internal.CoreActivator;

/**
 * Singleton facade over the opencode server connection.
 *
 * <p>Two modes, selected in {@link OpencodePreferences}:</p>
 * <ul>
 *   <li><b>connect</b> - talks to an externally-started {@code opencode serve}.</li>
 *   <li><b>spawn</b> - lazily starts a child {@code opencode serve} (owned by this
 *       facade). The working directory is taken from the {@link ProjectContext}
 *       service when available (the CDT bundle supplies it).</li>
 * </ul>
 *
 * <p>{@link #getClient()} may block (in spawn mode it waits for the server to
 * become healthy), so callers should not invoke it from the UI thread. The views
 * already call it from a background {@code Job}. {@link #refresh()} is cheap and
 * safe to call from the UI thread - it just drops the cached client so the next
 * {@link #getClient()} rebuilds/restarts.</p>
 */
public final class OpencodeConnection {

    private static final Duration SPAWN_TIMEOUT = Duration.ofSeconds(60);

    private static volatile OpencodeConnection instance;

    private OpencodeClient client;
    private ConnectionConfig currentConfig;
    private OpencodeServerLauncher launcher;
    private OpencodeEventStream eventStream;
    private final List<OpencodeEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final OpencodePreferences preferences = new OpencodePreferences();

    private OpencodeConnection() {
    }

    public static OpencodeConnection getInstance() {
        OpencodeConnection local = instance;
        if (local == null) {
            synchronized (OpencodeConnection.class) {
                local = instance;
                if (local == null) {
                    local = new OpencodeConnection();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * @return the current client, building it lazily (and starting the server in
     *         spawn mode). May block; never call from the UI thread.
     */
    public synchronized OpencodeClient getClient() throws OpencodeException {
        if (client == null) {
            rebuild();
        }
        return client;
    }

    /**
     * Drop the cached client and stop any spawned server. The next
     * {@link #getClient()} call rebuilds/restarts. Safe to call from the UI thread.
     */
    public synchronized void refresh() {
        stopEventStream();
        stopLauncher();
        client = null;
        currentConfig = null;
    }

    /**
     * @return the connection parameters backing the current client. Triggers a
     *         build (best-effort) if the client has not been created yet.
     */
    public synchronized ConnectionConfig getConnectConfig() {
        if (currentConfig == null) {
            try {
                rebuild();
            } catch (OpencodeException ignored) {
                // surfaced lazily on the next getClient()
            }
        }
        return currentConfig;
    }

    /** Connection mode currently selected by the user ({@code CONNECT}/{@code SPAWN}). */
    public String getMode() {
        return preferences.getMode();
    }

    /** @return the OS pid of the spawned server (spawn mode, running), else {@code null}. */
    public synchronized Long getSpawnedProcessId() {
        return (launcher != null && launcher.isRunning()) ? launcher.getProcessId() : null;
    }

    /**
     * Register for live opencode server events ({@code /event} SSE). Events are
     * delivered on a background thread - dispatch to the UI thread where needed.
     */
    public void addEventListener(OpencodeEventListener listener) {
        eventListeners.add(listener);
    }

    /** Unregister a previously-added event listener. */
    public void removeEventListener(OpencodeEventListener listener) {
        eventListeners.remove(listener);
    }

    /** Releases any held resources (a spawned server + the SSE stream). */
    public synchronized void dispose() {
        stopEventStream();
        stopLauncher();
        client = null;
        currentConfig = null;
    }

    private void stopLauncher() {
        if (launcher != null) {
            launcher.stop();
            launcher = null;
        }
    }

    private void rebuild() throws OpencodeException {
        stopEventStream();
        if (preferences.isConnectMode()) {
            currentConfig = preferences.toConnectConfig();
        } else {
            currentConfig = buildSpawnConfig();
        }
        client = OpencodeClients.http(currentConfig);
        startEventStream();
    }

    private synchronized void startEventStream() {
        if (currentConfig == null) {
            return;
        }
        eventStream = new OpencodeEventStream(currentConfig, this::dispatchEvent);
        eventStream.start();
    }

    private synchronized void stopEventStream() {
        if (eventStream != null) {
            eventStream.stop();
            eventStream = null;
        }
    }

    private void dispatchEvent(OpencodeEvent event) {
        for (OpencodeEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Throwable t) {
                CoreActivator.logError("opencode event listener failed", t);
            }
        }
    }

    private ConnectionConfig buildSpawnConfig() throws OpencodeException {
        if (launcher == null || !launcher.isRunning()) {
            stopLauncher();

            Path workingDirectory = null;
            ProjectContext context = CoreActivator.getProjectContext();
            if (context != null) {
                workingDirectory = context.getWorkingDirectory().orElse(null);
            }
            if (workingDirectory == null) {
                // fallback: the configured repo root (default Hephaestus) so the
                // server loads that repo's .opencode/ agents, skills and MCP config
                String configured = preferences.getWorkingDirectory();
                if (configured != null && !configured.isBlank()) {
                    Path candidate = Path.of(configured);
                    if (Files.isDirectory(candidate)) {
                        workingDirectory = candidate;
                    }
                }
            }

            launcher = new OpencodeServerLauncher(
                    preferences.getOpencodeBinary(),
                    preferences.getSpawnHostname(),
                    preferences.getSpawnPort(),
                    workingDirectory,
                    resolveSpawnPassword(preferences));
            launcher.start(SPAWN_TIMEOUT);
        }
        URI base = launcher.getBaseUrl();
        String user = preferences.getUsername();
        String password = resolveSpawnPassword(preferences);
        return new ConnectionConfig(
                base,
                (user == null || user.isEmpty()) ? "opencode" : user,
                (password == null || password.isEmpty()) ? null : password);
    }

    /**
     * P2-2: an unset password means the spawned server would run
     * unauthenticated on loopback — any local process could drive it.
     * Generate a fresh random password instead (the same discipline as the
     * fleet's {@code FleetControl.resolvePassword}).
     */
    private static String resolveSpawnPassword(OpencodePreferences preferences) {
        String configured = preferences.getPassword();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
