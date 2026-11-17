package com.opencode.ide.chat.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The model selector's recent-models list (CP-09 / FR-CP-09) - the pure list math: most-recent
 * first, no duplicates, blanks skipped, capped. Persisted by {@link ChatViewSettings} exactly like
 * the switcher's pins ({@link SessionPins}): the settings own WHERE it lives, this class owns HOW
 * it behaves, so both are unit-testable without an OSGi runtime.
 */
public final class ModelRecents {

  /** How many recent models the selector remembers (FR-CP-09: "the last N used"). */
  public static final int LIMIT = 5;

  private ModelRecents() {}

  /**
   * The list that results from using {@code model}: it moves to the front, the previous front keeps
   * its order behind it, and the list is trimmed to {@code limit}. Blank/unknown-shaped input and
   * an over-long list are handled here, never by callers.
   */
  public static List<String> recentWith(List<String> current, String model, int limit) {
    List<String> next = new ArrayList<>();
    if (model != null && !model.isBlank()) {
      next.add(model.trim());
    }
    if (current != null) {
      for (String entry : current) {
        if (entry != null && !entry.isBlank()) {
          next.add(entry.trim());
        }
      }
    }
    // de-duplicate most-recent-first (the freshly used model wins its place at the front)
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(next));
    return unique.size() > limit ? new ArrayList<>(unique.subList(0, limit)) : unique;
  }

  /** Formats the list for storage - order matters (most recent first), so no sorting. */
  public static String format(List<String> models) {
    return models == null ? "" : String.join(",", models);
  }

  /** Parses the stored comma list, preserving order; {@code null}/blank -> the empty list. */
  public static List<String> parse(String stored) {
    List<String> models = new ArrayList<>();
    if (stored == null || stored.isBlank()) {
      return models;
    }
    for (String id : stored.split(",")) {
      String trimmed = id.trim();
      if (!trimmed.isEmpty()) {
        models.add(trimmed);
      }
    }
    return models;
  }
}
