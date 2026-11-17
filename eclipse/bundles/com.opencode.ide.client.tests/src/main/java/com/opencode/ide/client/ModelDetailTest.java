package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.opencode.ide.client.model.ContextUsage;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.ModelDetail;
import java.util.List;
import org.junit.Test;

/**
 * Unit test for T-153's model-detail line (gap G5): the TUI {@code model-options} facts over the
 * {@code /model} catalog, in the wording the rest of the harness already uses. The last test pins
 * the seam criterion - the line formats through the SAME helpers as the T-149 status strip and the
 * T-151 Context block, so no two surfaces can disagree about a number.
 */
public class ModelDetailTest {

  private static Model model(double input, double output, long context, boolean reasoning) {
    JsonObject variants = null;
    if (reasoning) {
      variants = new JsonObject();
      variants.add("high", new JsonObject());
      variants.add("low", new JsonObject());
    }
    return new Model(
        "m1",
        "m1",
        "prov",
        null,
        "Model One",
        null,
        null,
        List.of(new Model.Cost(null, input, output, null)),
        new Model.Limit(context, 64_000L),
        null,
        null,
        null,
        null,
        variants);
  }

  @Test
  public void aPaidModelReadsPriceContextAndReasoningInTheTuiOrder() {
    Model model = model(2, 10, 1_048_576, true);

    assertEquals("$2.00 / $10.00 per 1M", ModelDetail.priceLine(model));
    assertEquals("1,048,576 tokens", ModelDetail.contextLine(model));
    assertEquals("Reasoning", ModelDetail.reasoningLine(model));
    assertEquals(
        "$2.00 / $10.00 per 1M \u00b7 1,048,576 tokens \u00b7 Reasoning", ModelDetail.line(model));
  }

  @Test
  public void aFreeModelSaysFreeAndKeepsItsFacts() {
    Model model = model(0, 0, 262_144, false);

    assertEquals("Free", ModelDetail.priceLine(model));
    assertTrue(ModelDetail.isFree(model));
    assertEquals("Free \u00b7 262,144 tokens", ModelDetail.line(model));
  }

  @Test
  public void aModelWithoutAPriceStillShowsItsContextWindow() {
    Model unpriced =
        new Model(
            "m2",
            "m2",
            "prov",
            null,
            "Auto",
            null,
            null,
            null,
            new Model.Limit(128_000L, 0L),
            null,
            null,
            null,
            null,
            null);

    assertEquals("", ModelDetail.priceLine(unpriced));
    assertEquals("128,000 tokens", ModelDetail.contextLine(unpriced));
    assertEquals("128,000 tokens", ModelDetail.line(unpriced));
  }

  @Test
  public void anUnknownModelRendersNoDetailRatherThanAGuess() {
    assertEquals("", ModelDetail.line(null));
    assertEquals("", ModelDetail.contextLine(null));
    assertEquals("", ModelDetail.priceLine(null));
    assertEquals("", ModelDetail.reasoningLine(null));
  }

  @Test
  public void theBadgeOnlyAppearsWhenTheModelExposesEffortVariants() {
    assertEquals("Reasoning", ModelDetail.reasoningLine(model(2, 10, 1_000, true)));
    assertEquals("", ModelDetail.reasoningLine(model(2, 10, 1_000, false)));
  }

  /** T-153 criterion 2: the strip's own formatters produce this line's words. */
  @Test
  public void theLineFormatsThroughTheSharedStripHelpers() {
    assertEquals("$9.96", ContextUsage.usdLabel(9.9627968));
    assertEquals("$0.00", ContextUsage.usdLabel(0));
    assertEquals("1,234 tokens", ContextUsage.tokensLabel(1234));
    assertEquals("$9.96", ContextUsage.costLabel(9.96));
    assertEquals("the footer keeps hiding zero spend", null, ContextUsage.costLabel(0.0));
  }
}
