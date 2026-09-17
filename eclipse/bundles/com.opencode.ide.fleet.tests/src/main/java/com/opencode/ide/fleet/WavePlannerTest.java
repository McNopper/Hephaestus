package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.fleet.dispatch.WavePlanner;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;

/**
 * U-022: unit tests for the pure wave-planning policy — the minimalistic
 * PM-agent logic the recurring loop applies when a wave drains: from the
 * prioritized product backlog take the top-priority READY-satisfiable
 * tickets, respecting the concurrency cap (wave size) and the cost budget
 * (admission stop; the hard stop lives in {@link com.opencode.ide.fleet.dispatch.RecurringWaves}).
 */
public class WavePlannerTest {

    private static StageReadiness.Readiness verdict(StageReadiness.Kind kind) {
        return new StageReadiness.Readiness(kind, kind.name());
    }

    private static Task task(String id, String priority, StageReadiness.Kind kind) {
        Task t = new Task();
        t.id = id;
        t.priority = priority;
        t.status = "product-backlog";
        t.stage = kind == StageReadiness.Kind.WAIT_UPSTREAM ? "design" : "requirements";
        t.createdAt = Instant.parse("2026-09-18T10:00:00Z");
        t.updatedAt = t.createdAt;
        return t;
    }

    private static String reasonOf(WavePlanner.PlannedWave wave, String id) {
        return wave.skipped().stream()
                .filter(s -> id.equals(s.id()))
                .map(AutoDispatch.Skip::reason)
                .findFirst().orElse(null);
    }

    @Test
    public void plansTopPriorityReadyTicketsInBacklogOrder() {
        WavePlanner planner = new WavePlanner(4, 0, false, 0.05);
        Task low = task("T-3", "low", StageReadiness.Kind.READY);
        Task high = task("T-1", "high", StageReadiness.Kind.READY);
        Task medium = task("T-2", "medium", StageReadiness.Kind.READY);
        Map<String, StageReadiness.Readiness> verdicts = Map.of(
                "T-1", verdict(StageReadiness.Kind.READY),
                "T-2", verdict(StageReadiness.Kind.READY),
                "T-3", verdict(StageReadiness.Kind.READY));

        WavePlanner.PlannedWave wave = planner.plan(List.of(low, high, medium),
                verdicts, CostOverview.empty(), Set.of());

        // input order is irrelevant — the store's backlog order governs
        assertEquals(List.of("T-1", "T-2", "T-3"), wave.ticketIds());
        assertFalse(wave.budgetExhausted());
    }

    @Test
    public void olderTicketsWinPriorityTiesThenId() {
        WavePlanner planner = new WavePlanner(4, 0, false, 0.05);
        Task later = task("T-1", "high", StageReadiness.Kind.READY);
        later.createdAt = Instant.parse("2026-09-18T12:00:00Z");
        Task earlier = task("T-9", "high", StageReadiness.Kind.READY);
        earlier.createdAt = Instant.parse("2026-09-18T11:00:00Z");
        Task sameInstant = task("T-2", "high", StageReadiness.Kind.READY);
        sameInstant.createdAt = Instant.parse("2026-09-18T11:00:00Z");
        Map<String, StageReadiness.Readiness> verdicts = Map.of(
                "T-1", verdict(StageReadiness.Kind.READY),
                "T-9", verdict(StageReadiness.Kind.READY),
                "T-2", verdict(StageReadiness.Kind.READY));

        WavePlanner.PlannedWave wave = planner.plan(List.of(later, earlier, sameInstant),
                verdicts, CostOverview.empty(), Set.of());

        assertEquals(List.of("T-2", "T-9", "T-1"), wave.ticketIds());
    }

    @Test
    public void onlyReadySatisfiableTicketsArePlanned() {
        WavePlanner planner = new WavePlanner(4, 0, false, 0.05);
        Task ready = task("T-1", "high", StageReadiness.Kind.READY);
        Task waiting = task("T-2", "high", StageReadiness.Kind.WAIT_UPSTREAM);
        Task blocked = task("T-3", "critical", StageReadiness.Kind.BLOCKED);
        Task stale = task("T-4", "critical", StageReadiness.Kind.STALE);
        Map<String, StageReadiness.Readiness> verdicts = Map.of(
                "T-1", verdict(StageReadiness.Kind.READY),
                "T-2", verdict(StageReadiness.Kind.WAIT_UPSTREAM),
                "T-3", verdict(StageReadiness.Kind.BLOCKED),
                "T-4", verdict(StageReadiness.Kind.STALE));

        WavePlanner.PlannedWave wave = planner.plan(List.of(ready, waiting, blocked, stale),
                verdicts, CostOverview.empty(), Set.of());

        assertEquals(List.of("T-1"), wave.ticketIds());
        assertTrue(reasonOf(wave, "T-3").contains("NEEDS-HUMAN"));
        assertTrue(reasonOf(wave, "T-2").contains("WAIT_UPSTREAM"));
        assertTrue(reasonOf(wave, "T-4").contains("includeStale is off"));
    }

