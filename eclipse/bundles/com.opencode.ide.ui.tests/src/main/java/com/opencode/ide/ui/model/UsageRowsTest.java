package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.UsageLimits;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for the Providers view's Usage projection (T-152, gap G4): which models get the
 * budget/spent/left cells, the TUI wording of those cells, and - just as important - which models
 * get NONE of them. An unloaded {@link UsageLimits} must yield an empty projection, so the view can
 * never render a number the server did not hand over.
 */
public class UsageRowsTest {

  private static final long RESET =
      java.time.LocalDate.of(2026, 11, 30)
          .atStartOfDay(java.time.ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();

  private static UsageLimits limits() {
    return new UsageLimits(
        new UsageLimits.Workspace("wrk_1", "Default", "https://opencode.ai/console/wrk_1"),
        List.of(
            new UsageLimits.Budget(
                "b1", 100, 0, "each", "set", "all", List.of(), List.of(), false, false, null,
                RESET)));
  }

  private static Model model(String id, double inputPrice) {
    return new Model(
        id,
        id,
        "opencode",
        null,
        id,
        null,
        null,
        List.of(new Model.Cost(null, inputPrice, inputPrice * 5, null)),
        new Model.Limit(1_000_000L, 64_000L),
        null,
        null,
        null,
        null,
        null);
  }

  private static ModelRow row(String providerId, Model model) {
    return new ModelRow(providerId, providerId, model, false);
  }

  @Test
  public void aConsoleServedPaidModelGetsAllThreeCellsInTuiWording() {
    ModelRow claude = row("opencode", model("claude", 2));

    Map<String, UsageRows.Row> rows =
        UsageRows.build(limits(), List.of(claude), Map.of("opencode", "opencode"));

    UsageRows.Row value = rows.get(UsageRows.key(claude));
    assertEquals("$100", value.budget());
    assertEquals("$0", value.spent());
    assertEquals("$100 left", value.left());
    assertEquals("Only you · All models", value.scope());
    assertEquals("$100", UsageRows.cell(rows, claude, UsageRows.BUDGET));
    assertEquals("$0", UsageRows.cell(rows, claude, UsageRows.SPENT));
    assertEquals("$100 left", UsageRows.cell(rows, claude, UsageRows.LEFT));
    assertEquals("Only you · All models", UsageRows.scope(rows, claude));
  }

  @Test
  public void aModelOutsideTheWorkspaceBudgetGetsNoCellsAtAll() {
    ModelRow xiaomi = row("xiaomi", model("mimo", 0.14));
    Map<String, String> integrations = Map.of("xiaomi", "xiaomi");

    Map<String, UsageRows.Row> rows = UsageRows.build(limits(), List.of(xiaomi), integrations);

    assertTrue("the workspace cannot bill another provider's model", rows.isEmpty());
    assertEquals("", UsageRows.cell(rows, xiaomi, UsageRows.BUDGET));
    assertEquals("", UsageRows.cell(rows, xiaomi, UsageRows.SPENT));
    assertEquals("", UsageRows.cell(rows, xiaomi, UsageRows.LEFT));
    assertEquals("", UsageRows.scope(rows, xiaomi));
  }

  @Test
  public void aFreeModelGetsNoCells() {
    ModelRow free = row("opencode", model("flash-free", 0));

    Map<String, UsageRows.Row> rows =
        UsageRows.build(limits(), List.of(free), Map.of("opencode", "opencode"));

    assertTrue("free models stay usable whatever is spent", rows.isEmpty());
    assertEquals("", UsageRows.cell(rows, free, UsageRows.LEFT));
  }

  @Test
  public void noLimitsLoadedMeansNoRowsAndNoNumbers() {
    ModelRow claude = row("opencode", model("claude", 2));

    Map<String, UsageRows.Row> rows =
        UsageRows.build(null, List.of(claude), Map.of("opencode", "opencode"));

    assertTrue("absent data must degrade to an empty projection", rows.isEmpty());
    assertEquals("", UsageRows.cell(rows, claude, UsageRows.LEFT));
    assertNull(UsageRows.sortValue(rows, claude, UsageRows.LEFT));
  }

  @Test
  public void anUnknownProviderReadsAsNotConsoleServed() {
    ModelRow claude = row("opencode", model("claude", 2));

    Map<String, UsageRows.Row> rows = UsageRows.build(limits(), List.of(claude), Map.of());

    assertTrue("no integration data = no all-coverage budget (honest default)", rows.isEmpty());
  }

  @Test
  public void theKeyIsProviderSlashModelAndNullSafe() {
    ModelRow claude = row("opencode", model("claude", 2));

    assertEquals("opencode/claude", UsageRows.key(claude));
    assertEquals("", UsageRows.key(null));
    assertEquals("", UsageRows.key(new ModelRow("p", "p", null, false)));
  }

  @Test
  public void theSortValuesMirrorTheCells() {
    ModelRow claude = row("opencode", model("claude", 2));

    Map<String, UsageRows.Row> rows =
        UsageRows.build(limits(), List.of(claude), Map.of("opencode", "opencode"));

    assertEquals(Double.valueOf(100), UsageRows.sortValue(rows, claude, UsageRows.BUDGET));
    assertEquals(Double.valueOf(0), UsageRows.sortValue(rows, claude, UsageRows.SPENT));
    assertEquals(Double.valueOf(100), UsageRows.sortValue(rows, claude, UsageRows.LEFT));
  }
}
