package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.util.List;

/**
 * Headless entry point for the fleet daemon (V-006): composes exactly the
 * tool surface {@link FleetStdioMain} serves - one {@link FleetToolProvider}
 * over the task store - but hosts it in a detached per-repo daemon
 * (loopback TCP, pidfile in {@code .git/opencode-fleet}) so the engine
 * outlives any client session. The {@code eclipse/fleet-daemon.ps1}
 * launcher resolves the jars (same jar/gson set as {@code fleet-tools.ps1})
 * and starts this detached; the daemon idles without spawning the engine -
 * it stays lazy, exactly as in the stdio server.
 *
 * <p>Logs {@code fleet daemon listening on 127.0.0.1:<port>} once the
 * pidfile is published (the launcher's readiness signal), then blocks until
 * a {@code daemon/shutdown} arrives or the JVM dies. A JVM shutdown hook
 * runs the same graceful {@link FleetDaemon#stop()} path, so the pidfile
 * never outlives the process even on a plain kill of the wrapper.</p>
 */
public final class FleetDaemonMain {

    private FleetDaemonMain() {
    }

    /** Starts the daemon and blocks until shutdown; exits non-zero when a daemon is already live. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(".opencode", "tasks");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--root".equals(args[i])) {
                root = Path.of(args[i + 1]);
                break;
            }
        }
        FleetToolProvider provider = new FleetToolProvider(root);
        // the daemon owns the engine: daemon/shutdown drains through
        // FleetControl.close() (stop admission, bounded grace, kill the
        // spawned server tree) before the pidfile goes away
        FleetDaemon daemon = FleetDaemon.start(
                FleetControl.repoRootOf(root), List.of(provider), provider::close);
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::stop, "fleet-daemon-shutdown"));
        System.out.println("fleet daemon listening on 127.0.0.1:" + daemon.port());
        System.out.flush();
        try {
            daemon.awaitStopped();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
