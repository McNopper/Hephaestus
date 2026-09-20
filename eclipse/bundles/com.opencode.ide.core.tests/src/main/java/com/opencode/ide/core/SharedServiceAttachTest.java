package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.OpencodeServiceDiscovery;

/**
 * Tests for the local/primary connection's shared-service attach (v2): the
 * {@link OpencodePreferences#isAttachSharedService()} preference (default ON),
 * the attach → spawn decision in {@link OpencodeConnection#selectLocalConfig},
 * and the attach attempt itself ({@code tryAttachSharedService}) including the
 * H-002 version-pin warning on the captured health snapshot. No real
 * processes, no sockets: the discovery's seams are faked.
 */
public class SharedServiceAttachTest {

    private static final ConnectionConfig ATTACHED = new ConnectionConfig(
            URI.create("http://127.0.0.1:49374"), "opencode", "svc-secret");
    private static final ConnectionConfig SPAWNED = new ConnectionConfig(
            URI.create("http://127.0.0.1:5555"), "opencode", "spawn-pw");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final OpencodePreferences prefs = new OpencodePreferences(new FakeCredentials());

    private Path registrationFile;
    private Path passwordFile;

    @Before
    public void setUp() throws IOException {
        // the instance-scope node is shared with other tests: start from the default
        prefs.raw().remove(OpencodePreferences.KEY_ATTACH_SHARED_SERVICE);
        registrationFile = temp.newFolder("state", "opencode").toPath().resolve("service.json");
        passwordFile = temp.newFolder("config", "opencode").toPath().resolve("service.json");
    }

    @After
    public void tearDown() {
        prefs.raw().remove(OpencodePreferences.KEY_ATTACH_SHARED_SERVICE);
    }

    // ---------- the preference ----------

    @Test
    public void attachPreferenceDefaultsOn() {
        assertTrue("v2-native default: attach to the shared service",
                prefs.isAttachSharedService());
    }

    @Test
    public void attachPreferenceRoundTrips() {
        prefs.setAttachSharedService(false);
        assertFalse(prefs.isAttachSharedService());
        prefs.setAttachSharedService(true);
        assertTrue(prefs.isAttachSharedService());
    }

    // ---------- the attach → spawn decision ----------

    @Test
    public void preferenceOffSpawnsWithoutAttaching() throws Exception {
        prefs.setAttachSharedService(false);
        AtomicBoolean attachRan = new AtomicBoolean();

        ConnectionConfig result = OpencodeConnection.selectLocalConfig(prefs,
                () -> {
                    attachRan.set(true);
                    return ATTACHED;
                },
                () -> SPAWNED);

        assertSame(SPAWNED, result);
        assertFalse("preference OFF must skip the attach attempt entirely", attachRan.get());
    }

    @Test
    public void healthyServiceAttachWinsOverSpawn() throws Exception {
        AtomicBoolean spawnRan = new AtomicBoolean();

        ConnectionConfig result = OpencodeConnection.selectLocalConfig(prefs,
                () -> ATTACHED,
                () -> {
                    spawnRan.set(true);
                    return SPAWNED;
                });

        assertSame(ATTACHED, result);
        assertFalse("an attached service never falls back to spawn", spawnRan.get());
    }

    @Test
    public void unavailableServiceFallsBackToSpawn() throws Exception {
        AtomicBoolean spawnRan = new AtomicBoolean();

        ConnectionConfig result = OpencodeConnection.selectLocalConfig(prefs,
                () -> null, // nothing healthy, start failed - already logged
                () -> {
                    spawnRan.set(true);
                    return SPAWNED;
                });

        assertSame("the user is never left without a connection", SPAWNED, result);
        assertTrue(spawnRan.get());
    }

    @Test
    public void attachErrorFallsBackToSpawnWithAClearLog() throws Exception {
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            ConnectionConfig result = OpencodeConnection.selectLocalConfig(prefs,
                    () -> {
                        throw new IllegalStateException("discovery blew up (test)");
                    },
                    () -> SPAWNED);

            assertSame(SPAWNED, result);
            assertTrue("the failure must be logged, got: " + log.messages,
                    log.contains("attach attempt failed"));
        } finally {
            ClientLog.install(previous);
        }
    }

    // ---------- the attach attempt ----------

    @Test
    public void attachSuccessMapsTheEndpointAndEvaluatesTheVersionPin() throws IOException {
        // a drifting server version proves the captured HealthStatus flows to
        // ServerVersionPin.evaluate exactly like the spawn path's does
        Files.writeString(registrationFile, "{\"url\":\"http://127.0.0.1:49374\","
                + "\"pid\":4321,\"password\":\"svc-secret\"}", StandardCharsets.UTF_8);
        OpencodeServiceDiscovery discovery = new OpencodeServiceDiscovery(
                registrationFile, passwordFile,
                (url, auth) -> new OpencodeServiceDiscovery.ProbeOutcome(200,
                        "{\"version\":\"9.9.9\",\"pid\":4321}"),
                () -> null,
                () -> {
                    throw new AssertionError("a healthy service needs no start");
                },
                Duration.ofMillis(5));
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            ConnectionConfig config = OpencodeConnection.tryAttachSharedService(
                    discovery, Duration.ofSeconds(5));

            assertEquals(URI.create("http://127.0.0.1:49374"), config.baseUrl());
            assertEquals("opencode", config.username());
            assertEquals("svc-secret", config.password());
            assertTrue("the version-pin warning must fire on the drifted server, got: "
                    + log.messages, log.contains("9.9.9"));
        } finally {
            ClientLog.install(previous);
        }
    }

    @Test
    public void unavailableServiceReturnsNullWithAClearLog() {
        OpencodeServiceDiscovery discovery = new OpencodeServiceDiscovery(
                registrationFile, passwordFile,
                (url, auth) -> new OpencodeServiceDiscovery.ProbeOutcome(-1, null),
                () -> null,
                () -> {
                    throw new IOException("opencode binary not found (test)");
                },
                Duration.ofMillis(5));
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            ConnectionConfig config = OpencodeConnection.tryAttachSharedService(
                    discovery, Duration.ofSeconds(5));

            assertNull("unavailable service: the caller falls back to spawn", config);
            assertTrue("the concrete reason must be logged, got: " + log.messages,
                    log.contains("could not start the shared background service"));
        } finally {
            ClientLog.install(previous);
        }
    }

    /** ClientLog sink that records entries for assertions. */
    private static final class RecordingLog implements ClientLog {
        final List<String> messages = new ArrayList<>();

        @Override
        public void log(Level level, String message, Throwable cause) {
            messages.add(level + " " + message);
        }

        boolean contains(String fragment) {
            return messages.stream().anyMatch(m -> m.contains(fragment));
        }
    }

    /** In-memory {@link RemoteCredentials} so these tests never touch secure storage. */
    private static final class FakeCredentials implements RemoteCredentials {
        final Map<String, String> passwords = new LinkedHashMap<>();

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
