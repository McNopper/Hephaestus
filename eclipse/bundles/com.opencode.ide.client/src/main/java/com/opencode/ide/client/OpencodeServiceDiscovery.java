package com.opencode.ide.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.client.internal.Auth;
import com.opencode.ide.client.model.HealthStatus;

/**
 * v2 shared-service attach: discovers — and when missing starts — the per-user
 * opencode background service that every v2 client (the TUI, {@code opencode
 * api}, the JS {@code @opencode/client}) talks to, instead of spawning a
 * private {@code opencode serve} per IDE.
 *
 * <p>The discovery algorithm mirrors the JS reference client
 * ({@code @opencode/client}, {@code dist/promise/service.js} —
 * {@code discover()} at line 25, {@code probeResult()} at line 155):</p>
 * <ol>
 *   <li>read the service registration file
 *       {@code $XDG_STATE_HOME/opencode/service.json} (default
 *       {@code ~/.local/state/opencode/service.json} — the JS {@code fallback()}
 *       at line 133), shaped {@code {id?, version?, url, pid, password?}} (the
 *       JS {@code Info} type, {@code dist/service.d.ts});</li>
 *   <li>probe {@code GET {url}/api/info} with HTTP Basic auth
 *       {@code opencode}:{@code password}; a 404 means a legacy v1 server owns
 *       the port (incompatible), and a 200 whose {@code pid} — or
 *       {@code version}, when the registration carries one — does not match the
 *       registration is stale (a different process now owns the port). Both
 *       read as "not found";</li>
 *   <li>a 200 with a matching {@code pid}/{@code version} is healthy: the
 *       service is attachable.</li>
 * </ol>
 *
 * <p>When no usable registration file exists, the IDE adds one fallback the JS
 * client does not have: parse the base URL from {@code opencode service
 * status} output and take the password from the legacy
 * {@code ~/.config/opencode/service.json} file ({@code {"password":"..."}}).
 * That legacy file is also the fallback password source when the registration
 * carries no {@code password} field. A registration that IS present is trusted
 * alone (as the JS client does) — the status command is never polled while a
 * registration exists.</p>
 *
 * <p>{@link #ensure(Duration)} mirrors the JS {@code ensure()} (line 35): when
 * nothing healthy is found it spawns the managed service —
 * {@code opencode serve --service} (the JS default command, line 50), detached
 * and deliberately NOT tracked or killed by us — then polls discovery until
 * the service is healthy or the timeout elapses. Deliberate simplifications
 * versus the JS client: one contender instead of two with exponential backoff,
 * no unresponsive-service recovery (the SIGTERM/SIGKILL dance), no
 * persistent-terminal handoff. Any failure is logged and returned as
 * {@link Optional#empty()} so the caller falls back to spawning a private
 * server — the user is never left without a connection.</p>
 *
 * <p>Pure Java with injectable seams (file paths, HTTP probe, CLI calls), the
 * same discipline as {@link BinaryResolver} and {@link OpencodeServerLauncher}:
 * tests drive fakes, production uses {@link #create(String)}.</p>
 */
public final class OpencodeServiceDiscovery {

    /**
     * One parsed registration-file entry (the JS client's {@code Info}).
     * {@code url} and {@code pid} are required by the JS staleness check — a
     * registration without them can never validate and is treated as absent.
     */
    public record Registration(String id, String version, URI url, long pid, String password) {
    }

    /**
     * A verified-healthy service endpoint: base URL, Basic-auth credentials and
     * the {@code /api/info} health snapshot the version pin consumes.
     */
    public record DiscoveredService(URI baseUrl, String username, String password, HealthStatus health) {

        /** The connection parameters for the attached service (auth may be absent). */
        public ConnectionConfig toConnectionConfig() {
            return new ConnectionConfig(baseUrl, username, password);
        }
    }

    /** The {@code /api/info} probe outcome; status {@code -1} means unreachable (IO). Never thrown. */
    public record ProbeOutcome(int status, String body) {

