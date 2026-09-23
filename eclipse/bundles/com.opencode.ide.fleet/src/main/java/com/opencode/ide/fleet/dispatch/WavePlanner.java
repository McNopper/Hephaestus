package com.opencode.ide.fleet.dispatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.opencode.ide.tasks.StageReadiness.Kind;
import com.opencode.ide.tasks.StageReadiness.Readiness;
import com.opencode.ide.tasks.Task;

/**
 * The U-022 wave-to-wave planning policy — the minimalistic PM-agent logic
 * the recurring loop uses when the active wave drains: from the prioritized
 * product backlog take the top-priority READY-satisfiable tickets (STALE
 * re-runs only when {@code includeStale}), respecting the concurrency cap
 * (a wave is planned at most {@code maxConcurrent} tickets — it drains in
 * minutes and the next planning round re-evaluates with fresh readiness)
 * and the cost budget (admission stops once the next launch's estimate
 * would EXCEED {@code costBudgetUsd}; 0 means unlimited). A pure value
 * object over its inputs: no I/O, no clock, never throws.
 *
 * <p>Budget basis mirrors {@link AutoDispatch}: the handed-in overview's
 * project grand total plus one estimate per already-running launch; the
 * per-launch estimate is the caller's (typically
 * {@link AutoDispatch#calibratedEstimate}). Priority order is the store's
 * backlog order — priority desc, then created asc, then id — re-derived
 * here defensively so a caller's ordering mistake cannot reorder a wave.</p>
 */
public record WavePlanner(int maxConcurrent, double costBudgetUsd, boolean includeStale,
        double estimateUsd) {

    public WavePlanner {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1: " + maxConcurrent);
        }
        if (!Double.isFinite(costBudgetUsd) || costBudgetUsd < 0) {
            throw new IllegalArgumentException(
                    "costBudgetUsd must be >= 0 (0 = unlimited): " + costBudgetUsd);
        }
        if (!Double.isFinite(estimateUsd) || estimateUsd < 0) {
            throw new IllegalArgumentException("estimateUsd must be >= 0: " + estimateUsd);
        }
    }

    /** The policy values of {@code policy}, keeping its calibrated estimate. */
    public static WavePlanner of(AutoDispatch policy) {
        return new WavePlanner(policy.maxConcurrent(), policy.costBudgetUsd(),
                policy.includeStale(), policy.estimateUsd());
    }

    /**
     * Plans the next wave, never throws.
     *
     * @param backlog   the prioritized product backlog (re-sorted defensively;
     *                  null elements and tickets without id are skipped)
     * @param readiness the verdicts to gate on, evaluated over the whole
     *                  project; ids without a verdict are never planned
     * @param cost      the actuals overview whose grand total the budget
     *                  counts; {@code null} reads as no recorded spend
     * @param running   ids with a live fleet job — never planned into the
     *                  wave, and counted against the budget
     */
    public PlannedWave plan(List<Task> backlog, Map<String, Readiness> readiness,
            CostOverview cost, Set<String> running) {
        Map<String, Readiness> verdicts = readiness == null ? Map.of() : readiness;
        Set<String> inFlight = running == null ? Set.of() : running;
        double spend = spentSoFar(cost) + inFlight.size() * estimateUsd;

        List<Task> candidates = new ArrayList<>();
        if (backlog != null) {
            Set<String> seen = new HashSet<>();
            for (Task t : backlog) {
                if (t == null || t.id == null || !seen.add(t.id)) {
                    continue;
                }
                Readiness r = verdicts.get(t.id);
                if (r == null || inFlight.contains(t.id)) {
                    continue;
                }
                if (r.kind() == Kind.READY || (includeStale && r.kind() == Kind.STALE)) {
                    candidates.add(t);
                }
            }
        }
        candidates.sort(prioritized());

        Set<String> planned = new LinkedHashSet<>();
        Map<String, String> withheld = new LinkedHashMap<>();
        boolean budgetExhausted = false;
        for (Task t : candidates) {
            if (planned.size() >= maxConcurrent) {
                withheld.put(t.id, "wave size capped at concurrency " + maxConcurrent
                        + " — drains, then the next wave is planned");
            } else if (costBudgetUsd > 0 && spend + estimateUsd > costBudgetUsd) {
                budgetExhausted = true;
                withheld.put(t.id, "cost budget " + CostOverview.usd(costBudgetUsd) + " reached (spent "
                        + CostOverview.usd(spend) + ", estimated " + CostOverview.usd(estimateUsd)
                        + " per launch)");
            } else {
                planned.add(t.id);
                spend += estimateUsd;
            }
        }

        List<AutoDispatch.Skip> skipped = new ArrayList<>();
        if (backlog != null) {
            Set<String> processed = new HashSet<>();
            for (Task t : backlog) {
                if (t == null || t.id == null) {
                    continue;
                }
                if (!processed.add(t.id)) {
                    skipped.add(new AutoDispatch.Skip(t.id, "duplicate id in the input"));
                } else if (!planned.contains(t.id)) {
                    skipped.add(new AutoDispatch.Skip(t.id, waitingReason(t, verdicts, inFlight, withheld)));
                }
            }
        }
        return new PlannedWave(List.copyOf(planned), List.copyOf(skipped), budgetExhausted);
    }

    /** The next wave: the planned ticket ids in launch-worthy priority order, plus everyone else with why. */
    public record PlannedWave(List<String> ticketIds, List<AutoDispatch.Skip> skipped,
            boolean budgetExhausted) {

        public PlannedWave {
            ticketIds = List.copyOf(ticketIds);
            skipped = List.copyOf(skipped);
        }
    }

    private String waitingReason(Task t, Map<String, Readiness> verdicts, Set<String> running,
            Map<String, String> withheld) {
        if (running.contains(t.id)) {
            return "already running (a live fleet job owns the ticket)";
        }
        String withheldReason = withheld.get(t.id);
        if (withheldReason != null) {
            return withheldReason;
        }
        Readiness r = verdicts.get(t.id);
        if (r == null) {
            return "no readiness verdict";
        }
        if (r.kind() == Kind.STALE && !includeStale) {
            return "STALE (re-run needed) excluded: includeStale is off";
        }
        if (r.kind() == Kind.BLOCKED) {
            return "blocked — NEEDS-HUMAN: " + r.reason();
        }
        return r.kind() + ": " + r.reason();
    }

    /** The store's backlog order: priority desc, then created asc, then id. */
    private static Comparator<Task> prioritized() {
        return Comparator
                .comparing((Task t) -> -Task.PRIORITY_ORDER.getOrDefault(t.priority, 0))
                .thenComparing(t -> t.createdAt == null ? java.time.Instant.EPOCH : t.createdAt)
                .thenComparing(t -> t.id);
    }

    /** The overview's grand total; unknown ({@code null}) spend reads as 0. */
    private static double spentSoFar(CostOverview cost) {
        if (cost == null || cost.project() == null || cost.project().costUsd() == null) {
            return 0;
        }
        return cost.project().costUsd();
    }
}
