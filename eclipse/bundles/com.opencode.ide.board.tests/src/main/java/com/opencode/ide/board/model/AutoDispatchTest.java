package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.opencode.ide.tasks.StageReadiness.Kind;
import com.opencode.ide.tasks.StageReadiness.Readiness;
import com.opencode.ide.tasks.Task;

/**
 * Unit tests for the SWT-free {@link AutoDispatch} policy: validation, the
 * candidate kinds (READY always, STALE only when included), the launch order
 * (STALE before READY — rework before new work — ids in natural order within
 * both groups), the concurrency cap counting already-running jobs, the cost
 * budget boundary (reaching the budget exactly is still admitted; recorded
 * spend counts; unknown spend reads as 0; 0 means unlimited), the per-launch
 * estimate (placeholder default, calibrated swap, plan boundary math), the
 * {@link AutoDispatch#calibratedEstimate(CostOverview)} calibration rules,
 * and the pure-function properties (never throws, never launches
 * non-candidates, same inputs yield the same plan).
 */
public class AutoDispatchTest {

    @Test
    public void budgetReservesEstimatedCostOfInFlightJobs() {
        AutoDispatch policy = new AutoDispatch(4, 0.10, false, 0.05);
        assertTrue(policy.plan(List.of(ticket("T-3")), Map.of("T-3", READY),
                CostOverview.empty(), Set.of("T-1", "T-2")).launch().isEmpty());
    }

    private static final Readiness READY = new Readiness(Kind.READY, "upstream satisfied");
    private static final Readiness STALE = new Readiness(Kind.STALE, "upstream changed; re-run needed");
    private static final Readiness WAITING = new Readiness(Kind.WAIT_UPSTREAM, "no done/in-review ticket upstream");
    private static final Readiness BLOCKED = new Readiness(Kind.BLOCKED, "ticket is blocked");
    private static final Readiness RUNNING = new Readiness(Kind.RUNNING, "work is in-progress");
    private static final Readiness NOT_APPLICABLE = new Readiness(Kind.NOT_APPLICABLE, "no V stage");

    private static Task ticket(String id) {
        Task t = new Task();
        t.id = id;
        t.stage = "design";
        t.status = "sprint-backlog";
        t.createdAt = Instant.EPOCH;
        t.updatedAt = Instant.EPOCH;
        return t;
    }

    /** An overview whose project total is one actuals comment's cost. */
    private static CostOverview spent(String actualsComment) {
        Task t = ticket("T-cost");
        t.comments.add(new Task.Comment(Instant.EPOCH, "fleet", actualsComment));
        return CostOverview.of(List.of(t));
    }

    private static String reasonOf(AutoDispatch.DispatchPlan plan, String id) {
        return plan.skipped().stream()
                .filter(skip -> id.equals(skip.id()))
                .map(AutoDispatch.Skip::reason)
                .findFirst()
                .orElse("");
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    public void ofRejectsConcurrencyBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> AutoDispatch.of(0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> AutoDispatch.of(-1, 1, true));
        assertThrows("the canonical constructor validates too",
                IllegalArgumentException.class, () -> new AutoDispatch(0, 1, true));
    }

    @Test
    public void ofRejectsNegativeOrNaNBudget() {
        assertThrows(IllegalArgumentException.class, () -> AutoDispatch.of(1, -0.01, true));
        assertThrows("NaN is not a budget either",
                IllegalArgumentException.class, () -> AutoDispatch.of(1, Double.NaN, true));
    }

    @Test
    public void ofRejectsNegativeOrNaNEstimate() {
        assertThrows(IllegalArgumentException.class, () -> new AutoDispatch(4, 0, true, -0.01));
        assertThrows(IllegalArgumentException.class, () -> new AutoDispatch(4, 0, true, Double.NaN));
    }

    // ------------------------------------------------------------------
    // Concurrency cap
    // ------------------------------------------------------------------

