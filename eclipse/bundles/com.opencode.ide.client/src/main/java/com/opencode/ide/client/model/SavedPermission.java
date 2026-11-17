package com.opencode.ide.client.model;

/**
 * One remembered allow/deny permission rule ({@code GET /permission/saved}), i.e. the persisted
 * outcome of an {@code always} permission decision. v2.0.19 live probe: a {@code {data: [...]}}
 * envelope whose items carry an {@code id} plus further fields (action, pattern, ...); CP-07 pins
 * the three the saved-permission manager edits and verifies against ({@code id}, {@code action},
 * {@code resource}), everything else stays ignored. Nullable-tolerant.
 *
 * @param id the rule id ({@code perm_saved_...})
 * @param action the remembered action (e.g. {@code allow})
 * @param resource the remembered resource/pattern the rule covers
 */
public record SavedPermission(String id, String action, String resource) {

  /** The pre-CP-07 shape (id only) - keeps every existing construction site valid. */
  public SavedPermission(String id) {
    this(id, null, null);
  }

  /**
   * CP-07 AC3: does this remembered rule cover one of the requested patterns? Exact match, or a
   * wildcard tail in either direction ({@code /src/*} covers {@code /src/a.txt}, and a requested
   * {@code /src/*} is covered by a stored {@code /src/}). Absent resource/patterns never match.
   */
  public boolean matches(java.util.Collection<String> patterns) {
    if (resource == null || resource.isBlank() || patterns == null || patterns.isEmpty()) {
      return false;
    }
    for (String pattern : patterns) {
      if (pattern == null || pattern.isBlank()) {
        continue;
      }
      if (resource.equals(pattern)) {
        return true;
      }
      if (resource.endsWith("*")
          && pattern.startsWith(resource.substring(0, resource.length() - 1))) {
        return true;
      }
      if (pattern.endsWith("*")
          && resource.startsWith(pattern.substring(0, pattern.length() - 1))) {
        return true;
      }
    }
    return false;
  }
}
