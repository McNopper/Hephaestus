package com.opencode.ide.fleet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.git.FleetGit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client-side liveness probe for the fleet daemon (V-006): answers "is there
 * a daemon for this repository an MCP client could attach to?" from the
 * per-repo pidfile — {@code <repo>/.git/opencode-fleet/daemon.json} as
 * written by the daemon — without speaking the wire protocol. Live means the
 * recorded pid is alive (ProcessHandle, the same convention as dispatch
 * markers) AND one TCP connect to the recorded port succeeds; the connect
 * probe doubles as pid-reuse insurance (a recycled pid does not have our
 * port listening) and costs the daemon nothing: the connection closes
 * without ever sending a handshake line.
 *
 * <p>The daemon-side start guard keeps its own probe for its exclusivity
 * rules; this one belongs to attaching clients ({@link FleetStdioMain} in
 * auto/always mode, launcher scripts) and is strictly read-only — a stale
 * pidfile is reported as "no daemon", never cleaned up here.</p>
 */
public final class DaemonProwl {

    /** Bound for the one TCP connect probe: a live loopback daemon answers instantly. */
    private static final int PROBE_TIMEOUT_MS = 500;

    /**
     * The pidfile fields a client needs: where the daemon listens, who it is,
     * and the shared token the {@code daemon/hello} handshake expects. The
     * token is a secret — {@link #toString()} masks it so incidental logging
     * of a contact cannot leak it.
     */
    public record Contact(long pid, int port, String token, Instant startedAt) {

        @Override
        public String toString() {
            return "Daemon[pid=" + pid + ", port=" + port + ", token=<masked>, startedAt=" + startedAt + "]";
        }
    }

    private DaemonProwl() {
    }

    /**
     * Probes this repository for a live fleet daemon.
     *
     * @param repoRoot the repository root (not the task store root)
     * @return the contact when the pidfile names a live daemon, else empty;
     *         never throws — any problem simply means "no daemon"
     */
    public static Optional<Contact> probe(Path repoRoot) {
        try {
            return read(FleetGit.fleetRoot(repoRoot).resolve("daemon.json"))
                    .filter(contact -> processAlive(contact.pid()) && portAnswers(contact.port()));
        } catch (RuntimeException e) {
            // e.g. an unreadable .git layout: there is no daemon to find
            return Optional.empty();
        }
    }

    /**
     * Parses a pidfile without liveness checks. Every problem (missing,
     * unreadable, corrupt, missing mandatory fields, out-of-range values)
     * means "no daemon"; unknown extra fields (the daemon records its
     * spawned server alongside) are tolerated, and an unparseable
     * {@code startedAt} degrades to {@code null} — liveness is pid + port,
     * the start instant is informational.
     */
    public static Optional<Contact> read(Path daemonJson) {
        String raw;
        try {
            if (!Files.isRegularFile(daemonJson)) {
                return Optional.empty();
            }
            raw = Files.readString(daemonJson);
        } catch (IOException e) {
            ClientLog.warning("cannot read daemon pidfile " + daemonJson + ": " + e.getMessage());
            return Optional.empty();
        }
        try {
            var element = JsonParser.parseString(raw);
            if (!element.isJsonObject()) {
                ClientLog.warning("daemon pidfile " + daemonJson + " is corrupt; ignoring it");
                return Optional.empty();
            }
            JsonObject json = element.getAsJsonObject();
            if (!isPrimitive(json, "pid") || !isPrimitive(json, "port") || !isPrimitive(json, "token")) {
                ClientLog.warning("daemon pidfile " + daemonJson + " lacks pid/port/token; ignoring it");
                return Optional.empty();
            }
            long pid = json.get("pid").getAsLong();
            int port = json.get("port").getAsInt();
            String token = json.get("token").getAsString();
            if (pid <= 0 || port < 1 || port > 65535 || token.isBlank()) {
                ClientLog.warning("daemon pidfile " + daemonJson + " has out-of-range values; ignoring it");
                return Optional.empty();
            }
            Instant startedAt = null;
            if (isPrimitive(json, "startedAt")) {
                try {
                    startedAt = Instant.parse(json.get("startedAt").getAsString());
                } catch (DateTimeParseException e) {
                    startedAt = null;
                }
            }
            return Optional.of(new Contact(pid, port, token, startedAt));
        } catch (RuntimeException e) {
            // deliberately without the parser message: it can quote file
            // fragments, and the token must never surface in a log
            ClientLog.warning("daemon pidfile " + daemonJson + " is corrupt; ignoring it");
            return Optional.empty();
        }
    }

    private static boolean isPrimitive(JsonObject json, String member) {
        return json.has(member) && json.get(member).isJsonPrimitive();
    }

    private static boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** One plain TCP connect; the socket closes without a handshake (the daemon just drops it). */
    private static boolean portAnswers(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
