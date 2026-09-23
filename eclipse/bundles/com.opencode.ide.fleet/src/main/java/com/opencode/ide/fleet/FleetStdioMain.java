package com.opencode.ide.fleet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.opencode.ide.tools.McpDispatcher;

/**
 * Standalone stdio entry point for the fleet tools (chat-first control): a
 * minimal line-based MCP JSON-RPC loop over stdin/stdout, modeled on
 * {@code TasksStdioMain} and reusing the same {@link McpDispatcher}. The
 * {@code eclipse/fleet-tools.ps1} launcher resolves the jars (fleet, client,
 * git, tasks, tools, gson, and the Eclipse JobManager - {@link WorkerPools}
 * is the same {@code JobGroup} bridge in every host); the default store root
 * is {@code .opencode/tasks} relative to the working directory, so an
 * opencode session started in a repository controls that repo's fleet.
 *
 * <p>Host discipline (user direction 2026-09-23: "either we let things run in
 * opencode ... or we let it run in Eclipse. everything else is reinventing
 * the wheel"): the AUTOMATIC pump - auto-dispatch and recurring waves - lives
 * in Eclipse, and when Eclipse is closed the fleet simply does not pump, by
 * design. This process is opencode's own MCP tool server and serves the
 * chat's MANUAL fleet control over that repo's engine. The former V-006
 * detached daemon (a third runtime: TCP core, proxy, pidfile, launcher) is
 * retired - there is nothing left to attach to and nothing left to keep
 * alive outside the two hosts.</p>
 *
 * <p>A shutdown hook closes the {@link FleetControl}, killing the spawned
 * {@code opencode serve} process when opencode closes stdin.</p>
 */
public final class FleetStdioMain {

    private FleetStdioMain() {
    }

    /** Runs the stdio loop; returns when stdin closes. */
    public static void main(String[] args) throws Exception {
        serveOwnedEngine(storeRoot(args));
    }

    private static void serveOwnedEngine(Path root) throws IOException {
        FleetToolProvider provider = new FleetToolProvider(root);
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "fleet-shutdown"));
        McpDispatcher dispatcher = new McpDispatcher(provider);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        // B-012 hardened (shared with TasksStdioMain): a dead pipe or an
        // orphaned launcher exits NONZERO so the host respawns instead of
        // keeping a zombie JVM. The shutdown hook above runs on System.exit
        // and kills the engine's spawned serve in every exit path.
        int code = com.opencode.ide.tools.StdioServer.serve(
                in, out, dispatcher, new com.opencode.ide.tools.StdioServer.ParentLiveness());
        if (code != com.opencode.ide.tools.StdioServer.EXIT_OK) {
            System.exit(code);
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
