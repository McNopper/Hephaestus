package com.opencode.ide.mcp.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.cpp.CppToolProvider;
import com.opencode.ide.tools.cpp.ToolchainRegistry;
import com.opencode.ide.tools.cpp.ToolchainRegistry.Toolchain;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end: a real CMake project built headless through the MCP tool layer
 * with an available toolchain - configure C and C++, build, run the binary,
 * check output. Skips only when no complete build toolchain is installed.
 */
public class EndToEndBuildTest {

    private static final CppToolProvider TOOLS = new CppToolProvider();
    private static Path projectDir;
    private static Toolchain toolchain;

    @BeforeClass
    public static void gateAndSetup() throws IOException {
        // Preserve the existing MSYS2 exercise when available; otherwise use a
        // complete detected toolchain (including native GCC/Clang on Linux).
        toolchain = ToolchainRegistry.byId("ucrt64").filter(EndToEndBuildTest::usable)
                .orElseGet(() -> ToolchainRegistry.detected().stream()
                        .filter(EndToEndBuildTest::usable).findFirst().orElse(null));
        org.junit.Assume.assumeTrue("CMake, a C/C++ compiler and a generator required", toolchain != null);
        projectDir = Files.createTempDirectory("mcp-e2e-");
        Files.writeString(projectDir.resolve("CMakeLists.txt"), """
                cmake_minimum_required(VERSION 3.20)
                project(hello C CXX)
                set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}/bin>")
                add_executable(hello main.c message.cpp)
                """, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("main.c"), """
                #include <stdio.h>
                const char* message(void);
                int main(void) {
                    printf("%s\\n", message());
                    return 0;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(projectDir.resolve("message.cpp"), """
                extern "C" const char* message() { return "hello-fleet"; }
                """, StandardCharsets.UTF_8);
    }

    @AfterClass
    public static void cleanup() throws IOException {
        if (projectDir != null) {
            deleteRecursively(projectDir);
        }
    }

    @Test(timeout = 120_000)
    public void configureBuildRunHello() {
        Path buildDir = projectDir.resolve("build");

        McpToolResult configure = call("cmake_configure", "source_dir", projectDir.toString(), "build_dir",
                buildDir.toString(), "toolchain", toolchain.id());
        assertFalse(textOf(configure), configure.isError());
        assertEquals(0, toolResult(configure).get("exitCode").getAsInt());

        if (!"msvc".equals(toolchain.id())) {
            assertTrue("compile commands required for clang-tidy",
                    Files.isRegularFile(buildDir.resolve("compile_commands.json")));
        }
        McpToolResult build = call("cmake_build", "build_dir", buildDir.toString(), "toolchain", toolchain.id());
        assertFalse(textOf(build), build.isError());
        assertEquals(0, toolResult(build).get("exitCode").getAsInt());

        Path binary = buildDir.resolve("bin").resolve(File.separatorChar == '\\' ? "hello.exe" : "hello");
        assertTrue("built binary expected at " + binary, Files.isRegularFile(binary));

        McpToolResult run = call("run_binary", "binary", binary.toString());
        assertFalse(textOf(run), run.isError());
        JsonObject runResult = toolResult(run);
        assertEquals(0, runResult.get("exitCode").getAsInt());
        assertTrue("output should contain the marker: " + textOf(run),
                runResult.get("output").getAsString().contains("hello-fleet"));
    }

    private static McpToolResult call(String tool, String... keyValueArgs) {
        JsonObject args = new JsonObject();
        for (int i = 0; i + 1 < keyValueArgs.length; i += 2) {
            args.addProperty(keyValueArgs[i], keyValueArgs[i + 1]);
        }
        return TOOLS.call(tool, args);
    }

    private static String textOf(McpToolResult toolResult) {
        return toolResult.text();
    }

    private static JsonObject toolResult(McpToolResult toolResult) {
        JsonObject parsed = JsonParser.parseString(textOf(toolResult)).getAsJsonObject();
        assertNotNull(parsed.get("exitCode"));
        assertNotNull(parsed.get("durationMs"));
        assertNotNull(parsed.get("output"));
        return parsed;
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

    private static boolean usable(Toolchain candidate) {
        return candidate.cmake().isPresent() && candidate.compiler().isPresent()
                && candidate.generator().isPresent();
    }
}
