package com.opencode.ide.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Resolves the {@code opencode} executable. The pure logic is split out (with
 * the environment read separately) so resolution can be unit-tested with a
 * controlled PATH and OS flag.
 */
public final class BinaryResolver {

    private BinaryResolver() {
    }

    /** Resolves using the real environment: an explicit path, else the {@code PATH}. */
    public static Path resolveBinary(String configured) {
        return resolveBinary(configured, System.getenv("PATH"), isWindows());
    }

    /**
     * @param configured an explicit binary path chosen by the user (may be {@code null}/blank)
     * @param pathEnv    the {@code PATH} string (semicolon on Windows, colon on Unix)
     * @param windows    whether to look for {@code .cmd/.exe/.bat} shims (true) or bare/{@code .sh} (false)
     * @return the resolved binary, or {@code null} if none found
     */
    public static Path resolveBinary(String configured, String pathEnv, boolean windows) {
        return resolveBinary(configured, pathEnv, windows, Files::isRegularFile, Files::isExecutable);
    }

    /** Filesystem seam for deterministic platform tests, including Unix execute permissions. */
    public static Path resolveBinary(String configured, String pathEnv, boolean windows,
            Predicate<Path> regularFile, Predicate<Path> executable) {
        if (configured != null && !configured.isBlank()) {
            Path explicit = Path.of(configured.trim());
            if (regularFile.test(explicit) && (windows || executable.test(explicit))) {
                return explicit;
            }
        }
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        List<String> suffixes = windows
                ? List.of(".cmd", ".exe", ".bat", "")
                : List.of("", ".sh");
        for (String dir : pathEnv.split(windows ? ";" : ":")) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : suffixes) {
                Path candidate = Path.of(dir, "opencode" + ext);
                if (regularFile.test(candidate) && (windows || executable.test(candidate))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