        static ProbeOutcome unreachable() {
            return new ProbeOutcome(-1, null);
        }
    }

    /** {@code GET {base}/api/info} seam. */
    @FunctionalInterface
    public interface Prober {

        /**
         * @param baseUrl          the service base URL
         * @param basicAuthHeader  the {@code Authorization} header value, or {@code null} for no auth
         * @return the outcome; IO failures fold into {@link ProbeOutcome#unreachable()}
         */
        ProbeOutcome probe(URI baseUrl, String basicAuthHeader);
    }

    /** {@code opencode service status} seam (the fallback URL source). */
    @FunctionalInterface
    public interface StatusCommand {

        /** @return the command's stdout, or {@code null} when it is unavailable/failed. */
        String run();
    }

    /**
     * {@code opencode serve --service} spawn seam. The spawned process is the
     * per-user background service: deliberately NOT owned, tracked or killed by
     * the caller.
     */
    @FunctionalInterface
    public interface Starter {
        Process start() throws IOException;
    }

    /** Trailing punctuation a CLI may print right after a URL. */
    private static final Pattern STATUS_URL = Pattern.compile("https?://[^\\s\"'<>]+");

    private final Path registrationFile;
    private final Path passwordFile;
    private final Prober prober;
    private final StatusCommand statusCommand;
    private final Starter starter;
    private final Duration pollInterval;

    /**
     * Full-seam constructor (test seam): fake paths, probes and CLI calls keep
     * discovery off the filesystem and the network.
     */
    public OpencodeServiceDiscovery(Path registrationFile, Path passwordFile, Prober prober,
            StatusCommand statusCommand, Starter starter, Duration pollInterval) {
        this.registrationFile = registrationFile;
        this.passwordFile = passwordFile;
        this.prober = Objects.requireNonNull(prober, "prober");
        this.statusCommand = Objects.requireNonNull(statusCommand, "statusCommand");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    }

    /** Production defaults: real file locations, a real HTTP probe, real CLI calls. */
    public static OpencodeServiceDiscovery create(String configuredBinary) {
        Path home = Path.of(System.getProperty("user.home", "."));
        return new OpencodeServiceDiscovery(
                defaultRegistrationFile(System.getenv("XDG_STATE_HOME"), home),
                defaultPasswordFile(System.getenv("XDG_CONFIG_HOME"), home),
                httpProber(),
                statusCommand(configuredBinary),
                serviceStarter(configuredBinary),
                ClientTuning.PROBE_INTERVAL);
    }

    /**
     * One discovery pass: the registration file first, the
     * {@code opencode service status} fallback only when no usable registration
     * exists. Never throws and never logs (the {@link #ensure} poll loop would
     * spam); an empty result simply means "nothing healthy right now".
     */
    public Optional<DiscoveredService> discover() {
        Registration registration = parseRegistration(readFile(registrationFile));
        if (registration != null) {
            String password = registration.password() != null ? registration.password() : readLegacyPassword();
            return probeHealthy(registration.url(), password, registration.pid(), registration.version());
        }
        URI url = parseStatusUrl(statusCommand.run());
        if (url == null) {
            return Optional.empty();
        }
        // no registration: the pid/version staleness checks have nothing to compare against
        return probeHealthy(url, readLegacyPassword(), null, null);
    }

