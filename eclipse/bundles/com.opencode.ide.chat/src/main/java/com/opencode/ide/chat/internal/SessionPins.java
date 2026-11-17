package com.opencode.ide.chat.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pinned-session set of the in-chat switcher (CP-05 / FR-CP-05, AC2) - pure set/format logic
 * over the ids, SWT-free and persistence-free so it is unit-testable on its own.
 *
 * <p>Pin is a HOST-side marker: the live v2 API carries no pin verb and no {@code pinned} field
 * (probed 2026-11-17 against {@code GET /openapi.json}: zero occurrences), so the chat keeps its
 * own set in {@link ChatViewSettings} and the switcher lists pinned sessions first. Nothing here
 * invents a server write - the pin exists for the surface, exactly like the TUI's tab pin is a
 * client notion.
 */
public final class SessionPins {

  private SessionPins() {}

  /** Parses the stored comma-separated id list; {@code null}/blank/whitespace -> the empty set. */
  public static Set<String> parse(String stored) {
    Set<String> ids = new LinkedHashSet<>();
    if (stored == null || stored.isBlank()) {
      return ids;
    }
    for (String id : stored.split(",")) {
      String trimmed = id.trim();
      if (!trimmed.isEmpty()) {
        ids.add(trimmed);
      }
    }
    return ids;
  }

  /** Formats a set back to its storage form - sorted, so two equal sets serialize equally. */
  public static String format(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return "";
    }
    return String.join(",", new TreeSet<>(ids));
  }

  /** The set that results from pinning/unpinning {@code id} (a no-op id leaves the set alone). */
  public static Set<String> with(Set<String> current, String id, boolean pinned) {
    Set<String> next = new LinkedHashSet<>(current == null ? Set.of() : current);
    if (id == null || id.isBlank()) {
      return next;
    }
    if (pinned) {
      next.add(id);
    } else {
      next.remove(id);
    }
    return next;
  }

  /** Whether {@code id} is pinned ({@code null} sets/ids are not). */
  public static boolean contains(Set<String> ids, String id) {
    return ids != null && id != null && ids.contains(id);
  }
}
