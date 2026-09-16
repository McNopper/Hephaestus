package com.opencode.ide.fleet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tools.McpDispatcher;

/**
 * Standalone stdio entry point for the fleet tools (chat-first control): a
 * minimal line-based MCP JSON-RPC loop over stdin/stdout, modeled on
 * {@code TasksStdioMain} and reusing the same {@link McpDispatcher}. The
 * {@code eclipse/fleet-tools.ps1} launcher resolves the jars (fleet, client,
 * git, tasks, tools, gson); the default store root is
 * {@code .opencode/tasks} relative to the working directory, so an opencode
 * session started in a repository controls that repo's fleet. The loop speaks
 * the same subset as the tasks launcher (initialize, ping, tools/list,
 * tools/call); notifications produce no output.
 *
 * <p>A shutdown hook closes the {@link FleetControl}, killing the spawned
 * {@code opencode serve} process when opencode closes stdin.</p>
 *
 * <p>V-006 daemon modes, selected by the {@code FLEET_DAEMON} environment
 * variable (set it per MCP server in the opencode config or in the launching
 * shell): {@code off} (default) keeps today's behavior byte-for-byte — this
 * process owns a local engine and the shutdown hook kills engine + jobs on
 * stdin close; {@code auto} attaches as a thin proxy ({@link
 * FleetDaemonProxy}) when {@link DaemonProwl} finds a live daemon in the
 * repo's pidfile, else owns a local engine exactly as today; {@code always}
 * proxies or fails fast with a clear stderr message and a non-zero exit, for
 * sessions that must not double-spawn engines. The decision itself is the
 * pure function {@link #decide(String, boolean)} so its truth table is
 * unit-testable without processes. In proxy mode the client detaching never
 * shuts the daemon down (runs keep running); a daemon death mid-session ends
 * this process non-zero — opencode then restarts the stdio server, which
 * re-evaluates the mode; an attach failure in {@code auto} mode falls back
 * to a local engine with a warning.</p>
 */
public final class FleetStdioMain {

    /** What this stdio process should serve. */
    public enum StdioMode {
        /** Today's behavior: a local {@link FleetControl} owned by this process. */
        OWN_ENGINE,
        /** A thin verbatim line pump to a live fleet daemon. */
        PROXY,
        /** {@code always} without a live daemon: refuse to start, exit non-zero. */
        FAIL
    }

    private FleetStdioMain() {
    }

    /** Runs the stdio loop (or the daemon proxy); returns when stdin closes. */
    public static void main(String[] args) throws Exception {
        Path root = storeRoot(args);
        String env = System.getenv("FLEET_DAEMON");
        DaemonProwl.Contact daemon = null;
        if (needsDaemonProbe(env)) {
            // off/unset never touches the pidfile: byte-for-byte today's behavior
            daemon = DaemonProwl.probe(FleetControl.repoRootOf(root)).orElse(null);
        }
        StdioMode mode = decide(env, daemon != null);
        if (mode == StdioMode.OWN_ENGINE) {
            warnUnrecognized(env);
        }
        switch (mode) {
            case FAIL -> failFast(FleetControl.repoRootOf(root));
            case PROXY -> serveAsProxy("always".equals(normalized(env)), daemon, root);
            case OWN_ENGINE -> serveOwnedEngine(root);
        }
    }

    /**
     * The pure mode decision:
     *
     * <table>
     *   <tr><th>{@code FLEET_DAEMON}</th><th>live daemon</th><th>decision</th></tr>
     *   <tr><td>off / unset / blank</td><td>ignored</td><td>{@link StdioMode#OWN_ENGINE}</td></tr>
     *   <tr><td>auto</td><td>yes</td><td>{@link StdioMode#PROXY}</td></tr>
     *   <tr><td>auto</td><td>no</td><td>{@link StdioMode#OWN_ENGINE}</td></tr>
     *   <tr><td>always</td><td>yes</td><td>{@link StdioMode#PROXY}</td></tr>
     *   <tr><td>always</td><td>no</td><td>{@link StdioMode#FAIL}</td></tr>
     *   <tr><td>anything else</td><td>ignored</td><td>{@link StdioMode#OWN_ENGINE}</td></tr>
     * </table>
     *
     * <p>Unrecognized values behave as {@code off} (the safe historical
     * default; the caller warns) rather than guessing a stronger mode — a
     * typo must not silently turn a local engine into a hard failure.</p>
     */
    public static StdioMode decide(String envValue, boolean daemonLive) {
        switch (normalized(envValue)) {
            case "auto":
                return daemonLive ? StdioMode.PROXY : StdioMode.OWN_ENGINE;
            case "always":
                return daemonLive ? StdioMode.PROXY : StdioMode.FAIL;
            default:
                return StdioMode.OWN_ENGINE;
        }
    }

    /** Trims and lowercases; {@code null} becomes the empty string. */
    private static String normalized(String envValue) {
        return envValue == null ? "" : envValue.trim().toLowerCase(Locale.ROOT);
    }

    /** Only auto/always need to know whether a daemon exists. */
    private static boolean needsDaemonProbe(String envValue) {
        String value = normalized(envValue);
        return "auto".equals(value) || "always".equals(value);
    }

    private static void warnUnrecognized(String envValue) {
        String value = normalized(envValue);
        if (!value.isEmpty() && !"off".equals(value) && !"auto".equals(value) && !"always".equals(value)) {
            System.err.println("FLEET_DAEMON='" + envValue.trim()
                    + "' is not one of off|auto|always; behaving as 'off'");
        }
    }

    private static void failFast(Path repoRoot) {
        System.err.println("FLEET_DAEMON=always: no live fleet daemon for this repository (pidfile "
                + FleetGit.fleetRoot(repoRoot).resolve("daemon.json")
                + "); start one with eclipse/fleet-daemon.ps1");
        System.exit(1);
    }

    private static void serveAsProxy(boolean strict, DaemonProwl.Contact daemon, Path root) throws Exception {
        System.err.println("attaching to fleet daemon on 127.0.0.1:" + daemon.port());
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            FleetDaemonProxy.Attached proxy = FleetDaemonProxy.attach(
                    "127.0.0.1", daemon.port(), daemon.token(), System.in, out);
            if (proxy.awaitCompletion() == FleetDaemonProxy.End.DAEMON_CLOSED) {
                System.err.println("fleet daemon closed the connection; exiting non-zero"
                        + " (the next start re-evaluates FLEET_DAEMON)");
                System.exit(1);
            }
            // CLIENT_CLOSED: the session left; exit 0 and leave the daemon running
        } catch (IOException e) {
            if (strict) {
                System.err.println("FLEET_DAEMON=always: cannot attach to the fleet daemon on 127.0.0.1:"
                        + daemon.port() + ": " + e.getMessage());
                System.exit(1);
            }
            System.err.println("fleet daemon attach failed (" + e.getMessage()
                    + "); running a local engine instead");
            serveOwnedEngine(root);
        }
    }

    private static void serveOwnedEngine(Path root) throws IOException {
        FleetToolProvider provider = new FleetToolProvider(root);
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "fleet-shutdown"));
        McpDispatcher dispatcher = new McpDispatcher(provider);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String response = dispatcher.handle(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
        }
    }

    private static Path storeRoot(String[] args) {
        Path root = Path.of(".opencode", "tasks");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--root".equals(args[i])) {
                root = Path.of(args[i + 1]);
                break;
            }
        }
        return root;
    }
}
