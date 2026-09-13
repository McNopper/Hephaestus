package com.opencode.ide.tasks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure readiness evaluation for the V-model dataflow (H6): decides, per
 * ticket of one snapshot, whether its stage may be dispatched now, must wait
 * on unfinished upstream work, or must re-run because a changed upstream
 * invalidated already-produced results.
 *
 * <p>A pure function over one project's ticket list (e.g.
 * {@code TaskStore.list(project, null, null, null, null)}): no file I/O, no
 * clock - every timestamp comes from the tickets. The store models <b>one
 * ticket flowing through the stages</b> ({@code advance}/{@code sendBack}
 * rewrite the {@code stage} field in place), so upstream evidence is either
 * the ticket's own gated advance history ("advanced to X" proves the ladder
 * stage before X finished, because advance requires in-review/done) or
 * another ticket of the epic chain (the epic parent {@code u.id == t.epic},
 * as the traceability pairing uses, or a sibling {@code u.epic == t.epic})
 * sitting in the upstream stage with status done/in-review.</p>
 *
 * <p>Upstream of a definition stage is the previous ladder stage; upstream of
 * a verification stage is its paired definition stage (test-design waits on
 * design, test-implementation on implementation, ...).</p>
 *
 * <p>Precedence, first match wins (pinned by tests):
 * NOT_APPLICABLE (no/invalid stage) &gt; BLOCKED &gt; RUNNING (in-progress/
 * in-review) &gt; WAIT_UPSTREAM &gt; STALE &gt; READY. BLOCKED outranks
 * RUNNING because the fleet releases failed claims back to sprint-backlog -
 * a blocked ticket has no live work on it, and a stale blocked claim must
 * read as failed, never as running. A ticket
 * that already ran to {@code done} with unchanged inputs falls through to
 * NOT_APPLICABLE: the fixed Kind set has no FINISHED, and READY would re-dispatch
 * finished work. A sent-back ticket reports BLOCKED (that is exactly how the
 * store records a send-back) and, once unblocked, is READY for rework via its
 * own advance history; epic-chain downstream tickets automatically go
 * WAIT_UPSTREAM because the sent-back upstream left done/in-review.</p>
 */
public final class StageReadiness {

    /** The dispatch verdict for one ticket. */
    public enum Kind {
        READY, WAIT_UPSTREAM, STALE, BLOCKED, RUNNING, NOT_APPLICABLE
    }

    /** Verdict plus a human-readable reason naming ids, stages and timestamps where useful. */
    public record Readiness(Kind kind, String reason) {
    }

    private StageReadiness() {
    }

    /**
     * Evaluates every ticket of the snapshot. The result is keyed by ticket
     * id, in snapshot order; null list elements and tickets without id are
     * skipped, a null list yields an empty map.
     */
    public static Map<String, Readiness> evaluate(List<Task> tickets) {
        Map<String, Readiness> out = new LinkedHashMap<>();
        if (tickets == null) {
            return out;
        }
        for (Task t : tickets) {
            if (t != null && t.id != null) {
                out.put(t.id, of(t, tickets));
            }
        }
        return out;
    }

    /**
     * The epic-chain upstream ticket a STALE verdict for {@code t} blames:
     * the newest done/in-review ticket in {@code t}'s upstream stage. Only
     * meaningful once {@link #evaluate} returned STALE for {@code t} (the
     * verdict owns the precedence chain and the staleness comparison); the
     * store's invalidation recorder uses this to name the upstream in the
     * marker it appends.
     */
    static Task staleCause(Task t, List<Task> tickets) {
        String upstream = upstreamStage(t.stage);
        return upstream == null ? null : satisfiedViaEpicChain(t, upstream, tickets);
    }

    /**
     * The upstream stage whose outputs feed {@code stage}: the previous
     * ladder stage for definition stages ({@code null} for requirements),
     * the paired definition stage for verification stages.
     */
    public static String upstreamStage(String stage) {
        if (!VStages.isValid(stage)) {
            return null;
        }
        if (VStages.isVerification(stage)) {
            return stage.substring(stage.indexOf('-') + 1);
        }
        return VStages.previous(stage);
    }

