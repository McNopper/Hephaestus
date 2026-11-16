package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatEntryDeserializer;
import com.opencode.ide.client.model.ContextUsage;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for {@link ContextUsage} - the shared TUI context-usage seam (T-151): the numbers both
 * Eclipse surfaces render (chat status strip footer dialect, Session Details sidebar dialect) and
 * their formatters. Entries parse through the SAME {@link ChatEntryDeserializer} the production
 * wire path uses, so the tests exercise the real shapes (assistant usage, compaction boundary).
 */
public class ContextUsageTest {

  private static final Gson GSON =
      new GsonBuilder().registerTypeAdapter(ChatEntry.class, new ChatEntryDeserializer()).create();

  private static final String ASSISTANT_WITH_USAGE =
      """
      {"id":"a1","type":"assistant","time":{"created":1},
       "model":{"id":"m1","providerID":"prov","variant":"default"},
       "tokens":{"input":600000,"output":0,"reasoning":0,"cache":{"read":0,"write":0}},
       "content":[]}
      """;

  private static final String COMPLETED_COMPACTION =
      """
      {"id":"c1","type":"compaction","time":{"created":1},"status":"completed","summary":"kept"}
      """;

  private static final Map<String, Long> LIMITS = Map.of("prov/m1", 1_000_000L);

  private static List<ChatEntry> entries(String... json) {
    return GSON.fromJson(
        "[" + String.join(",", json) + "]",
        TypeToken.getParameterized(List.class, ChatEntry.class).getType());
  }

  // ---------- compute (the numbers) ----------

  @Test
  public void computeSumsFiveFieldsAndPercentOfTheModelLimit() {
    ContextUsage.Usage usage = ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), LIMITS);

    assertEquals(600_000L, usage.tokens());
    assertEquals(Long.valueOf(60L), usage.percent());
  }

  @Test
  public void computeOmitsThePercentWhenTheModelIsUnknown() {
    ContextUsage.Usage usage = ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), Map.of());

    assertEquals(600_000L, usage.tokens());
    assertNull("the TUI omits the percent for an unknown model", usage.percent());
  }

  @Test
  public void computeStartsAfterTheNewestCompletedCompaction() {
    ContextUsage.Usage usage =
        ContextUsage.compute(entries(ASSISTANT_WITH_USAGE, COMPLETED_COMPACTION), LIMITS);

    assertNull("a completed compaction with no assistant step after it hides the segment", usage);
  }

  @Test
  public void computeHidesOnEmptyInputAndZeroSumUsage() {
    assertNull(ContextUsage.compute(null, LIMITS));
    assertNull(ContextUsage.compute(List.of(), LIMITS));
    assertNull(
        "zero-sum usage hides and does NOT fall through to older steps",
        ContextUsage.compute(
            entries(
                ASSISTANT_WITH_USAGE,
                """
                {"id":"a2","type":"assistant","time":{"created":2},
                 "tokens":{"input":0,"output":0,"reasoning":0},"content":[]}
                """),
            LIMITS));
  }

  // ---------- the footer dialect (chat status strip / TUI prompt footer) ----------

  @Test
  public void footerLabelRendersCompactWithPercentInParentheses() {
    assertEquals(
        "600.0K (60%)",
        ContextUsage.footerLabel(ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), LIMITS)));
    assertEquals(
        "600.0K",
        ContextUsage.footerLabel(ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), Map.of())));
    assertNull(ContextUsage.footerLabel(null));
  }

  @Test
  public void compactNumberMatchesTheTuiLocaleNumber() {
    assertEquals("999", ContextUsage.compactNumber(999));
    assertEquals("1.2K", ContextUsage.compactNumber(1234));
    assertEquals("1.0M", ContextUsage.compactNumber(999950));
  }

  // ---------- the sidebar dialect (Session Details Context block, T-151) ----------

  @Test
  public void sidebarLinesUseTheTuiWordingVerbatim() {
    ContextUsage.Usage usage = ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), LIMITS);

    assertEquals(
        "grouped plain numbers, not the compact footer",
        "600,000 tokens",
        ContextUsage.tokensLine(usage));
    assertEquals("60% used", ContextUsage.percentLine(usage));
    assertEquals("$9.96 spent", ContextUsage.spentLine(9.96));
  }

  @Test
  public void sidebarLinesHideIndividuallyLikeTheTuiShows() {
    ContextUsage.Usage noPercent = ContextUsage.compute(entries(ASSISTANT_WITH_USAGE), Map.of());

    assertNull("no percent -> no percent line", ContextUsage.percentLine(noPercent));
    assertNull(ContextUsage.tokensLine(null));
    assertNull(ContextUsage.percentLine(null));
    assertNull("free sessions show no spent line", ContextUsage.spentLine(null));
    assertNull(ContextUsage.spentLine(0.0));
    assertNull(ContextUsage.costLabel(-1.0));
  }

  @Test
  public void costLabelFormatsUsdLikeTheTuiMoney() {
    assertEquals("$9.96", ContextUsage.costLabel(9.96));
    assertEquals("$1,234.50", ContextUsage.costLabel(1234.5));
  }
}
