package com.opencode.ide.ui.model;

import java.util.Locale;

/**
 * Case-insensitive substring filter across the visible fields of a {@link ModelRow} (provider
 * name/id, model id/name/status). An empty filter passes everything. SWT-free — the Providers view
 * feeds it the filter text and runs it over the source array.
 */
public final class ModelFilter {

  private String f = "";

  /** Sets the filter text (trimmed, lower-cased; null/blank clears it). */
  public void setFilter(String text) {
    this.f = (text == null) ? "" : text.trim().toLowerCase(Locale.ROOT);
  }

  /** Whether the row matches the current filter. */
  public boolean accepts(ModelRow r) {
    if (f.isEmpty()) {
      return true;
    }
    var m = r.model();
    return matches(r.providerName())
        || matches(r.providerId())
        || matches(m == null ? null : m.id())
        || matches(m == null ? null : m.name())
        || matches(m == null ? null : m.status());
  }

  private boolean matches(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(f);
  }
}
