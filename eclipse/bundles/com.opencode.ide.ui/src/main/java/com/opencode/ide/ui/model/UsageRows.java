package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.UsageLimits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Providers view's Usage columns (T-152, gap G4): for every model the workspace budgets
 * actually cover, the three cells the TUI's {@code usage.show} dialog shows - budget, spent and
 * left - in the TUI's wording ({@code $100}, {@code $0}, {@code $100 left}).
 *
 * <p>SWT-free and projection-only: {@link #build} pairs the view's rows with the loaded {@link
 * UsageLimits} and the providers' {@code integrationID}s, every number comes from {@link
 * UsageLimits}' TUI ports, and a model no budget covers simply gets no cell (the TUI shows nothing
 * for it either - free models and models billed outside the workspace stay out). Nothing here
 * invents a number: no limits loaded = no rows = empty columns plus the view's honest "unavailable"
 * line.
 *
 * <p>When several budgets cover one model, the BINDING one (the closest to running out, the one
 * that sets {@code left}) supplies all three cells, so budget/spent/left always belong to a single
 * rule and read as one fact.
 */
public final class UsageRows {

  /** The Usage columns follow the view's seven existing ones: 7 = Budget, 8 = Spent, 9 = Left. */
  public static final int BUDGET = 7;

  /** See {@link #BUDGET}. */
  public static final int SPENT = 8;

  /** See {@link #BUDGET}. */
  public static final int LEFT = 9;

  /** One model's usage cells plus the numeric values the table sorts those columns by. */
  public record Row(
      String budget,
      String spent,
      String left,
      String scope,
      Double limitValue,
      Double spentValue,
      Double leftValue) {}

  private UsageRows() {}

  /** The stable key of a row's usage ({@code providerId/modelId}), also the lookup key. */
  public static String key(ModelRow row) {
    if (row == null || row.model() == null || row.model().id() == null) {
      return "";
    }
    return row.providerId() + "/" + row.model().id();
  }

  /**
   * The usage cell per model key: empty when no limits were loaded, and - per row - when no budget
   * covers that model.
   *
   * @param integrations providerId -&gt; the integration serving it ({@code integrationID}); an
   *     unknown provider reads as "not console-served", so no all-coverage budget applies to it
   */
  public static Map<String, Row> build(
      UsageLimits limits, List<ModelRow> rows, Map<String, String> integrations) {
    if (limits == null || rows == null || rows.isEmpty()) {
      return Map.of();
    }
    Map<String, String> providers = integrations == null ? Map.of() : integrations;
    String workspace = limits.workspace() == null ? null : limits.workspace().name();
    Map<String, Row> out = new HashMap<>();
    for (ModelRow row : rows) {
      if (row == null || row.model() == null || row.model().id() == null) {
        continue;
      }
      UsageLimits.ModelUsage usage =
          UsageLimits.modelUsage(
              limits,
              row.providerId(),
              row.model().id(),
              row.model().cost(),
              UsageLimits.servedByConsole(row.providerId(), providers.get(row.providerId())));
      if (usage == null || usage.limits().isEmpty()) {
        continue; // the TUI shows nothing for a model no budget covers
      }
      UsageLimits.Limit binding = usage.limits().get(0);
      out.put(
          key(row),
          new Row(
              UsageLimits.formatDollars(binding.budget().limit()),
              UsageLimits.formatDollars(binding.spent()),
              UsageLimits.leftLabel(usage),
              UsageLimits.scopeLabel(binding.budget(), workspace),
              binding.budget().limit(),
              binding.spent(),
              usage.left()));
    }
    return out;
  }

  /** A model's cell for a usage column; empty string when no budget covers it. */
  public static String cell(Map<String, Row> rows, ModelRow row, int column) {
    Row value = rows == null ? null : rows.get(key(row));
    if (value == null) {
      return "";
    }
    if (column == BUDGET) {
      return value.budget();
    }
    if (column == SPENT) {
      return value.spent();
    }
    return value.left();
  }

  /** The numeric sort value of a usage column; {@code null} when the model has no usage. */
  public static Double sortValue(Map<String, Row> rows, ModelRow row, int column) {
    Row value = rows == null ? null : rows.get(key(row));
    if (value == null) {
      return null;
    }
    if (column == BUDGET) {
      return value.limitValue();
    }
    if (column == SPENT) {
      return value.spentValue();
    }
    return value.leftValue();
  }

  /** The tooltip of a usage column cell - the TUI's scope line ({@code Only you · All models}). */
  public static String scope(Map<String, Row> rows, ModelRow row) {
    Row value = rows == null ? null : rows.get(key(row));
    return value == null ? "" : value.scope();
  }
}
