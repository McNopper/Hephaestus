package com.opencode.ide.board.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import com.opencode.ide.core.OpencodeConnection;

/**
 * The SWT-free resolution order behind the Board's task-store root (B-002
 * AC-4 / O-001 parity): which {@code <something>/.opencode/tasks} directory
 * the board — and therefore its {@link TaskStoreWatcher} — actually watches.
 *
 * <p>Order (first hit wins):
 * <ol>
 * <li><b>Explicit override</b> — the toolbar's Store field / its persisted
 * dialog setting. A user-typed path is honored as-is (relative resolves
 * against the workspace), even when it does not exist: the mistake stays
 * visible as the board's "Task store not found" notice instead of silently
 * watching a different store.</li>
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
 * <p>Extracted from BoardView so the order — especially adoption-beats-
 * preference — is unit-testable without the workbench. Pure Java: the view
 * feeds in the Eclipse-derived inputs (workspace location, open project
 * locations, preference supplier).</p>
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
     */
    public static Path resolve(String override, Path workspace, List<Path> projectLocations,
            Supplier<String> preferenceRoot) {
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            return (path.isAbsolute() ? path : workspace.resolve(path)).normalize();
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
                return candidate.normalize();
            }
        }
        return workspace.resolve("..").resolve(".opencode").resolve("tasks").normalize();
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
}
