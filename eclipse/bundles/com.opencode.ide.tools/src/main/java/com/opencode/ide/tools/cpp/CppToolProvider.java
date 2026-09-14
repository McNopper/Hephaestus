package com.opencode.ide.tools.cpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.opencode.ide.tools.McpTool;
import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.ParamError;
import com.opencode.ide.tools.ToolProvider;

/**
 * The C/C++ language pack of the {@code eclipse-build} MCP server:
 * toolchain introspection plus headless CMake configure/build/test/run/debug,
 * linting (clang-tidy, cppcheck) and formatting (clang-format). Implements
 * the {@link ToolProvider} SPI; see its javadoc for how further language
 * packs plug in. All process runs go through {@link BuildRunner} with
 * argument lists only (no shell), merged UTF-8 output and hard wall-clock
 * timeouts.
 */
public final class CppToolProvider implements ToolProvider {

    /** Machine-dependent failure (unknown/undetected toolchain, missing prerequisite, bad path). */
    static final class ToolchainError extends RuntimeException {
        ToolchainError(String message) {
            super(message);
        }
    }

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(15);
    private static final List<String> DEFAULT_GDB_COMMANDS = List.of("run", "bt", "info locals");
    private static final Gson GSON = new Gson();
    private static final Set<String> SOURCE_EXTENSIONS =
            Set.of("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx");
    private static final Pattern ANSI_CODES = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final Pattern DIAGNOSTIC_FILE = Pattern.compile("^(.+?):\\d+:\\d+:\\s*error:",
            Pattern.MULTILINE);
    private static final List<McpTool> TOOLS = buildTools();
    private static final String TOOL_NAMES = TOOLS.stream().map(McpTool::name)
            .collect(Collectors.joining(", "));

    private final LintBinaries injectedLint;
    private volatile LintBinaries detectedLint;

    /** Creates the provider resolving lint/format binaries lazily from the {@link ToolchainRegistry}. */
    public CppToolProvider() {
        this(null);
    }

    /** Test seam: fixed lint/format binaries; empty optionals force the missing-binary paths. */
    public CppToolProvider(LintBinaries lint) {
        this.injectedLint = lint;
    }

    private LintBinaries lint() {
        if (injectedLint != null) {
            return injectedLint;
        }
        LintBinaries result = detectedLint;
        if (result == null) {
            result = LintBinaries.detect();
            detectedLint = result;
        }
        return result;
    }

    @Override
    public String language() {
        return "cpp";
    }

    @Override
    public List<McpTool> tools() {
        return TOOLS;
    }

