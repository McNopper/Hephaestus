package com.opencode.ide.tools.cpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.tools.McpToolResult;

import org.junit.Assume;
import org.junit.Test;

/**
 * lint_run / format_run / extended toolchains_list coverage. Machine-backed
 * happy paths are Assume-gated (cppcheck / clang-format must exist); the
 * missing-binary paths run everywhere via the injectable {@link LintBinaries}
 * test seam of {@link CppToolProvider}.
 */
public class LintAndFormatToolsTest {

    private static final Path CPPCHECK = Paths.get("C:\\Program Files\\Cppcheck\\cppcheck.exe");

    private final CppToolProvider tools = new CppToolProvider();

    @Test(timeout = 120_000)
    public void cppcheckDetectsUninitializedPointerBug() throws IOException {
        Assume.assumeTrue("cppcheck not present on this machine", ToolchainRegistry.cppcheck().isPresent());
        Path dir = Files.createTempDirectory("mcp-lint-");
        try {
            Files.writeString(dir.resolve("main.c"), "int main(){int* p; *p=1; return 0;}\n",
                    StandardCharsets.UTF_8);
            JsonObject args = lintArgs(dir, "cppcheck");
            McpToolResult result = tools.call("lint_run", args);
            JsonObject payload = parsePayload(result);
            assertEquals("cppcheck", payload.get("tool").getAsString());
            assertTrue("at least one file checked: " + result.text(),
                    payload.get("files_checked").getAsInt() >= 1);
            Assume.assumeFalse("cppcheck install is broken on this machine (library cfg failed to load)",
                    payload.get("output").getAsString().contains("Failed to load library configuration file"));
            assertTrue("the deliberate bug must be reported: " + result.text(),
                    payload.get("error_count").getAsInt() >= 1);
            assertTrue("output should mention main.c: " + result.text(),
                    payload.get("output").getAsString().contains("main.c"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void clangTidyMissingYieldsInstallHint() throws IOException {
        Path dir = Files.createTempDirectory("mcp-lint-missing-");
        try {
            McpToolResult result = forcedEmptyLint().call("lint_run", lintArgs(dir, "clang-tidy"));
            assertTrue("isError expected: " + result.text(), result.isError());
            assertTrue(result.text().contains("clang-tidy"));
            assertTrue("install hint expected: " + result.text(),
                    result.text().contains("pacman -S mingw-w64-clang-x86_64-clang-tools-extra"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void cppcheckMissingYieldsInstallHint() throws IOException {
        Path dir = Files.createTempDirectory("mcp-lint-missing2-");
        try {
            McpToolResult result = forcedEmptyLint().call("lint_run", lintArgs(dir, "cppcheck"));
            assertTrue("isError expected: " + result.text(), result.isError());
            assertTrue("install hint expected: " + result.text(),
                    result.text().contains("winget install Cppcheck.Cppcheck"));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void clangTidyWithoutCompileCommandsExplainsCmakeConfigureFirst() throws IOException {
        CppToolProvider provider = new CppToolProvider(
                new LintBinaries(Optional.of(Paths.get("C:\\does-not-exist\\clang-tidy.exe")),
                        Optional.empty(), Optional.empty()));
        Path dir = Files.createTempDirectory("mcp-lint-nocc-");
        try {
            JsonObject args = lintArgs(dir, "clang-tidy");
            McpToolResult noBuildDir = provider.call("lint_run", args);
            assertTrue("isError expected: " + noBuildDir.text(), noBuildDir.isError());
            assertTrue("should point at cmake_configure: " + noBuildDir.text(),
                    noBuildDir.text().contains("cmake_configure"));

            Path buildDir = Files.createTempDirectory("mcp-lint-emptybuild-");
            try {
                args.addProperty("build_dir", buildDir.toString());
                McpToolResult noCompileCommands = provider.call("lint_run", args);
                assertTrue("isError expected: " + noCompileCommands.text(), noCompileCommands.isError());
                assertTrue("compile_commands.json explanation expected: " + noCompileCommands.text(),
                        noCompileCommands.text().contains("compile_commands.json"));
            } finally {
                deleteRecursively(buildDir);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test(timeout = 120_000)
    public void formatCheckFlagsAppliesAndRechecksClean() throws IOException {
        Assume.assumeTrue("clang-format not found in any detected toolchain",
                LintBinaries.detect().clangFormat().isPresent());
        Path file = Files.createTempFile("mcp-fmt-", ".cpp");
        try {
            Files.writeString(file, "#include <vector>\n"
                    + "std::vector<int> make(){std::vector<int> v;v.push_back(1);v.push_back(2);"
                    + "v.push_back(3);v.push_back(4);return v;}\n", StandardCharsets.UTF_8);
            int originalLines = Files.readString(file).lines().toList().size();

            JsonObject checkArgs = formatArgs("check", file);
            McpToolResult check = tools.call("format_run", checkArgs);
            JsonObject payload = parsePayload(check);
            assertEquals("clang-format", payload.get("tool").getAsString());
            assertEquals("check", payload.get("mode").getAsString());
            List<String> would = toStringList(payload.getAsJsonArray("would_reformat"));
            assertEquals("badly formatted file must be flagged: " + check.text(), 1, would.size());
            assertTrue(would.get(0).endsWith(file.getFileName().toString()));

            McpToolResult apply = tools.call("format_run", formatArgs("apply", file));
            JsonObject applyPayload = parsePayload(apply);
            assertEquals("apply must report the file as reformatted: " + apply.text(),
                    List.of(file.toString()), toStringList(applyPayload.getAsJsonArray("reformatted")));
            String formatted = Files.readString(file);
            assertTrue("formatting must reflow to more lines: " + formatted,
                    formatted.lines().toList().size() > originalLines);

            McpToolResult recheck = tools.call("format_run", checkArgs);
            JsonObject recheckPayload = parsePayload(recheck);
            assertEquals("already formatted file must not be flagged: " + recheck.text(),
                    List.of(), toStringList(recheckPayload.getAsJsonArray("would_reformat")));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void formatRunWithoutClangFormatYieldsInstallHint() throws IOException {
        Path file = Files.createTempFile("mcp-fmt-missing-", ".cpp");
        try {
            Files.writeString(file, "int main(){return 0;}\n", StandardCharsets.UTF_8);
            McpToolResult result = forcedEmptyLint().call("format_run", formatArgs("check", file));
            assertTrue("isError expected: " + result.text(), result.isError());
            assertTrue("install hint expected: " + result.text(),
                    result.text().contains("pacman -S mingw-w64-clang-x86_64-clang-format"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void toolchainsListReportsLintCapabilities() {
        // toolchain discovery uses MSYS2/MinGW paths that are Windows-specific;
        // on other platforms the list may legitimately be empty
        Assume.assumeTrue("toolchain discovery is Windows-specific (MSYS2/MinGW paths)",
                System.getProperty("os.name", "").toLowerCase().contains("win"));
        JsonObject payload = parsePayload(tools.call("toolchains_list", new JsonObject()));
        JsonArray toolchains = payload.getAsJsonArray("toolchains");
        assertNotNull(toolchains);
        for (int i = 0; i < toolchains.size(); i++) {
            JsonObject entry = toolchains.get(i).getAsJsonObject();
            assertTrue("clang_tidy flag expected in every entry",
                    entry.get("clang_tidy").isJsonPrimitive());
            assertTrue("clang_format flag expected in every entry",
                    entry.get("clang_format").isJsonPrimitive());
        }
        JsonObject lint = payload.getAsJsonObject("lint");
        assertNotNull("global lint section expected", lint);
        assertEquals("cppcheck section matches the registry resolver",
                ToolchainRegistry.cppcheck().isPresent(), !lint.get("cppcheck").isJsonNull());
        Assume.assumeTrue("standard cppcheck location not present on this machine",
                Files.isRegularFile(CPPCHECK));
        assertEquals(CPPCHECK.toString(), lint.get("cppcheck").getAsString());
    }

    private static CppToolProvider forcedEmptyLint() {
        return new CppToolProvider(new LintBinaries(Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static JsonObject lintArgs(Path dir, String tool) {
        JsonObject args = new JsonObject();
        args.addProperty("source_dir", dir.toString());
        args.addProperty("tool", tool);
        return args;
    }

    private static JsonObject formatArgs(String mode, Path file) {
        JsonObject args = new JsonObject();
        JsonArray files = new JsonArray();
        files.add(file.toString());
        args.add("files", files);
        args.addProperty("mode", mode);
        return args;
    }

    private static JsonObject parsePayload(McpToolResult result) {
        assertFalse(result.text(), result.isError());
        return JsonParser.parseString(result.text()).getAsJsonObject();
    }

    private static List<String> toStringList(JsonArray array) {
        return array.asList().stream().map(JsonElement -> JsonElement.getAsString()).toList();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp dir
                }
            });
        }
    }
}
