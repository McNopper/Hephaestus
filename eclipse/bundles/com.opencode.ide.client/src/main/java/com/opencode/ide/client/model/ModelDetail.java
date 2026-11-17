package com.opencode.ide.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat model selector's per-model DETAIL line (T-153, gap G5) - the facts the TUI's {@code
 * model-options.tsx} {@code ModelDetail} shows next to a model's name, rendered beside the
 * selector's {@code provider/model} entry.
 *
 * <p>Order and badges follow the TUI ({@code component/model-options.tsx:49}, read from the
 * opencode source 2026-11-17): the price first, then the facts - context window, then {@code
 * Reasoning} when the model exposes effort variants - joined by the TUI's {@code ·} separator.
 * Price reads {@code Free} for a model whose every price tier is zero, and nothing at all when the
 * catalog carries no price (the TUI's {@code ""}).
 *
 * <p>The WORDING is deliberately the strip's: tokens render through {@link
 * ContextUsage#tokensLabel(long)} ({@code 1,048,576 tokens}, the T-151 sidebar dialect) and dollars
 * through {@link ContextUsage#usdLabel(double)} ({@code $2.00}, the T-149 footer currency), so the
 * selector, the status strip and the Session Details block can never disagree about a number (T-153
 * acceptance criterion 2). Nothing here fetches: the line is pure formatting over the
 * already-consumed {@code /model} catalog ({@code limit.context}, {@code cost[]}).
 */
public final class ModelDetail {

  private ModelDetail() {}

  /** Whether every price tier is zero - the TUI's {@code isFreeModel}. */
  public static boolean isFree(Model model) {
    if (model == null || model.cost() == null || model.cost().isEmpty()) {
      return false;
    }
    for (Model.Cost entry : model.cost()) {
      if (entry == null || entry.input() != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * The context window in the sidebar's wording: {@code 1,048,576 tokens}; {@code ""} if unknown.
   */
  public static String contextLine(Model model) {
    if (model == null || model.limit() == null || model.limit().context() <= 0) {
      return "";
    }
    return ContextUsage.tokensLabel(model.limit().context());
  }

  /**
   * The base price tier per million tokens: {@code $2.00 / $10.00 per 1M}, {@code Free}, {@code
   * ""}.
   */
  public static String priceLine(Model model) {
    if (model == null) {
      return "";
    }
    if (isFree(model)) {
      return "Free";
    }
    Model.Cost base = model.baseCost();
    if (base == null) {
      return "";
    }
    return ContextUsage.usdLabel(base.input())
        + " / "
        + ContextUsage.usdLabel(base.output())
        + " per 1M";
  }

  /** {@code Reasoning} when the model exposes reasoning-effort variants, {@code ""} otherwise. */
  public static String reasoningLine(Model model) {
    return model != null && !model.variantNames().isEmpty() ? "Reasoning" : "";
  }

  /**
   * The selector's detail line: {@code Free · 1,048,576 tokens · Reasoning}; {@code ""} when the
   * catalog knows nothing about the model (an unknown/vanished selection degrades to no detail
   * rather than a guess).
   */
  public static String line(Model model) {
    if (model == null) {
      return "";
    }
    List<String> facts = new ArrayList<>();
    add(facts, priceLine(model));
    add(facts, contextLine(model));
    add(facts, reasoningLine(model));
    return String.join(" \u00b7 ", facts);
  }

  private static void add(List<String> facts, String fact) {
    if (fact != null && !fact.isEmpty()) {
      facts.add(fact);
    }
  }
}
