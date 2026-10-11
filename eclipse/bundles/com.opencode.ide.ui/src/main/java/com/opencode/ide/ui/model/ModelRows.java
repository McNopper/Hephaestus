package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure row derivation and formatting behind the Providers view: maps a {@link ProviderList} to one
 * {@link ModelRow} per model (resolving each provider's default) and formats the
 * capabilities/context columns.
 *
 * <p>SWT-free and JFace-free on purpose (records and static methods over client model types only)
 * so it is unit-testable without a {@code Display} (see {@code ModelRowsTest} in {@code
 * com.opencode.ide.ui.tests}).
 */
public final class ModelRows {

  private ModelRows() {}

  /** One row per model; providers without models and null entries are skipped. */
  public static List<ModelRow> toRows(ProviderList list) {
    List<ModelRow> rows = new ArrayList<>();
    if (list == null || list.providers() == null) {
      return rows;
    }
    for (Provider provider : list.providers()) {
      if (provider == null || provider.models() == null) {
        continue;
      }
      String pName = provider.name() == null ? provider.id() : provider.name();
      String defaultModel = list.defaults() == null ? null : list.defaults().get(provider.id());
      provider.models().values().stream()
          .filter(m -> m != null)
          .forEach(
              m ->
                  rows.add(
                      new ModelRow(
                          pName, provider.id(), m, m.id() != null && m.id().equals(defaultModel))));
    }
    return rows;
  }

  /**
   * Capability letters: {@code R}easoning / {@code A}ttachment / {@code T}ool call ("" when none).
   */
  public static String capabilities(Model m) {
    if (m == null || m.capabilities() == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (m.capabilities().reasoning()) {
      sb.append("R");
    }
    if (m.capabilities().attachment()) {
      sb.append("A");
    }
    if (m.capabilities().toolcall()) {
      sb.append("T");
    }
    return sb.toString();
  }

  /** Context column text: {@code "128k"}-style ("" when absent or non-positive). */
  public static String context(Model m) {
    if (m == null || m.limit() == null) {
      return "";
    }
    long ctx = m.limit().context();
    return ctx <= 0 ? "" : (ctx / 1000) + "k";
  }

  /** The numeric context limit behind {@link #context} (0 when absent) — the sort key. */
  public static long contextValue(Model m) {
    return (m == null || m.limit() == null) ? 0L : m.limit().context();
  }
}