    private static Readiness of(Task t, List<Task> tickets) {
        if (!VStages.isValid(t.stage)) {
            return new Readiness(Kind.NOT_APPLICABLE, "ticket has no V stage");
        }
        // BLOCKED outranks RUNNING (F-001): the fleet releases failed claims,
        // so a blocked ticket never has live work on it - and a blocked claim
        // that was never released must read as failed, not as running
        if (t.blocked) {
            return new Readiness(Kind.BLOCKED,
                    "ticket is blocked" + (t.blocker == null ? "" : ": " + t.blocker));
        }
        if ("in-progress".equals(t.status)) {
            return new Readiness(Kind.RUNNING, "stage '" + t.stage + "' work is in-progress");
        }
        if ("in-review".equals(t.status)) {
            return new Readiness(Kind.RUNNING, "stage '" + t.stage + "' work is in-review");
        }
        String upstream = upstreamStage(t.stage);
        if (upstream == null) {
            return finishedOrBacklog(t, "stage 'requirements' has no upstream");
        }
        Task viaEpic = satisfiedViaEpicChain(t, upstream, tickets);
        if (viaEpic == null && !flowedFromUpstream(t, upstream)) {
            return new Readiness(Kind.WAIT_UPSTREAM, "no done/in-review ticket in upstream stage '"
                    + upstream + "' (epic chain or own advance history)");
        }
        if (viaEpic != null && ranBefore(t) && isStrictlyAfter(viaEpic.updatedAt, t.updatedAt)) {
            return new Readiness(Kind.STALE, "upstream " + viaEpic.id + " (" + upstream
                    + ", updated " + Task.formatTs(viaEpic.updatedAt) + ") changed after " + t.id
                    + " (updated " + Task.formatTs(t.updatedAt) + "); re-run needed");
        }
        if (viaEpic != null) {
            return finishedOrBacklog(t, "upstream '" + upstream + "' satisfied by " + viaEpic.id
                    + " (" + viaEpic.status + ")");
        }
        return finishedOrBacklog(t, "upstream '" + upstream + "' satisfied by own advance history"
                + " (advanced to " + VStages.next(upstream) + ")");
    }

    private static Readiness finishedOrBacklog(Task t, String satisfiedReason) {
        if ("done".equals(t.status)) {
            return new Readiness(Kind.NOT_APPLICABLE,
                    "stage '" + t.stage + "' finished (done) and inputs unchanged");
        }
        return new Readiness(Kind.READY, satisfiedReason);
    }

    /**
     * The newest done/in-review ticket in the upstream stage that is linked
     * to {@code t} through the epic chain (epic parent or sibling); tickets
     * without timestamps sort as oldest. Null when the chain satisfies
     * nothing - the ticket's own flow history may still satisfy the upstream.
     */
    private static Task satisfiedViaEpicChain(Task t, String upstream, List<Task> tickets) {
        Task best = null;
        for (Task u : tickets) {
            if (u == null || u.id == null || u.id.equals(t.id)) {
                continue;
            }
            if (!upstream.equals(u.stage) || !("done".equals(u.status) || "in-review".equals(u.status))) {
                continue;
            }
            if (!u.id.equals(t.epic) && (t.epic == null || !t.epic.equals(u.epic))) {
                continue;
            }
            if (best == null || (isStrictlyAfter(u.updatedAt, best.updatedAt))) {
                best = u;
            }
        }
        return best;
    }

    /**
     * True when {@code t}'s own history proves it left the upstream stage
     * through the quality gate: an "advanced to {@code next(upstream)}"
     * event, which the store only writes from in-review/done.
     */
    private static boolean flowedFromUpstream(Task t, String upstream) {
        String gate = VStages.next(upstream);
        if (gate == null) {
            return false;
        }
        for (Task.HistoryEvent e : t.history) {
            if (e != null && ("advanced to " + gate).equals(e.action())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the ticket already ran at this stage instance: dispatched
     * (in-progress/in-review/done) or touched after creation (any history
     * event beyond the initial "created" - planning a sprint alone does not
     * count as running).
     */
    private static boolean ranBefore(Task t) {
        if ("in-progress".equals(t.status) || "in-review".equals(t.status) || "done".equals(t.status)) {
            return true;
        }
        for (Task.HistoryEvent e : t.history) {
            if (e != null && !"created".equals(e.action())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStrictlyAfter(Instant a, Instant b) {
        return a != null && b != null && a.isAfter(b);
    }
}
