package com.opencode.ide.client.model;

import com.google.gson.annotations.SerializedName;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The workspace budgets of {@code GET /api/experimental/usage/limits} (T-152, gap G4) and the TUI's
 * wording over them - the client half of the {@code usage.show} parity, consumed by the Providers
 * view's Usage columns.
 *
 * <p>Live probe (local opencode 2.3.0, 2026-11-17, authenticated - the route answers HTTP 401
 * without a token, 200 with one, exactly like every other {@code /api} route):
 *
 * <pre>
 * {location:{directory:"..."},
 *  data:{workspace:{id:"wrk_...", name:"Default", usageURL:"https://opencode.ai/console/..."},
 *        budgets:[{id:"rule_legacy_budget_...", limit:100, spent:0, scope:"each", per:"set",
 *                  coverage:"all", models:[], vendors:[], picked:false, shared:false,
 *                  resetsAt:1796083200000}]}}
 * </pre>
 *
 * <p>{@code limit} and {@code spent} are USD, {@code resetsAt} is epoch milliseconds, and an empty
 * {@code models}/{@code vendors} list under {@code coverage:"all"} means "every model". The
 * workspace carries {@code usageURL}, the console page behind the "open usage console" link.
 *
 * <p>{@link #modelUsage}, {@link #leftLabel}, {@link #formatDollars}, {@link #formatReset} and
 * {@link #scopeLabel} are the port of upstream {@code packages/tui/src/util/usage-limits.ts} and
 * {@code packages/tui/src/component/usage.tsx} (read from the opencode source 2026-11-17), so the
 * Eclipse wording is the TUI dialog's wording:
 *
 * <ul>
 *   <li>a budget applies to a model only when the model is paid <b>and</b> - for an all-coverage
 *       budget - its provider is served by the OpenCode Console ({@code integrationID ==
 *       "opencode"}); a model-specific budget applies through its {@code models} entry;
 *   <li>the binding limit is the one closest to running out ({@code left} ascending);
 *   <li>labels: {@code $100 left}, {@code Limit reached}, {@code $0 used of $100}, {@code Resets
 *       Nov 30}, {@code Only you · All models}.
 * </ul>
 *
 * <p>Numbers format through {@link Locale#ROOT}/{@link Locale#US} so the rendered wording matches
 * the TUI's documented examples on every machine (deterministic for tests too).
 */
public record UsageLimits(Workspace workspace, List<Budget> budgets) {

  /** Where the budgets live: the workspace a console {@code usageURL} belongs to. */
  public record Workspace(String id, String name, @SerializedName("usageURL") String usageUrl) {}

  /**
   * One budget rule. {@code scope} who spends from it ({@code each}/shared), {@code per} whether
   * spend is counted per model or as a set, {@code coverage} which models it covers ({@code all} or
   * the {@code models} list).
   */
  public record Budget(
      String id,
      double limit,
      double spent,
      String scope,
      String per,
      String coverage,
      List<BudgetModel> models,
      List<String> vendors,
      Boolean picked,
      Boolean shared,
      @SerializedName("inputPriceAtLeast") Double inputPriceAtLeast,
      long resetsAt) {}

  /**
   * A model entry of a model-scoped budget; {@code spent} is per-model only when {@code per} is.
   */
  public record BudgetModel(
      @SerializedName("providerID") String providerId,
      @SerializedName("modelID") String modelId,
      String name,
      Double spent) {}

  /** One limit as it applies to one model: what was spent against it and what is left. */
  public record Limit(Budget budget, double spent, double left) {}

  /**
   * What a model has left: the limit closest to running out sets {@code left} (any limit running
   * out stops the model), {@code reached} is whether any of them is spent, {@code resetsAt} the
   * earliest reset of them.
   */
  public record ModelUsage(double left, boolean reached, long resetsAt, List<Limit> limits) {}

  /** The TUI's empty-state sentence ({@code component/usage.tsx emptyView}), verbatim. */
  public static final String NO_LIMITS =
      "No limits apply to your models. Paid models stop only if the workspace runs out of credits.";

  // ---------- wording (TUI ports) ----------

  /**
   * {@code formatDollars}: whole amounts without cents ({@code $100}), cents when the amount has
   * them ({@code $9.96}), and a sliver as {@code <$0.01} rather than {@code $0.00}.
   */
  public static String formatDollars(double value) {
    if (value > 0 && value < 0.01) {
      return "<$0.01";
    }
    NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
    // JS Number.isInteger(value): whole amounts render without cents
    format.setMinimumFractionDigits(value == Math.floor(value) ? 0 : 2);
    format.setMaximumFractionDigits(2);
    return format.format(value);
  }

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

  /**
   * {@code formatReset}: the day, with the time unless it is midnight here, or just the time when
   * that is today - {@code 1:00 AM today}, {@code Nov 30}, {@code Nov 30, 1:00 AM}.
   */
  public static String formatReset(long at) {
    ZoneId zone = ZoneId.systemDefault();
    Instant instant = Instant.ofEpochMilli(at);
    LocalDate date = instant.atZone(zone).toLocalDate();
    LocalTime time = instant.atZone(zone).toLocalTime();
    String clock = CLOCK.format(time).replace('\u202f', ' ').replace('\u00a0', ' ');
    if (date.equals(LocalDate.now(zone))) {
      return clock + " today";
    }
    String day = DAY.format(date);
    return time.getHour() == 0 && time.getMinute() == 0 ? day : day + ", " + clock;
  }

  /**
   * {@code limitLabel}'s wording for one model: {@code null} while there is plenty left (the TUI
   * shows nothing), {@code Limit reached} at the limit, otherwise {@code $100 left}.
   */
  public static String leftLabel(ModelUsage usage) {
    if (usage == null) {
      return "";
    }
    if (usage.reached() || usage.left() <= 0) {
      return "Limit reached";
    }
    return formatDollars(usage.left()) + " left";
  }

  /**
   * {@code limitScope}: who spends from the budget and - when it pools spend across models - which
   * ones. {@code Only you · All models}, {@code Everyone in Default}, {@code You and others · All
   * anthropic models}.
   */
  public static String scopeLabel(Budget budget, String workspace) {
    if (budget == null) {
      return "";
    }
    String who;
    if ("each".equals(budget.scope())) {
      who = "Only you";
    } else if (Boolean.TRUE.equals(budget.shared())) {
      who =
          "Everyone in " + (workspace == null || workspace.isBlank() ? "the workspace" : workspace);
    } else {
      who = "You and others";
    }
    if ("model".equals(budget.per())) {
      return who;
    }
    return who + " \u00b7 " + limitModels(budget);
  }

  /** Which models a pooled budget covers (the TUI's {@code limitModels}). */
  private static String limitModels(Budget budget) {
    if ("all".equals(budget.coverage())) {
      return "All models";
    }
    List<String> vendors = budget.vendors() == null ? List.of() : budget.vendors();
    String vendorList = vendors.isEmpty() ? null : list(vendors);
    String price =
        budget.inputPriceAtLeast() == null
            ? null
            : formatDollars(budget.inputPriceAtLeast()) + " per 1M";
    if (vendorList != null && price != null) {
      return vendorList + " models over " + price;
    }
    if (vendorList != null) {
      return "All " + vendorList + " models";
    }
    if (price != null) {
      return "Models over " + price;
    }
    List<String> names = new ArrayList<>();
    if (budget.models() != null) {
      for (BudgetModel model : budget.models()) {
        if (model != null && model.name() != null) {
          names.add(model.name());
        }
      }
    }
    if (names.size() > 3) {
      return String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3) + " more";
    }
    return String.join(", ", names);
  }

  /**
   * {@code Intl.ListFormat} en-US "long conjunction": {@code a}, {@code a and b}, {@code a, b, c}.
   */
  private static String list(List<String> items) {
    if (items.size() == 1) {
      return items.get(0);
    }
    if (items.size() == 2) {
      return items.get(0) + " and " + items.get(1);
    }
    return String.join(", ", items.subList(0, items.size() - 1))
        + ", and "
        + items.get(items.size() - 1);
  }

  // ---------- the description line (the TUI dialog's title description) ----------

  /**
   * The TUI dialog's description, one line: {@code Workspace: Default · $0 used of $100 · $100 left
   * · Resets Nov 30, 1:00 AM}; the empty-state sentence when the workspace carries no budgets;
   * {@code null} when nothing was loaded (the caller then says "unavailable" - never a number).
   */
  public static String summary(UsageLimits info) {
    if (info == null) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    Workspace workspace = info.workspace();
    if (workspace != null && workspace.name() != null && !workspace.name().isBlank()) {
      parts.add("Workspace: " + workspace.name());
    }
    List<Budget> budgets = info.budgets() == null ? List.of() : info.budgets();
    List<Budget> real = new ArrayList<>();
    for (Budget budget : budgets) {
      if (budget != null) {
        real.add(budget);
      }
    }
    if (real.isEmpty()) {
      parts.add(NO_LIMITS);
      return String.join(" \u00b7 ", parts);
    }
    long resetsAt = Long.MAX_VALUE;
    for (Budget budget : real) {
      parts.add(formatDollars(budget.spent()) + " used of " + formatDollars(budget.limit()));
      parts.add(formatDollars(Math.max(0, budget.limit() - budget.spent())) + " left");
      resetsAt = Math.min(resetsAt, budget.resetsAt());
    }
    if (resetsAt != Long.MAX_VALUE) {
      parts.add("Resets " + formatReset(resetsAt));
    }
    return String.join(" \u00b7 ", parts);
  }

  // ---------- per-model usage (the TUI's modelUsage) ----------

  /**
   * Whether the OpenCode Console serves this provider, so an all-coverage workspace budget can
   * apply to its models ({@code servedByConsole}: {@code integrationID == "opencode"}, OpenCode Go
   * bills its own allowance and stays out).
   */
  public static boolean servedByConsole(String providerId, String integrationId) {
    return "opencode".equals(integrationId) && !"opencode-go".equals(providerId);
  }

  /**
   * The limits on one paid model, or {@code null} when none applies (free models stay usable
   * whatever is spent - the TUI returns undefined and shows nothing for them).
   *
   * @param cost the model's price tiers ({@code cost[]}, first entry is the base rate)
   */
  public static ModelUsage modelUsage(
      UsageLimits info,
      String providerId,
      String modelId,
      List<Model.Cost> cost,
      boolean servedByConsole) {
    if (info == null || info.budgets() == null || info.budgets().isEmpty()) {
      return null;
    }
    if (isFree(cost)) {
      return null;
    }
    List<Limit> limits = new ArrayList<>();
    for (Budget budget : info.budgets()) {
      if (budget == null) {
        continue;
      }
      if ("all".equals(budget.coverage())) {
        if (servedByConsole) {
          limits.add(limit(budget, budget.spent()));
        }
        continue;
      }
      BudgetModel entry = find(budget.models(), providerId, modelId);
      if (entry == null) {
        continue;
      }
      double spent =
          "model".equals(budget.per()) && entry.spent() != null ? entry.spent() : budget.spent();
      limits.add(limit(budget, spent));
    }
    if (limits.isEmpty()) {
      return null;
    }
    limits.sort(Comparator.comparingDouble(Limit::left));
    Limit binding = limits.get(0);
    boolean reached = false;
    long resetsAt = Long.MAX_VALUE;
    for (Limit entry : limits) {
      reached |= entry.spent() >= entry.budget().limit();
      resetsAt = Math.min(resetsAt, entry.budget().resetsAt());
    }
    return new ModelUsage(binding.left(), reached, resetsAt, List.copyOf(limits));
  }

  private static Limit limit(Budget budget, double spent) {
    return new Limit(budget, spent, Math.max(0, budget.limit() - spent));
  }

  /** A model whose every price tier is zero is free: the TUI never attaches a limit to it. */
  private static boolean isFree(List<Model.Cost> cost) {
    if (cost == null || cost.isEmpty()) {
      return false;
    }
    for (Model.Cost entry : cost) {
      if (entry == null || entry.input() != 0) {
        return false;
      }
    }
    return true;
  }

  private static BudgetModel find(List<BudgetModel> models, String providerId, String modelId) {
    if (models == null) {
      return null;
    }
    for (BudgetModel model : models) {
      if (model != null
          && model.providerId() != null
          && model.modelId() != null
          && model.providerId().equals(providerId)
          && model.modelId().equals(modelId)) {
        return model;
      }
    }
    return null;
  }
}
