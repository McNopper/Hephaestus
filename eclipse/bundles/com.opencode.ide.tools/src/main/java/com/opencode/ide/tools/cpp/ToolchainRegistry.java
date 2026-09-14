package com.opencode.ide.tools.cpp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects the C/C++ toolchains available on this machine, in default order
 * MSVC first, then the MSYS2 environments clang64, mingw64, ucrt64 on Windows;
 * native GCC then Clang from PATH on Unix. Also
 * resolves the lint/format tools: clang-tidy and clang-format per toolchain
 * (MSYS2 env bin, standalone LLVM, then PATH) and cppcheck as a standalone
 * lint tool. Detection shells out to vswhere / cmake --help and is cached for
 * the lifetime of the JVM (machine facts do not change mid-run).
 */
public final class ToolchainRegistry {

    /** One detected toolchain. Absent optionals mean the tool is not available; tools must fail with a clear message. */
    public record Toolchain(String id, String displayName, Optional<Path> cmake, Optional<Path> ninja,
            Optional<Path> compiler, Optional<Path> ctest, Optional<Path> gdb, Optional<Path> clangTidy,
            Optional<Path> clangFormat, Optional<String> generator, List<Path> pathPrepend) {

        /**
         * CMake arguments that select this toolchain's native C/C++ compiler pair.
         * Native toolchains must carry the C++ driver with its C sibling in the
         * same directory, as guaranteed by {@link ToolchainRegistry#detectNative}.
         * Windows toolchains return an empty list: their generator/PATH selects the compiler.
         *
         * @return immutable arguments, each passed to CMake as one process argument
         */
        public List<String> compilerArguments() {
            if (!"gcc".equals(id) && !"clang".equals(id)) {
                return List.of();
            }
            Path cxx = compiler.orElseThrow();
            Path c = cxx.resolveSibling("gcc".equals(id) ? "gcc" : "clang");
            return List.of("-DCMAKE_C_COMPILER=" + c, "-DCMAKE_CXX_COMPILER=" + cxx);
        }
    }

    private static final Path MSYS2_ROOT = Paths.get("C:\\msys64");
    private static final Path VSWHERE =
            Paths.get("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe");
    private static final Path STANDALONE_CMAKE = Paths.get("C:\\Program Files\\CMake\\bin\\cmake.exe");
    private static final Path STANDALONE_LLVM_BIN = Paths.get("C:\\Program Files\\LLVM\\bin");
    private static final Path STANDALONE_CPPCHECK = Paths.get("C:\\Program Files\\Cppcheck\\cppcheck.exe");
    private static final List<String> MSYS_ENV_IDS = List.of("clang64", "mingw64", "ucrt64");
    private static final java.util.Map<String, List<String>> MSYS_COMPILERS = java.util.Map.of(
            "clang64", List.of("clang++", "clang"),
            "mingw64", List.of("clang++", "g++", "gcc"),
            "ucrt64", List.of("g++", "gcc"));
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final Pattern VS_GENERATOR = Pattern.compile("Visual Studio (\\d+) (\\d{4})");

    private static volatile List<Toolchain> cached;
    private static volatile Optional<Path> cachedCppcheck;

    private ToolchainRegistry() {
    }

    /** @return the cached detection result, detecting on first use in platform default order. */
    public static List<Toolchain> detected() {
        List<Toolchain> result = cached;
        if (result == null) {
            synchronized (ToolchainRegistry.class) {
                result = cached;
                if (result == null) {
                    result = detect();
                    cached = result;
                }
            }
        }
        return result;
    }

    /** Runs the actual detection (vswhere, filesystem probes, cmake --help); no caching. */
    public static List<Toolchain> detect() {
        if (!isWindows()) {
            return detectNative(ToolchainRegistry::which);
        }
        List<Toolchain> list = new ArrayList<>();
        detectMsvc().ifPresent(list::add);
        for (String id : MSYS_ENV_IDS) {
            detectMsysEnv(id).ifPresent(list::add);
        }
        return List.copyOf(list);
    }

