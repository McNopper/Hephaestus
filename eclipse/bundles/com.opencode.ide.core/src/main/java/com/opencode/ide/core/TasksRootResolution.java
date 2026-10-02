package com.opencode.ide.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;

/**
 * The ONE task-store root order (B-016): which {@code <something>/.opencode/tasks}
 * directory the harness actually watches. Both surfaces resolve through this
 * seam — the Board view (its toolbar override feeds {@link #resolveForWorkspace})
 * and the {@code eclipse-build} MCP endpoint (the core activator bridges
 * {@link #resolveForWorkspace(String)} with an empty override into the
 * {@code opencode.tasks.root} system property) — so a workspace, its open
 * projects and the preferences always yield ONE store for the Board, the
 * fleet and the in-session {@code task_*} tools.
 *
 * <p>Order (first hit wins):
 * <ol>
 * <li><b>Explicit override</b> — the Board toolbar's Store field / its
 * persisted dialog setting. A user-typed path is honored as-is (relative
 * resolves against the workspace), even when it does not exist: the mistake
 * stays visible as the board's "Task store not found" notice instead of
 * silently watching a different store.</li>
 * <li><b>Workspace climb</b> — walk up from the workspace location; the
 * first ancestor carrying {@code .opencode/tasks} (workspace nested in the
 * repo).</li>
 * <li><b>Open-project repo adoption</b> — any open workspace project that
 * lives in an opencode repo (nearest marker ancestor, the same
 * {@link OpencodeConnection#repoRootOf} rule the spawned server uses)
 * contributes its {@code <repo>/.opencode/tasks}. This is what makes an
 * imported repo project adopt its store even when the workspace directory
 * itself is outside the repo — the O-001 auto-adoption rule. Deterministic
 * among candidates: lexicographically smallest store path (mirrors the
 * server-side pick).</li>
 * <li><b>Preference default</b> — the {@code tasksRoot} workspace
 * preference, but only as a <em>fallback</em> below adoption: a stale
 * preference value (old clone, pre-P1-4 dev default) must never override
 * the repo that is actually open (the B-002 live failure).</li>
 * <li><b>Historical guess</b> — {@code <workspace>/../.opencode/tasks},
 * kept so the resolution always yields a path (the board shows its
 * not-found notice for it).</li>
 * </ol>
 *
 * <p>Extracted from BoardView (originally B-002 AC-4 / O-001 parity) and
 * moved into core (B-016) so the eclipse-build endpoint shares the exact
 * same order instead of a preference-only fallback. The pure
 * {@link #resolve} stays SWT/workbench-free and unit-testable; the view and
 * the bridge feed it the Eclipse-derived inputs via
 * {@link #resolveForWorkspace(String)}.</p>
 */
public final class TasksRootResolution {

    private TasksRootResolution() {
    }

