package com.opencode.ide.core;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;

/**
 * Public facade over the core connection preferences (node {@value #NODE_ID}).
 *
 * <p>This is the only place the connection settings are read or written; both
 * the core {@link OpencodeConnection} and the UI preference page use it.</p>
 *
 * <p>Storage split (documented decision): the PRIMARY connection's password
 * ({@code connect} mode) stays in plain instance preferences — that server is
 * spawned locally by the plugin, so there is no remote secret to protect.
 * REMOTE-connection passwords go through the {@link RemoteCredentials} seam
 * into Equinox secure storage; instance preferences keep only
 * {@code url[|user]} lines. Legacy {@code url[|user|password]} lines still
 * present on disk are migrated into secure storage on the first
 * {@link #getRemoteConnectionConfigs()} read.</p>
 */
public final class OpencodePreferences {

    public static final String NODE_ID = "com.opencode.ide.core";

    public static final String KEY_MODE = "mode";
    public static final String KEY_SERVER_URL = "serverUrl";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_OPENCODE_BINARY = "opencodeBinary";
    public static final String KEY_SPAWN_HOSTNAME = "spawnHostname";
    public static final String KEY_SPAWN_PORT = "spawnPort";
    public static final String KEY_ADVERTISE_RENDERING = "advertiseRendering";
    public static final String KEY_REMOTE_CONNECTIONS = "remoteConnections";
    public static final String KEY_DEFAULT_MODEL = "defaultModel";
    public static final String KEY_DEFAULT_VARIANT = "defaultVariant";
    public static final String KEY_TASKS_ROOT = "tasksRoot";
    public static final String KEY_TASKS_PROJECT = "tasksProject";
    public static final String KEY_WORKING_DIRECTORY = "workingDirectory";

    public static final String MODE_CONNECT = "CONNECT";
    public static final String MODE_SPAWN = "SPAWN";

    private static final String DEFAULT_URL = "http://127.0.0.1:4096";
    private static final String DEFAULT_HOST = "127.0.0.1";
    /** Preferred chat model for this machine ({@code provider/model}); validated against the live provider list. */
    private static final String DEFAULT_MODEL = "zai-coding-plan/glm-5.3";
    private static final String DEFAULT_VARIANT = "max";
    /**
     * Default task store: derived from the workspace at first use (P1-4 fix —
     * no developer-machine hard-coded path). The Board view walks up from
     * the workspace root looking for {@code .opencode/tasks}; if none is
     * found the user must configure it via preferences.
     */
    private static final String DEFAULT_TASKS_ROOT = "";
    private static final String DEFAULT_TASKS_PROJECT = "hephaestus";
    /**
     * Spawn working directory fallback: empty means "derive from the
     * workspace" (P1-4). The active CDT project's directory (ProjectContext)
     * still wins when available.
     */
    private static final String DEFAULT_WORKING_DIRECTORY = "";

    private final IEclipsePreferences prefs;
    private final RemoteCredentials credentials;

    /** Production constructor: passwords go to Equinox secure storage. */
    public OpencodePreferences() {
        this(defaultCredentials());
    }

    /**
     * Injection constructor for tests (or alternative hosts): supply an
     * in-memory fake {@link RemoteCredentials} so no OSGi / secure storage
     * is touched.
     */
    public OpencodePreferences(RemoteCredentials credentials) {
        this.prefs = InstanceScope.INSTANCE.getNode(NODE_ID);
        this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
    }

    private static RemoteCredentials defaultCredentials() {
        try {
            return new SecureRemoteCredentials();
        } catch (RuntimeException | LinkageError e) {
            ClientLog.warning("secure storage unavailable; remote-connection passwords will not persist: " + e);
            return new RemoteCredentials() {
                @Override
                public String loadPassword(String url) {
                    return null;
                }

                @Override
                public void storePassword(String url, String password) {
                    // no-op: secure storage unavailable
                }

                @Override
                public void removePassword(String url) {
                    // no-op: secure storage unavailable
                }

                @Override
                public void removeAll() {
                    // no-op: secure storage unavailable
                }
            };
        }
    }

    public IEclipsePreferences raw() {
        return prefs;
    }

    public String getMode() {
        return prefs.get(KEY_MODE, MODE_SPAWN);
    }