    @Override
    public McpToolResult call(String toolName, JsonObject arguments) {
        JsonObject args = arguments != null ? arguments : new JsonObject();
        try {
            return switch (toolName) {
                case "toolchains_list" -> McpToolResult.json(toolchainsListJson());
                case "cmake_configure" -> execResult(configure(args));
                case "cmake_build" -> execResult(build(args));
                case "ctest_run" -> execResult(ctest(args));
                case "run_binary" -> execResult(runBinary(args));
                case "debug_batch" -> debugBatch(args);
                case "lint_run" -> lintRun(args);
                case "format_run" -> formatRun(args);
                default -> McpToolResult.error("unknown tool '" + toolName + "'; available tools: " + TOOL_NAMES);
            };
        } catch (ParamError e) {
            throw e;
        } catch (ToolchainError | IOException e) {
            return McpToolResult.error(toolName + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return McpToolResult.error(toolName + " failed: " + e);
        }
    }

    private static List<McpTool> buildTools() {
        List<McpTool> tools = new ArrayList<>();
        tools.add(new McpTool("toolchains_list",
                "List the C/C++ toolchains detected on this machine (msvc, clang64, mingw64, ucrt64, gcc, clang), which "
                        + "tools each provides (cmake/ninja/compiler/ctest/gdb/clang-tidy/clang-format) and its "
                        + "CMake generator, plus a global lint section with the standalone cppcheck path (or null).",
                schema(new JsonObject())));
        JsonObject configureProps = new JsonObject();
        configureProps.add("source_dir", stringProperty(
                "Absolute path to the directory containing CMakeLists.txt"));
        configureProps.add("build_dir", stringProperty(
                "Absolute path to the build directory (created if missing)"));
        configureProps.add("toolchain", toolchainProperty("Toolchain id; omit for the platform default"));
        configureProps.add("build_type", stringProperty("CMAKE_BUILD_TYPE value; default \"Debug\""));
        configureProps.add("extra_args", arrayProperty(
                "Extra cmake arguments, e.g. [\"-DCMAKE_C_COMPILER=...\"]"));
        tools.add(new McpTool("cmake_configure",
                "Run cmake -S <source_dir> -B <build_dir> with the selected toolchain's generator "
                         + "(Ninja or Unix Makefiles for native GCC/Clang, Ninja for MSYS2, Visual Studio for MSVC).",
                schema(configureProps, "source_dir", "build_dir")));
        JsonObject buildProps = new JsonObject();
        buildProps.add("build_dir", stringProperty("Absolute path to an existing build directory"));
        buildProps.add("toolchain", toolchainProperty("Toolchain id; omit for the default"));
        buildProps.add("target", stringProperty("Optional build target; omit to build the default target"));
        tools.add(new McpTool("cmake_build", "Run cmake --build <build_dir> -j (optionally for one target).",
                schema(buildProps, "build_dir")));
        JsonObject ctestProps = new JsonObject();
        ctestProps.add("build_dir", stringProperty("Absolute path to the build directory"));
        ctestProps.add("toolchain", toolchainProperty("Toolchain id; omit for the default"));
        ctestProps.add("test_filter", stringProperty("Optional regex passed to ctest -R"));
        ctestProps.add("extra_args", arrayProperty("Extra ctest arguments"));
        tools.add(new McpTool("ctest_run", "Run ctest --test-dir <build_dir> --output-on-failure.",
                schema(ctestProps, "build_dir")));
        JsonObject runProps = new JsonObject();
        runProps.add("binary", stringProperty("Absolute path to the executable to run"));
        runProps.add("args", arrayProperty("Command-line arguments passed to the binary"));
        runProps.add("cwd", stringProperty("Absolute working directory; omit to inherit"));
        runProps.add("timeout_s", timeoutProperty(
                "Timeout in seconds (default 60, max 600)", 1, 600));
        tools.add(new McpTool("run_binary", "Run a built executable and capture merged stdout+stderr.",
                schema(runProps, "binary")));
        JsonObject debugProps = new JsonObject();
        debugProps.add("binary", stringProperty("Absolute path to the executable to debug"));
        debugProps.add("args", arrayProperty("Command-line arguments passed to the binary"));
        debugProps.add("gdb_commands", arrayProperty(
                "gdb commands executed in order via -ex (default [\"run\",\"bt\",\"info locals\"])"));
        tools.add(new McpTool("debug_batch",
                "Run the binary under gdb --batch (e.g. run + backtrace + info locals). Requires gdb.",
                schema(debugProps, "binary")));
        JsonObject lintProps = new JsonObject();
        lintProps.add("source_dir", stringProperty(
                "Absolute path to the directory whose C/C++ sources are linted"));
        lintProps.add("build_dir", stringProperty(
                "Absolute path to the build directory holding compile_commands.json; required for clang-tidy "
                        + "(produced by cmake_configure)"));
        lintProps.add("tool", enumProperty("Linter to run: \"clang-tidy\" (default) or \"cppcheck\"",
                "clang-tidy", "cppcheck"));
        lintProps.add("files", arrayProperty(
                "Source paths relative to source_dir; default = all discovered sources (*.c,*.cc,*.cpp,*.cxx,"
                        + "*.h,*.hh,*.hpp,*.hxx, recursive, build directories skipped)"));
        lintProps.add("extra_args", arrayProperty("Extra linter arguments"));
        lintProps.add("timeout_s", timeoutProperty(
                "Timeout in seconds per linter run (default 600, max 1800)", 1, 1800));
        tools.add(new McpTool("lint_run",
                "Lint C/C++ sources with clang-tidy (needs build_dir with compile_commands.json, i.e. run "
                        + "cmake_configure first) or cppcheck; returns {tool, files_checked, error_count, output}.",
                schema(lintProps, "source_dir")));
        JsonObject formatProps = new JsonObject();
        formatProps.add("files", arrayProperty("Absolute paths of the files to format"));
        formatProps.add("mode", enumProperty("\"check\" (default, dry-run) or \"apply\" (rewrite in place)",
                "check", "apply"));
        formatProps.add("toolchain", toolchainProperty(
                "Toolchain id used to locate clang-format; omit for the default (first detected that has it)"));
        formatProps.add("style", stringProperty(
                "--style value, e.g. \"llvm\", \"google\" or inline JSON; default \"file\" (falls back to LLVM "
                        + "style when no .clang-format file is found)"));
        tools.add(new McpTool("format_run",
                "Run clang-format over the given files: check mode reports which files would be reformatted "
                        + "(dry-run --Werror), apply mode rewrites them in place and reports reformatted.",
                schema(formatProps, "files")));
        return List.copyOf(tools);
    }

    private static JsonObject toolchainProperty(String description) {
        return enumProperty(description, "msvc", "clang64", "mingw64", "ucrt64", "gcc", "clang");
    }

    private BuildRunner.ToolResult configure(JsonObject args) throws IOException {
        Path source = absolutePath(args, "source_dir");
        Path build = absolutePath(args, "build_dir");
        ToolchainRegistry.Toolchain tc = resolveToolchain(args);
        if (!Files.isDirectory(source)) {
            throw new ToolchainError("source_dir is not an existing directory: " + source);
        }
        Path cmake = tc.cmake()
                .orElseThrow(() -> new ToolchainError("toolchain '" + tc.id()
                        + "' has no cmake (install CMake and add it to PATH or the selected MSYS2 bin directory)"));
        String generator = tc.generator()
                .orElseThrow(() -> new ToolchainError("toolchain '" + tc.id() + "' has no generator "
                        + "(native toolchains need Ninja or Make on PATH; MSYS2 needs ninja.exe in its bin directory)"));
        Files.createDirectories(build);
        String buildType = optString(args, "build_type");
        List<String> command = new ArrayList<>(List.of(cmake.toString(), "-S", source.toString(), "-B",
                build.toString(), "-G", generator,
                "-DCMAKE_BUILD_TYPE=" + (buildType != null ? buildType : "Debug")));
        command.addAll(tc.compilerArguments());
        command.add("-DCMAKE_EXPORT_COMPILE_COMMANDS=ON");
        command.addAll(stringArray(args, "extra_args"));
        return BuildRunner.run(command, tc.pathPrepend(), null, BUILD_TIMEOUT);
    }

    private BuildRunner.ToolResult build(JsonObject args) {
        Path build = absolutePath(args, "build_dir");
        ToolchainRegistry.Toolchain tc = resolveToolchain(args);
        if (!Files.isDirectory(build)) {
            throw new ToolchainError("build_dir is not an existing directory: " + build
                    + " (run cmake_configure first)");
        }
        Path cmake = tc.cmake()
                .orElseThrow(() -> new ToolchainError("toolchain '" + tc.id() + "' has no cmake"));
        List<String> command = new ArrayList<>(List.of(cmake.toString(), "--build", build.toString(), "-j"));
        String target = optString(args, "target");
        if (target != null) {
            command.add("--target");
            command.add(target);
        }
        return BuildRunner.run(command, tc.pathPrepend(), null, BUILD_TIMEOUT);
    }

    private BuildRunner.ToolResult ctest(JsonObject args) {
        Path build = absolutePath(args, "build_dir");
        ToolchainRegistry.Toolchain tc = resolveToolchain(args);
        if (!Files.isDirectory(build)) {
            throw new ToolchainError("build_dir is not an existing directory: " + build);
        }
        Path ctest = tc.ctest()
                .orElseThrow(() -> new ToolchainError("toolchain '" + tc.id() + "' has no ctest"));
        List<String> command = new ArrayList<>(
                List.of(ctest.toString(), "--test-dir", build.toString(), "--output-on-failure"));
        String filter = optString(args, "test_filter");
        if (filter != null) {
            command.add("-R");
            command.add(filter);
        }
        command.addAll(stringArray(args, "extra_args"));
        return BuildRunner.run(command, tc.pathPrepend(), null, BUILD_TIMEOUT);
    }

    private BuildRunner.ToolResult runBinary(JsonObject args) {
        Path binary = absolutePath(args, "binary");
        if (!Files.isRegularFile(binary)) {
            throw new ToolchainError("binary does not exist: " + binary);
        }
        Path cwd = null;
        String cwdRaw = optString(args, "cwd");
        if (cwdRaw != null) {
            cwd = Paths.get(cwdRaw);
            if (!cwd.isAbsolute()) {
                throw new ToolchainError("parameter 'cwd' must be an absolute path, got: " + cwdRaw);
            }
            if (!Files.isDirectory(cwd)) {
                throw new ToolchainError("cwd is not an existing directory: " + cwd);
            }
        }
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.addAll(stringArray(args, "args"));
        return BuildRunner.run(command, List.of(), cwd, Duration.ofSeconds(timeoutSeconds(args, 60, 600)));
    }

    private McpToolResult debugBatch(JsonObject args) {
        Path binary = absolutePath(args, "binary");
        if (!Files.isRegularFile(binary)) {
            throw new ToolchainError("binary does not exist: " + binary);
        }
        List<String> gdbCommands = stringArray(args, "gdb_commands");
        if (gdbCommands.isEmpty()) {
            gdbCommands = DEFAULT_GDB_COMMANDS;
        }
        Optional<Path> fromToolchain = ToolchainRegistry.defaultToolchain()
                .flatMap(ToolchainRegistry.Toolchain::gdb);
        Optional<Path> gdb = fromToolchain.isPresent() ? fromToolchain : whichGdb();
        if (gdb.isEmpty()) {
            if (!ToolchainRegistry.isWindows()) {
                return McpToolResult.error("debug_batch: gdb was not found on PATH. Install gdb with your "
                        + "system package manager (Ubuntu: apt install gdb).");
            }
            return McpToolResult.error("debug_batch: gdb was not found on this machine (checked the default "
                    + "toolchain and PATH). gdb is not silently faked here. Install it into the MSYS2 "
                    + "environment matching your binary, e.g. open C:\\msys64\\ucrt64.exe (or clang64.exe / "
                    + "mingw64.exe) and run: pacman -S gdb (non-interactively: C:\\msys64\\usr\\bin\\bash -lc "
                    + "'pacman -S --noconfirm gdb'; pick the env that matches your build). gdb is not available "
                    + "for the MSVC toolchain.");
        }
        List<String> command = new ArrayList<>(List.of(gdb.get().toString(), "--batch"));
        for (String gdbCommand : gdbCommands) {
            command.add("-ex");
            command.add(gdbCommand);
        }
        command.add("--args");
        command.add(binary.toString());
        command.addAll(stringArray(args, "args"));
        List<Path> pathPrepend = List.of(gdb.get().getParent());
        return execResult(BuildRunner.run(command, pathPrepend, null, BUILD_TIMEOUT));
    }

    private McpToolResult lintRun(JsonObject args) {
        Path sourceDir = absolutePath(args, "source_dir");
        if (!Files.isDirectory(sourceDir)) {
            throw new ToolchainError("source_dir is not an existing directory: " + sourceDir);
        }
        String tool = optString(args, "tool");
        if (tool == null) {
            tool = "clang-tidy";
        }
        if (!"clang-tidy".equals(tool) && !"cppcheck".equals(tool)) {
            throw new ParamError("parameter 'tool' must be \"clang-tidy\" or \"cppcheck\", got: " + tool);
        }
        Duration timeout = Duration.ofSeconds(timeoutSeconds(args, 600, 1800));
        List<String> extraArgs = stringArray(args, "extra_args");
        return "clang-tidy".equals(tool) ? clangTidy(sourceDir, args, extraArgs, timeout)
                : cppcheck(sourceDir, extraArgs, timeout);
    }

    private McpToolResult clangTidy(Path sourceDir, JsonObject args, List<String> extraArgs, Duration timeout) {
        Optional<Path> clangTidy = lint().clangTidy();
        if (clangTidy.isEmpty()) {
            if (!ToolchainRegistry.isWindows()) {
                return McpToolResult.error("lint_run: clang-tidy was not found on PATH. Install clang-tidy "
                        + "with your system package manager (Ubuntu: apt install clang-tidy).");
            }
            return McpToolResult.error("lint_run: clang-tidy was not found on this machine (probed every "
                    + "detected MSYS2 environment bin, standalone LLVM at \"C:\\Program Files\\LLVM\\bin\" "
                    + "and PATH). clang-tidy is not silently faked here. With MSYS2 install it into the "
                    + "clang64 environment: pacman -S mingw-w64-clang-x86_64-clang-tools-extra");
        }
        Path buildDir = optAbsolutePath(args, "build_dir");
        if (buildDir == null) {
            return McpToolResult.error("lint_run: clang-tidy needs a configured build dir with "
                    + "compile_commands.json to know the compile flags (run cmake_configure first, then pass "
                    + "its build_dir)");
        }
        if (!Files.isRegularFile(buildDir.resolve("compile_commands.json"))) {
            return McpToolResult.error("lint_run: " + buildDir.resolve("compile_commands.json") + " not found "
                    + "(clang-tidy needs a configured build dir; run cmake_configure first and pass build_dir)");
        }
        List<Path> files = lintFiles(sourceDir, args);
        StringBuilder merged = new StringBuilder();
        for (Path file : files) {
            List<String> command = new ArrayList<>(
                    List.of(clangTidy.get().toString(), "-p", buildDir.toString(), file.toString()));
            command.addAll(extraArgs);
            BuildRunner.ToolResult result = BuildRunner.run(command, List.of(clangTidy.get().getParent()),
                    null, timeout);
            if (!merged.isEmpty()) {
                merged.append('\n');
            }
            merged.append(result.output());
        }
        return lintResult("clang-tidy", files, merged.toString());
    }

    private McpToolResult cppcheck(Path sourceDir, List<String> extraArgs, Duration timeout) {
        Optional<Path> cppcheck = lint().cppcheck();
        if (cppcheck.isEmpty()) {
            if (!ToolchainRegistry.isWindows()) {
                return McpToolResult.error("lint_run: cppcheck was not found on PATH. Install cppcheck "
                        + "with your system package manager (Ubuntu: apt install cppcheck).");
            }
            return McpToolResult.error("lint_run: cppcheck was not found on this machine (probed "
                    + "\"C:\\Program Files\\Cppcheck\\cppcheck.exe\" and PATH). Install it e.g. with: "
                    + "winget install Cppcheck.Cppcheck");
        }
        List<Path> files = lintFiles(sourceDir, null);
        List<String> command = new ArrayList<>(List.of(cppcheck.get().toString(),
                "--enable=warning,style,performance,portability", "--inline-suppr", "--template=gcc"));
        command.addAll(extraArgs);
        for (Path file : files) {
            command.add(file.toString());
        }
        BuildRunner.ToolResult result = BuildRunner.run(command, List.of(cppcheck.get().getParent()), null,
                timeout);
        return lintResult("cppcheck", files, result.output());
    }

    private static McpToolResult lintResult(String tool, List<Path> files, String output) {
        JsonObject o = new JsonObject();
        o.addProperty("tool", tool);
        o.addProperty("files_checked", files.size());
        o.addProperty("error_count", countDiagnostics(output));
        o.addProperty("output", output);
        return McpToolResult.json(o);
    }

    private static List<Path> lintFiles(Path sourceDir, JsonObject args) {
        List<String> requested = args == null ? List.of() : stringArray(args, "files");
        if (requested.isEmpty()) {
            List<Path> discovered = discoverSources(sourceDir);
            if (discovered.isEmpty()) {
                throw new ToolchainError("no C/C++ source files found under " + sourceDir
                        + " (patterns *.c,*.cc,*.cpp,*.cxx,*.h,*.hh,*.hpp,*.hxx, build directories skipped)");
            }
            return discovered;
        }
        List<Path> files = new ArrayList<>();
        for (String relative : requested) {
            Path candidate = Paths.get(relative);
            if (candidate.isAbsolute()) {
                throw new ToolchainError("files entries must be relative to source_dir, got: " + relative);
            }
            Path file = sourceDir.resolve(candidate);
            if (!Files.isRegularFile(file)) {
                throw new ToolchainError("file not found under source_dir: " + file);
            }
            files.add(file);
        }
        return files;
    }

    private static List<Path> discoverSources(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> hasSourceExtension(p.getFileName().toString()))
                    .filter(p -> !insideBuildDir(root.relativize(p)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ToolchainError("failed to scan source_dir " + root + ": " + e.getMessage());
        }
    }

    private static boolean hasSourceExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && SOURCE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean insideBuildDir(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if ("build".equalsIgnoreCase(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private static int countDiagnostics(String output) {
        return (int) ANSI_CODES.matcher(output).replaceAll("").lines()
                .filter(line -> {
                    String lower = line.toLowerCase(Locale.ROOT);
                    return lower.contains("error:") || lower.contains("warning:");
                })
                .count();
    }

    private McpToolResult formatRun(JsonObject args) {
        List<Path> files = formatFiles(args);
        String mode = optString(args, "mode");
        if (mode == null) {
            mode = "check";
        }
        if (!"check".equals(mode) && !"apply".equals(mode)) {
            throw new ParamError("parameter 'mode' must be \"check\" or \"apply\", got: " + mode);
        }
        Optional<Path> clangFormat = resolveClangFormat(args);
        if (clangFormat.isEmpty()) {
            if (!ToolchainRegistry.isWindows()) {
                return McpToolResult.error("format_run: clang-format was not found on PATH. Install clang-format "
                        + "with your system package manager (Ubuntu: apt install clang-format).");
            }
            return McpToolResult.error("format_run: clang-format was not found on this machine (probed every "
                    + "detected MSYS2 environment bin, standalone LLVM at \"C:\\Program Files\\LLVM\\bin\" "
                    + "and PATH). clang-format is present in the MSYS2 clang64 environment "
                    + "(pacman -S mingw-w64-clang-x86_64-clang-format) or ships with clang.");
        }
        String style = optString(args, "style");
        List<String> command = new ArrayList<>(List.of(clangFormat.get().toString(),
                "--style=" + (style != null ? style : "file")));
        command.add("check".equals(mode) ? "--dry-run" : "-i");
        if ("check".equals(mode)) {
            command.add("--Werror");
        }
        for (Path file : files) {
            command.add(file.toString());
        }
        Map<Path, String> before = "apply".equals(mode) ? readContents(files) : Map.of();
        BuildRunner.ToolResult result = BuildRunner.run(command, List.of(clangFormat.get().getParent()), null,
                BUILD_TIMEOUT);
        if (result.exitCode() == -1) {
            return McpToolResult.error("format_run: " + result.output());
        }
        JsonObject o = new JsonObject();
        o.addProperty("tool", "clang-format");
        o.addProperty("mode", mode);
        o.add("files", pathArray(files));
        if ("check".equals(mode)) {
            o.add("would_reformat", pathArray(offendingFiles(result.output(), files)));
        } else {
            o.add("reformatted", pathArray(files.stream()
                    .filter(f -> !Objects.equals(before.get(f), readOrNull(f)))
                    .toList()));
        }
        o.addProperty("output", result.output());
        return McpToolResult.json(o);
    }

    private static List<Path> formatFiles(JsonObject args) {
        JsonElement raw = args.get("files");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw new ParamError("parameter 'files' must be a non-empty array of absolute file paths");
        }
        List<Path> files = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                throw new ParamError("parameter 'files' must be an array of strings");
            }
            Path file = Paths.get(item.getAsString());
            if (!file.isAbsolute()) {
                throw new ToolchainError("files entries must be absolute paths, got: " + item.getAsString());
            }
            if (!Files.isRegularFile(file)) {
                throw new ToolchainError("file does not exist: " + file);
            }
            files.add(file);
        }
        return files;
    }

    /** clang-format of the selected toolchain, or of the first detected toolchain that carries it. */
    private Optional<Path> resolveClangFormat(JsonObject args) {
        if (optString(args, "toolchain") != null) {
            return resolveToolchain(args).clangFormat();
        }
        return lint().clangFormat();
    }

    private static List<Path> offendingFiles(String output, List<Path> checked) {
        String clean = ANSI_CODES.matcher(output).replaceAll("");
        Set<String> reported = new LinkedHashSet<>();
        Matcher matcher = DIAGNOSTIC_FILE.matcher(clean);
        while (matcher.find()) {
            reported.add(Paths.get(matcher.group(1)).normalize().toString().toLowerCase(Locale.ROOT));
        }
        return checked.stream()
                .filter(f -> reported.contains(f.normalize().toString().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static Map<Path, String> readContents(List<Path> files) {
        Map<Path, String> contents = new HashMap<>();
        for (Path file : files) {
            contents.put(file, readOrNull(file));
        }
        return contents;
    }

    private static String readOrNull(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Optional<Path> whichGdb() {
        return ToolchainRegistry.which("gdb");
    }

    private static ToolchainRegistry.Toolchain resolveToolchain(JsonObject args) {
        String id = optString(args, "toolchain");
        List<ToolchainRegistry.Toolchain> all = ToolchainRegistry.detected();
        if (all.isEmpty()) {
            if (!ToolchainRegistry.isWindows()) {
                throw new ToolchainError("no native C/C++ toolchains detected on PATH; install gcc and g++ "
                        + "or clang and clang++, plus CMake and Ninja or Make (Ubuntu: apt install build-essential cmake ninja-build)");
            }
            throw new ToolchainError("no toolchains detected on this machine (looked for MSVC via vswhere at "
                    + "\"C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe\" and MSYS2 "
                    + "environments under C:\\msys64)");
        }
        if (id == null) {
            return all.get(0);
        }
        return all.stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ToolchainError("unknown toolchain '" + id + "'; available: "
                        + all.stream().map(ToolchainRegistry.Toolchain::id).toList()));
    }

    private JsonElement toolchainsListJson() {
        JsonArray array = new JsonArray();
        boolean first = true;
        for (ToolchainRegistry.Toolchain t : ToolchainRegistry.detected()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("displayName", t.displayName());
            o.addProperty("default", first);
            first = false;
            o.addProperty("cmake", t.cmake().isPresent());
            o.addProperty("ninja", t.ninja().isPresent());
            o.addProperty("compiler", t.compiler().isPresent());
            o.addProperty("ctest", t.ctest().isPresent());
            o.addProperty("gdb", t.gdb().isPresent());
            o.addProperty("clang_tidy", t.clangTidy().isPresent());
            o.addProperty("clang_format", t.clangFormat().isPresent());
            o.addProperty("generator", t.generator().orElse(null));
            o.addProperty("cmakePath", pathOrNull(t.cmake()));
            o.addProperty("ninjaPath", pathOrNull(t.ninja()));
            o.addProperty("compilerPath", pathOrNull(t.compiler()));
            o.addProperty("ctestPath", pathOrNull(t.ctest()));
            o.addProperty("gdbPath", pathOrNull(t.gdb()));
            o.addProperty("clangTidyPath", pathOrNull(t.clangTidy()));
            o.addProperty("clangFormatPath", pathOrNull(t.clangFormat()));
            array.add(o);
        }
        JsonObject lint = new JsonObject();
        lint.addProperty("cppcheck", pathOrNull(ToolchainRegistry.cppcheck()));
        JsonObject result = new JsonObject();
        result.add("toolchains", array);
        result.add("lint", lint);
        return result;
    }

    private static String pathOrNull(Optional<Path> path) {
        return path.map(Path::toString).orElse(null);
    }

    private static String optString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String value = e.getAsString();
        return value.isEmpty() ? null : value;
    }

    private static String reqString(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().isBlank()) {
            throw new ParamError("missing required string parameter '" + key + "'");
        }
        return e.getAsString();
    }

    private static Path absolutePath(JsonObject args, String key) {
        String raw = reqString(args, key);
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            throw new ToolchainError("parameter '" + key + "' must be an absolute path, got: " + raw);
        }
        return p;
    }

    private static Path optAbsolutePath(JsonObject args, String key) {
        String raw = optString(args, key);
        if (raw == null) {
            return null;
        }
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) {
            throw new ToolchainError("parameter '" + key + "' must be an absolute path, got: " + raw);
        }
        return p;
    }

