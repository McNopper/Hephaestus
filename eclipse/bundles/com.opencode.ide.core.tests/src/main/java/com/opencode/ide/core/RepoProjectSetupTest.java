package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * O-001: the pure repo-adoption logic ({@link RepoProjectSetup}) — the
 * minimal .project content, the marker detection, and the refusal rules
 * (not a repo, already a project, nested Eclipse projects forbidden), incl.
 * the pruned/depth-bounded nested scan.
 */
public class RepoProjectSetupTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path repoWithDotOpencode() throws IOException {
        Path root = folder.newFolder("repo").toPath();
        Files.createDirectory(root.resolve(".opencode"));
        return root;
    }

    @Test
    public void theDotOpencodeMarkerQualifiesAndOpencodeJsonQualifies() throws IOException {
        assertTrue(RepoProjectSetup.isRepoRoot(repoWithDotOpencode()));
        Path jsonRepo = folder.newFolder("json-repo").toPath();
        Files.writeString(jsonRepo.resolve("opencode.json"), "{}");
        assertTrue(RepoProjectSetup.isRepoRoot(jsonRepo));
        assertFalse(RepoProjectSetup.isRepoRoot(folder.newFolder("plain").toPath()));
        assertFalse(RepoProjectSetup.isRepoRoot(null));
    }

    @Test
    public void adoptionProblemRefusesNonDirectoriesAndNonRepos() throws IOException {
        assertNotNull(RepoProjectSetup.adoptionProblem(folder.getRoot().toPath().resolve("missing")));
        String problem = RepoProjectSetup.adoptionProblem(folder.newFolder("plain2").toPath());
        assertNotNull(problem);
        assertTrue(problem.contains("not an opencode repo"));
    }

    @Test
    public void adoptionProblemRefusesAnExistingProjectRoot() throws IOException {
        Path root = repoWithDotOpencode();
        Files.writeString(root.resolve(".project"), "<projectDescription/>");
        String problem = RepoProjectSetup.adoptionProblem(root);
        assertNotNull(problem);
        assertTrue(problem.contains("already an Eclipse project"));
    }

    @Test
    public void adoptionProblemRefusesNestedEclipseProjectsWithTheAutoDiscoveryExplanation()
            throws IOException {
        Path root = repoWithDotOpencode();
        Path nested = Files.createDirectories(root.resolve("bundles").resolve("some.bundle"));
        Files.writeString(nested.resolve(".project"), "<projectDescription/>");
        String problem = RepoProjectSetup.adoptionProblem(root);
        assertNotNull(problem);
        assertTrue(problem.contains("nested Eclipse project"));
        assertTrue(problem.contains("auto-discovery"));
    }

    @Test
    public void aCleanRepoAdoptsWithNullProblem() throws IOException {
        assertNull(RepoProjectSetup.adoptionProblem(repoWithDotOpencode()));
    }

    @Test
    public void theNestedScanPrunesHiddenAndBuildDirectories() throws IOException {
        Path root = repoWithDotOpencode();
        Path deep = Files.createDirectories(root.resolve("node_modules").resolve("pkg"));
        Files.writeString(deep.resolve(".project"), "<projectDescription/>");
        // the .project inside node_modules must NOT count as nested
        assertNull(RepoProjectSetup.adoptionProblem(root));
    }

    @Test
    public void theProjectFileCarriesTheNameAndTheNatureAndParsesAsXml() throws Exception {
        String xml = RepoProjectSetup.projectFileXml("my-repo");
        assertTrue(xml.contains("<name>my-repo</name>"));
        assertTrue(xml.contains("<nature>" + RepoProjectSetup.NATURE_ID + "</nature>"));
        assertFalse(xml.contains("<buildCommand>"));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void theNatureIdMatchesTheRegisteredExtension() {
        assertEquals(OpenCodeRepoNature.ID, RepoProjectSetup.NATURE_ID);
        assertTrue(RepoProjectSetup.NATURE_ID.endsWith(".repoNature"));
    }
}
