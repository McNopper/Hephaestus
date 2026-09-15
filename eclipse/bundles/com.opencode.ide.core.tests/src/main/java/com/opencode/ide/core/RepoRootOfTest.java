package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * O-001 "same folder level" rule: spawning a server for a nested project
 * folder must resolve to the enclosing opencode repo (nearest ancestor with a
 * {@code .opencode} directory or an {@code opencode.json}), so the server sees
 * the repo's agents, skills and MCP servers exactly as an opencode started in
 * the repo root would.
 */
public class RepoRootOfTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void nestedProjectClimbsToTheDotOpencodeRepoRoot() throws IOException {
        Path repo = temp.newFolder("repo").toPath();
        Files.createDirectories(repo.resolve(".opencode"));
        Path nested = Files.createDirectories(repo.resolve("eclipse/bundles/com.opencode.ide.core"));
        assertEquals(repo.normalize(), OpencodeConnection.repoRootOf(nested));
    }

    @Test
    public void opencodeJsonMarkerIsRecognizedLikeDotOpencode() throws IOException {
        Path repo = temp.newFolder("repo2").toPath();
        Files.writeString(repo.resolve("opencode.json"), "{\"mcpServers\":{}}");
        Path nested = Files.createDirectories(repo.resolve("src/module"));
        assertEquals(repo.normalize(), OpencodeConnection.repoRootOf(nested));
    }

    @Test
    public void nearestAncestorWinsOverAFartherOne() throws IOException {
        Path outer = temp.newFolder("outer").toPath();
        Path inner = Files.createDirectories(outer.resolve("inner"));
        Files.writeString(inner.resolve("opencode.json"), "{}");
        Path nested = Files.createDirectories(inner.resolve("pkg/lib"));
        assertEquals(inner.normalize(), OpencodeConnection.repoRootOf(nested));
    }

    @Test
    public void theRepoRootItselfQualifiesWithoutClimbing() throws IOException {
        Path repo = temp.newFolder("repo3").toPath();
        Files.createDirectories(repo.resolve(".opencode"));
        assertEquals(repo.normalize(), OpencodeConnection.repoRootOf(repo));
    }

    @Test
    public void noMarkerAnywhereReturnsTheCandidateUnchanged() throws IOException {
        Path dir = Files.createTempDirectory("no-marker");
        assertEquals(dir.toAbsolutePath().normalize(), OpencodeConnection.repoRootOf(dir));
    }
}
