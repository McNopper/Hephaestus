package com.opencode.ide.board.model;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Migrates a persisted Board store override that still points at the PRE-RELOCATION location
 * (T-052: {@code .opencode/tasks} moved to {@code .harness/tasks} because opencode's config watch
 * cycled the tasks MCP server on every store-write burst).
 *
 * <p>The override is priority #1 in {@link com.opencode.ide.core.TasksRootResolution} and is
 * honored as-is so a user's mistake stays visible - but a stale override pointing at the LEGACY
 * path is not a mistake the user typed after the migration: the old directory often still exists as
 * an empty skeleton (the tracked {@code .lock} residue kept it alive), so the store "loads" fine
 * and the board silently shows zero tickets (observed live 2026-11-18 on workspace-cpp: {@code
 * [board] PIPELINE | total=0} while the relocated store held 383 tickets). This one-key re-pointing
 * is the settings migration the relocation should have carried: legacy {@code
 * <repo>/.opencode/tasks} &rarr; sibling {@code <repo>/.harness/tasks} when that exists; anything
 * else is returned unchanged, and the legacy path stays honored when the relocation target is
 * absent (the visible not-found notice then still applies).
 */
public final class TasksOverrideMigration {

  private TasksOverrideMigration() {}

  /** The legacy store directory name under the repo's {@code .opencode} folder. */
  private static final String LEGACY_DIR = ".opencode" + java.io.File.separator + "tasks";

  /**
   * @param rootOverride the persisted override text (may be {@code null})
   * @return the migrated override when the legacy layout is detected and the relocation target
   *     exists; otherwise the input unchanged
   */
  public static String migrate(String rootOverride) {
    if (rootOverride == null || rootOverride.isBlank()) {
      return rootOverride;
    }
    String normalized = rootOverride.trim().replace('/', java.io.File.separatorChar);
    if (!normalized
        .toLowerCase(java.util.Locale.ROOT)
        .endsWith(LEGACY_DIR.toLowerCase(java.util.Locale.ROOT))) {
      return rootOverride; // not the legacy layout: the user's value stands
    }
    try {
      Path legacy = Path.of(normalized);
      // <repo>/.opencode/tasks -> <repo>/.harness/tasks
      Path repo = legacy.getParent() == null ? null : legacy.getParent().getParent();
      if (repo == null) {
        return rootOverride;
      }
      Path relocated = repo.resolve(".harness").resolve("tasks");
      return Files.isDirectory(relocated) ? relocated.toString() : rootOverride;
    } catch (RuntimeException e) {
      return rootOverride; // an unreadable value stays the user's value
    }
  }
}
