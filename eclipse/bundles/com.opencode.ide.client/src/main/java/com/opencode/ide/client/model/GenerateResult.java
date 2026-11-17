package com.opencode.ide.client.model;

/**
 * The response of {@code POST /session/:id/generate} - the transient completion's text plus the
 * turn's ACTUALS the server reports there since v2.2.7 (#62835, adoption L265): {@code tokens} and
 * {@code cost}. Both figures are optional: a build older than 2.2.7 answers {@code {text}} only,
 * and then {@link #hasUsage()} is {@code false} and nothing downstream renders a usage line.
 *
 * <p>This is the cheap actuals source the chat's per-turn cost line renders from (CP-06 / FR-CP-06
 * AC2) - no parallel bookkeeping of our own (adoption L265's decision: pull cost from upstream).
 *
 * @param text the generated text ({@code null} when the server returned none)
 * @param cost the call's cost in USD ({@code null} when unreported)
 * @param tokens the call's token counts ({@code null} when unreported)
 */
public record GenerateResult(String text, Double cost, Session.Tokens tokens) {

  /** Whether the server reported ANY usage figure (the per-turn line renders only then). */
  public boolean hasUsage() {
    if (cost != null && cost > 0) {
      return true;
    }
    return tokens != null && tokenSum(tokens) > 0;
  }

  /** The summed token count (all five fields, cache null-safe); {@code 0} when unreported. */
  public static long tokenSum(Session.Tokens tokens) {
    if (tokens == null) {
      return 0;
    }
    long cache = tokens.cache() == null ? 0 : tokens.cache().read() + tokens.cache().write();
    return tokens.input() + tokens.output() + tokens.reasoning() + cache;
  }
}
