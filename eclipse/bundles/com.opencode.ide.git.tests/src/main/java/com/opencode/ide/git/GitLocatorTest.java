package com.opencode.ide.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/**
 * Tests the pure git-resolution logic (PATH scan first, then explicit
 * fallback probes) without touching the real PATH.
 */
public class GitLocatorTest {

    @Test
    public void pathEntryWinsOverFallbackProbes() throws IOException {
        Path pathDir = Files.createTempDirectory("opencode-git-loc-path");
        Path pathGit = pathDir.resolve("git.exe");
        Files.createFile(pathGit);
        Path fallback = Files.createTempDirectory("opencode-git-loc-fb").resolve("git.exe");
        Files.createFile(fallback);
        try {
            assertEquals(pathGit, GitLocator.resolveGit(pathDir.toString(), true, List.of(fallback)));
        } finally {
            delete(pathGit, pathDir, fallback, fallback.getParent());
        }
    }

    @Test
    public void fallbackProbeUsedWhenPathMisses() throws IOException {
        Path fallback = Files.createTempDirectory("opencode-git-loc-fb").resolve("git.exe");
        Files.createFile(fallback);
        try {
            assertEquals(fallback, GitLocator.resolveGit("", true, List.of(fallback)));
            assertEquals(fallback, GitLocator.resolveGit(null, true, List.of(fallback)));
        } finally {
            delete(fallback, fallback.getParent());
        }
    }

    @Test
    public void returnsNullWhenNothingMatches() {
        assertNull(GitLocator.resolveGit("", true, List.of()));
        assertNull(GitLocator.resolveGit("C:\\does-not-exist-12345", true, List.of()));
    }

    @Test
    public void posixLooksForBareGit() throws IOException {
        Path dir = Files.createTempDirectory("opencode-git-loc-posix");
        Path bare = dir.resolve("git");
        Files.createFile(bare);
        try {
            assertEquals(bare, GitLocator.resolveGit(dir.toString(), false, List.of()));
        } finally {
            delete(bare, dir);
        }
    }

    @Test
    public void windowsFallbacksResolveOnMachineWithStandardInstall() {
        Assume.assumeTrue(GitLocator.WINDOWS_FALLBACKS.stream().anyMatch(Files::isRegularFile));
        Path resolved = GitLocator.resolveGit("", true, GitLocator.WINDOWS_FALLBACKS);
        assertEquals(GitLocator.WINDOWS_FALLBACKS.get(0).toAbsolutePath(), resolved.toAbsolutePath());
    }

    private static void delete(Path... paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }
}
