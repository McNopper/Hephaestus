package com.opencode.ide.core;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeClients;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.OpencodeServerLauncher;
import com.opencode.ide.client.OpencodeServiceDiscovery;
import com.opencode.ide.client.ServerVersionPin;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.core.context.ProjectContext;
import com.opencode.ide.core.internal.CoreActivator;

/**
 * Singleton facade over the opencode server connection.
 *
 * <p>Two modes, selected in {@link OpencodePreferences}:</p>
 * <ul>
 *   <li><b>connect</b> - talks to an externally-started {@code opencode serve}.</li>
 *   <li><b>spawn</b> - the local/primary connection. With
 *       {@link OpencodePreferences#isAttachSharedService()} on (the v2-native
 *       default) it first attaches to the user's shared opencode background
 *       service (starting it with {@code opencode serve --service} when
 *       absent) and only falls back to lazily spawning a private child
 *       {@code opencode serve} (owned by this facade) when the service cannot
 *       be discovered or started. The spawned server's working directory is
 *       taken from the {@link ProjectContext} service when available (the CDT
 *       bundle supplies it).</li>
 * </ul>
 *
 * <p><b>v2 isolation reality (shared-service attach):</b> session STATE is
 * global per user (one DB — every server sees all sessions; the Server view
 * scopes by directory, handled separately), while the event stream is
 * per-server-process. Attaching to the shared service therefore means sessions
 * created by ANY opencode client are visible here, and this connection's SSE
 * stream carries everything the shared service processes — not just
 * Eclipse-driven activity (a privately spawned server streams only what this
 * Eclipse instance drives). Unlike a spawned server, an attached service is
 * never stopped by {@link #refresh()}/{@link #dispose()}: it is a per-user
 * background process owned by opencode itself.</p>
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
    // Password handed to the spawned server - the SAME value must be reused for
    // the client config; resolveSpawnPassword() generates a fresh random value
    // on every call, so calling it twice hands server and client DIFFERENT
    // passwords and every request fails with HTTP 401.
    private String spawnPassword;
    /** The working directory the spawn config last resolved to (null = unknown/inherited). */
    private volatile java.nio.file.Path lastWorkingDirectory;
    private OpencodeEventStream eventStream;
    private final List<OpencodeEventListener> eventListeners = new CopyOnWriteArrayList<>();
    // Lazy: constructing OpencodePreferences touches InstanceScope, which needs the
    // platform's instance data location. That is not yet available when early OSGi
    // class loading activates this bundle before the workbench is up.
    private OpencodePreferences preferences;

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
        return preferences().getMode();
    }

    /** Disposes the singleton only if it was ever created (never constructs eagerly). */
    public static void disposeIfCreated() {
        OpencodeConnection local = instance;
        if (local != null) {
            local.dispose();
        }
    }

    /** Refreshes the singleton only if it was ever created (never constructs eagerly). */
    public static void refreshIfCreated() {
        OpencodeConnection local = instance;
        if (local != null) {
            local.refresh();
        }
    }

    private OpencodePreferences preferences() {
        OpencodePreferences local = preferences;
        if (local == null) {
            synchronized (this) {
                local = preferences;
                if (local == null) {
                    local = new OpencodePreferences();
                    preferences = local;
                }
            }
        }
        return local;
    }

    /** @return the OS pid of the spawned server (spawn mode, running), else {@code null}. */
    public synchronized Long getSpawnedProcessId() {
        return (launcher != null && launcher.isRunning()) ? launcher.getProcessId() : null;
    }

    /**
     * @return the working directory the last spawn config resolved to, or
     *         {@code null} when unknown. v2 serves sessions for the whole user
     *         from every server, so views scope their session lists to this
     *         directory via {@code getSessions(directory)}.
     */
    public String getWorkingDirectory() {
        java.nio.file.Path dir = lastWorkingDirectory;
        return dir == null ? null : dir.toString();
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
        spawnPassword = null;
    }

    private void rebuild() throws OpencodeException {
        stopEventStream();
        OpencodePreferences preferences = preferences();
        if (preferences.isConnectMode()) {
            currentConfig = preferences.toConnectConfig();
        } else {
            currentConfig = buildLocalConfig(preferences);
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

    /**
     * The local/primary connection (mode {@code SPAWN}): v2-native
     * shared-service attach first, spawn as the fallback. This is where the
     * mode is chosen — the v2 isolation reality from the class javadoc applies:
     * session STATE is shared per user, the event stream is per-process, and an
     * attached service is never stopped by this facade.
     */
    private ConnectionConfig buildLocalConfig(OpencodePreferences preferences) throws OpencodeException {
        // the working directory scopes the Server view's session list and the
        // fs/vcs probes - it must be resolved for BOTH outcomes, or an attached
        // connection would show every session on the machine and browse $HOME
        lastWorkingDirectory = resolveWorkingDirectory(preferences);
        return selectLocalConfig(preferences,
                () -> tryAttachSharedService(newServiceDiscovery(preferences), SPAWN_TIMEOUT),
                this::buildSpawnConfig);
    }

    /** The spawn working directory: project context, then the configured repo root, then the workspace. */
    private Path resolveWorkingDirectory(OpencodePreferences preferences) {
        Path workingDirectory = null;
        ProjectContext context = CoreActivator.getProjectContext();
        if (context != null) {
            workingDirectory = context.getWorkingDirectory().orElse(null);
        }
        if (workingDirectory == null) {
            // fallback 1: the configured repo root (default Hephaestus) so the
            // server loads that repo's .opencode/ agents, skills and MCP config
            String configured = preferences.getWorkingDirectory();
            if (configured != null && !configured.isBlank()) {
                Path candidate = Path.of(configured);
                if (Files.isDirectory(candidate)) {
                    workingDirectory = candidate;
                }
            }
        }
        if (workingDirectory == null) {
            // fallback 2 (O-001): adopt an open workspace project that lives
            // in an opencode repo - opening the repo's projects in Eclipse then
            // behaves like opening the repo itself. Without this, a null
            // directory makes the child inherit Eclipse's own working
            // directory (the install folder), which carries no repo config.
            workingDirectory = workspaceRepoRoot();
        }
        if (workingDirectory != null) {
            // O-001 parity rule: a nested project folder resolves to its repo
            // root so the server sees .opencode/ agents+skills and the
            // opencode.json MCP servers
            workingDirectory = repoRootOf(workingDirectory);
        }
        return workingDirectory;
    }

    /**
     * The local-connection decision (public test seam — OSGi split-package
     * rules hide package-private members from the sibling test bundle): when
     * {@link OpencodePreferences#isAttachSharedService()} is on, the attach
     * attempt runs first and wins when it produces a config; otherwise (off, or
     * no healthy service) the spawn fallback runs — the user is never left
     * without a connection.
     */
    public static ConnectionConfig selectLocalConfig(OpencodePreferences preferences,
            ConfigAttempt attachAttempt, ConfigAttempt spawnFallback) throws OpencodeException {
        if (preferences.isAttachSharedService()) {
            ConnectionConfig attached;
            try {
                attached = attachAttempt.get();
            } catch (RuntimeException | OpencodeException e) {
                // belt and braces: tryAttachSharedService already catches
                // everything, but an attach failure must never leave the user
                // without a connection — the spawn fallback always exists.
                ClientLog.warning("[opencode service] attach attempt failed: " + e.getMessage()
                        + " - falling back to a private 'opencode serve'");
                attached = null;
            }
            if (attached != null) {
                return attached;
            }
        }
        return spawnFallback.get();
    }

    /** One local-connection attempt that may surface a connection error. */
    @FunctionalInterface
    public interface ConfigAttempt {
        ConnectionConfig get() throws OpencodeException;
    }

    /**
     * One service-attach attempt: discover (or start) the shared background
     * service and turn its endpoint into a {@link ConnectionConfig}. The
     * captured {@code HealthStatus} flows to {@link ServerVersionPin#evaluate}
     * exactly as the spawn path's does (H-002 — a warning, never a failed
     * attach).
     *
     * @return the attached config, or {@code null} when the service is
     *         unavailable — the caller then falls back to the spawn path (the
     *         concrete reason was already logged by the discovery's
     *         {@code ensure}).
     */
    public static ConnectionConfig tryAttachSharedService(OpencodeServiceDiscovery discovery, Duration timeout) {
        Optional<OpencodeServiceDiscovery.DiscoveredService> service;
        try {
            service = discovery.ensure(timeout);
        } catch (RuntimeException e) {
            ClientLog.warning("[opencode service] discovery failed unexpectedly: " + e
                    + " - falling back to a private 'opencode serve'");
            return null;
        }
        if (service.isEmpty()) {
            return null;
        }
        OpencodeServiceDiscovery.DiscoveredService found = service.get();
        String warning = ServerVersionPin.evaluate(found.health()).warning();
        if (warning != null) {
            ClientLog.warning(warning);
        }
        ClientLog.info("[opencode service] attached to the shared background service at "
                + found.baseUrl());
        return found.toConnectionConfig();
    }

    /** Production discovery: the configured opencode binary drives the CLI fallbacks. */
    private static OpencodeServiceDiscovery newServiceDiscovery(OpencodePreferences preferences) {
        return OpencodeServiceDiscovery.create(preferences.getOpencodeBinary());
    }

    private ConnectionConfig buildSpawnConfig() throws OpencodeException {
        OpencodePreferences preferences = preferences();
        if (launcher == null || !launcher.isRunning()) {
            stopLauncher();

            // one resolution ladder (resolveWorkingDirectory) - this block used
            // to duplicate its 29 lines here (CPD finding 2026-09-23)
            Path workingDirectory = resolveWorkingDirectory(preferences);
            if (workingDirectory != null) {
                ClientLog.info("[opencode serve] working directory: " + workingDirectory);
            }
            lastWorkingDirectory = workingDirectory;

            spawnPassword = resolveSpawnPassword(preferences);
            launcher = new OpencodeServerLauncher(
                    preferences.getOpencodeBinary(),
                    preferences.getSpawnHostname(),
                    preferences.getSpawnPort(),
                    workingDirectory,
                    spawnPassword);
            launcher.start(SPAWN_TIMEOUT);
        }
        URI base = launcher.getBaseUrl();
        String user = preferences.getUsername();
        String password = launcher.isRunning() ? spawnPassword : resolveSpawnPassword(preferences);
        return new ConnectionConfig(
                base,
                (user == null || user.isEmpty()) ? "opencode" : user,
                (password == null || password.isEmpty()) ? null : password);
    }

    /**
     * O-001 fallback: the first open workspace project whose location lies in
     * an opencode repo (nearest ancestor with a repo marker). Deterministic
     * order: project name. Null when the resources plugin is unavailable (tests,
     * non-workbench hosts) or no project qualifies.
     */
    static Path workspaceRepoRoot() {
        try {
            var resources = org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
            var projects = resources.getRoot().getProjects();
            Path best = null;
            for (var project : projects) {
                var location = project.getLocation();
                if (location == null) {
                    continue;
                }
                Path repo = repoRootOf(location.toPath());
                if (Files.isDirectory(repo.resolve(".opencode"))
                        || Files.isRegularFile(repo.resolve("opencode.json"))) {
                    if (best == null || repo.toString().compareTo(best.toString()) < 0) {
                        best = repo;
                    }
                }
            }
            return best;
        } catch (LinkageError | RuntimeException e) {
            // no resources plugin / no workbench - preference and context paths remain
            return null;
        }
    }

    /**
     * O-001 repo-marker predicate: the one shared definition of "this
     * directory is an opencode repo" ({@code .opencode} dir or
     * {@code opencode.json}). Every climb - server spawn AND the board's
     * store adoption - must agree on the marker set or the two drift apart.
     */
    public static boolean isRepoMarker(Path dir) {
        return dir != null
                && (Files.isDirectory(dir.resolve(".opencode"))
                        || Files.isRegularFile(dir.resolve("opencode.json")));
    }

    /**
     * O-001 "same folder level" rule: given a working directory (typically the
     * active project's folder inside a repo), walk up to the nearest ancestor
     * that carries an opencode repo marker - a {@code .opencode} directory or an
     * {@code opencode.json} - so a server spawned for a nested project behaves
     * exactly like one started in the repo root. Returns the input unchanged
     * when no ancestor qualifies.
     */
    public static Path repoRootOf(Path candidate) {
        Path current = candidate.toAbsolutePath().normalize();
        while (current != null) {
            if (isRepoMarker(current)) {
                return current;
            }
            current = current.getParent();
        }
        return candidate;
    }

    /**
     * P2-2: an unset password means the spawned server would run
     * unauthenticated on loopback — any local process could drive it.
     * Generate a fresh random password instead (the same discipline as the
     * fleet's {@code FleetControl.resolvePassword}).
     */
    /** One shared generator - seeding a SecureRandom per password is costly and a lint/bug pattern. */
    private static final java.security.SecureRandom SPAWN_RANDOM = new java.security.SecureRandom();

    private static String resolveSpawnPassword(OpencodePreferences preferences) {
        String configured = preferences.getPassword();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }        byte[] bytes = new byte[32];
        SPAWN_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
