package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.client.OpencodeServiceDiscovery.DiscoveredService;
import com.opencode.ide.client.OpencodeServiceDiscovery.ProbeOutcome;

/**
 * Unit tests for {@link OpencodeServiceDiscovery}: the v2 shared-service
 * discovery (registration file → {@code /api/info} probe with staleness
 * rules), the {@code opencode service status} fallback, and the
 * {@code ensure} start-and-wait flow. Every seam (files, HTTP probe, CLI
 * calls) is faked — no real processes, no sockets.
 */
public class OpencodeServiceDiscoveryTest {

    private static final URI SERVICE_URL = URI.create("http://127.0.0.1:49374");
    private static final Duration POLL = Duration.ofMillis(5);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path registrationFile;
    private Path passwordFile;

    @Before
    public void setUp() throws IOException {
        registrationFile = temp.newFolder("state", "opencode").toPath().resolve("service.json");
        passwordFile = temp.newFolder("config", "opencode").toPath().resolve("service.json");
    }

    private OpencodeServiceDiscovery discovery(OpencodeServiceDiscovery.Prober prober,
            OpencodeServiceDiscovery.StatusCommand status, OpencodeServiceDiscovery.Starter starter) {
        return new OpencodeServiceDiscovery(registrationFile, passwordFile,
                prober, status, starter, POLL);
    }

    /** A starter for pure discover() tests: ensure() must never run there. */
    private static OpencodeServiceDiscovery.Starter noStarter() {
        return () -> {
            throw new AssertionError("the starter must not run during a discover() test");
        };
    }

    private void writeRegistration(String json) throws IOException {
        Files.writeString(registrationFile, json, StandardCharsets.UTF_8);
    }

    private void writeLegacyPassword(String json) throws IOException {
        Files.writeString(passwordFile, json, StandardCharsets.UTF_8);
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- discover(): the registration path ----------

    @Test
    public void healthyServiceAttachesFromTheRegistrationFile() throws IOException {
        writeRegistration("{\"id\":\"svc-1\",\"version\":\"2.0.10\","
                + "\"url\":\"http://127.0.0.1:49374\",\"pid\":1234,\"password\":\"svc-secret\"}");
        AtomicReference<String> seenAuth = new AtomicReference<>();
        OpencodeServiceDiscovery.Prober prober = (url, auth) -> {
            seenAuth.set(auth);
            return new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1234}");
        };

        Optional<DiscoveredService> found = discovery(prober, () -> null, noStarter()).discover();

        assertTrue("a matching registration + probe must attach", found.isPresent());
        assertEquals(SERVICE_URL, found.get().baseUrl());
        assertEquals("opencode", found.get().username());
        assertEquals("svc-secret", found.get().password());
        assertEquals("2.0.10", found.get().health().version());
        assertEquals("the probe authenticates with the registration password",
                basic("opencode", "svc-secret"), seenAuth.get());
    }

    @Test
    public void missingRegistrationDiscoversNothing() {
        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1}"),
                () -> null, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    @Test
    public void corruptRegistrationDiscoversNothing() throws IOException {
        writeRegistration("not json {");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1}"),
                () -> null, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    @Test
    public void registrationWithoutUrlOrPidDiscoversNothing() throws IOException {
        // the JS staleness check needs both; without them the entry can never validate
        writeRegistration("{\"version\":\"2.0.10\"}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1}"),
                () -> null, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    @Test
    public void staleRegistrationPidIsRejected() throws IOException {
        writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":9999}"),
                () -> null, noStarter()).discover();