    /**
     * Discovers native GCC/Clang toolchains using a caller-supplied executable resolver,
     * without reading or modifying the registry cache or process environment.
     *
     * @param lookup resolves bare tool names to absolute executable paths, or an empty
     *        optional when unavailable; the caller is responsible for executable validation
     * @return immutable list in GCC-then-Clang order; only complete, co-located C/C++
     *         pairs are included, with absent build tools represented by empty optionals
     */
    public static List<Toolchain> detectNative(Function<String, Optional<Path>> lookup) {
        List<Toolchain> list = new ArrayList<>();
        Optional<Path> cmake = lookup.apply("cmake");
        Optional<Path> ninja = lookup.apply("ninja");
        Optional<String> generator = ninja.isPresent() ? Optional.of("Ninja")
                : lookup.apply("make").map(p -> "Unix Makefiles");
        for (String id : List.of("gcc", "clang")) {
            Optional<Path> c = lookup.apply(id);
            Optional<Path> cxx = lookup.apply("gcc".equals(id) ? "g++" : "clang++");
            // Do not advertise a C/C++ pair if one language is missing or the drivers
            // come from different installations. This also makes selection deterministic.
            if (c.isEmpty() || cxx.isEmpty() || !c.get().getParent().equals(cxx.get().getParent())) {
                continue;
            }
            list.add(new Toolchain(id, "gcc".equals(id) ? "Native GCC" : "Native Clang",
                    cmake, ninja, cxx, lookup.apply("ctest"), lookup.apply("gdb"),
                    lookup.apply("clang-tidy"), lookup.apply("clang-format"), generator, List.of()));
        }
        return List.copyOf(list);
    }

    /** @return the toolchain with the given id, if detected. */
    public static Optional<Toolchain> byId(String id) {
        return detected().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** @return the first detected toolchain in platform default order. */
    public static Optional<Toolchain> defaultToolchain() {
        List<Toolchain> all = detected();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Standalone lint tool, not tied to any toolchain: the Cppcheck installer
     * location first, then PATH. Cached like the toolchain detection.
     *
     * @return the cppcheck executable path if present on this machine
     */
    public static Optional<Path> cppcheck() {
        Optional<Path> result = cachedCppcheck;
        if (result == null) {
            synchronized (ToolchainRegistry.class) {
                result = cachedCppcheck;
                if (result == null) {
                    result = isWindows() ? exe(STANDALONE_CPPCHECK).or(() -> which("cppcheck"))
                            : which("cppcheck");
                    cachedCppcheck = result;
                }
            }
        }
        return result;
    }

    private static Optional<Toolchain> detectMsysEnv(String id) {
        Path bin = MSYS2_ROOT.resolve(id).resolve("bin");
        if (!Files.isDirectory(bin)) {
            return Optional.empty();
        }
        Optional<Path> cmake = exe(bin.resolve("cmake.exe"));
        Optional<Path> ninja = exe(bin.resolve("ninja.exe"));
        Optional<Path> compiler = MSYS_COMPILERS.get(id).stream()
                .map(name -> bin.resolve(name + ".exe"))
                .filter(Files::isRegularFile)
                .findFirst();
        if (cmake.isEmpty() && compiler.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> ctest = exe(bin.resolve("ctest.exe"));
        Optional<Path> gdb = exe(bin.resolve("gdb.exe")).or(() -> which("gdb"));
        Optional<Path> clangTidy = exe(bin.resolve("clang-tidy.exe")).or(() -> which("clang-tidy"));
        Optional<Path> clangFormat = exe(bin.resolve("clang-format.exe")).or(() -> which("clang-format"));
        Optional<String> generator = ninja.isPresent() ? Optional.of("Ninja") : Optional.empty();
        String compilerName = compiler.map(p -> p.getFileName().toString().replace(".exe", ""))
                .orElse("no compiler");
        String displayName = "MSYS2 " + id + " (" + compilerName + (generator.isPresent() ? " + Ninja)" : ")");
        return Optional.of(new Toolchain(id, displayName, cmake, ninja, compiler, ctest, gdb, clangTidy,
                clangFormat, generator, List.of(bin)));
    }

    private static Optional<Toolchain> detectMsvc() {
        if (!Files.isRegularFile(VSWHERE)) {
            return Optional.empty();
        }
        String installPath = capture(VSWHERE, List.of("-latest", "-products", "*", "-property", "installationPath"));
        if (installPath == null || installPath.isBlank()) {
            return Optional.empty();
        }
        Path vsDir = Paths.get(installPath.trim());
        if (!Files.isDirectory(vsDir)) {
            return Optional.empty();
        }
        String version = capture(VSWHERE,
                List.of("-latest", "-products", "*", "-property", "installationVersion"));
        int major = parseMajor(version);
        Optional<Path> cmake = exe(STANDALONE_CMAKE).or(() -> which("cmake"));
        Optional<Path> ctest = cmake.map(p -> p.resolveSibling("ctest.exe"))
                .filter(Files::isRegularFile);
        Optional<Path> ninja = which("ninja");
        Optional<Path> gdb = which("gdb");
        Optional<Path> clangTidy = exe(STANDALONE_LLVM_BIN.resolve("clang-tidy.exe"))
                .or(() -> which("clang-tidy"));
        Optional<Path> clangFormat = exe(STANDALONE_LLVM_BIN.resolve("clang-format.exe"))
                .or(() -> which("clang-format"));
        Optional<Path> compiler = findCl(vsDir);
        Optional<String> generator = msvcGenerator(major, cmake);
        String displayName = generator.map(g -> "MSVC (" + g + " generator)").orElse("MSVC");
        return Optional.of(new Toolchain("msvc", displayName, cmake, ninja, compiler, ctest, gdb, clangTidy,
                clangFormat, generator, List.of()));
    }

    /** Picks the matching "Visual Studio <major> <year>" generator from cmake --help, with a static fallback. */
    private static Optional<String> msvcGenerator(int major, Optional<Path> cmake) {
        if (cmake.isPresent() && major > 0) {
            String help = capture(cmake.get(), List.of("--help"));
            if (help != null) {
                String clean = ANSI.matcher(help).replaceAll("");
                Matcher m = VS_GENERATOR.matcher(clean);
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) == major) {
                        return Optional.of(m.group(0));
                    }
                }
            }
        }
        return switch (major) {
            case 17 -> Optional.of("Visual Studio 17 2022");
            default -> Optional.of("Visual Studio 18 2026");
        };
    }