    public void setMode(String mode) {
        prefs.put(KEY_MODE, mode);
    }

    public boolean isConnectMode() {
        return MODE_CONNECT.equals(getMode());
    }

    public String getServerUrl() {
        return prefs.get(KEY_SERVER_URL, DEFAULT_URL);
    }

    public void setServerUrl(String url) {
        prefs.put(KEY_SERVER_URL, url);
    }

    public String getUsername() {
        return prefs.get(KEY_USERNAME, "opencode");
    }

    public void setUsername(String username) {
        if (username == null || username.isEmpty()) {
            prefs.remove(KEY_USERNAME);
        } else {
            prefs.put(KEY_USERNAME, username);
        }
    }

    public String getPassword() {
        return prefs.get(KEY_PASSWORD, "");
    }

    public void setPassword(String password) {
        if (password == null || password.isEmpty()) {
            prefs.remove(KEY_PASSWORD);
        } else {
            prefs.put(KEY_PASSWORD, password);
        }
    }

    public String getOpencodeBinary() {
        return prefs.get(KEY_OPENCODE_BINARY, "");
    }

    public void setOpencodeBinary(String path) {
        prefs.put(KEY_OPENCODE_BINARY, path == null ? "" : path);
    }

    public String getSpawnHostname() {
        return prefs.get(KEY_SPAWN_HOSTNAME, DEFAULT_HOST);
    }

    public void setSpawnHostname(String hostname) {
        prefs.put(KEY_SPAWN_HOSTNAME, hostname);
    }

    public int getSpawnPort() {
        return prefs.getInt(KEY_SPAWN_PORT, 0);
    }

    public void setSpawnPort(int port) {
        prefs.putInt(KEY_SPAWN_PORT, port);
    }

    /**
     * Whether every chat message advertises what the chat view can render
     * (markdown, KaTeX math, mermaid, highlighted code) via the request's
     * {@code system} field. On by default: without it models answer in plain
     * terminal style and math/diagrams have to be requested manually.
     */
    public boolean isAdvertiseRendering() {
        return prefs.getBoolean(KEY_ADVERTISE_RENDERING, true);
    }

    public void setAdvertiseRendering(boolean enabled) {
        prefs.putBoolean(KEY_ADVERTISE_RENDERING, enabled);
    }

    // ---------- chat defaults ----------

    /**
     * Preferred default chat model as {@code provider/model} (validated against
     * the live provider list by the views; invalid values fall back to the
     * server default).
     */
    public String getDefaultModel() {
        return prefs.get(KEY_DEFAULT_MODEL, DEFAULT_MODEL);
    }

    public void setDefaultModel(String providerSlashModel) {
        prefs.put(KEY_DEFAULT_MODEL, providerSlashModel == null ? "" : providerSlashModel.trim());
    }

    /** Preferred reasoning variant for the default model (e.g. {@code max}); empty = model default. */
    public String getDefaultVariant() {
        return prefs.get(KEY_DEFAULT_VARIANT, DEFAULT_VARIANT);
    }

    public void setDefaultVariant(String variant) {
        prefs.put(KEY_DEFAULT_VARIANT, variant == null ? "" : variant.trim());
    }

    /** @return {@link #getDefaultModel()} split into {@code [provider, model]}, or {@code null} when blank/malformed. */
    public String[] getDefaultModelParts() {
        String raw = getDefaultModel();
        if (raw == null) {
            return null;
        }
        int slash = raw.indexOf('/');
        if (slash <= 0 || slash >= raw.length() - 1) {
            return null; // needs both a non-empty provider and model
        }
        return new String[] { raw.substring(0, slash), raw.substring(slash + 1) };
    }

    // ---------- task board defaults ----------

    /** Default task-store root ({@code <repo>/.opencode/tasks}); the Board view's fallback when no override is set. */
    public String getTasksRoot() {
        return prefs.get(KEY_TASKS_ROOT, DEFAULT_TASKS_ROOT);
    }

    public void setTasksRoot(String root) {
        prefs.put(KEY_TASKS_ROOT, root == null ? "" : root.trim());
    }

