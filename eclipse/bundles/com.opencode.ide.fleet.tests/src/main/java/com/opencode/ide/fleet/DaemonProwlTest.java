package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link DaemonProwl} against real pidfiles, a real loopback listener and
 * real ProcessHandles: live means pid alive AND the port answers one TCP
 * connect; every other state means "no daemon".
 */
public class DaemonProwlTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String TOKEN = "0123456789abcdef".repeat(4);

    @Test
    public void missingPidfileMeansNoDaemon() {
        assertFalse(DaemonProwl.probe(tmp.getRoot().toPath()).isPresent());
    }

    @Test
    public void corruptPidfileMeansNoDaemon() throws Exception {
        Path repo = tmp.getRoot().toPath();
        writePidfile(repo, "{not json at all");
        assertFalse(DaemonProwl.probe(repo).isPresent());
    }

    @Test
    public void pidfileWithoutMandatoryFieldsMeansNoDaemon() throws Exception {
        Path repo = tmp.getRoot().toPath();
        writePidfile(repo, "{\"startedAt\":\"" + Instant.now() + "\"}");
        assertFalse(DaemonProwl.probe(repo).isPresent());
    }

    @Test
    public void liveDaemonIsFoundWithPortAndToken() throws Exception {
        try (ServerSocket listener = bound()) {
            Path repo = tmp.getRoot().toPath();
            writePidfile(repo, ProcessHandle.current().pid(), listener.getLocalPort());
            Optional<DaemonProwl.Contact> contact = DaemonProwl.probe(repo);
            assertTrue(contact.isPresent());
            assertEquals(listener.getLocalPort(), contact.get().port());
            assertEquals(TOKEN, contact.get().token());
            // token hygiene: incidental logging of a contact must not leak it
            assertFalse(contact.get().toString().contains(TOKEN));
        }
    }

    @Test
    public void unknownExtraPidfileFieldsAreTolerated() throws Exception {
        try (ServerSocket listener = bound()) {
            Path repo = tmp.getRoot().toPath();
            String json = "{\"port\":" + listener.getLocalPort()
                    + ",\"pid\":" + ProcessHandle.current().pid()
                    + ",\"token\":\"" + TOKEN + "\""
                    + ",\"startedAt\":\"" + Instant.now() + "\""
                    + ",\"serverPid\":4711,\"serverPort\":8080}";
            writePidfile(repo, json);
            Optional<DaemonProwl.Contact> contact = DaemonProwl.probe(repo);
            assertTrue(contact.isPresent());
            assertTrue(contact.get().startedAt() != null);
        }
    }

    @Test
    public void deadPidMeansNoDaemon() throws Exception {
        // 999999996: a plausible Windows pid shape (multiple of 4) far above
        // any real allocation; skip rather than flake if a machine ever has it
        long dead = 999_999_996L;
        Assume.assumeFalse(ProcessHandle.of(dead).isPresent());
        Path repo = tmp.getRoot().toPath();
        writePidfile(repo, dead, 1);
        assertFalse(DaemonProwl.probe(repo).isPresent());
    }

    @Test
    public void closedPortMeansNoDaemon() throws Exception {
        // pid alive (this JVM), but tcpmux has no listener: the connect probe decides
        Path repo = tmp.getRoot().toPath();
        writePidfile(repo, ProcessHandle.current().pid(), 1);
        assertFalse(DaemonProwl.probe(repo).isPresent());
    }

    private static ServerSocket bound() throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return listener;
    }

    private void writePidfile(Path repo, long pid, int port) throws IOException {
        writePidfile(repo, "{\"port\":" + port + ",\"pid\":" + pid + ",\"token\":\"" + TOKEN
                + "\",\"startedAt\":\"" + Instant.now() + "\"}");
    }

    private void writePidfile(Path repo, String json) throws IOException {
        Path pidfile = repo.resolve(".git").resolve("opencode-fleet").resolve("daemon.json");
        Files.createDirectories(pidfile.getParent());
        Files.writeString(pidfile, json, StandardCharsets.UTF_8);
    }
}
