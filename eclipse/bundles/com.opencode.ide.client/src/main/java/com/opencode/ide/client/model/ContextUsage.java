package com.opencode.ide.client.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The TUI's context-usage NUMBERS and the two wording dialects over them (T-151's shared seam, so
 * the chat status strip and the Session Details Context block can never drift apart).
 *
 * <p>{@link #compute(List, Map)} is the port of the TUI's {@code util/session.ts contextUsage}
 * (probed against the TUI source 2026-11-16): the LAST assistant step that carries usage, at or
 * after the newest completed compaction, with all five token fields summed; {@code percent} =
 * {@code round(tokens / limit.context)} of the matched model's {@code limit}. Zero-sum usage or
 * nothing qualifying returns {@code null} - both TUI surfaces then HIDE (the footer drops the
 * segment, the sidebar block only shows when the session cost &gt; 0, {@code context.tsx:21}).
 *
 * <p>Two dialects render the same {@link Usage}, exactly as the TUI does:
 *
 * <ul>
 *   <li><b>footer</b> ({@code prompt/footer.tsx} + the chat status strip): {@code 596.1K (7%)} -
 *       compact numbers, percent in parentheses;
 *   <li><b>sidebar</b> ({@code sidebar/context.tsx} + Session Details Context block): {@code 1,234
 *       tokens} / {@code 57% used} / {@code $9.96 spent} - grouped plain numbers, separate lines.
 * </ul>
 *
 * <p>Grouping/format use {@link Locale#ROOT} so the rendered wording matches the TUI's documented
 * examples on every machine (deterministic for tests too).
 */
public final class ContextUsage {

  private ContextUsage() {}

  /** The raw numbers both dialects render: summed tokens + rounded percent ({@code null} = n/a). */
  public record Usage(long tokens, Long percent) {}

  /**
   * The TUI's {@code contextUsage} port: last assistant step with usage after the newest completed
   * compaction; five token fields summed; percent of the model's {@code limit.context}; {@code
   * null} when nothing qualifies or the newest usage sums to zero (the TUI hides, no fall-through
   * to older steps).
   */
  public static Usage compute(List<ChatEntry> entries, Map<String, Long> limits) {
    if (entries == null || entries.isEmpty()) {
      return null;
    }
    int start = 0;
    for (int i = entries.size() - 1; i >= 0; i--) {
      ChatEntry entry = entries.get(i);
      if (entry == null) {
        continue;
      }
      if (entry.isCompaction()
          && entry.compaction() != null
          && "completed".equals(entry.compaction().status())) {
        start = i + 1; // only assistant steps AFTER the newest completed compaction count
        break;
      }
    }
    for (int i = entries.size() - 1; i >= start; i--) {
      ChatEntry entry = entries.get(i);
      if (entry == null || entry.info() == null || !"assistant".equals(entry.info().role())) {
        continue;
      }
      Session.Tokens tokens = entry.info().tokens();
      if (tokens == null) {
        continue; // the TUI skips assistants without usage
      }
      long sum =
          tokens.input()
              + tokens.output()
              + tokens.reasoning()
              + (tokens.cache() == null ? 0 : tokens.cache().read() + tokens.cache().write());
      if (sum <= 0) {
        return null; // TUI: tokens <= 0 -> undefined -> hidden (no fall-through to older steps)
      }
      Long limit =
          limits == null
              ? null
              : limits.get(entry.info().providerId() + "/" + entry.info().modelId());
      if (limit == null || limit <= 0) {
        return new Usage(
            sum, null); // percent omitted exactly like the TUI when the model is unknown
      }
      return new Usage(sum, Math.round(sum / (double) limit * 100));
    }
    return null;
  }

  /**
   * TUI {@code Locale.number}: 999950+ renders "X.XM", 1000+ renders "X.XK", else the plain number.
   */
  public static String compactNumber(long value) {
    if (value >= 999950) {
      return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
    }
    if (value >= 1000) {
      return String.format(Locale.ROOT, "%.1fK", value / 1000.0);
    }
    return Long.toString(value);
  }

  /** The footer dialect ({@code formatContextUsage}): {@code 596.1K (7%)}; {@code null} hides. */
  public static String footerLabel(Usage usage) {
    if (usage == null) {
      return null;
    }
    String value = compactNumber(usage.tokens());
    return usage.percent() == null ? value : value + " (" + usage.percent() + "%)";
  }

  /** The sidebar dialect line 1: {@code 1,234 tokens}; {@code null} hides. */
  public static String tokensLine(Usage usage) {
    return usage == null ? null : tokensLabel(usage.tokens());
  }

  /** The tokens wording shared by every surface: {@code 1,234 tokens} (T-153 reuses it). */
  public static String tokensLabel(long tokens) {
    return String.format(Locale.ROOT, "%,d tokens", tokens);
  }

  /** The sidebar dialect line 2: {@code 57% used}; {@code null} hides. */
  public static String percentLine(Usage usage) {
    return usage == null || usage.percent() == null ? null : usage.percent() + "% used";
  }

  /** The footer cost ({@code Intl.NumberFormat} USD): {@code $9.96}; {@code null} hides. */
  public static String costLabel(Double cost) {
    if (cost == null || cost <= 0) {
      return null;
    }
    return usdLabel(cost);
  }

  /** The one USD formatter every surface formats through ({@code $9.96}, {@code $0.00}). */
  public static String usdLabel(double value) {
    return String.format(Locale.ROOT, "$%,.2f", value);
  }

  /** The sidebar dialect line 3: {@code $9.96 spent}; {@code null} hides. */
  public static String spentLine(Double cost) {
    String label = costLabel(cost);
    return label == null ? null : label + " spent";
  }
}