    @Test
    public void staleTicketsJoinOnlyWhenIncluded() {
        WavePlanner withStale = new WavePlanner(4, 0, true, 0.05);
        Task stale = task("T-1", "high", StageReadiness.Kind.STALE);

        WavePlanner.PlannedWave wave = withStale.plan(List.of(stale),
                Map.of("T-1", verdict(StageReadiness.Kind.STALE)), CostOverview.empty(), Set.of());

        assertEquals(List.of("T-1"), wave.ticketIds());
    }

    @Test
    public void waveSizeIsCappedAtTheConcurrencyLimit() {
        WavePlanner planner = new WavePlanner(2, 0, false, 0.05);
        Task one = task("T-1", "critical", StageReadiness.Kind.READY);
        Task two = task("T-2", "high", StageReadiness.Kind.READY);
        Task three = task("T-3", "medium", StageReadiness.Kind.READY);
        List<Task> backlog = List.of(three, two, one);

        WavePlanner.PlannedWave wave = planner.plan(backlog,
                StageReadiness.evaluate(backlog), CostOverview.empty(), Set.of());

        assertEquals(List.of("T-1", "T-2"), wave.ticketIds());
        assertTrue(reasonOf(wave, "T-3").contains("capped"));
        assertFalse(wave.budgetExhausted());
    }

    @Test
    public void budgetStopsAdmissionAndFlagsExhaustion() {
        WavePlanner planner = new WavePlanner(4, 0.10, false, 0.05);
        Task one = task("T-1", "critical", StageReadiness.Kind.READY);
        Task two = task("T-2", "high", StageReadiness.Kind.READY);
        Task three = task("T-3", "medium", StageReadiness.Kind.READY);
        List<Task> backlog = List.of(one, two, three);

        WavePlanner.PlannedWave wave = planner.plan(backlog,
                StageReadiness.evaluate(backlog), CostOverview.empty(), Set.of());

        // 0 + 0.05 admits, 0.05 + 0.05 admits (lands exactly on budget), 0.10 + 0.05 exceeds
        assertEquals(List.of("T-1", "T-2"), wave.ticketIds());
        assertTrue(wave.budgetExhausted());
        assertTrue(reasonOf(wave, "T-3").contains("cost budget"));
    }

    @Test
    public void recordedSpendAndRunningJobsCountAgainstTheBudget() {
        WavePlanner planner = new WavePlanner(4, 0.13, false, 0.05);
        Task one = task("T-1", "high", StageReadiness.Kind.READY);
        Task two = task("T-2", "medium", StageReadiness.Kind.READY);
        Task running = task("T-9", "low", StageReadiness.Kind.READY);
        List<Task> backlog = List.of(running, one, two);

        // recorded spend 0.03 + one running launch (0.05) = 0.08 leaves room
        // for exactly one 0.05 admission (0.13) — the second would exceed
        Task spent = task("T-0", "low", StageReadiness.Kind.NOT_APPLICABLE);
        spent.comments.add(new Task.Comment(Instant.now(), "fleet",
                "fleet actuals: cost 0.03 USD, tokens 100 (in 50 / out 25 / reasoning 25), agent a, model m"));
        CostOverview cost = CostOverview.of(List.of(spent));

        WavePlanner.PlannedWave wave = planner.plan(backlog,
                StageReadiness.evaluate(backlog), cost, Set.of("T-9"));

        assertEquals(List.of("T-1"), wave.ticketIds());
        assertTrue(wave.budgetExhausted());
        assertTrue(reasonOf(wave, "T-9").contains("already running"));
    }

    @Test
    public void nullsDegenerateInputAndDuplicatesAreTolerated() {
        WavePlanner planner = new WavePlanner(2, 0, false, 0.05);

        assertEquals(List.of(), planner.plan(null, null, null, null).ticketIds());
        assertEquals(List.of(), planner.plan(java.util.Arrays.asList(null, new Task()), Map.of(),
                CostOverview.empty(), null).ticketIds());

        Task dup = task("T-1", "high", StageReadiness.Kind.READY);
        WavePlanner.PlannedWave wave = planner.plan(java.util.Arrays.asList(dup, dup),
                StageReadiness.evaluate(List.of(dup)), CostOverview.empty(), Set.of());
        assertEquals(List.of("T-1"), wave.ticketIds());
        assertTrue(reasonOf(wave, "T-1").contains("duplicate"));
    }

    @Test
    public void invalidPolicyValuesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new WavePlanner(0, 1, true, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new WavePlanner(1, -0.01, true, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new WavePlanner(1, Double.NaN, true, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new WavePlanner(1, 1, true, -0.01));
    }
}