    /**
     * Resolves the task-store root by the order above.
     *
     * @param override        the explicit toolbar/dialog override text
     *                        (blank = none)
     * @param workspace       the workspace location (relative overrides and
     *                        the climb start; never null in practice)
     * @param projectLocations locations of the open workspace projects
     *                        (adoption candidates; empty when the resources
     *                        plugin is unavailable)
     * @param preferenceRoot  supplies the {@code tasksRoot} preference text
     *                        (null/blank = unset); tolerant of a throwing
     *                        supplier (headless contexts)
     * @return the resolved store root; never null
     */
    public static Path resolve(String override, Path workspace, List<Path> projectLocations,
            Supplier<String> preferenceRoot) {
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            return normalizeStore(path.isAbsolute() ? path : workspace.resolve(path));
        }
        for (Path dir = workspace; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(".opencode").resolve("tasks");
            if (Files.isDirectory(candidate)) {
                return candidate.normalize();
            }
        }
        Path adopted = adoptedStore(projectLocations);
        if (adopted != null) {
            return adopted;
        }
        String configured = preferenceValue(preferenceRoot);
        if (configured != null) {
            Path candidate = Path.of(configured);
            if (Files.isDirectory(candidate)) {
                return normalizeStore(candidate);
            }
        }
        return workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize();
    }

    /**
     * The current workspace's store root: {@link #resolve} fed with the
     * live Eclipse inputs — the Board view's entry point (its override
     * text, empty for none) AND the value the core activator bridges into
     * {@code opencode.tasks.root} for the eclipse-build endpoint (empty
     * override: the endpoint has no Board-UI override). Both callers
     * therefore see ONE store for the same workspace and preferences.
     *
     * @param override the Board's explicit override text (blank = none)
     * @return the resolved store root; never null
     */
    public static Path resolveForWorkspace(String override) {
        return resolve(override, workspaceRoot(), workspaceProjectLocations(),
                TasksRootResolution::preferenceTasksRoot);
    }

    /**
     * A root that is really a REPO ROOT (carries {@code .opencode/tasks})
     * descends into that store: typing {@code C:\dev\myrepo} must yield its
     * {@code .opencode/tasks}, not treat every top-level folder as a project
     * (2026-09-23 live finding: "the repo points to root and not to
     * hephaestus"). Anything else is honored as-is.
     */
    private static Path normalizeStore(Path root) {
        Path store = root.resolve(".opencode").resolve("tasks");
        return (Files.isDirectory(store) ? store : root).normalize();
    }

    /**
     * The adopted store of the first (deterministic) open project that lives
     * in an opencode repo with a task store, or {@code null} when none
     * qualifies. The repo-marker climb reuses
     * {@link OpencodeConnection#repoRootOf} — one shared "same folder level"
     * rule for the spawned server and the board. {@code repoRootOf} returns
     * its input unchanged when no ancestor carries a marker, so the
     * {@link OpencodeConnection#isRepoMarker} re-check filters non-repo
     * projects (review S3: the marker predicate exists exactly once).
     */
    private static Path adoptedStore(List<Path> projectLocations) {
        if (projectLocations == null) {
            return null;
        }
        Path best = null;
        for (Path location : projectLocations) {
            if (location == null) {
                continue;
            }
            Path repo = OpencodeConnection.repoRootOf(location);
            if (!OpencodeConnection.isRepoMarker(repo)) {
                continue;
            }
            Path store = repo.resolve(".opencode").resolve("tasks");
            if (!Files.isDirectory(store)) {
                continue;
            }
            if (best == null || store.toString().compareTo(best.toString()) < 0) {
                best = store;
            }
        }
        return best == null ? null : best.normalize();
    }

    /** The preference text, or {@code null} when unset/blank/unavailable. */
    private static String preferenceValue(Supplier<String> preferenceRoot) {
        if (preferenceRoot == null) {
            return null;
        }
        try {
            String value = preferenceRoot.get();
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException e) {
            // headless/test contexts without the preferences node
            return null;
        }
    }

    /**
     * The {@code tasksRoot} workspace preference text (Preferences →
     * OpenCode); {@code null} when unset or unreadable (headless/test
     * contexts) — {@link #resolve} treats null as no preference.
     */
    static String preferenceTasksRoot() {
        try {
            String configured = new OpencodePreferences().getTasksRoot();
            return configured == null || configured.isBlank() ? null : configured.trim();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * O-001/B-002 adoption candidates: the locations of the open workspace
     * projects. A project imported from inside a repo makes that repo's
     * {@code .opencode/tasks} adoptable even when the workspace directory
     * itself is outside every repo. Empty when the resources plugin is
     * unavailable (tests, non-workbench hosts) — the climb still runs.
     */
    static List<Path> workspaceProjectLocations() {
        try {
            var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            List<Path> locations = new ArrayList<>();
            for (var project : projects) {
                var location = project.getLocation();
                if (location != null) {
                    locations.add(location.toFile().toPath().toAbsolutePath().normalize());
                }
            }
            return locations;
        } catch (LinkageError | RuntimeException e) {
            // no resources plugin / no workbench: adoption falls back to the climb
            return List.of();
        }
    }

    /**
     * The workspace location (the climb start and the base for relative
     * overrides); the process working directory when the platform location
     * is not yet set (the Board's historical fallback).
     */
    static Path workspaceRoot() {
        try {
            var location = Platform.getLocation();
            return location == null ? Path.of(".").toAbsolutePath().normalize()
                    : location.toFile().toPath().toAbsolutePath().normalize();
        } catch (LinkageError | RuntimeException e) {
            // no workbench: the climb starts at the working directory
            return Path.of(".").toAbsolutePath().normalize();
        }
    }
}
