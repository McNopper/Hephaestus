package com.opencode.ide.tools.cpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tools.cpp.ToolchainRegistry.Toolchain;

/** Native discovery contracts without depending on a compiler installed on the test host. */
public class NativeToolchainTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Function<String, Optional<Path>> binaries(String... names) {
        Path bin = tmp.getRoot().toPath().resolve("native bin");
        Map<String, Path> paths = java.util.Arrays.stream(names)
                .collect(Collectors.toMap(Function.identity(), bin::resolve));
        return name -> Optional.ofNullable(paths.get(name));
    }

    @Test
    public void detectsBothFamiliesAndPrefersNinja() {
        List<Toolchain> all = ToolchainRegistry.detectNative(binaries(
                "gcc", "g++", "clang", "clang++", "cmake", "ninja", "make", "ctest", "gdb",
                "clang-tidy", "clang-format"));
        assertEquals(List.of("gcc", "clang"), all.stream().map(Toolchain::id).toList());
        for (Toolchain tc : all) {
            assertEquals("Ninja", tc.generator().orElseThrow());
            assertTrue(tc.cmake().isPresent());
            assertTrue(tc.ctest().isPresent());
            assertTrue(tc.gdb().isPresent());
            assertTrue(tc.clangTidy().isPresent());
            assertTrue(tc.clangFormat().isPresent());
            assertTrue("native PATH is already usable", tc.pathPrepend().isEmpty());
            Path bin = tc.compiler().orElseThrow().getParent();
            assertEquals(List.of("-DCMAKE_C_COMPILER=" + bin.resolve(tc.id()),
                    "-DCMAKE_CXX_COMPILER=" + bin.resolve("gcc".equals(tc.id()) ? "g++" : "clang++")),
                    tc.compilerArguments());
        }
    }

    @Test
    public void makeFallbackAndMissingBuildToolsRemainExplicit() {
        Toolchain make = ToolchainRegistry.detectNative(binaries("gcc", "g++", "cmake", "make")).get(0);
        assertEquals("Unix Makefiles", make.generator().orElseThrow());
        assertTrue(make.ninja().isEmpty());
        Toolchain incomplete = ToolchainRegistry.detectNative(binaries("clang", "clang++")).get(0);
        assertTrue(incomplete.cmake().isEmpty());
        assertTrue(incomplete.generator().isEmpty());
    }

    @Test
    public void requiresBothLanguagesFromOneInstallation() {
        assertTrue(ToolchainRegistry.detectNative(binaries("gcc", "clang++", "cmake", "ninja")).isEmpty());
        assertTrue(ToolchainRegistry.detectNative(name -> switch (name) {
            case "gcc" -> Optional.of(tmp.getRoot().toPath().resolve("one/gcc"));
            case "g++" -> Optional.of(tmp.getRoot().toPath().resolve("two/g++"));
            default -> Optional.empty();
        }).isEmpty());
    }

    @Test
    public void nativeLookupRejectsWindowsExeAndDirectories() throws Exception {
        Path bin = tmp.newFolder("bin").toPath();
        Files.createFile(bin.resolve("gcc.exe"));
        Files.createDirectory(bin.resolve("gcc"));
        assertTrue(ToolchainRegistry.which("gcc", List.of(bin), false).isEmpty());
        assertEquals(bin.resolve("gcc.exe"), ToolchainRegistry.which("gcc", List.of(bin), true).orElseThrow());
    }

    @Test
    public void nativeLookupRequiresExecutePermissionAndPreservesPathOrder() throws Exception {
        Path first = tmp.newFolder("first").toPath();
        Path second = tmp.newFolder("second").toPath();
        Path a = Files.createFile(first.resolve("gcc"));
        Path b = Files.createFile(second.resolve("gcc"));
        if (Files.getFileStore(a).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(a, Set.of(PosixFilePermission.OWNER_READ));
            Files.setPosixFilePermissions(b, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assertEquals(b, ToolchainRegistry.which("gcc", List.of(first, second), false).orElseThrow());
            Files.setPosixFilePermissions(a, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        }
        assertTrue(Files.isExecutable(a));
        assertEquals(a, ToolchainRegistry.which("gcc", List.of(first, second), false).orElseThrow());
    }

    @Test
    public void allToolchainSchemasAdvertiseNativeAndWindowsIds() {
        int schemas = 0;
        for (var tool : new CppToolProvider().tools()) {
            var properties = tool.inputSchema().getAsJsonObject("properties");
            if (!properties.has("toolchain")) {
                continue;
            }
            schemas++;
            var ids = properties.getAsJsonObject("toolchain").getAsJsonArray("enum");
            assertEquals(List.of("msvc", "clang64", "mingw64", "ucrt64", "gcc", "clang"),
                    ids.asList().stream().map(value -> value.getAsString()).toList());
        }
        assertEquals(4, schemas);
    }
}
