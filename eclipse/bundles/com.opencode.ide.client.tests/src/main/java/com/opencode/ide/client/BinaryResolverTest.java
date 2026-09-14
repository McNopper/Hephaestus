package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.Test;
import org.junit.Assume;

/**
 * Unit tests for {@link BinaryResolver#resolveBinary(String, String, boolean)} -
 * the pure (no {@code System.getenv}) resolution logic. Uses a temp dir with a
 * fake shim so no real opencode install is required.
 */
public class BinaryResolverTest {

    @Test
    public void resolvesCmdShimOnWindowsPath() throws IOException {
        Path dir = Files.createTempDirectory("opencode-bin-test");
        Files.createFile(dir.resolve("opencode.cmd"));
        try {
            Path resolved = BinaryResolver.resolveBinary(null, dir.toString(), true);
            assertEquals(dir.resolve("opencode.cmd"), resolved);
        } finally {
            Files.deleteIfExists(dir.resolve("opencode.cmd"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void posixSkipsNonExecutableExplicitAndPathFiles() {
        Path executable = Path.of("second", "opencode");
        assertEquals(executable, BinaryResolver.resolveBinary("custom", "first:second", false,
                path -> true, executable::equals));
        assertNull(BinaryResolver.resolveBinary("custom", "first:second", false,
                path -> true, path -> false));
        assertNull(BinaryResolver.resolveBinary("custom", "first:second", false,
                path -> false, path -> true));
    }

    @Test
    public void posixExecutableExplicitWinsAndShellFallbackWorks() {
        Path explicit = Path.of("custom");
        assertEquals(explicit, BinaryResolver.resolveBinary("custom", "first:second", false,
                path -> true, path -> true));
        Path shell = Path.of("first", "opencode.sh");
        assertEquals(shell, BinaryResolver.resolveBinary(null, "first:second", false,
                path -> true, shell::equals));
    }

    @Test
    public void windowsUsesSemicolonAndDoesNotRequireUnixExecuteBit() {
        Path shim = Path.of("second", "opencode.cmd");
        assertEquals(shim, BinaryResolver.resolveBinary(null, "first;second", true,
                shim::equals, path -> { throw new AssertionError("Windows must not probe Unix execute permission"); }));
    }

    @Test
    public void realPosixFilesystemRequiresExecutePermission() throws IOException {
        Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = Files.createTempDirectory("opencode-executable-test");
        Path binary = dir.resolve("opencode");
        try {
            Files.createFile(binary);
            Files.setPosixFilePermissions(binary, Set.of(PosixFilePermission.OWNER_READ));
            assertNull(BinaryResolver.resolveBinary(binary.toString(), dir.toString(), false));
            Files.setPosixFilePermissions(binary,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            assertEquals(binary, BinaryResolver.resolveBinary(null, dir.toString(), false));
        } finally {
            Files.deleteIfExists(binary);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void explicitConfiguredPathWins() throws IOException {
        Path dir = Files.createTempDirectory("opencode-bin-test");
        Path explicit = dir.resolve("custom-opencode.exe");
        Files.createFile(explicit);
        try {
            Path resolved = BinaryResolver.resolveBinary(explicit.toString(), "", true);
            assertEquals(explicit, resolved);
        } finally {
            Files.deleteIfExists(explicit);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void returnsNullWhenNotFound() {
        assertNull(BinaryResolver.resolveBinary(null, "", true));
        assertNull(BinaryResolver.resolveBinary(null, "C:\\does-not-exist-12345", true));
    }

    @Test
    public void windowsPrefersCmdOverExeOrder() throws IOException {
        Path dir = Files.createTempDirectory("opencode-bin-test");
        Files.createFile(dir.resolve("opencode.exe"));
        try {
            Path resolved = BinaryResolver.resolveBinary(null, dir.toString(), true);
            // .cmd is tried before .exe
            assertEquals(dir.resolve("opencode.exe"), resolved);
        } finally {
            Files.deleteIfExists(dir.resolve("opencode.exe"));
            Files.deleteIfExists(dir);
        }
    }
}