    @Test
    public void capsByConcurrencyCountingAlreadyRunning() {
        AutoDispatch policy = AutoDispatch.of(3, 0, true);
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-2"), ticket("T-3"));
        Map<String, Readiness> readiness = Map.of(
                "T-1", READY, "T-2", READY, "T-3", READY);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, CostOverview.empty(), Set.of("T-9"));
        assertEquals("one of three slots already taken by a job outside the sprint",
                List.of("T-1", "T-2"), plan.launch());
        assertTrue(reasonOf(plan, "T-3"), reasonOf(plan, "T-3").contains("concurrency"));
    }

    @Test
    public void alreadyRunningTicketIsNeverLaunchedEvenWhenReady() {
        AutoDispatch policy = AutoDispatch.of(4, 0, true);
        AutoDispatch.DispatchPlan plan = policy.plan(
                List.of(ticket("T-1")), Map.of("T-1", READY), CostOverview.empty(), Set.of("T-1"));
        assertEquals(List.of(), plan.launch());
        assertTrue(reasonOf(plan, "T-1"), reasonOf(plan, "T-1").contains("already running"));
    }

    @Test
    public void oversubscribedRunningFloorsCapacityAtZero() {
        AutoDispatch policy = AutoDispatch.of(1, 0, true);
        AutoDispatch.DispatchPlan plan = policy.plan(
                List.of(ticket("T-1")), Map.of("T-1", READY), CostOverview.empty(), Set.of("T-8", "T-9"));
        assertEquals(List.of(), plan.launch());
        assertTrue(reasonOf(plan, "T-1"), reasonOf(plan, "T-1").contains("concurrency"));
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    public void staleRunsBeforeReady() {
        AutoDispatch policy = AutoDispatch.of(1, 0, true);
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-9"));
        Map<String, Readiness> readiness = Map.of("T-1", READY, "T-9", STALE);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, CostOverview.empty(), Set.of());
        assertEquals("rework precedes new work even against ticket order",
                List.of("T-9"), plan.launch());
        assertTrue(reasonOf(plan, "T-1"), reasonOf(plan, "T-1").contains("concurrency"));
    }

    @Test
    public void readyGroupSortsByTicketId() {
        AutoDispatch policy = AutoDispatch.of(3, 0, true);
        List<Task> tasks = List.of(ticket("T-3"), ticket("T-1"), ticket("T-2"));
        Map<String, Readiness> readiness = Map.of("T-1", READY, "T-2", READY, "T-3", READY);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, CostOverview.empty(), Set.of());
        assertEquals("input order must not leak into the plan",
                List.of("T-1", "T-2", "T-3"), plan.launch());
    }

    // ------------------------------------------------------------------
    // STALE inclusion
    // ------------------------------------------------------------------

    @Test
    public void includeStaleFalseExcludesStale() {
        AutoDispatch policy = AutoDispatch.of(4, 0, false);
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-9"));
        Map<String, Readiness> readiness = Map.of("T-1", READY, "T-9", STALE);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, CostOverview.empty(), Set.of());
        assertEquals(List.of("T-1"), plan.launch());
        assertTrue(reasonOf(plan, "T-9"), reasonOf(plan, "T-9").contains("includeStale"));
    }

    // ------------------------------------------------------------------
    // Cost budget
    // ------------------------------------------------------------------

    @Test
    public void budgetAdmitsUpToAndIncludingTheBoundary() {
        // $0.10 budget, nothing spent, $0.05 per launch: the second launch
        // lands exactly ON the budget and is admitted; the third would exceed
        AutoDispatch policy = AutoDispatch.of(10, 0.10, true);
        AutoDispatch.DispatchPlan plan = planThreeReady(policy);
        assertEquals(List.of("T-1", "T-2"), plan.launch());
        assertTrue(reasonOf(plan, "T-3"), reasonOf(plan, "T-3").contains("budget"));
    }

    @Test
    public void budgetCountsAlreadyRecordedSpend() {
        // $0.05 already recorded + $0.05 per launch against a $0.10 budget: one launch fits
        AutoDispatch policy = AutoDispatch.of(10, 0.10, true);
        CostOverview cost = spent("fleet actuals: cost 0.05 USD");
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-2"));
        Map<String, Readiness> readiness = Map.of("T-1", READY, "T-2", READY);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, cost, Set.of());
        assertEquals(List.of("T-1"), plan.launch());
        assertTrue(reasonOf(plan, "T-2"), reasonOf(plan, "T-2").contains("budget"));
    }

    @Test
    public void unknownSpendReadsAsZero() {
        // an actuals run without a cost segment contributes no spend
        AutoDispatch policy = AutoDispatch.of(10, 0.05, true);
        CostOverview cost = spent("fleet actuals: agent executor, model a/b");
        AutoDispatch.DispatchPlan plan = policy.plan(
                List.of(ticket("T-1")), Map.of("T-1", READY), cost, Set.of());
        assertEquals("the first (boundary) launch still fits",
                List.of("T-1"), plan.launch());
    }

    @Test
    public void budgetZeroMeansUnlimitedEvenWithRecordedSpend() {
        AutoDispatch policy = AutoDispatch.of(5, 0, true);
        CostOverview cost = spent("fleet actuals: cost 9.99 USD");
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-2"), ticket("T-3"), ticket("T-4"), ticket("T-5"));
        Map<String, Readiness> readiness = Map.of(
                "T-1", READY, "T-2", READY, "T-3", READY, "T-4", READY, "T-5", READY);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, cost, Set.of());
        assertEquals(List.of("T-1", "T-2", "T-3", "T-4", "T-5"), plan.launch());
    }

    // ------------------------------------------------------------------
    // Candidate kinds / degenerate inputs / purity
    // ------------------------------------------------------------------

    @Test
    public void nonCandidateKindsWaitWithTheirReasons() {
        AutoDispatch policy = AutoDispatch.of(4, 0, true);
        List<Task> tasks = List.of(
                ticket("T-1"), ticket("T-2"), ticket("T-3"), ticket("T-4"), ticket("T-5"));
        Map<String, Readiness> readiness = Map.of(
                "T-1", READY, "T-2", WAITING, "T-3", BLOCKED, "T-4", RUNNING, "T-5", NOT_APPLICABLE);
        AutoDispatch.DispatchPlan plan = policy.plan(tasks, readiness, CostOverview.empty(), Set.of());
        assertEquals(List.of("T-1"), plan.launch());
        assertEquals(4, plan.skipped().size());
        assertTrue(reasonOf(plan, "T-2"), reasonOf(plan, "T-2").contains("WAIT_UPSTREAM"));
        assertTrue(reasonOf(plan, "T-3"), reasonOf(plan, "T-3").contains("BLOCKED"));
        assertTrue(reasonOf(plan, "T-4"), reasonOf(plan, "T-4").contains("RUNNING"));
        assertTrue(reasonOf(plan, "T-5"), reasonOf(plan, "T-5").contains("NOT_APPLICABLE"));
        for (AutoDispatch.Skip skip : plan.skipped()) {
            assertTrue(skip.id(), skip.reason() != null && !skip.reason().isBlank());
        }
    }

    @Test
    public void noVerdictMeansNoLaunch() {
        AutoDispatch policy = AutoDispatch.of(4, 0, true);
        AutoDispatch.DispatchPlan plan = policy.plan(
                List.of(ticket("T-1")), Map.of(), CostOverview.empty(), Set.of());
        assertEquals(List.of(), plan.launch());
        assertTrue(reasonOf(plan, "T-1"), reasonOf(plan, "T-1").contains("no readiness verdict"));
    }

    @Test
    public void nullAndEmptyInputsYieldAnEmptyPlan() {
        AutoDispatch policy = AutoDispatch.of(1, 1, true);
        assertEquals(new AutoDispatch.DispatchPlan(List.of(), List.of()),
                policy.plan(null, null, null, null));
        assertEquals(new AutoDispatch.DispatchPlan(List.of(), List.of()),
                policy.plan(List.of(), Map.of(), CostOverview.empty(), Set.of()));
    }

    @Test
    public void degenerateEntriesAreIgnored() {
        AutoDispatch policy = AutoDispatch.of(4, 0, true);
        AutoDispatch.DispatchPlan plan = policy.plan(
                Arrays.asList(null, ticket(null), ticket("T-1")),
                Map.of("T-1", READY), CostOverview.empty(), Set.of());
        assertEquals(List.of("T-1"), plan.launch());
        assertEquals(List.of(), plan.skipped());
    }

    @Test
    public void duplicateIdLaunchesOnceAndIsFlagged() {
        AutoDispatch policy = AutoDispatch.of(4, 0, true);
        AutoDispatch.DispatchPlan plan = policy.plan(
                List.of(ticket("T-1"), ticket("T-1"), ticket("T-2")),
                Map.of("T-1", READY, "T-2", READY), CostOverview.empty(), Set.of());
        assertEquals(List.of("T-1", "T-2"), plan.launch());
        assertTrue(reasonOf(plan, "T-1"), reasonOf(plan, "T-1").contains("duplicate"));
    }

    @Test
    public void sameInputsYieldTheSamePlanTwice() {
        AutoDispatch policy = AutoDispatch.of(2, 0.10, true);
        List<Task> tasks = List.of(ticket("T-3"), ticket("T-1"), ticket("T-9"), ticket("T-2"));
        Map<String, Readiness> readiness = Map.of(
                "T-1", READY, "T-2", READY, "T-3", READY, "T-9", STALE);
        CostOverview cost = spent("fleet actuals: cost 0.05 USD");
        Set<String> running = Set.of("T-8");
        assertEquals(policy.plan(tasks, readiness, cost, running),
                policy.plan(tasks, readiness, cost, running));
    }

    // ------------------------------------------------------------------
    // Per-launch estimate (placeholder default, calibrated swap)
    // ------------------------------------------------------------------

    @Test
    public void estimateDefaultsToThePlaceholderConstant() {
        assertEquals(AutoDispatch.ESTIMATED_COST_USD, AutoDispatch.of(4, 0, true).estimateUsd(), 0);
        assertEquals("the convenience constructor carries the placeholder too",
                AutoDispatch.ESTIMATED_COST_USD, new AutoDispatch(4, 0, true).estimateUsd(), 0);
    }

    @Test
    public void withEstimateKeepsThePolicyAndReplacesTheEstimate() {
        AutoDispatch policy = AutoDispatch.of(3, 1, false).withEstimateUsd(0.02);
        assertEquals(3, policy.maxConcurrent());
        assertEquals(1, policy.costBudgetUsd(), 0);
        assertFalse(policy.includeStale());
        assertEquals(0.02, policy.estimateUsd(), 0);
    }

    @Test
    public void planBooksTheCustomEstimateAgainstTheBudget() {
        // $0.10 budget, $0.04 per launch: the first two land within (0.04,
        // 0.08), the third (0.12) would exceed — the boundary math must use
        // the calibrated estimate, not the placeholder
        AutoDispatch policy = AutoDispatch.of(10, 0.10, true).withEstimateUsd(0.04);
        AutoDispatch.DispatchPlan plan = planThreeReady(policy);
        assertEquals(List.of("T-1", "T-2"), plan.launch());
        assertTrue(reasonOf(plan, "T-3"), reasonOf(plan, "T-3").contains("$0.04"));
    }

    // ------------------------------------------------------------------
    // Calibrated estimate
    // ------------------------------------------------------------------

    /** Plans T-1..T-3 (all READY) with no spend recorded. */
    private static AutoDispatch.DispatchPlan planThreeReady(AutoDispatch policy) {
        List<Task> tasks = List.of(ticket("T-1"), ticket("T-2"), ticket("T-3"));
        Map<String, Readiness> readiness = Map.of("T-1", READY, "T-2", READY, "T-3", READY);
        return policy.plan(tasks, readiness, CostOverview.empty(), Set.of());
    }

    /** An overview of one recorded ticket per cost. */
    private static CostOverview overviewOfCosts(double... costs) {
        List<Task> tasks = new ArrayList<>();
        int i = 1;
        for (double cost : costs) {
            Task t = ticket("T-c" + i++);
            t.comments.add(new Task.Comment(Instant.EPOCH, "fleet",
                    "fleet actuals: cost " + cost + " USD"));
            tasks.add(t);
        }
        return CostOverview.of(tasks);
    }

    @Test
    public void calibratedEstimateMeansTheRecordedTicketCosts() {
        assertEquals(0.06, AutoDispatch.calibratedEstimate(overviewOfCosts(0.02, 0.04, 0.06, 0.12)), 1e-9);
    }

    @Test
    public void calibratedEstimateFallsBackBelowThreeSamples() {
        assertEquals("two samples are noise, not signal",
                AutoDispatch.ESTIMATED_COST_USD,
                AutoDispatch.calibratedEstimate(overviewOfCosts(0.9, 0.1)), 0);
        assertEquals("no samples at all",
                AutoDispatch.ESTIMATED_COST_USD,
                AutoDispatch.calibratedEstimate(CostOverview.empty()), 0);
    }

    @Test
    public void calibratedEstimateTrustsExactlyThreeSamples() {
        assertEquals(0.02, AutoDispatch.calibratedEstimate(overviewOfCosts(0.01, 0.02, 0.03)), 1e-9);
    }

    @Test
    public void calibratedEstimateToleratesZeroCostActuals() {
        assertEquals("recorded zeros are samples, not unknowns",
                0.01, AutoDispatch.calibratedEstimate(overviewOfCosts(0, 0, 0.03)), 1e-9);
    }

    @Test
    public void calibratedEstimateIgnoresUnknownCostTickets() {
        Task unknown = ticket("T-u");
        unknown.comments.add(new Task.Comment(Instant.EPOCH, "fleet",
                "fleet actuals: agent executor, model a/b"));
        Task known = ticket("T-k");
        known.comments.add(new Task.Comment(Instant.EPOCH, "fleet", "fleet actuals: cost 0.5 USD"));
        assertEquals("only tickets with a recorded cost are samples — two known stay below the minimum",
                AutoDispatch.ESTIMATED_COST_USD,
                AutoDispatch.calibratedEstimate(CostOverview.of(List.of(unknown, known))), 0);
    }

    @Test
    public void calibratedEstimatePreservesPrecisionForBudgetAdmission() {
        assertEquals("rounding down would under-reserve the admission budget",
                (0.01 + 0.01 + 0.01005) / 3,
                AutoDispatch.calibratedEstimate(overviewOfCosts(0.01, 0.01, 0.01005)), 1e-12);
    }

    @Test
    public void calibratedEstimateOfNullOverviewFallsBack() {
        assertEquals(AutoDispatch.ESTIMATED_COST_USD, AutoDispatch.calibratedEstimate(null), 0);
    }
}
