package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for {@link StageJourney} (U-026): the movement-vocabulary
 * parse (advance / send-back / pass / report / clarification / review
 * doubt / direct stage writes), the distinct-stages-visited count behind
 * {@code <visited>/10} (FR-002, Q-002's lean: rework counts once), the
 * send-back shapes of FR-004, and the read-only projection contract
 * (FR-007 / AC-004: parsing never mutates the ticket).
 */
public class StageJourneyTest {

    private static final Instant TS = Instant.parse("2026-09-29T10:00:00.000Z");

    private static Task.HistoryEvent ev(String action) {
        return new Task.HistoryEvent(TS, action, "agent");
    }

    private static StageJourney journey(String stage, String role, String... actions) {
        List<Task.HistoryEvent> history = new ArrayList<>();
        for (String action : actions) {
            history.add(ev(action));
        }
        return StageJourney.of(stage, role, history);
    }

    @Test
    public void creationMarkerCountsTheEntryStageWithoutBeingAMovement() {
        StageJourney journey = journey("system", "architect",
                "created (stage: requirements - the V-chain entry)", "planned into S-01");

        assertEquals("the creation marker records the entry, not a transition",
                0, journey.movements().size());
        assertEquals("entry stage + current stage", "2/10", journey.progressLabel());
    }

    @Test
    public void advanceDerivesBothStages() {
        StageJourney journey = journey("system", "architect", "created", "advanced to system");

        assertEquals(1, journey.movements().size());
        StageJourney.Movement move = journey.movements().get(0);
        assertEquals(StageJourney.Kind.ADVANCE, move.kind());
        assertEquals("requirements", move.fromStage());
        assertEquals("system", move.toStage());
        assertEquals("advanced requirements → system", move.describe());
        assertEquals("2/10", journey.progressLabel());
        assertTrue(journey.moved());
    }

    @Test
    public void sendBackCarriesSourceDestinationAndReason() {
        StageJourney journey = journey("requirements", "pm",
                "created (stage: requirements - entry)",
                "advanced to system",
                "sent back to requirements: missing NFR");

        StageJourney.Movement back = journey.movements().get(1);
        assertEquals(StageJourney.Kind.SEND_BACK, back.kind());
        assertEquals("system", back.fromStage());
        assertEquals("requirements", back.toStage());
        assertEquals("missing NFR", back.reason());
        assertTrue("FR-004's exact shape", back.describe().contains("sent back from system: missing NFR"));
        assertTrue(back.describe().contains("back to requirements"));
        assertTrue(journey.hasSetback());
        assertEquals(back, journey.latestSetback());
        assertTrue(journey.moved());
    }

    @Test
    public void reworkCountsDistinctStagesOnce() {
        StageJourney journey = journey("architecture", "architect",
                "created (stage: requirements - entry)",
                "advanced to system",
                "advanced to architecture",
                "sent back to system: rework the system doc",
                "advanced to architecture");

        assertEquals("requirements, system, architecture — the repeat visit counts once (Q-002)",
                "3/10", journey.progressLabel());
        assertEquals("the trace keeps every transition", 4, journey.movements().size());
    }

    @Test
    public void passThroughCountsAsMovementAndVisitsBothStages() {
        StageJourney journey = journey("system", "architect",
                "created (stage: requirements - entry)",
                "stage 1 passed: no requirements impact");

        StageJourney.Movement pass = journey.movements().get(0);
        assertEquals(StageJourney.Kind.PASS, pass.kind());
        assertEquals("the passed stage is stage 1", "requirements", pass.fromStage());
        assertEquals("system", pass.toStage());
        assertEquals("no requirements impact", pass.reason());
        assertEquals("FR-008: the pass visits the passed stage AND the next",
                "2/10", journey.progressLabel());
        assertTrue(pass.describe().contains("passed requirements → system: no requirements impact"));
    }

    @Test
    public void reportDerivesThePairSource() {
        StageJourney journey = journey("design", "developer",
                "created",
                "reported to design: interface doubt");

        StageJourney.Movement report = journey.movements().get(0);
        assertEquals(StageJourney.Kind.REPORT, report.kind());
        assertEquals("test-design", report.fromStage());
        assertEquals("design", report.toStage());
        assertTrue(report.describe().startsWith("reported to design: interface doubt"));
        assertTrue(report.describe().contains("from test-design"));
        assertTrue(journey.hasSetback());
    }

    @Test
    public void clarificationAndReviewDoubtTraceWithoutMovingTheTicket() {
        StageJourney journey = journey("design", "developer",
                "created",
                "clarification to requirements (U-001): which format?",
                "review doubt retry (1/1) for stage design: unclear acceptance");

        assertEquals(2, journey.movements().size());
        assertEquals(StageJourney.Kind.CLARIFICATION, journey.movements().get(0).kind());
        assertEquals(StageJourney.Kind.REVIEW_DOUBT, journey.movements().get(1).kind());
        assertTrue(journey.movements().get(0).describe().contains("which format?"));
        assertFalse("questions do not move the ticket", journey.moved());
        assertTrue("the clarification marker is still a visible setback (FR-004)",
                journey.hasSetback());
    }

    @Test
    public void clarificationEscalationsAreClarificationMarkers() {
        StageJourney journey = journey("design", "developer",
                "clarification escalated to NEEDS-HUMAN: no agent route");

        assertEquals(StageJourney.Kind.CLARIFICATION, journey.movements().get(0).kind());
        assertFalse(journey.moved());
    }

    @Test
    public void directStageWritesAreSetMovementsWithoutFromOrTo() {
        StageJourney journey = journey("design", "developer",
                "created", "updated:stage", "updated:status,stage", "updated:status,assignee");

        assertEquals("every history line that changed the stage is a movement",
                2, journey.movements().size());
        assertEquals(StageJourney.Kind.SET, journey.movements().get(0).kind());
        assertEquals(StageJourney.Kind.SET, journey.movements().get(1).kind());
        assertTrue(journey.movements().get(0).describe().contains("stage set directly"));
        assertTrue("a direct write moved the ticket", journey.moved());
        assertEquals("the current stage counts once entered even when the write target is unknowable",
                "1/10", journey.progressLabel());
    }

    @Test
    public void currentStageCountsOnceEntered() {
        assertEquals("stored stage wins", "1/10", StageJourney.of("design", "tester", null).progressLabel());
        assertEquals("role fallback (legacy ticket)", "1/10",
                StageJourney.of(null, "developer", null).progressLabel());
        assertEquals("untracked role", "0/10", StageJourney.of(null, "research", null).progressLabel());
        assertEquals("no stage, no role", "0/10", StageJourney.of(null, null, null).progressLabel());
    }

    @Test
    public void foreignEventsAreNotMovements() {
        StageJourney journey = journey("design", "developer",
                "planned into S-05", "blocked:reason", "unblocked", "claimed by x",
                "released by ?", "artifact:doc:x.md", "todo_added:x", "paused: maintenance",
                "archived", "returned from S-05", "updated:status,assignee", "created");

        assertEquals(0, journey.movements().size());
        assertFalse(journey.moved());
        assertFalse(journey.hasSetback());
    }

    @Test
    public void corruptMarkersNeverBreakTheProjection() {
        StageJourney journey = journey(null, null,
                "advanced to nonsense", "sent back to nonsense: why", "reported to nonsense: why",
                "stage 99 passed: why", "stage x passed: why",
                "advanced to requirements", "sent back to test-requirements: why",
                "stage 10 passed: tip cannot pass");

        assertEquals(0, journey.movements().size());
        assertEquals("0/10", journey.progressLabel());
    }

    @Test
    public void latestSetbackPrefersTheLatestAndCoversReports() {
        StageJourney journey = journey("design", "developer",
                "sent back to design: first",
                "reported to design: second");

        assertEquals(StageJourney.Kind.REPORT, journey.latestSetback().kind());
    }

    @Test
    public void plainTextCarriesHeaderAndOneLinePerMovement() {
        StageJourney journey = journey("requirements", "pm",
                "created (stage: requirements - entry)",
                "advanced to system",
                "sent back to requirements: missing NFR");

        String text = journey.plainText();
        assertTrue(text.startsWith("Stage journey: 2/10 stages visited"));
        assertTrue(text.contains("[2026-09-29T10:00:00.000Z] agent · advanced requirements → system"));
        assertTrue(text.contains("⚠ sent back from system: missing NFR"));
        assertEquals(2, journey.lines().size());
    }

    @Test
    public void nullTaskYieldsTheEmptyJourney() {
        StageJourney journey = StageJourney.of((Task) null);

        assertEquals(0, journey.movements().size());
        assertEquals("0/10", journey.progressLabel());
        assertFalse(journey.hasSetback());
        assertFalse(journey.moved());
        assertTrue(journey.plainText().contains("(no movements yet)"));
    }

    @Test
    public void parsingNeverMutatesTheTicket() {
        Task task = new Task();
        task.id = "T-1";
        task.stage = "system";
        task.role = "architect";
        task.history.add(new Task.HistoryEvent(TS, "created (stage: requirements - entry)", null));
        task.history.add(new Task.HistoryEvent(TS, "advanced to system", "agent"));
        List<Task.HistoryEvent> before = List.copyOf(task.history);

        StageJourney journey = StageJourney.of(task);

        assertEquals("2/10", journey.progressLabel());
        assertEquals("AC-004: the store record is untouched", before, task.history);
        assertEquals("system", task.stage);
        assertEquals("architect", task.role);
    }
}
