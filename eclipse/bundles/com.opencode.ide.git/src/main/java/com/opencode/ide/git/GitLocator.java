package com.opencode.ide.git;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Locates the git executable: PATH first, then well-known Windows install
 * locations. The environment read is kept separate from the probing logic so
 * the probe order is unit-testable without touching the real PATH.
 *
 * <p>Exported API (review S2): every bundle that shells out to git resolves
 * its binary here — one discovery rule product-wide. It previously lived in
 * the internal package and leaked to consumers via an x-friends exception;
 * the export makes that coupling a contract instead.</p>
 */
public final class GitLocator {

    public static final List<Path> WINDOWS_FALLBACKS = List.of(
            Path.of("C:\\Program Files\\Git\\cmd\\git.exe"),
            Path.of("C:\\Program Files\\Git\\bin\\git.exe"));

    private GitLocator() {
    }

    /** The git command to invoke plus a note on how it was located. */
    public record Resolution(Path command, String source) {
    }

    public static Resolution resolve() {
        Path found = resolveGit(System.getenv("PATH"), isWindows(), WINDOWS_FALLBACKS);
        if (found == null) {
            return new Resolution(Path.of("git"),
                    "not found on PATH nor at " + WINDOWS_FALLBACKS);
        }
        return new Resolution(found, WINDOWS_FALLBACKS.contains(found) ? "fallback probe" : "PATH");
    }

    /**
     * @param pathEnv       the {@code PATH} string (entries split by {@link File#pathSeparator})
     * @param windows       whether to probe {@code .exe/.cmd/.bat} shims (true) or bare/{@code .sh} (false)
     * @param fallbackProbes absolute locations probed when PATH yields nothing
     * @return the git executable, or {@code null} when nothing matched
     */
    public static Path resolveGit(String pathEnv, boolean windows, List<Path> fallbackProbes) {
        if (pathEnv != null && !pathEnv.isBlank()) {
            List<String> suffixes = windows ? List.of(".exe", ".cmd", ".bat", "") : List.of("", ".sh");
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                for (String ext : suffixes) {
                    Path candidate = Path.of(dir, "git" + ext);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        for (Path probe : fallbackProbes) {
            if (Files.isRegularFile(probe)) {
                return probe;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
