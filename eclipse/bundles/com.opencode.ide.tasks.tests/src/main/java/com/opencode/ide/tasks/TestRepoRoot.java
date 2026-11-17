package com.opencode.ide.tasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared repo-root finder for tests that pin REPO TEXT (the SKILL/AGENTS contract tests): walks up
 * from the run directory until every marker file exists. One home for the walk so the contract
 * tests do not copy it (CPD).
 */
public final class TestRepoRoot {

  private TestRepoRoot() {}

  /**
   * @return the repo root containing ALL {@code markers}; never null (fails loudly)
   */
  public static Path find(String... markers) {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      boolean all = true;
      for (String marker : markers) {
        if (!Files.exists(dir.resolve(marker))) {
          all = false;
          break;
        }
      }
      if (all) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new AssertionError(
        "repo root not found from "
            + Paths.get("").toAbsolutePath()
            + " looking for "
            + String.join(", ", markers));
  }
}