        assertFalse("a different pid owns the port now - the registration is stale", found.isPresent());
    }

    @Test
    public void staleRegistrationVersionIsRejected() throws IOException {
        writeRegistration("{\"version\":\"2.0.9\",\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1234}"),
                () -> null, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    @Test
    public void legacyServerOwningThePortIsNotCompatible() throws IOException {
        writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(404, null), () -> null, noStarter()).discover();

        assertFalse("404 = a v1 server without /api/info - not a v2 service", found.isPresent());
    }

    @Test
    public void unreachableServiceDiscoversNothing() throws IOException {
        writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(-1, null), () -> null, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    @Test
    public void registrationWithoutPasswordUsesTheLegacyConfigPassword() throws IOException {
        writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");
        writeLegacyPassword("{\"password\":\"legacy-pw\"}");
        AtomicReference<String> seenAuth = new AtomicReference<>();

        Optional<DiscoveredService> found = discovery((url, auth) -> {
            seenAuth.set(auth);
            return new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1234}");
        }, () -> null, noStarter()).discover();

        assertTrue(found.isPresent());
        assertEquals("legacy-pw", found.get().password());
        assertEquals(basic("opencode", "legacy-pw"), seenAuth.get());
    }

    // ---------- discover(): the status-command fallback ----------

    @Test
    public void statusCommandSuppliesTheUrlWhenNoRegistrationExists() throws IOException {
        writeLegacyPassword("{\"password\":\"legacy-pw\"}");
        AtomicReference<URI> probed = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();

        Optional<DiscoveredService> found = discovery((url, auth) -> {
            probed.set(url);
            seenAuth.set(auth);
            return new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":42}");
        }, () -> "http://127.0.0.1:49374\n", noStarter()).discover();

        assertTrue(found.isPresent());
        assertEquals(SERVICE_URL, probed.get());
        assertEquals("the fallback has no registration to compare against - the legacy "
                + "password file authenticates", basic("opencode", "legacy-pw"), seenAuth.get());
        assertEquals("legacy-pw", found.get().password());
    }

    @Test
    public void corruptRegistrationFallsBackToTheStatusCommand() throws IOException {
        writeRegistration("{corrupt");
        writeLegacyPassword("{\"password\":\"legacy-pw\"}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":42}"),
                () -> "service: http://127.0.0.1:49374.", noStarter()).discover();

        assertTrue(found.isPresent());
        assertEquals(SERVICE_URL, found.get().baseUrl());
    }

    @Test
    public void registrationPresentMeansTheStatusCommandIsNotPolled() throws IOException {
        writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");

        Optional<DiscoveredService> found = discovery(
                (url, auth) -> new ProbeOutcome(-1, null),
                () -> {
                    throw new AssertionError("a present registration is trusted alone - "
                            + "the status command must not run");
                }, noStarter()).discover();

        assertFalse(found.isPresent());
    }

    // ---------- ensure(): start-and-wait ----------

    @Test
    public void ensureStartsTheServiceWhenNothingIsRunning() {
        AtomicInteger starts = new AtomicInteger();
        OpencodeServiceDiscovery discovery = discovery(
                (url, auth) -> new ProbeOutcome(200, "{\"version\":\"2.0.10\",\"pid\":1234}"),
                () -> null,
                () -> {
                    starts.incrementAndGet();
                    writeRegistration("{\"url\":\"http://127.0.0.1:49374\",\"pid\":1234}");
                    return new FakeProcess(true, 0);
                });

        Optional<DiscoveredService> found = discovery.ensure(Duration.ofSeconds(5));

        assertTrue(found.isPresent());
        assertEquals("one contender spawn, like the JS ensure's first attempt", 1, starts.get());
        assertEquals(SERVICE_URL, found.get().baseUrl());
    }

    @Test
    public void ensureReturnsEmptyWhenTheStarterFails() {
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            OpencodeServiceDiscovery discovery = discovery(
                    (url, auth) -> new ProbeOutcome(-1, null), () -> null,
                    () -> {
                        throw new IOException("opencode binary not found (test)");
                    });

            Optional<DiscoveredService> found = discovery.ensure(Duration.ofSeconds(5));

            assertFalse(found.isPresent());
            assertTrue("the start failure must be logged, got: " + log.messages,
                    log.contains("could not start the shared background service"));
        } finally {
            ClientLog.install(previous);
        }
    }

    @Test
    public void ensureTimesOutWhenTheServiceNeverBecomesHealthy() {
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            OpencodeServiceDiscovery discovery = discovery(
                    (url, auth) -> new ProbeOutcome(-1, null), () -> null,
                    () -> new FakeProcess(true, 0));

            Optional<DiscoveredService> found = discovery.ensure(Duration.ofMillis(150));

            assertFalse(found.isPresent());
            assertTrue("the timeout must be logged, got: " + log.messages,
                    log.contains("did not become healthy"));
        } finally {
            ClientLog.install(previous);
        }
    }

    @Test
    public void ensureReturnsEmptyWhenTheContenderExitsWithAnError() {
        RecordingLog log = new RecordingLog();
        ClientLog previous = ClientLog.BACKEND.get();
        ClientLog.install(log);
        try {
            OpencodeServiceDiscovery discovery = discovery(
                    (url, auth) -> new ProbeOutcome(-1, null), () -> null,
                    () -> new FakeProcess(false, 1));

            Optional<DiscoveredService> found = discovery.ensure(Duration.ofSeconds(5));

            assertFalse(found.isPresent());
            assertTrue("the contender's exit code must be logged, got: " + log.messages,
                    log.contains("exited with code 1"));
        } finally {
            ClientLog.install(previous);
        }
    }

    // ---------- pure parsing helpers ----------

    @Test
    public void parseStatusUrlExtractsTheBareUrl() {
        assertEquals(SERVICE_URL,
                OpencodeServiceDiscovery.parseStatusUrl("http://127.0.0.1:49374\n"));
        assertEquals(URI.create("https://opencode.example.com:8443"),
                OpencodeServiceDiscovery.parseStatusUrl("running at https://opencode.example.com:8443."));
        assertNull(OpencodeServiceDiscovery.parseStatusUrl(null));
        assertNull(OpencodeServiceDiscovery.parseStatusUrl("service is not running"));
        assertNull(OpencodeServiceDiscovery.parseStatusUrl("ftp://127.0.0.1:21"));
    }

    @Test
    public void defaultRegistrationFilePrefersXdgStateHome() {
        Path home = Path.of("home");
        assertEquals(Path.of("xdg", "opencode", "service.json"),
                OpencodeServiceDiscovery.defaultRegistrationFile("xdg", home));
        assertEquals(Path.of("home", ".local", "state", "opencode", "service.json"),
                OpencodeServiceDiscovery.defaultRegistrationFile(null, home));
        assertEquals(Path.of("home", ".local", "state", "opencode", "service.json"),
                OpencodeServiceDiscovery.defaultRegistrationFile("  ", home));
    }

    @Test
    public void defaultPasswordFilePrefersXdgConfigHome() {
        Path home = Path.of("home");
        assertEquals(Path.of("xdgcfg", "opencode", "service.json"),
                OpencodeServiceDiscovery.defaultPasswordFile("xdgcfg", home));
        assertEquals(Path.of("home", ".config", "opencode", "service.json"),
                OpencodeServiceDiscovery.defaultPasswordFile(null, home));
    }

    @Test
    public void discoveredServiceMapsToAConnectionConfig() {
        DiscoveredService service = new DiscoveredService(SERVICE_URL, "opencode", "pw",
                new com.opencode.ide.client.model.HealthStatus(true, "2.0.10"));

        ConnectionConfig config = service.toConnectionConfig();

        assertEquals(SERVICE_URL, config.baseUrl());
        assertEquals("opencode", config.username());
        assertEquals("pw", config.password());
        assertTrue(config.hasAuth());
    }

    /** A controllable {@link Process} fake for the ensure() contender. */
    private static final class FakeProcess extends Process {
        private volatile boolean alive;
        private final int exitCode;

        FakeProcess(boolean alive, int exitCode) {
            this.alive = alive;
            this.exitCode = exitCode;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("fake process still running");
            }
            return exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
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
}