    /** Newest cl.exe under the VS installation's VC Tools (bin/Hostx64/x64). */
    private static Optional<Path> findCl(Path vsDir) {
        Path base = vsDir.resolve("VC").resolve("Tools").resolve("MSVC");
        if (!Files.isDirectory(base)) {
            return Optional.empty();
        }
        try (Stream<Path> versions = Files.list(base)) {
            return versions.filter(Files::isDirectory)
                    .filter(v -> v.getFileName() != null)
                    .max(Comparator.comparing(v -> v.getFileName().toString(),
                            ToolchainRegistry::compareVersions))
                    .map(v -> v.resolve("bin").resolve("Hostx64").resolve("x64").resolve("cl.exe"))
                    .filter(Files::isRegularFile);
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }

    private static int compareVersions(String a, String b) {
        int[] va = versionSegments(a);
        int[] vb = versionSegments(b);
        for (int i = 0; i < Math.max(va.length, vb.length); i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] versionSegments(String version) {
        String[] parts = version.split("\\.");
        int[] segments = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                segments[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                segments[i] = 0;
            }
        }
        return segments;
    }

    private static int parseMajor(String version) {
        if (version == null) {
            return -1;
        }
        String trimmed = version.trim();
        int dot = trimmed.indexOf('.');
        String major = dot > 0 ? trimmed.substring(0, dot) : trimmed;
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Runs a helper executable (30s cap) and returns its stdout, or null on failure. */
    private static String capture(Path exe, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(args);
        BuildRunner.ToolResult result = BuildRunner.run(command, List.of(), null, Duration.ofSeconds(30));
        return result.exitCode() == 0 ? result.output() : null;
    }

    static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    static Optional<Path> which(String tool) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        List<Path> directories = Stream.of(path.split(Pattern.quote(File.pathSeparator)))
                .filter(dir -> !dir.isBlank()).map(Paths::get).toList();
        return which(tool, directories, isWindows());
    }

    /**
     * Resolves an executable in an explicit ordered search path, without consulting PATH.
     *
     * @param tool bare executable name without a directory or extension
     * @param directories directories to search, in precedence order
     * @param windows true to try {@code .exe} then the bare name in each directory;
     *        false to require a bare regular file executable on the host filesystem
     * @return the first matching absolute path, or empty if no executable matches
     */
    public static Optional<Path> which(String tool, List<Path> directories, boolean windows) {
        for (Path dir : directories) {
            for (String suffix : windows ? List.of(".exe", "") : List.of("")) {
                Path candidate = dir.resolve(tool + suffix).toAbsolutePath();
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> exe(Path path) {
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }
}
