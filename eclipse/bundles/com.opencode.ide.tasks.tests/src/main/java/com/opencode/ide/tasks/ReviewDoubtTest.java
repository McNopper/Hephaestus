package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * B-007 FR-009..FR-012 (AC-003, AC-004): reviewer doubt round-trips to the
 * originator - the ticket returns to its OWN stage's backlog for exactly one
 * retry per stage visit, recorded as an attributable comment plus a history
 * marker; a recursing doubt inside the visit escalates to blocked
 * (blocked = needs-a-human, reached only after the originator had its
 * attempt). A stage transition resets the budget (Q-003/C-004). Also pins
 * FR-006's store-side evidence window.
 */
public class ReviewDoubtTest {

    private static final String PROJECT = "p";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    }

    private Task staged(String stage) {
        return store.create(PROJECT,
                new TaskStore.CreateSpec("Stage work", "Do the thing.", "task",
                        VStages.roleOf(stage), "high", 3,
                        List.of("ac one"), List.of(), null, "D"),
                stage);
    }

    @Test
    public void aFirstDoubtRoutesToTheStageBacklogForOneRetry() {
        Task t = staged("system");

        Task after = store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");

        assertFalse("doubt is never the first-outcome block (FR-011)", after.blocked);
        assertEquals("the stage keeps the originator (FR-009: the stage's own backlog)",
                "system", after.stage);
        assertEquals("product-backlog", after.status);
        assertNull(after.assignee);
        assertTrue(after.history.stream().anyMatch(h -> h.action()
                .equals("review doubt retry (1/1) for stage system: cannot determine X")
                && "reviewer".equals(h.by())));
        assertTrue("FR-012: attributable comment records the doubt + retry",
                after.comments.stream()
                        .anyMatch(c -> "reviewer".equals(c.by()) && c.text().contains("review doubt retry (1/1)")));
    }

    @Test
    public void aRecurringDoubtBlocksOnlyAfterTheRetryIsConsumed() {
        Task t = staged("system");
        store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");

        Task after = store.routeReviewDoubt(PROJECT, t.id, "still cannot determine X", "reviewer");

        assertTrue("FR-010: blocked is the needs-a-human signal after the retry", after.blocked);
        assertTrue(after.blocker, after.blocker
                .startsWith("review doubt unresolved after " + TaskStore.REVIEW_DOUBT_RETRY_LIMIT
                        + " originator retries: still cannot determine X"));
        assertTrue(after.history.stream()
                .anyMatch(h -> h.action().startsWith("review doubt escalated to NEEDS-HUMAN")));
    }

    @Test
    public void aStageTransitionResetsTheDoubtBudget() {
        Task t = staged("system");
        store.routeReviewDoubt(PROJECT, t.id, "cannot determine X", "reviewer");
        store.update(PROJECT, t.id, java.util.Map.of("status", "in-review"));
        store.advance(PROJECT, t.id, "reviewer");

        Task after = store.routeReviewDoubt(PROJECT, t.id, "doubt at the next stage", "reviewer");

        assertFalse("a new stage visit gets a fresh retry budget (C-004)", after.blocked);
        assertEquals("architecture", after.stage);
        assertTrue(after.history.stream()
                .anyMatch(h -> h.action().equals("review doubt retry (1/1) for stage architecture: doubt at the next stage")));
    }

    @Test
    public void anUnstagedTicketCannotHostADoubtRetry() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec("Unstaged work", "d", "task",
                "developer", "high", 2, List.of("ac"), List.of(), null, "D"));
        try {
            store.routeReviewDoubt(PROJECT, t.id, "doubt", "reviewer");
            throw new AssertionError("expected Invalid for an unstaged ticket");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("no stage"));
        }
    }

    @Test
    public void blankDoubtReasonsAreRejected() {
        Task t = staged("design");
        try {
            store.routeReviewDoubt(PROJECT, t.id, "  ", "reviewer");
            throw new AssertionError("expected Invalid for a blank reason");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("reason"));
        }
    }

    /** FR-006: the store-side evidence window sees run writes, never bookkeeping before it. */
    @Test
    public void bookkeepingBeforeTheWindowIsNotEvidence() {
        Task t = staged("requirements");

        assertFalse("bookkeeping before the window is not evidence",
                store.hasStoreSideWrites(PROJECT, t.id, Instant.now()));
    }

    @Test
    public void storeSideWritesAreDetectedInsideTheRunWindow() {
        Task t = staged("requirements");
        Instant since = Instant.now().minusMillis(2);

        store.addComment(PROJECT, t.id, "requirements doc written via task_add_artifact", "executor");

        assertTrue("a run-window store write IS stage evidence (FR-006)",
                store.hasStoreSideWrites(PROJECT, t.id, since));
    }
}
