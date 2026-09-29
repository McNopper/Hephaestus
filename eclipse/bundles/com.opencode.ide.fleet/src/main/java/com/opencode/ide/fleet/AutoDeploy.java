package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

import com.opencode.ide.tasks.TaskStore;

/**
 * U-034 auto-deploy: when a merge-back landed ECLIPSE-FACING changes
 * (anything under {@code eclipse/}), the engine triggers the central
 * reactor build and refreshes the dropins ({@code eclipse/auto-deploy.ps1},
 * deploy-dev semantics). The contract is deliberate and narrow:
 *
 * <ul>
 *   <li><b>Red builds never deploy.</b> The script exits non-zero before
 *       touching the dropins; the ticket is marked with the failure and
 *       surfaces as NEEDS-HUMAN (blocked) per the escalation policy.</li>
 *   <li><b>The user is only ever told "restart Eclipse" when new jars
 *       actually landed</b> - exactly once, as the {@code deploy:} comment;
 *       every deploy is also recorded in {@code .git/opencode-fleet/last-deploy.log}.</li>
 *   <li><b>It never blocks the settle path</b> - the build runs on the
 *       shared executor (the Eclipse JobManager bridge, per host
 *       discipline), long after the launch returned.</li>
 *   <li><b>B-005 precondition:</b> the stdio tool servers run off staged
 *       jar copies, so a reactor rebuild can never rot a running server's
 *       classpath.</li>
 * </ul>
 *
 * <p>Pure Java apart from the {@link #processRunner()} seam; the decision
 * ({@link #touchesEclipseCode}) and the script contract ({@link #parse})
 * are static and unit-pinned.</p>
 */
public final class AutoDeploy {

    /** Author of every auto-deploy store write (attributable per NFR-AUDIT-001 doctrine). */
    public static final String BY = "auto-deploy";

    /** Runs the deploy command, streaming stdout lines; returns the exit code (test seam). */
    @FunctionalInterface
    public interface Runner {
        int run(List<String> command, java.util.function.Consumer<String> stdoutLine) throws Exception;
    }

    /** The script's parsed contract: {@code DEPLOYED <iso>} / {@code NOTHING <reason>} / red build. */
    public record Result(boolean deployed, boolean redBuild, String line) {
    }

    private AutoDeploy() {
    }

    /** Eclipse-facing = anything under {@code eclipse/} (bundles, features, releng, scripts). */
    public static boolean touchesEclipseCode(List<String> changedFiles) {
        if (changedFiles == null) {
            return false;
        }
        return changedFiles.stream()
                .filter(java.util.Objects::nonNull)
                .map(file -> file.replace('\\', '/'))
                .anyMatch(file -> file.startsWith("eclipse/"));
    }

    /**
     * Parses the script's stdout + exit code per the auto-deploy.ps1
     * contract: any non-zero exit is a RED build (nothing was deployed);
     * {@code DEPLOYED <iso>} means new jars landed; everything else is a
     * no-op outcome.
     */
    public static Result parse(String stdout, int exitCode) {
        String line = lastLine(stdout);
        if (exitCode != 0) {
            return new Result(false, true, line);
        }
        return new Result(line.startsWith("DEPLOYED "), false, line);
    }

    /** The last non-blank line of the script's output (its contract line). */
    static String lastLine(String stdout) {
        if (stdout == null) {
            return "";
        }
        String[] lines = stdout.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }
        return "";
    }

    /**
     * The production {@link TaskFleet.MergeObserver}: decides per merged
     * file set, runs the deploy asynchronously on the given executor, and
     * records the outcome on the ticket - {@code deploy: jars refreshed -
     * restart Eclipse} on a real deploy, the failure as NEEDS-HUMAN on a
     * red build. Never throws into the engine.
     */
    public static TaskFleet.MergeObserver mergeObserver(TaskStore store, Path repoRoot,
            Executor executor, Runner runner) {
        return (project, ticketId, changedFiles) -> {
            if (!touchesEclipseCode(changedFiles)) {
                return;
            }
            executor.execute(() -> runAndRecord(store, repoRoot, runner, project, ticketId));
        };
    }

    private static void runAndRecord(TaskStore store, Path repoRoot, Runner runner,
            String project, String ticketId) {
        String script = repoRoot.resolve("eclipse").resolve("auto-deploy.ps1").toString();
        try {
            StringBuilder out = new StringBuilder();
            int exit = runner.run(
                    List.of("pwsh", "-NoProfile", "-File", script),
                    line -> out.append(line).append('\n'));
            Result result = parse(out.toString(), exit);
            if (result.redBuild()) {
                store.setBlocked(project, ticketId,
                        "auto-deploy: RED build - " + result.line(), BY);
            } else if (result.deployed()) {
                store.addComment(project, ticketId,
                        "deploy: jars refreshed - restart Eclipse (" + result.line() + ")", BY);
            } else {
                store.addComment(project, ticketId, "deploy: " + result.line(), BY);
            }
        } catch (Exception e) {
            // a deploy tool failure is a human decision, never a launch failure
            store.setBlocked(project, ticketId,
                    "auto-deploy failed: " + e.getMessage(), BY);
        }
    }

    /** The production {@link Runner}: pwsh over the script, stdout lines to the consumer. */
    public static Runner processRunner() {
        return (command, stdoutLine) -> {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutLine.accept(line);
                }
            }
            return process.waitFor();
        };
    }
}