    /** Default task-store project shown in the Board view. */
    public String getTasksProject() {
        return prefs.get(KEY_TASKS_PROJECT, DEFAULT_TASKS_PROJECT);
    }

    public void setTasksProject(String project) {
        prefs.put(KEY_TASKS_PROJECT, project == null ? "" : project.trim());
    }

    /**
     * Working directory for the spawned opencode server when no project context
     * is available — the repo root so its {@code .opencode/} agents, skills and
     * MCP config load. Blank = Eclipse process default.
     */
    public String getWorkingDirectory() {
        return prefs.get(KEY_WORKING_DIRECTORY, DEFAULT_WORKING_DIRECTORY);
    }

    public void setWorkingDirectory(String directory) {
        prefs.put(KEY_WORKING_DIRECTORY, directory == null ? "" : directory.trim());
    }

    /** Builds the {@link ConnectionConfig} used for {@code connect} mode. */
    public ConnectionConfig toConnectConfig() {
        String url = getServerUrl();
        String user = getUsername();
        String pw = getPassword();
        return new ConnectionConfig(
                java.net.URI.create(url),
                user.isEmpty() ? null : user,
                pw.isEmpty() ? null : pw);
    }

    /**
     * @return the raw stored remote-connections value: one entry per line,
     *         each shaped {@code url[|user]} (blank when unset). Passwords
     *         live in secure storage and never appear here; legacy
     *         {@code url[|user|password]} lines may still be present until
     *         {@link #getRemoteConnectionConfigs()} migrates them.
     */
    public String getRemoteConnections() {
        return prefs.get(KEY_REMOTE_CONNECTIONS, "");
    }

    /**
     * Stores the raw remote-connections list (see {@link #getRemoteConnections()}).
     * Note: an empty value clears the instance lines only — secure-storage
     * entries are managed by {@link #setRemoteConnectionConfigs(java.util.List)}.
     */
    public void setRemoteConnections(String raw) {
        if (raw == null || raw.isBlank()) {
            prefs.remove(KEY_REMOTE_CONNECTIONS);
        } else {
            prefs.put(KEY_REMOTE_CONNECTIONS, raw);
        }
    }

