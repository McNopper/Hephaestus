package com.opencode.ide.tasks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.opencode.ide.tools.McpDispatcher;

/**
 * Standalone stdio entry point for the task tools (TUI-only use, replacing
 * the retired Python {@code pm} stdio server): a minimal line-based MCP
 * JSON-RPC loop over stdin/stdout, reusing the same {@link McpDispatcher}
 * and {@link TaskToolProvider} the Eclipse-hosted {@code eclipse-build}
 * endpoint serves - one tool surface, two transports.
 *
 * <p>Usage: {@code java -cp <tasks-jar>;<gson-jar>;<tools-jar>
 * com.opencode.ide.tasks.TasksStdioMain [--root <dir>]} - the
 * {@code eclipse/tasks-tools.ps1} launcher resolves the jars. The default
 * root is {@code .opencode/tasks} relative to the working directory, so an
 * opencode session started in a repository shares that repo's task store.
 * The loop speaks the same subset as the HTTP endpoint (initialize, ping,
 * tools/list, tools/call); notifications produce no output.</p>
 */
public final class TasksStdioMain {

    private TasksStdioMain() {
    }

    /**
     * Runs the stdio loop (B-012 hardened: returns on clean stdin EOF;
     * a dead pipe or an orphaned launcher exits NONZERO so the host respawns
     * instead of keeping a zombie JVM — see {@link StdioServer}).
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(".opencode", "tasks");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--root".equals(args[i])) {
                root = Path.of(args[i + 1]);
                break;
            }
        }
        McpDispatcher dispatcher = new McpDispatcher(new TaskToolProvider(root));
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int code = com.opencode.ide.tools.StdioServer.serve(
                in, out, dispatcher, new com.opencode.ide.tools.StdioServer.ParentLiveness());
        if (code != com.opencode.ide.tools.StdioServer.EXIT_OK) {
            // abnormal end: nonzero exit = "respawn me" (B-012); the clean EOF
            // path returns normally so in-process callers keep working
            System.exit(code);
        }
    }
}
