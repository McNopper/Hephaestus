package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.Model;
import java.util.Comparator;

/**
 * Sorts {@link ModelRow}s by a chosen column (see the Providers view's column indexes): text
 * compare for most columns, numeric compare for the context column, boolean for the default column.
 * Ties break deterministically by provider name, then model name; the direction flips per re-click
 * on the same column and resets to ascending on a new column.
 *
 * <p>SWT-free and JFace-free on purpose — the view maps {@link #isAscending()} to {@code
 * SWT.UP}/{@code SWT.DOWN} itself.
 */
public final class ModelComparator implements Comparator<ModelRow> {

  private static final Comparator<String> TEXT =
      Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER);

  private int column = 0;
  private boolean ascending = true;

  /** Same column: flip the direction; new column: ascending. */
  public void applySort(int clicked) {
    if (clicked == column) {
      ascending = !ascending;
    } else {
      column = clicked;
      ascending = true;
    }
  }

  /** Whether the current direction is ascending. */
  public boolean isAscending() {
    return ascending;
  }

  /** Compares two rows by the current column and direction. */
  @Override
  public int compare(ModelRow x, ModelRow y) {
    int cmp;
    switch (column) {
      case 0:
        cmp = TEXT.compare(x.providerName(), y.providerName());
        break;
      case 1:
        cmp = TEXT.compare(modelName(x), modelName(y));
        break;
      case 2:
        cmp = TEXT.compare(modelId(x), modelId(y));
        break;
      case 3:
        cmp = TEXT.compare(modelStatus(x), modelStatus(y));
        break;
      case 4:
        cmp = TEXT.compare(ModelRows.capabilities(x.model()), ModelRows.capabilities(y.model()));
        break;
      case 5:
        cmp = Long.compare(ModelRows.contextValue(x.model()), ModelRows.contextValue(y.model()));
        break;
      case 6:
        cmp = Boolean.compare(x.defaultModel(), y.defaultModel());
        break;
      default:
        cmp = 0;
    }
    if (cmp == 0) {
      cmp = TEXT.compare(x.providerName(), y.providerName());
      if (cmp == 0) {
        cmp = TEXT.compare(modelName(x), modelName(y));
      }
    }
    return ascending ? cmp : -cmp;
  }

  private static String modelName(ModelRow r) {
    Model m = r.model();
    return m == null ? "" : (m.name() == null ? m.id() : m.name());
  }

  private static String modelId(ModelRow r) {
    Model m = r.model();
    return m == null ? "" : (m.id() == null ? "" : m.id());
  }

  private static String modelStatus(ModelRow r) {
    Model m = r.model();
    return m == null || m.status() == null ? "" : m.status();
  }
}
