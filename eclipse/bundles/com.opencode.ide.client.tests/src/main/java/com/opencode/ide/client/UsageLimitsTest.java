package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.UsageLimits;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

/**
 * Unit test for the T-152 usage-limits WORDING and per-model projection - the port of the TUI's
 * {@code util/usage-limits.ts} (upstream source read 2026-11-17). Every expectation is a TUI string
 * ({@code $100 left}, {@code Limit reached}, {@code $0 used of $100}, {@code Only you · All
 * models}, {@code Resets Nov 30}), so the Providers view cannot drift from the {@code usage.show}
 * dialog it mirrors. Date cases are built from the machine's own zone, so they hold everywhere.
 */
public class UsageLimitsTest {

  private static final long RESET_MIDNIGHT =
      LocalDate.of(2026, 11, 30)
          .atTime(0, 0)
          .atZone(ZoneId.systemDefault())
          .toInstant()
          .toEpochMilli();

  private static UsageLimits.Budget budget(
      double limit, double spent, String scope, String per, String coverage, long resetsAt) {
    return new UsageLimits.Budget(
        "b1", limit, spent, scope, per, coverage, List.of(), List.of(), false, false, null,
        resetsAt);
  }

  private static UsageLimits info(UsageLimits.Budget... budgets) {
    return new UsageLimits(
        new UsageLimits.Workspace("wrk_1", "Default", "https://opencode.ai/console/wrk_1"),
        List.of(budgets));
  }

  // ---------- formatDollars ----------

  @Test
  public void wholeAmountsDropTheCents() {
    assertEquals("$100", UsageLimits.formatDollars(100));
    assertEquals("$0", UsageLimits.formatDollars(0));
  }

  @Test
  public void amountsWithCentsKeepTwoDecimals() {
    assertEquals("$9.96", UsageLimits.formatDollars(9.9627968));
    assertEquals("$100.50", UsageLimits.formatDollars(100.5));
  }

  @Test
  public void aSliverReadsAsLessThanACent() {
    assertEquals("<$0.01", UsageLimits.formatDollars(0.004));
    assertEquals("$0.01", UsageLimits.formatDollars(0.01));
  }

  // ---------- formatReset ----------

  @Test
  public void midnightResetsToTheBareDay() {
    assertEquals("Nov 30", UsageLimits.formatReset(RESET_MIDNIGHT));
  }

  @Test
  public void anIntradayResetCarriesDayAndTime() {
    long at =
        LocalDate.of(2026, 12, 25)
            .atTime(13, 45)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();

    assertEquals("Dec 25, 1:45 PM", UsageLimits.formatReset(at));
  }