    /**
     * Discover, or start the shared service and wait for it to become healthy.
     * Mirrors the JS {@code ensure()} with the simplifications listed in the
     * class javadoc.
     *
     * @return the healthy endpoint, or {@link Optional#empty()} when the
     *         service could not be started or did not become healthy in time
     *         (the concrete reason is logged; the caller falls back to the
     *         spawn path)
     */
    public Optional<DiscoveredService> ensure(Duration timeout) {
        Optional<DiscoveredService> found = discover();
        if (found.isPresent()) {
            return found;
        }

        Process contender;
        try {
            contender = starter.start();
        } catch (IOException | RuntimeException e) {
            ClientLog.warning("[opencode service] could not start the shared background service: "
                    + e.getMessage());
            return Optional.empty();
        }
        ClientLog.info("[opencode service] started 'opencode serve --service'; waiting for it to become healthy");

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            found = discover();
            if (found.isPresent()) {
                return found;
            }
            if (!contender.isAlive()) {
                int code = contender.exitValue();
                if (code != 0) {
                    ClientLog.warning("[opencode service] 'opencode serve --service' exited with code "
                            + code + " before becoming healthy");
                    return Optional.empty();
                }
                // exit code 0: the serving process may be a detached grandchild
                // that is still starting (Windows .cmd shim / service hand-off),
                // so keep polling until the deadline.
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        ClientLog.warning("[opencode service] the shared background service did not become healthy within "
                + timeout.toSeconds() + "s");
        return Optional.empty();
    }

    // ---- discovery steps ----

    /**
     * The health probe with the JS staleness rules: 404 = a legacy v1 server
     * owns the port (not compatible); a 200 must carry a decodeable
     * {@code {version, pid}} body whose values match the registration (a
     * mismatch means the registration is stale and a different process owns the
     * port now).
     */
    private Optional<DiscoveredService> probeHealthy(URI url, String password,
            Long expectedPid, String expectedVersion) {
        ProbeOutcome outcome = prober.probe(url, Auth.basicHeader("opencode", password));
        if (outcome.status() != 200 || outcome.body() == null) {
            return Optional.empty();
        }
        ServerInfo info = parseServerInfo(outcome.body());
        if (info == null) {
            return Optional.empty();
        }
        if (expectedPid != null && info.pid() != expectedPid.longValue()) {
            return Optional.empty();
        }
        if (expectedVersion != null && !expectedVersion.equals(info.version())) {
            return Optional.empty();
        }
        return Optional.of(new DiscoveredService(url, "opencode", password,
                new HealthStatus(true, info.version())));
    }

    private String readLegacyPassword() {
        return parseLegacyPassword(readFile(passwordFile));
    }

    private static String readFile(Path file) {
        try {
            return file != null && Files.isRegularFile(file)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : null;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable reads as absent — the caller falls back
        }
    }

    // ---- parsing (pure, package-visible for tests) ----

    /**
     * Parses the registration file. Like the JS {@code read()} + the effective
     * requirements of {@code probeResult()}: without a {@code url} or a
     * {@code pid} the entry can never validate, so it reads as absent.
     * {@code null} on any malformed input.
     */
    static Registration parseRegistration(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (!object.has("url") || !object.get("url").isJsonPrimitive()) {
                return null;
            }
            URI url = URI.create(object.get("url").getAsString());
            if (url.getHost() == null) {
                return null;
            }
            if (!object.has("pid") || !object.get("pid").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("pid").isNumber()) {
                return null;
            }
            long pid = object.get("pid").getAsLong();
            String id = stringOrNull(object, "id");
            String version = stringOrNull(object, "version");
            String password = stringOrNull(object, "password");
            return new Registration(id, version, url, pid, password);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The legacy {@code ~/.config/opencode/service.json}: only the password is used. */
    static String parseLegacyPassword(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return stringOrNull(object, "password");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The JS {@code decodeInfo()}: the {@code /api/info} body must carry a
     * string {@code version} and a non-negative integer {@code pid}.
     */
    private static ServerInfo parseServerInfo(String body) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            String version = stringOrNull(object, "version");
            if (version == null || !object.has("pid")
                    || !object.get("pid").isJsonPrimitive()
                    || !object.getAsJsonPrimitive("pid").isNumber()) {
                return null;
            }
            long pid = object.get("pid").getAsLong();
            return pid < 0 ? null : new ServerInfo(version, pid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The parsed {@code /api/info} body (the fields the staleness check needs). */
    private record ServerInfo(String version, long pid) {
    }

    /**
     * Extracts the first {@code http(s)://…} URL from
     * {@code opencode service status} output (which prints the bare base URL,
     * e.g. {@code http://127.0.0.1:49374}); trailing sentence punctuation is
     * stripped. {@code null} when no usable URL is present.
     */
    public static URI parseStatusUrl(String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = STATUS_URL.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group();
        while (candidate.endsWith(".") || candidate.endsWith(",")
                || candidate.endsWith(")") || candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringOrNull(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : null;
    }

    // ---- default file locations ----

    /**
     * The registration file: {@code $XDG_STATE_HOME/opencode/service.json},
     * defaulting to {@code ~/.local/state/opencode/service.json} when
     * {@code XDG_STATE_HOME} is unset (the JS {@code fallback()}).
     */
    public static Path defaultRegistrationFile(String xdgStateHome, Path userHome) {
        Path stateHome = xdgStateHome != null && !xdgStateHome.isBlank()
                ? Path.of(xdgStateHome.trim())
                : userHome.resolve(".local").resolve("state");
        return stateHome.resolve("opencode").resolve("service.json");
    }

    /**
     * The legacy password file: {@code $XDG_CONFIG_HOME/opencode/service.json},
     * defaulting to {@code ~/.config/opencode/service.json}. Documented
     * assumption: opencode's config dir follows {@code XDG_CONFIG_HOME} with
     * the {@code ~/.config} fallback (verified on Windows, where XDG is unset).
     */
    public static Path defaultPasswordFile(String xdgConfigHome, Path userHome) {
        Path configHome = xdgConfigHome != null && !xdgConfigHome.isBlank()
                ? Path.of(xdgConfigHome.trim())
                : userHome.resolve(".config");
        return configHome.resolve("opencode").resolve("service.json");
    }

    // ---- production seams ----

    /** The real {@code /api/info} probe, authenticated exactly like the real client. */
    static Prober httpProber() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(ClientTuning.PROBE_CONNECT_TIMEOUT)
                .build();
        return (baseUrl, authHeader) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(baseUrl.resolve("/api/info"))
                    .timeout(ClientTuning.HEALTH_PROBE_TIMEOUT)
                    .GET();
            if (authHeader != null) {
                request.header("Authorization", authHeader);
            }
            try {
                HttpResponse<String> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new ProbeOutcome(response.statusCode(), response.body());
            } catch (IOException e) {
                return ProbeOutcome.unreachable();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ProbeOutcome.unreachable();
            }
        };
    }

    /** Runs {@code opencode service status} with a bounded wait; {@code null} on any failure. */
    static StatusCommand statusCommand(String configuredBinary) {
        return () -> {
            Path binary = BinaryResolver.resolveBinary(configuredBinary);
            if (binary == null) {
                return null;
            }
            try {
                Process process = new ProcessBuilder(wrapForWindows(binary, "service", "status"))
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                return output;
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
    }

    /**
     * Spawns {@code opencode serve --service} (the JS client's default managed
     * command). The process is deliberately detached from this JVM's lifecycle
     * — it is the per-user background service and must outlive the IDE; nothing
     * here redirects, tracks or kills it.
     */
    static Starter serviceStarter(String configuredBinary) {
        return () -> {
            Path binary = BinaryResolver.resolveBinary(configuredBinary);
            if (binary == null) {
                throw new IOException("opencode binary not found - configure it in "
                        + "Preferences → OpenCode, or put 'opencode' on PATH");
            }
            ProcessBuilder builder = new ProcessBuilder(wrapForWindows(binary, "serve", "--service"));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            return builder.start();
        };
    }

    /**
     * npm installs {@code opencode} as a {@code .cmd} shim on Windows, which
     * {@link ProcessBuilder} cannot exec directly — mirror of the launcher's
     * handling.
     */
    private static List<String> wrapForWindows(Path binary, String... args) {
        List<String> command = new ArrayList<>();
        String name = binary.getFileName().toString().toLowerCase();
        if (name.endsWith(".cmd") || name.endsWith(".bat")) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(binary.toString());
        command.addAll(List.of(args));
        return command;
    }
}
