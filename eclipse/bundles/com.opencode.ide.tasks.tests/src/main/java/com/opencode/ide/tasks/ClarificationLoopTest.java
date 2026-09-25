package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * U-023 clarification loop over the real store: a 'clarification:' hand-back
 * routes the question to the ORIGINATOR (upstream epic ticket, else the
 * epic's requirements ticket) instead of blocking for a human, the pair's
 * round-trips are capped, and only a missing originator or an exhausted
 * limit escalates to NEEDS-HUMAN. Plain defect send-backs keep blocking.
 */
public class ClarificationLoopTest extends StoreTestHarness {

    private Task ticket(String title, String epic, String stage) {
        Task t = store.create("p", new TaskStore.CreateSpec(
                title, "d", "task", "developer", "high", 3,
                List.of("it works"), List.of(), null, "T"), stage);
        if (epic != null) {
            t = store.update("p", t.id, java.util.Map.of("epic", epic));
        }
        return t;
    }

    @Test
    public void clarificationRoutesToTheUpstreamEpicTicket() {
        ticket("E-01", null, "requirements");
        Task originator = ticket("needs answers", "E-01", "architecture");
        Task requester = ticket("asks", "E-01", "design");

        Task after = store.sendBack("p", requester.id,
                "clarification: which interface wins?", "agent");

        assertFalse("a question never blocks for the human", after.blocked);
        String origin = store.get("p", originator.id).toJson().toString();
        assertTrue("the originator got the question: " + origin,
                origin.contains("clarification from design (" + requester.id + "): which interface wins?"));
        assertTrue("the requester's own trail records the route",
                after.toJson().toString().contains("clarification to architecture"));
    }

    @Test
    public void clarificationFallsBackToTheRequirementsTicket() {
        ticket("E-02", null, "requirements");
        Task originator = ticket("chain head is the fallback", "E-02", "requirements");
        Task requester = ticket("asks", "E-02", "design");

        store.clarify("p", requester.id, "what is the goal?", "agent");

        String origin = store.get("p", originator.id).toJson().toString();
        assertTrue("the chain head got the question: " + origin,
                origin.contains("clarification from design"));
    }

    @Test
    public void withoutAnOriginatorTheHumanDecides() {
        Task lone = ticket("asks", null, "design");

        Task after = store.clarify("p", lone.id, "what now?", "agent");

        assertTrue("no agent route: NEEDS-HUMAN", after.blocked);
        assertTrue(after.blocker.contains("NEEDS-HUMAN"));
    }

    @Test
    public void theRoundTripLimitEscalatesTheStuckPair() {
        ticket("E-03", null, "requirements");
        Task originator = ticket("answers", "E-03", "requirements");
        Task requester = ticket("asks", "E-03", "design");

        Task after = null;
        for (int i = 0; i < TaskStore.CLARIFICATION_LIMIT; i++) {
            after = store.clarify("p", requester.id, "question " + i, "agent");
        }
        assertFalse("three round-trips are still agent work", after.blocked);

        after = store.clarify("p", requester.id, "one too many", "agent");
        assertTrue("the stuck pair escalates", after.blocked);
        assertTrue(after.blocker.contains("NEEDS-HUMAN"));
        assertFalse("the originator is not blocked by the escalation",
                store.get("p", originator.id).blocked);
    }

    @Test
    public void plainDefectSendBacksStillBlock() {
        ticket("E-04", null, "requirements");
        ticket("upstream", "E-04", "requirements");
        Task requester = ticket("broken", "E-04", "design");

        Task after = store.sendBack("p", requester.id, "the design is wrong", "agent");

        assertTrue("a defect keeps today's semantics", after.blocked);
        assertTrue(after.blocker.startsWith("sent back from design: "));
    }

    @Test
    public void blankQuestionsAreRejected() {
        Task t = ticket("asks", null, "design");
        try {
            store.clarify("p", t.id, "  ", "agent");
            fail("a blank question must be rejected");
        } catch (RuntimeException e) {
            assertTrue(String.valueOf(e.getMessage()), e.getMessage().contains("question"));
        }
    }

    @Test
    public void theUpstreamStageOfDesignIsArchitecture() {
        assertEquals("architecture", StageReadiness.upstreamStage("design"));
    }
}
