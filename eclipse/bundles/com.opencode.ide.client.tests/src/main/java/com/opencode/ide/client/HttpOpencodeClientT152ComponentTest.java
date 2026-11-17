package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.UsageLimits;
import java.io.IOException;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Component test for the T-152 usage-limits verb ({@code GET /api/experimental/usage/limits}, gap
 * G4): the real {@link com.opencode.ide.client.internal.HttpOpencodeClient} over real HTTP against
 * the shared {@link StubHttpComponentTest} stub. The happy-path body is the shape PROBED live
 * against the local opencode 2.3.0 service on 2026-11-17 (recorded on T-152): workspace budgets
 * with USD {@code limit}/{@code spent}, selector fields and an epoch-millisecond {@code resetsAt}.
 *
 * <p>The refusal cases matter as much as the happy path: the route is experimental, so an older
 * build 404s and a tokenless client 401s. Both must THROW - the surface then says "unavailable"
 * instead of inventing zeros (T-152 acceptance criterion 2).
 */
public class HttpOpencodeClientT152ComponentTest extends StubHttpComponentTest {

  private static final String PROBE_BODY =
      """
      {"location":{"directory":"C:\\\\Users\\\\norbe"},
       "data":{"workspace":{"id":"wrk_01KXR2V70V80JS76QJFA3T84PY","name":"Default",
                "usageURL":"https://opencode.ai/console/wrk_01KXR2V70V80JS76QJFA3T84PY/activity/overview"},
        "budgets":[{"id":"rule_legacy_budget_62029","limit":100,"spent":0,"scope":"each",
                    "per":"set","coverage":"all","models":[],"vendors":[],"picked":false,
                    "shared":false,"resetsAt":1796083200000}]}}
      """;

  @BeforeClass
  public static void startStub() throws IOException {
    startStubServer(HttpOpencodeClientT152ComponentTest::respond);
  }

  private static String respond(String path) {
    return switch (path) {
      case "/api/experimental/usage/limits" -> PROBE_BODY;
      default -> "";
    };
  }

  /** The probed route answers the probed shape: workspace + budgets under the v2 envelope. */
  @Test
  public void getUsageLimitsReadsTheProbedShape() throws OpencodeException {
    UsageLimits limits = client.getUsageLimits();

    assertEquals("GET", lastMethod.get());
    assertEquals("/api/experimental/usage/limits", lastPath.get());
    assertEquals("wrk_01KXR2V70V80JS76QJFA3T84PY", limits.workspace().id());
    assertEquals("Default", limits.workspace().name());
    assertEquals(
        "https://opencode.ai/console/wrk_01KXR2V70V80JS76QJFA3T84PY/activity/overview",
        limits.workspace().usageUrl());

    assertEquals(1, limits.budgets().size());
    UsageLimits.Budget budget = limits.budgets().get(0);
    assertEquals("rule_legacy_budget_62029", budget.id());
    assertEquals(100.0, budget.limit(), 0.000001);
    assertEquals(0.0, budget.spent(), 0.000001);
    assertEquals("each", budget.scope());
    assertEquals("set", budget.per());
    assertEquals("all", budget.coverage());
    assertTrue(budget.models().isEmpty());
    assertTrue(budget.vendors().isEmpty());
    assertEquals(Boolean.FALSE, budget.picked());
    assertEquals(Boolean.FALSE, budget.shared());
    assertEquals(1796083200000L, budget.resetsAt());
  }

  /** A model-scoped budget carries its entries and the optional per-1M price floor. */
  @Test
  public void getUsageLimitsParsesModelScopedBudgets() throws OpencodeException {
    bodyOverride.set(
        """
        {"location":{},"data":{"workspace":{"id":"wrk_1","name":"Team"},
         "budgets":[{"id":"b1","limit":25,"spent":10.5,"scope":"shared","per":"model",
                     "coverage":"set","shared":true,
                     "models":[{"providerID":"opencode","modelID":"claude-x","name":"Claude X",
                                "spent":2.5}],
                     "vendors":["anthropic"],"inputPriceAtLeast":1,"resetsAt":1796083200000}]}}
        """);

    UsageLimits limits = client.getUsageLimits();

    UsageLimits.Budget budget = limits.budgets().get(0);
    assertEquals("shared", budget.scope());
    assertEquals("model", budget.per());
    assertEquals("set", budget.coverage());
    assertEquals(Boolean.TRUE, budget.shared());
    assertEquals(Double.valueOf(1), budget.inputPriceAtLeast());
    assertEquals(1, budget.models().size());
    UsageLimits.BudgetModel model = budget.models().get(0);
    assertEquals("opencode", model.providerId());
    assertEquals("claude-x", model.modelId());
    assertEquals("Claude X", model.name());
    assertEquals(Double.valueOf(2.5), model.spent());
    assertEquals("anthropic", budget.vendors().get(0));
  }

  /**
   * HTTP 404 (a build without the experimental route) - throws, so the view can say "unavailable".
   */
  @Test
  public void aMissingRouteThrowsInsteadOfReportingZeroBudgets() {
    statusOverride.set(404);
    bodyOverride.set("");

    OpencodeException failure =
        assertThrows(OpencodeException.class, () -> client.getUsageLimits());
    assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP 404"));
  }

  /** HTTP 401 (no token) - the probe's auth behavior: a refusal, never a fabricated number. */
  @Test
  public void anUnauthenticatedAnswerThrowsInsteadOfInventingNumbers() {
    statusOverride.set(401);
    bodyOverride.set("");

    OpencodeException failure =
        assertThrows(OpencodeException.class, () -> client.getUsageLimits());
    assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP 401"));
  }

  /** A wrong-shaped 200 must not render as a budget either - parsing fails loudly. */
  @Test
  public void aMalformedBodyThrows() {
    bodyOverride.set("not json at all");

    assertThrows(OpencodeException.class, () -> client.getUsageLimits());
  }
}