    private static long timeoutSeconds(JsonObject args, long defaultSeconds, long maxSeconds) {
        long value = defaultSeconds;
        JsonElement raw = args.get("timeout_s");
        if (raw != null && !raw.isJsonNull()) {
            try {
                value = raw.getAsLong();
            } catch (NumberFormatException e) {
                throw new ParamError("parameter 'timeout_s' must be an integer");
            }
        }
        return Math.max(1, Math.min(maxSeconds, value));
    }

    private static List<String> stringArray(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return List.of();
        }
        if (!e.isJsonArray()) {
            throw new ParamError("parameter '" + key + "' must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                throw new ParamError("parameter '" + key + "' must be an array of strings");
            }
            out.add(item.getAsString());
        }
        return out;
    }

    private static JsonArray pathArray(List<Path> paths) {
        JsonArray array = new JsonArray();
        for (Path path : paths) {
            array.add(path.toString());
        }
        return array;
    }

    private static McpToolResult execResult(BuildRunner.ToolResult result) {
        return McpToolResult.json(toolResultJson(result), result.exitCode() != 0);
    }

    private static JsonElement toolResultJson(BuildRunner.ToolResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("exitCode", r.exitCode());
        o.addProperty("durationMs", r.durationMs());
        o.addProperty("output", r.output());
        return o;
    }

    private static JsonObject schema(JsonObject properties, String... required) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", properties);
        if (required.length > 0) {
            s.add("required", GSON.toJsonTree(required, String[].class));
        }
        return s;
    }

    private static JsonObject stringProperty(String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject enumProperty(String description, String... values) {
        JsonObject p = stringProperty(description);
        p.add("enum", GSON.toJsonTree(values, String[].class));
        return p;
    }

    private static JsonObject arrayProperty(String description) {
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        JsonObject p = new JsonObject();
        p.addProperty("type", "array");
        p.add("items", items);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject timeoutProperty(String description, int minimum, int maximum) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("minimum", minimum);
        p.addProperty("maximum", maximum);
        p.addProperty("description", description);
        return p;
    }
}
