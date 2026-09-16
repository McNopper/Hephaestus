package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link TasksRootResolution}: the task-store root priority
 * (B-002 AC-4 / O-001 parity) — explicit override, then the workspace climb,
 * then open-project repo adoption (which must beat a stale preference
 * default — the live B-002 failure), then the preference, then the
 * historical guess. Everything runs against temp directories; the
 * preference is a supplier, so no Eclipse runtime is needed.
 */
public class TasksRootResolutionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path workspace;
    private Path stalePreferenceStore;
    private Path repo;
    private Path repoStore;

    @Before
    public void setUp() throws IOException {
        workspace = tmp.newFolder("workspace").toPath();
        // a stale store some old preference still points at (an earlier clone)
        stalePreferenceStore = storeAt(tmp.newFolder("stale-clone").toPath());
        // a real opencode repo (marker: .opencode dir) with a task store
        repo = tmp.newFolder("repo").toPath();
        repoStore = storeAt(repo.resolve(".opencode").resolve("tasks"));
    }

    private static Path storeAt(Path dir) throws IOException {
        Path project = dir.resolve("hephaestus");
        Files.createDirectories(project);
        Files.writeString(project.resolve("T-001.md"), "seed\n");
        return dir;
    }

    @Test
    public void explicitOverrideWinsOverEverything() {
        Path resolved = TasksRootResolution.resolve(stalePreferenceStore.toString(), workspace,
                List.of(repo.resolve("bundle-project")), () -> stalePreferenceStore.toString());
        assertEquals(stalePreferenceStore.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void relativeOverrideResolvesAgainstTheWorkspace() {
        Path resolved = TasksRootResolution.resolve("nested/store", workspace, List.of(),
                () -> stalePreferenceStore.toString());
        assertEquals(workspace.resolve("nested").resolve("store").normalize(), resolved);
    }

    @Test
    public void missingOverrideIsHonoredAsIs() {
        // an explicit-but-nonexistent path stays visible as the board's
        // not-found notice instead of silently watching another store
        Path resolved = TasksRootResolution.resolve(workspace.resolve("gone").toString(),
                workspace, List.of(repo), () -> stalePreferenceStore.toString());
        assertEquals(workspace.resolve("gone").normalize(), resolved);
    }

    @Test
    public void workspaceClimbBeatsAdoptionAndPreference() throws IOException {
        // workspace nested inside a repo: the climb finds that repo's store
        Path nestedWorkspace = repo.resolve("eclipse-workspace");
        Files.createDirectories(nestedWorkspace);

        Path resolved = TasksRootResolution.resolve("", nestedWorkspace, List.of(repo),
                () -> stalePreferenceStore.toString());

        assertEquals(repoStore, resolved);
    }

    @Test
    public void openProjectAdoptionBeatsTheStalePreferenceDefault() {
        // THE B-002 live failure: the workspace is not in a repo, an open
        // project lives in one, and a stale preference points elsewhere —
        // the adopted repo store must win over the preference default
        Path resolved = TasksRootResolution.resolve("", workspace,
                List.of(repo.resolve("bundle-project")), () -> stalePreferenceStore.toString());

        assertEquals(repoStore, resolved);
    }

    @Test
    public void nestedProjectLocationAdoptsTheRepoRoot() {
        // the project sits deep inside the repo; repoRootOf climbs to the
        // marker ancestor, so the same store resolves
        Path resolved = TasksRootResolution.resolve("", workspace,
                List.of(repo.resolve("eclipse").resolve("bundles").resolve("proj")),
                () -> stalePreferenceStore.toString());

        assertEquals(repoStore, resolved);
    }

    @Test
    public void opencodeJsonIsAlsoARepoMarker() throws IOException {
        Path jsonRepo = tmp.newFolder("json-repo").toPath();
        Files.writeString(jsonRepo.resolve("opencode.json"), "{}\n");
        Path jsonStore = storeAt(jsonRepo.resolve(".opencode").resolve("tasks"));

        Path resolved = TasksRootResolution.resolve("", workspace,
                List.of(jsonRepo.resolve("proj")), () -> stalePreferenceStore.toString());

        assertEquals(jsonStore, resolved);
    }

    @Test
    public void nonRepoProjectsAreIgnoredForAdoption() throws IOException {
        Path plain = tmp.newFolder("plain-project").toPath();
        Files.writeString(plain.resolve("pom.xml"), "<project/>\n");

        Path resolved = TasksRootResolution.resolve("", workspace,
                List.of(plain, repo.resolve("proj")), () -> stalePreferenceStore.toString());

        assertEquals("the plain project must not shadow the repo project",
                repoStore, resolved);
    }

    @Test
    public void adoptionWithoutAStoreIsSkipped() throws IOException {
        Path bareRepo = tmp.newFolder("bare-repo").toPath();
        Files.createDirectories(bareRepo.resolve(".opencode")); // marker, no tasks dir

        Path resolved = TasksRootResolution.resolve("", workspace, List.of(bareRepo),
                () -> stalePreferenceStore.toString());

        assertEquals("no adoptable store — the preference fallback applies",
                stalePreferenceStore.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void preferenceIsTheFallbackWhenNothingIsAdoptable() {
        Path resolved = TasksRootResolution.resolve("", workspace, List.of(),
                () -> stalePreferenceStore.toString());
        assertEquals(stalePreferenceStore.toAbsolutePath().normalize(), resolved);
    }

    @Test
    public void unsetBlankOrThrowingPreferenceReadsAsNone() {
        assertEquals(workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize(),
                TasksRootResolution.resolve("", workspace, List.of(), () -> null));
        assertEquals(workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize(),
                TasksRootResolution.resolve("", workspace, List.of(), () -> "  "));
        assertEquals(workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize(),
                TasksRootResolution.resolve("  ", workspace, List.of(), null));
        assertEquals("a throwing supplier (headless contexts) must not escape",
                workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize(),
                TasksRootResolution.resolve(null, workspace, List.of(),
                        () -> {
                            throw new IllegalStateException("no preferences node");
                        }));
    }

    @Test
    public void historicalGuessAppliesWhenNothingMatches() {
        assertEquals(workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize(),
                TasksRootResolution.resolve(null, workspace, List.of(), () -> ""));
    }
}
