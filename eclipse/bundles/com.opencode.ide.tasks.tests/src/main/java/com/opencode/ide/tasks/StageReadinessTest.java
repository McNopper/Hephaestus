package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The pure H6 readiness function over real store snapshots: the precedence
 * order (NOT_APPLICABLE &gt; RUNNING &gt; BLOCKED &gt; WAIT_UPSTREAM &gt;
 * STALE &gt; READY, done-with-fresh-inputs falling out as NOT_APPLICABLE),
 * the upstream mapping (previous ladder stage for definition stages, paired
 * definition stage for verification stages), the two upstream evidences the
 * store actually records (own gated advance history; epic-chain ticket in
 * the upstream stage), the staleness boundary (strictly-after timestamps),
 * and how send-backs surface (blocked while the hand-back is unresolved,
 * epic-chain downstreams waiting because the upstream left done/in-review).
 * Deterministic by construction: every timestamp is a pinned constant.
 */
public class StageReadinessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Instant T1 = Instant.parse("2026-08-23T10:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-08-23T11:00:00.000Z");
    private static final Instant T3 = Instant.parse("2026-08-23T12:00:00.000Z");

    private TaskStore store;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    }

    /** A hand-built ticket (pure fixture; no store, no clock). */
    private static Task ticket(String id, String stage, String status) {
        Task t = new Task();
        t.id = id;
        t.stage = stage;
        t.status = status;
        t.createdAt = T1;
        t.updatedAt = T1;
        return t;
    }

    private static void history(Task t, String action) {
        t.history.add(new Task.HistoryEvent(t.updatedAt, action, null));
    }

    /** Real-store walk: requirements -> ... -> target, each hop gated through in-review. */
    private String walkTo(String target) {
        Task t = store.create("p", TaskStore.CreateSpec.of("walk to " + target), "requirements");
        while (!target.equals(t.stage)) {
            store.update("p", t.id, Map.of("status", "in-review"));
            t = store.advance("p", t.id, "walker");
        }
        return t.id;
    }

    private Map<String, StageReadiness.Readiness> snapshot() {
        return StageReadiness.evaluate(store.list("p", null, null, null, null));
    }

    @Test
    public void requirementsIsReadyInAnEmptyStore() {
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(ticket("T-001", "requirements", "product-backlog")))
                .get("T-001");
        assertEquals(StageReadiness.Kind.READY, r.kind());
        assertTrue(r.reason(), r.reason().contains("no upstream"));
    }

    @Test
    public void everyKindIsReachableInOneSnapshot() {
        Task untracked = ticket("T-900", null, "product-backlog");
        Task running = ticket("T-901", "system", "in-progress");
        Task blocked = ticket("T-902", "architecture", "product-backlog");
        blocked.blocked = true;
        blocked.blocker = "waiting on legal";
        Task waiting = ticket("T-903", "system", "product-backlog");
        Task fresh = ticket("T-904", "requirements", "sprint-backlog");
        Task staleUpstream = ticket("T-905", "requirements", "done");
        staleUpstream.updatedAt = T3;
        Task stale = ticket("T-906", "system", "product-backlog");
        stale.epic = "T-905";
        stale.updatedAt = T2;
        history(stale, "claimed by dev");
        history(stale, "released by dev");

        Map<String, StageReadiness.Readiness> out = StageReadiness.evaluate(
                List.of(untracked, running, blocked, waiting, fresh, staleUpstream, stale));
        assertEquals(StageReadiness.Kind.NOT_APPLICABLE, out.get("T-900").kind());
        assertEquals(StageReadiness.Kind.RUNNING, out.get("T-901").kind());
        assertEquals(StageReadiness.Kind.BLOCKED, out.get("T-902").kind());
        assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, out.get("T-903").kind());
        assertEquals(StageReadiness.Kind.READY, out.get("T-904").kind());
        assertEquals("a done requirements ticket is finished, not dispatchable", StageReadiness.Kind.NOT_APPLICABLE, out.get("T-905").kind());
        assertEquals(StageReadiness.Kind.STALE, out.get("T-906").kind());
    }

    @Test
    public void precedenceBlockedOutranksRunning() {
        Task t = ticket("T-001", "system", "in-progress");
        t.blocked = true;
        t.blocker = "hit a wall mid-work";
        assertEquals("F-001: the fleet releases failed claims, so a blocked ticket has no live"
                        + " work - and a stale blocked claim must read as failed, never running",
                StageReadiness.Kind.BLOCKED, StageReadiness.evaluate(List.of(t)).get("T-001").kind());
    }

    @Test
    public void precedenceBlockedOutranksWaitUpstream() {
        Task t = ticket("T-001", "system", "product-backlog");
        t.blocked = true;
        t.blocker = "sent back from design: interface unclear";
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(t)).get("T-001");
        assertEquals(StageReadiness.Kind.BLOCKED, r.kind());
        assertTrue(r.reason(), r.reason().contains("sent back from design"));
    }

    @Test
    public void inReviewCountsAsRunning() {
        assertEquals(StageReadiness.Kind.RUNNING,
                StageReadiness.evaluate(List.of(ticket("T-001", "design", "in-review"))).get("T-001").kind());
    }

    @Test
    public void ticketWithoutOrWithInvalidStageIsNotApplicable() {
        assertEquals(StageReadiness.Kind.NOT_APPLICABLE,
                StageReadiness.evaluate(List.of(ticket("T-001", null, "product-backlog"))).get("T-001").kind());
        assertEquals("a hand-edited invalid stage string is not part of the pipeline either",
                StageReadiness.Kind.NOT_APPLICABLE,
                StageReadiness.evaluate(List.of(ticket("T-002", "waterfall", "product-backlog"))).get("T-002").kind());
    }

    @Test
    public void downstreamWaitsWhenNoUpstreamExists() {
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(ticket("T-001", "system", "product-backlog")))
                .get("T-001");
        assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, r.kind());
        assertTrue("the reason names the stage being waited on", r.reason().contains("requirements"));
    }

    @Test
    public void epicParentSatisfiesUpstream() {
        Task parent = ticket("T-001", "requirements", "done");
        Task child = ticket("T-002", "system", "product-backlog");
        child.epic = "T-001";
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(parent, child)).get("T-002");
        assertEquals(StageReadiness.Kind.READY, r.kind());
        assertTrue(r.reason(), r.reason().contains("T-001"));
    }

    @Test
    public void epicSiblingSatisfiesUpstream() {
        Task sibling = ticket("T-001", "requirements", "in-review");
        sibling.epic = "EPIC-1";
        Task t = ticket("T-002", "system", "product-backlog");
        t.epic = "EPIC-1";
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(sibling, t)).get("T-002");
        assertEquals(StageReadiness.Kind.READY, r.kind());
        assertTrue(r.reason(), r.reason().contains("T-001"));
    }

    @Test
    public void epicChainTicketInWrongStageDoesNotSatisfy() {
        Task parent = ticket("T-001", "implementation", "done");
        Task child = ticket("T-002", "system", "product-backlog");
        child.epic = "T-001";
        assertEquals("only a done/in-review ticket IN the upstream stage counts",
                StageReadiness.Kind.WAIT_UPSTREAM,
                StageReadiness.evaluate(List.of(parent, child)).get("T-002").kind());
    }

    @Test
    public void ownAdvanceHistorySatisfiesUpstream() {
        String id = walkTo("system");
        StageReadiness.Readiness r = snapshot().get(id);
        assertEquals("one ticket flows through the stages: 'advanced to system' proves requirements finished (the advance gate)",
                StageReadiness.Kind.READY, r.kind());
        assertTrue(r.reason(), r.reason().contains("advanced to system"));
    }

    @Test
    public void ownFlowIsNeverStaleByTimestamp() {
        String id = walkTo("test-design");
        assertEquals("self-evidence compares the ticket with itself; only an epic-chain upstream can be strictly newer",
                StageReadiness.Kind.READY, snapshot().get(id).kind());
    }

    @Test
    public void equalTimestampsAreNotStale() {
        Task parent = ticket("T-001", "architecture", "done");
        parent.updatedAt = T2;
        Task child = ticket("T-002", "design", "sprint-backlog");
        child.epic = "T-001";
        child.updatedAt = T2;
        history(child, "planned into S-01");
        assertEquals("changed-at exactly ran-at is the boundary: not strictly after, not stale",
                StageReadiness.Kind.READY,
                StageReadiness.evaluate(List.of(parent, child)).get("T-002").kind());
    }

    @Test
    public void newerUpstreamMarksStaleAndNamesBothTicketsAndTimestamps() {
        Task parent = ticket("T-001", "architecture", "done");
        parent.updatedAt = T3;
        Task child = ticket("T-002", "design", "done");
        child.epic = "T-001";
        child.updatedAt = T2;
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(parent, child)).get("T-002");
        assertEquals("a changed upstream invalidates already-produced downstream results",
                StageReadiness.Kind.STALE, r.kind());
        assertTrue(r.reason(), r.reason().contains("T-001"));
        assertTrue(r.reason(), r.reason().contains("T-002"));
        assertTrue(r.reason(), r.reason().contains(Task.formatTs(T3)));
        assertTrue(r.reason(), r.reason().contains(Task.formatTs(T2)));
    }

    @Test
    public void staleRequiresTheTicketToHaveRunBefore() {
        Task parent = ticket("T-001", "architecture", "done");
        parent.updatedAt = T3;
        Task child = ticket("T-002", "design", "product-backlog");
        child.epic = "T-001";
        child.updatedAt = T2;
        history(child, "created");
        assertEquals("a never-run ticket picks up the latest inputs when it runs; there is nothing to invalidate",
                StageReadiness.Kind.READY,
                StageReadiness.evaluate(List.of(parent, child)).get("T-002").kind());
    }

    @Test
    public void doneWithFreshInputsIsNotApplicable() {
        Task parent = ticket("T-001", "architecture", "done");
        parent.updatedAt = T2;
        Task child = ticket("T-002", "design", "done");
        child.epic = "T-001";
        child.updatedAt = T3;
        StageReadiness.Readiness r = StageReadiness.evaluate(List.of(parent, child)).get("T-002");
        assertEquals("the Kind set has no FINISHED; READY would re-dispatch finished work, so a done ticket with unchanged inputs drops out of the dispatch loop",
                StageReadiness.Kind.NOT_APPLICABLE, r.kind());
        assertTrue(r.reason(), r.reason().contains("done"));
    }

    @Test
    public void verificationStagesWaitOnTheirPairedDefinitionStage() {
        assertEquals("implementation", StageReadiness.upstreamStage("test-implementation"));
        assertEquals("design", StageReadiness.upstreamStage("test-design"));
        assertEquals("architecture", StageReadiness.upstreamStage("test-architecture"));
        assertEquals("system", StageReadiness.upstreamStage("test-system"));
        assertEquals("requirements", StageReadiness.upstreamStage("test-requirements"));
    }

    @Test
    public void definitionStagesWaitOnThePreviousLadderStage() {
        assertNull(StageReadiness.upstreamStage("requirements"));
        assertEquals("requirements", StageReadiness.upstreamStage("system"));
        assertEquals("system", StageReadiness.upstreamStage("architecture"));
        assertEquals("architecture", StageReadiness.upstreamStage("design"));
        assertEquals("design", StageReadiness.upstreamStage("implementation"));
    }

    @Test
    public void pairedUpstreamSatisfiesVerificationTicketViaEpicChain() {
        Task parent = ticket("T-001", "design", "done");
        Task tester = ticket("T-002", "test-design", "product-backlog");
        tester.epic = "T-001";
        assertEquals(StageReadiness.Kind.READY,
                StageReadiness.evaluate(List.of(parent, tester)).get("T-002").kind());

        parent.stage = "implementation";
        assertEquals("the implementation ticket alone does not satisfy test-design's paired upstream (design)",
                StageReadiness.Kind.WAIT_UPSTREAM,
                StageReadiness.evaluate(List.of(parent, tester)).get("T-002").kind());
    }

    @Test
    public void directlyCreatedVerificationTicketWaits() {
        Task t = store.create("p", TaskStore.CreateSpec.of("born at the right leg"), "test-implementation");
        assertEquals("no epic chain and no own advance history: the paired implementation upstream is unsatisfied",
                StageReadiness.Kind.WAIT_UPSTREAM, snapshot().get(t.id).kind());
    }

    @Test
    public void sendBackSurfacesAsBlockedUntilCleared() {
        String id = walkTo("design");
        store.update("p", id, Map.of("status", "in-review"));
        store.sendBack("p", id, "interface unclear", "architect");

        StageReadiness.Readiness r = snapshot().get(id);
        assertEquals("the store records a send-back as the blocked flag - that IS the wait signal",
                StageReadiness.Kind.BLOCKED, r.kind());
        assertTrue(r.reason(), r.reason().contains("sent back from design"));
    }

    @Test
    public void unblockedSendBackIsReadyForReworkViaOwnHistory() {
        String id = walkTo("design");
        store.update("p", id, Map.of("status", "in-review"));
        store.sendBack("p", id, "interface unclear", "architect");
        store.clearBlocked("p", id, "architect");

        StageReadiness.Readiness r = snapshot().get(id);
        assertEquals("after the hand-back is resolved the ticket is dispatchable rework, not a wait: its own 'advanced to architecture' history keeps the upstream satisfied",
                StageReadiness.Kind.READY, r.kind());
        assertTrue(r.reason(), r.reason().contains("advanced to architecture"));
    }

    @Test
    public void sendBackMakesEpicChainDownstreamWait() {
        String anchor = walkTo("architecture");
        store.update("p", anchor, Map.of("status", "done"));
        Task downstream = store.create("p", TaskStore.CreateSpec.of("downstream"), "design");
        store.update("p", downstream.id, Map.of("epic", anchor));
        assertEquals("the done architecture anchor satisfies design's upstream",
                StageReadiness.Kind.READY, snapshot().get(downstream.id).kind());

        store.sendBack("p", anchor, "goals conflict", "pm");
        Map<String, StageReadiness.Readiness> after = snapshot();
        assertEquals(StageReadiness.Kind.BLOCKED, after.get(anchor).kind());
        assertEquals("the anchor left done/in-review when sent back, so the downstream waits again",
                StageReadiness.Kind.WAIT_UPSTREAM, after.get(downstream.id).kind());
    }

    @Test
    public void nullListAndDegenerateEntriesAreSkipped() {
        assertTrue(StageReadiness.evaluate(null).isEmpty());
        Task idless = ticket(null, "requirements", "product-backlog");
        Task normal = ticket("T-001", "requirements", "product-backlog");
        Map<String, StageReadiness.Readiness> out = StageReadiness.evaluate(
                Arrays.asList(null, idless, normal));
        assertEquals(1, out.size());
        assertEquals(StageReadiness.Kind.READY, out.get("T-001").kind());
    }
}
