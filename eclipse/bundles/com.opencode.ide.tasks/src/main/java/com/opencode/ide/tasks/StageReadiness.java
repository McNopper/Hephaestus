package com.opencode.ide.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure readiness evaluation for the linked stage pipeline (U-110) with a legacy fallback: decides,
 * per ticket of one snapshot, whether its stage may be dispatched now, must wait on unfinished
 * upstream work, or must re-run because a changed upstream invalidated already-produced results.
 *
 * <p>A pure function over one project's ticket list (e.g. {@code TaskStore.list(project, null,
 * null, null, null)}): no file I/O, no clock - every timestamp comes from the tickets.
 *
 * <p>U-110 MODEL: tickets are stationary in their stage; the pipeline is the {@code basis} link
 * (child &rarr; the upstream work product whose completion spawned it). A ticket with a {@code
 * basis} waits on exactly its basis ticket: WAIT_UPSTREAM until the basis is done/in-review AND
 * carries a recorded artifact (U-081 - a label proves nothing), STALE when the basis changed after
 * the ticket ran (the ASPICE consistency check), READY otherwise, NOT_APPLICABLE when done.
 *
 * <p>Owner model 2026-11-19: the epic field is REMOVED - readiness walks basis chains. A ticket
 * without a basis resolves through its own gated flow history ("advanced to X", "stage N passed:
 * ...") only; there is no second chain.
 *
 * <p>Precedence, first match wins (pinned by tests): NOT_APPLICABLE (no/invalid stage) &gt; BLOCKED
 * &gt; RUNNING (in-progress/in-review) &gt; WAIT_UPSTREAM &gt; STALE &gt; READY. BLOCKED outranks
 * RUNNING because a blocked ticket has no live work on it - a stale blocked claim must read as
 * failed, never as running. A ticket already {@code done} with unchanged inputs falls through to
 * NOT_APPLICABLE (READY would re-dispatch finished work).
 */
public final class StageReadiness {

  /** The dispatch verdict for one ticket. */
  public enum Kind {
    READY,
    WAIT_UPSTREAM,
    STALE,
    BLOCKED,
    RUNNING,
    NOT_APPLICABLE
  }

  /** Verdict plus a human-readable reason naming ids, stages and timestamps where useful. */
  public record Readiness(Kind kind, String reason) {}

  private StageReadiness() {}

  /**
   * Evaluates every ticket of the snapshot. The result is keyed by ticket id, in snapshot order;
   * null list elements and tickets without id are skipped, a null list yields an empty map.
   */
  public static Map<String, Readiness> evaluate(List<Task> tickets) {
    return evaluate(tickets, List.of());
  }

  /**
   * U-027: readiness over the LIVE tickets with the ARCHIVED pool as extra basis-chain evidence -
   * an archived upstream still satisfies its downstream (archiving never orphans the V chain).
   * Archived tickets are evidence only; they are never evaluated as rows.
   */
  public static Map<String, Readiness> evaluate(List<Task> live, List<Task> archived) {
    Map<String, Readiness> out = new LinkedHashMap<>();
    if (live == null) {
      return out;
    }
    List<Task> pool = new java.util.ArrayList<>(live);
    if (archived != null) {
      pool.addAll(archived);
    }
    for (Task t : live) {
      if (t != null && t.id != null) {
        out.put(t.id, of(t, pool));
      }
    }
    return out;
  }

  /**
   * The upstream ticket a STALE verdict for {@code t} blames: the basis ticket (U-110). Only
   * meaningful once {@link #evaluate} returned STALE for {@code t} (the verdict owns the precedence
   * chain and the staleness comparison); the store's invalidation recorder uses this to name the
   * upstream in the marker it appends.
   */
  /**
   * The ticket whose change caused {@code t}'s staleness - looked up over the LIVE tickets plus the
   * archive pool (U-059): an archived upstream is still the cause its live downstream must re-run
   * for, and the marker must name it.
   */
  static Task staleCause(Task t, List<Task> live, List<Task> archived) {
    List<Task> pool = live;
    if (archived != null && !archived.isEmpty()) {
      pool = new ArrayList<>(live);
      pool.addAll(archived);
    }
    if (t.basis != null) {
      return find(t.basis, pool);
    }
    return null;
  }

  /**
   * The upstream stage whose outputs feed {@code stage}: the previous ladder stage for definition
   * stages ({@code null} for requirements), the paired definition stage for verification stages
   * (LEGACY model - the basis-linked path reads {@code t.basis} directly).
   */
  public static String upstreamStage(String stage) {
    if (!VStages.isValid(stage)) {
      return null;
    }
    if (VStages.isVerification(stage)) {
      return stage.substring(stage.indexOf('-') + 1);
    }
    int i = VStages.STAGES.indexOf(stage);
    return i > 0 ? VStages.STAGES.get(i - 1) : null;
  }

  /**
   * The tickets that GOVERN this stage (U-079: top-down coverage): the basis chain (the derivation
   * ancestors, nearest first). Deduplicated by id; status is not filtered - an upstream still in
   * rework is context too.
   */
  public static List<Task> governingDefinitions(Task t, List<Task> tickets) {
    if (t == null || !VStages.isValid(t.stage) || tickets == null) {
      return List.of();
    }
    Map<String, Task> byId = new LinkedHashMap<>();
    if (t.basis != null) {
      String cursor = t.basis;
      while (cursor != null && !byId.containsKey(cursor)) {
        Task u = find(cursor, tickets);
        if (u == null) {
          break;
        }
        byId.putIfAbsent(u.id, u);
        cursor = u.basis;
      }
      return new java.util.ArrayList<>(byId.values());
    }
    // basis is the only chain now (epic removed 2026-11-19): a basis-less ticket
    // has no governing ancestors to inject.
    List<Task> out = new java.util.ArrayList<>(byId.values());
    out.sort(
        java.util.Comparator.comparingInt((Task u) -> VStages.STAGES.indexOf(u.stage))
            .thenComparing(u -> u.id));
    return out;
  }

  private static Readiness of(Task t, List<Task> tickets) {
    if (!VStages.isValid(t.stage)) {
      return new Readiness(Kind.NOT_APPLICABLE, "ticket has no V stage");
    }
    // BLOCKED outranks RUNNING (F-001): the fleet releases failed claims,
    // so a blocked ticket never has live work on it - and a blocked claim
    // that was never released must read as failed, not as running
    if (t.isBlocked()) {
      return new Readiness(
          Kind.BLOCKED, "ticket is blocked" + (t.blocker == null ? "" : ": " + t.blocker));
    }
    if ("in-progress".equals(t.status)) {
      return new Readiness(Kind.RUNNING, "stage '" + t.stage + "' work is in-progress");
    }
    if ("in-review".equals(t.status)) {
      return new Readiness(Kind.RUNNING, "stage '" + t.stage + "' work is in-review");
    }
    // U-110: the derivation edge - a ticket with a basis waits on exactly its basis ticket;
    // basis-less tickets fall through to their own flow history (epic removed 2026-11-19).
    if (t.basis != null) {
      return ofBasis(t, tickets);
    }
    String upstream = upstreamStage(t.stage);
    if (upstream == null) {
      return finishedOrBacklog(t, "stage 'requirements' has no upstream");
    }

    String flow = flowEvidence(t, upstream);
    if (flow == null) {
      if ("done".equals(t.status)) {
        return new Readiness(
            Kind.NOT_APPLICABLE,
            "stage '"
                + t.stage
                + "' finished (done); the ticket"
                + " entered the V pipeline here - no '"
                + upstream
                + "' ticket to wait on");
      }
      return new Readiness(
          Kind.WAIT_UPSTREAM,
          "no done/in-review ticket in upstream stage '" + upstream + "' (own flow history)");
    }
    // U-081: proof of upstream work is the artifact, not the label - a
    // done/in-review upstream with nothing recorded cannot satisfy its
    // downstream (backfilling the artifact is what unblocks it)

    // flow != null here (the null case returned WAIT_UPSTREAM above): a pass
    // event names itself as the evidence (FR-011), an advance keeps its
    // established wording
    if (!("advanced to " + nextStage(upstream)).equals(flow)) {
      return finishedOrBacklog(
          t, "upstream '" + upstream + "' satisfied by own pass history (" + flow + ")");
    }
    return finishedOrBacklog(
        t,
        "upstream '"
            + upstream
            + "' satisfied by own advance history"
            + " (advanced to "
            + nextStage(upstream)
            + ")");
  }

  /** The next ladder stage (LEGACY model helper), or {@code null} at the V tip. */
  private static String nextStage(String stage) {
    int i = VStages.STAGES.indexOf(stage);
    return i >= 0 && i + 1 < VStages.STAGES.size() ? VStages.STAGES.get(i + 1) : null;
  }

  private static Readiness finishedOrBacklog(Task t, String satisfiedReason) {
    if ("done".equals(t.status)) {
      return new Readiness(
          Kind.NOT_APPLICABLE, "stage '" + t.stage + "' finished (done) and inputs unchanged");
    }
    return new Readiness(Kind.READY, satisfiedReason);
  }

  /**
   * The U-110 basis verdict: the ticket waits on exactly its basis ticket - WAIT_UPSTREAM until the
   * basis is done/in-review AND carries a recorded artifact (U-081), STALE when the basis changed
   * after the ticket ran, READY otherwise, NOT_APPLICABLE when done.
   */
  private static Readiness ofBasis(Task t, List<Task> tickets) {
    Task basis = find(t.basis, tickets);
    if (basis == null) {
      return new Readiness(
          Kind.WAIT_UPSTREAM, "basis ticket " + t.basis + " not found in the snapshot");
    }
    if (!"done".equals(basis.status) && !"in-review".equals(basis.status)) {
      return new Readiness(
          Kind.WAIT_UPSTREAM,
          "basis " + basis.id + " (" + basis.stage + ", " + basis.status + ") is not done yet");
    }
    if (basis.artifacts == null || basis.artifacts.isEmpty()) {
      return new Readiness(
          Kind.WAIT_UPSTREAM,
          "basis "
              + basis.id
              + " claims done but has no artifact; record it with task_add_artifact on the"
              + " basis first (U-081)");
    }
    if (ranBefore(t) && isStrictlyAfter(basis.inputsEffectiveAt(), t.inputsEffectiveAt())) {
      return new Readiness(
          Kind.STALE,
          "basis "
              + basis.id
              + " (updated "
              + Task.formatTs(basis.inputsEffectiveAt())
              + ") changed after "
              + t.id
              + " (updated "
              + Task.formatTs(t.inputsEffectiveAt())
              + "); re-run needed");
    }
    return finishedOrBacklog(
        t, "upstream satisfied by basis " + basis.id + " (" + basis.status + ")");
  }

  /** The ticket with the given id in the snapshot pool, or {@code null}. */
  private static Task find(String id, List<Task> tickets) {
    for (Task u : tickets) {
      if (u != null && id.equals(u.id)) {
        return u;
      }
    }
    return null;
  }

  /**
   * The ticket's own flow-history evidence that it left {@code upstream} through a quality gate:
   * the {@code advanced to next(upstream)} event (the store only writes it from in-review/done) or
   * the U-029 pass event {@code stage <N> passed: <reason>} for that same ladder rung - a recorded
   * pass is a completed stage, so it satisfies the downstream exactly like an advance (U-058,
   * FR-011); {@code null} when the history proves nothing.
   *
   * @return the matching history action, or {@code null}
   */
  private static String flowEvidence(Task t, String upstream) {
    String gate = nextStage(upstream);
    if (gate == null || t.history == null) {
      return null;
    }
    String advance = "advanced to " + gate;
    String pass = "stage " + (VStages.STAGES.indexOf(upstream) + 1) + " passed:";
    for (Task.HistoryEvent e : t.history) {
      String action = e == null ? null : e.action();
      if (action == null) {
        continue;
      }
      if (advance.equals(action) || action.startsWith(pass)) {
        return action;
      }
    }
    return null;
  }

  /**
   * True when the ticket already ran at this stage instance: dispatched
   * (in-progress/in-review/done) or touched after creation (any history event beyond the initial
   * "created" - planning a sprint alone does not count as running).
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