    /**
     * Parses the stored remote-connections list into {@link ConnectionConfig}s,
     * migrating legacy inline passwords into secure storage on the way.
     *
     * <p>Entries are shaped {@code url[|user]}; a legacy third {@code |}-segment
     * is a plaintext password: it is moved into the {@link RemoteCredentials}
     * seam and the instance line rewritten without it (the migration is
     * logged). Blank entries and invalid ones (bad URL shape, more than three
     * {@code |}-separated segments — i.e. a {@code |} inside the user — or a
     * line break inside a segment) are skipped with a {@code ClientLog}
     * warning; never throws.</p>
     */
    public java.util.List<ConnectionConfig> getRemoteConnectionConfigs() {
        java.util.List<ConnectionConfig> configs = new java.util.ArrayList<>();
        boolean migrated = false;
        StringBuilder rewritten = new StringBuilder();
        for (String line : getRemoteConnections().split("\\R")) {
            String entry = line.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] fields = entry.split("\\|", -1);
            if (fields.length > 3) {
                ClientLog.warning("skipped invalid remote connection entry '" + entry
                        + "': too many '|'-separated segments (the username must not contain '|')");
                continue;
            }
            String url = fields[0].trim();
            String user = fields.length > 1 ? fields[1].trim() : null;
            if (user != null && user.isEmpty()) {
                user = null;
            }
            ConnectionConfig config;
            try {
                config = new ConnectionConfig(java.net.URI.create(url), user, null);
            } catch (RuntimeException e) {
                ClientLog.warning("skipped invalid remote connection entry '" + entry + "': " + e.getMessage());
                continue;
            }
            String key = config.baseUrl().toString();
            String password;
            boolean keepLegacyLine = false;
            if (fields.length == 3) {
                String legacyPassword = fields[2].isEmpty() ? null : fields[2];
                migrated = true;
                if (legacyPassword == null) {
                    password = null;
                } else if (safeStore(key, legacyPassword)) {
                    ClientLog.warning("migrated password of remote connection '" + key
                            + "' from instance preferences into secure storage");
                    password = legacyPassword;
                } else {
                    // secure storage failed: keep the legacy line untouched so a later run can retry
                    password = legacyPassword;
                    keepLegacyLine = true;
                }
            } else {
                password = safeLoad(key);
            }
            configs.add(password == null ? config : new ConnectionConfig(config.baseUrl(), user, password));
            if (!rewritten.isEmpty()) {
                rewritten.append('\n');
            }
            if (keepLegacyLine) {
                rewritten.append(entry);
            } else {
                rewritten.append(key);
                if (user != null) {
                    rewritten.append('|').append(user);
                }
            }
        }
        if (migrated) {
            setRemoteConnections(rewritten.toString());
            ClientLog.warning("migrated legacy remote-connection passwords into secure storage;"
                    + " instance preferences rewritten without passwords");
        }
        return configs;
    }

    /**
     * Serializes the given remote connections back into the preferences: the
     * instance lines keep {@code url[|user]} only, while every non-null
     * password is stored (encrypted) via the {@link RemoteCredentials} seam.
     * Secure entries for URLs that are no longer present (or now have a null
     * password) are removed; an empty/all-invalid list clears the lines and
     * all secure entries. Entries that could never round-trip (a {@code |} or
     * line break in the username, a line break in the password) are refused
     * with a {@code ClientLog} warning.
     */
    public void setRemoteConnectionConfigs(java.util.List<ConnectionConfig> configs) {
        java.util.List<ConnectionConfig> previous = getRemoteConnectionConfigs();
        StringBuilder raw = new StringBuilder();
        java.util.List<ConnectionConfig> accepted = new java.util.ArrayList<>();
        if (configs != null) {
            for (ConnectionConfig config : configs) {
                if (config == null) {
                    continue;
                }
                String reason = invalidReason(config);
                if (reason != null) {
                    ClientLog.warning("refused remote connection entry '" + config.baseUrl()
                            + "': " + reason);
                    continue;
                }
                accepted.add(config);
                if (!raw.isEmpty()) {
                    raw.append('\n');
                }
                raw.append(config.baseUrl());
                if (config.username() != null) {
                    raw.append('|').append(config.username());
                }
            }
        }
        if (accepted.isEmpty()) {
            setRemoteConnections("");
            safeRemoveAll();
            return;
        }
        java.util.Set<String> keepPassword = new java.util.HashSet<>();
        for (ConnectionConfig config : accepted) {
            String key = config.baseUrl().toString();
            if (config.password() != null) {
                safeStore(key, config.password());
                keepPassword.add(key);
            }
        }
        for (ConnectionConfig old : previous) {
            String key = old.baseUrl().toString();
            if (!keepPassword.contains(key)) {
                safeRemove(key);
            }
        }
        setRemoteConnections(raw.toString());
    }

    /** @return why the entry can never round-trip, or {@code null} when valid. */
    private static String invalidReason(ConnectionConfig config) {
        String user = config.username();
        if (user != null && (user.indexOf('|') >= 0 || hasLineBreak(user))) {
            return "the username must not contain '|' or line breaks";
        }
        if (hasLineBreak(config.baseUrl().toString())) {
            return "the URL must not contain line breaks";
        }
        if (config.password() != null && hasLineBreak(config.password())) {
            return "the password must not contain line breaks";
        }
        return null;
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private String safeLoad(String url) {
        try {
            return credentials.loadPassword(url);
        } catch (RuntimeException e) {
            ClientLog.warning("could not load secure password for remote connection '" + url + "': " + e);
            return null;
        }
    }

    private boolean safeStore(String url, String password) {
        try {
            credentials.storePassword(url, password);
            return true;
        } catch (RuntimeException e) {
            ClientLog.warning("could not store secure password for remote connection '" + url + "': " + e);
            return false;
        }
    }

    private void safeRemove(String url) {
        try {
            credentials.removePassword(url);
        } catch (RuntimeException e) {
            ClientLog.warning("could not remove secure password for remote connection '" + url + "': " + e);
        }
    }

    private void safeRemoveAll() {
        try {
            credentials.removeAll();
        } catch (RuntimeException e) {
            ClientLog.warning("could not clear secure passwords for remote connections: " + e);
        }
    }

    public void save() throws BackingStoreException {
        prefs.flush();
    }
}