  @Test
  public void aResetTodayReadsAsTimeToday() {
    long at =
        LocalDate.now().atTime(9, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    assertEquals("9:05 AM today", UsageLimits.formatReset(at));
  }

  // ---------- leftLabel (limitLabel) ----------

  @Test
  public void nothingShowsWhileThereIsPlentyLeft() {
    assertEquals("", UsageLimits.leftLabel(null));
  }

  @Test
  public void theLeftoverReadsAsLeftAndTheLimitAsReached() {
    UsageLimits.ModelUsage left = new UsageLimits.ModelUsage(100, false, RESET_MIDNIGHT, List.of());
    UsageLimits.ModelUsage reached = new UsageLimits.ModelUsage(0, true, RESET_MIDNIGHT, List.of());
    UsageLimits.ModelUsage exhausted =
        new UsageLimits.ModelUsage(0, false, RESET_MIDNIGHT, List.of());

    assertEquals("$100 left", UsageLimits.leftLabel(left));
    assertEquals("Limit reached", UsageLimits.leftLabel(reached));
    assertEquals("Limit reached", UsageLimits.leftLabel(exhausted));
  }

  // ---------- servedByConsole + modelUsage ----------

  @Test
  public void onlyConsoleServedProvidersCarryWorkspaceBudgets() {
    assertTrue(UsageLimits.servedByConsole("opencode", "opencode"));
    assertFalse(
        "OpenCode Go bills its own allowance",
        UsageLimits.servedByConsole("opencode-go", "opencode"));
    assertFalse(UsageLimits.servedByConsole("xiaomi", "xiaomi"));
    assertFalse(
        "a build that reports no integration applies nothing",
        UsageLimits.servedByConsole("opencode", null));
  }

  @Test
  public void anAllCoverageBudgetAppliesToConsoleServedPaidModels() {
    UsageLimits limits = info(budget(100, 0, "each", "set", "all", RESET_MIDNIGHT));

    UsageLimits.ModelUsage usage =
        UsageLimits.modelUsage(limits, "opencode", "claude", paid(), true);

    assertEquals(100.0, usage.left(), 0.000001);
    assertFalse(usage.reached());
    assertEquals(RESET_MIDNIGHT, usage.resetsAt());
    assertEquals("$100 left", UsageLimits.leftLabel(usage));
  }

  @Test
  public void anAllCoverageBudgetNeverTouchesAHostProvidedModel() {
    UsageLimits limits = info(budget(100, 0, "each", "set", "all", RESET_MIDNIGHT));

    assertNull(
        "the workspace cannot bill another provider's model",
        UsageLimits.modelUsage(limits, "xiaomi", "mimo", paid(), false));
  }

  @Test
  public void aFreeModelCarriesNoLimitWhateverIsSpent() {
    UsageLimits limits = info(budget(100, 0, "each", "set", "all", RESET_MIDNIGHT));
    List<Model.Cost> free = List.of(new Model.Cost(null, 0, 0, null));

    assertNull(UsageLimits.modelUsage(limits, "opencode", "flash-free", free, true));
  }

  @Test
  public void aModelScopedBudgetAppliesThroughItsOwnEntry() {
    UsageLimits.Budget scoped =
        new UsageLimits.Budget(
            "b2",
            40,
            30,
            "shared",
            "model",
            "set",
            List.of(new UsageLimits.BudgetModel("opencode", "claude", "Claude X", 12.5)),
            List.of(),
            false,
            true,
            null,
            RESET_MIDNIGHT);
    UsageLimits limits = info(scoped);

    UsageLimits.ModelUsage mine =
        UsageLimits.modelUsage(limits, "opencode", "claude", paid(), false);
    UsageLimits.ModelUsage other = UsageLimits.modelUsage(limits, "opencode", "gpt", paid(), false);

    assertEquals("per-model spend counts per model", 27.5, mine.left(), 0.000001);
    assertNull("a model the budget does not name stays out", other);
  }

  @Test
  public void theBindingLimitIsTheOneClosestToRunningOut() {
    UsageLimits limits =
        info(
            budget(100, 10, "each", "set", "all", RESET_MIDNIGHT),
            budget(20, 15, "each", "set", "all", RESET_MIDNIGHT + 1000));

    UsageLimits.ModelUsage usage =
        UsageLimits.modelUsage(limits, "opencode", "claude", paid(), true);

    assertEquals("the smaller leftover binds", 5.0, usage.left(), 0.000001);
    assertEquals("both budgets are carried", 2, usage.limits().size());
    assertEquals("the earliest reset wins", RESET_MIDNIGHT, usage.resetsAt());
  }

  @Test
  public void aSpentPastTheLimitReportsReached() {
    UsageLimits limits = info(budget(10, 12, "each", "set", "all", RESET_MIDNIGHT));

    UsageLimits.ModelUsage usage =
        UsageLimits.modelUsage(limits, "opencode", "claude", paid(), true);

    assertEquals(0.0, usage.left(), 0.000001);
    assertTrue(usage.reached());
    assertEquals("Limit reached", UsageLimits.leftLabel(usage));
  }

  @Test
  public void noBudgetsMeansNoUsage() {
    assertNull(UsageLimits.modelUsage(null, "opencode", "claude", paid(), true));
    assertNull(
        UsageLimits.modelUsage(
            new UsageLimits(new UsageLimits.Workspace("wrk_1", "Default", null), List.of()),
            "opencode",
            "claude",
            paid(),
            true));
  }

  private static List<Model.Cost> paid() {
    return List.of(new Model.Cost(null, 2, 10, null));
  }

  // ---------- scopeLabel (limitScope) ----------

  @Test
  public void aPooledBudgetNamesWhoSpendsAndWhichModels() {
    UsageLimits.Budget all = budget(100, 0, "each", "set", "all", RESET_MIDNIGHT);
    UsageLimits.Budget shared =
        new UsageLimits.Budget(
            "b3",
            100,
            0,
            "shared",
            "set",
            "all",
            List.of(),
            List.of(),
            false,
            true,
            null,
            RESET_MIDNIGHT);

    assertEquals("Only you · All models", UsageLimits.scopeLabel(all, "Default"));
    assertEquals("Everyone in Default · All models", UsageLimits.scopeLabel(shared, "Default"));
    assertEquals("Everyone in the workspace · All models", UsageLimits.scopeLabel(shared, null));
  }

  @Test
  public void aPerModelBudgetNamesOnlyTheSpender() {
    // per-model spend is never pooled: the models clause drops and the "who" stays
    UsageLimits.Budget perModel = budget(100, 0, "shared", "model", "all", RESET_MIDNIGHT);

    assertEquals("You and others", UsageLimits.scopeLabel(perModel, "Default"));
    assertEquals(
        "Only you",
        UsageLimits.scopeLabel(budget(100, 0, "each", "model", "all", RESET_MIDNIGHT), "Default"));
  }

  @Test
  public void aVendorScopedBudgetNamesTheVendorFamily() {
    UsageLimits.Budget vendors =
        new UsageLimits.Budget(
            "b4",
            100,
            0,
            "each",
            "set",
            "set",
            List.of(),
            List.of("anthropic"),
            false,
            false,
            null,
            RESET_MIDNIGHT);

    assertEquals("Only you · All anthropic models", UsageLimits.scopeLabel(vendors, "Default"));
  }

  // ---------- summary (the dialog's description line) ----------

  @Test
  public void nothingLoadedMeansNoNumbersAtAll() {
    assertNull(UsageLimits.summary(null));
  }

  @Test
  public void theSummaryCarriesWorkspaceBudgetSpentLeftAndReset() {
    UsageLimits limits = info(budget(100, 0, "each", "set", "all", RESET_MIDNIGHT));

    assertEquals(
        "Workspace: Default · $0 used of $100 · $100 left · Resets Nov 30",
        UsageLimits.summary(limits));
  }

  @Test
  public void aWorkspaceWithoutBudgetsSaysSoInTheTuiSentence() {
    UsageLimits empty =
        new UsageLimits(new UsageLimits.Workspace("wrk_1", "Default", null), List.of());

    assertEquals("Workspace: Default · " + UsageLimits.NO_LIMITS, UsageLimits.summary(empty));
  }
}
