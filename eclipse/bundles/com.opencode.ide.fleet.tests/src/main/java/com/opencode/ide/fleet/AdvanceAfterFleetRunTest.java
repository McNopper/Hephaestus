package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * The fleet-to-pipeline hand-off, in-process: a staged ticket (requirements,
 * role pm) that completes and merges lands in-review - exactly the state
 * {@link TaskStore#advance} quality-gates on - so the advance succeeds and
 * the ticket re-enters the NEXT stage's product backlog (system, architect),
 * where the fleet can launch it again under the next stage's agent. A run
 * that failed (timeout, still in-progress) must NOT be advanceable.
 */
public class AdvanceAfterFleetRunTest {

    private static final Path REPO = Path.of("repo");
    private static final String PROJECT = "p";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private FakeClient client;
    private FakeWorktreeManager worktrees;
    private TaskFleet fleet;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        client = new FakeClient();
        worktrees = new FakeWorktreeManager();
        fleet = new TaskFleet(new FleetRunner(client, worktrees, () -> { }), store);
    }

    /** A requirements-stage ticket (role pm per VStages) in the product backlog. */
    private String requirementsTicket() {
        Task t = store.create(PROJECT, new TaskStore.CreateSpec(
                "Write the requirements", "Capture WHAT and WHY.", "story",
                VStages.roleOf("requirements"), "high", 3,
                List.of("ac one", "ac two"), List.of(), null, "V"), "requirements");
        return t.id;
    }

    private void sessionCompletes() {
        client.replyOnSend = "done";
        client.sessionType = "idle";
    }

    @Test
    public void finishedFleetRunAdvancesIntoTheNextStageBacklog() {
        String id = requirementsTicket();
        sessionCompletes();

        FleetJob job = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, job.state());
        Task afterRun = store.get(PROJECT, id);
        assertEquals("the fleet leaves a finished staged run in-review "
                + "(the advance quality gate), no manual update needed",
                "in-review", afterRun.status);
        assertEquals("requirements", afterRun.stage);
        assertEquals("pm", afterRun.role);

        Task advanced = store.advance(PROJECT, id, "pm");

        assertEquals("system", advanced.stage);
        assertEquals("architect", advanced.role);
        assertEquals("the next stage's backlog is fed by the previous stage",
                "product-backlog", advanced.status);
        assertNull("assignee cleared for the next claim", advanced.assignee);
        assertFalse(advanced.blocked);
        assertNotNull(advanced.history.stream()
                .filter(h -> "advanced to system".equals(h.action()))
                .findAny().orElse(null));
        // persisted through the file: a fresh read agrees
        Task reread = store.get(PROJECT, id);
        assertEquals("system", reread.stage);
        assertEquals("architect", reread.role);
        assertEquals("product-backlog", reread.status);
    }

    @Test
    public void advancedTicketRelaunchesUnderTheNextStageAgent() {
        String id = requirementsTicket();
        sessionCompletes();
        fleet.launch(PROJECT, id, REPO, TIMEOUT);
        store.advance(PROJECT, id, "pm");

        FleetJob second = fleet.launch(PROJECT, id, REPO, TIMEOUT);

        assertEquals(FleetJob.State.MERGED, second.state());
        assertEquals("two launches total", 2, client.sentRequests.size());
        assertEquals("system stage dispatches through architect to manifest-author",
                "manifest-author", client.sentRequests.get(1).agent());
        assertTrue(client.sentRequests.get(1).text().contains("Stage: system"));
        assertTrue(client.sentRequests.get(1).text()
                .contains("work in the software-system skill"));
        assertEquals("the second run ends in-review again",
                "in-review", store.get(PROJECT, id).status);
        assertEquals("system", store.get(PROJECT, id).stage);
    }

    @Test
    public void failedRunCannotAdvanceTheStage() {
        String id = requirementsTicket();
        // client stays busy: the session never completes, the run times out

        FleetJob job = fleet.launch(PROJECT, id, REPO, Duration.ofMillis(50));

        assertEquals(FleetJob.State.FAILED, job.state());
        Task after = store.get(PROJECT, id);
        assertTrue(after.blocked);
        assertEquals("F-001: a failed run is released to sprint-backlog, never a zombie claim",
                "sprint-backlog", after.status);

        // a RELEASED failed run can still not advance: advance requires
        // in-review/done - sprint-backlog+blocked is just as non-advanceable
        try {
            store.advance(PROJECT, id, "pm");
            fail("expected Invalid: an unfinished stage never advances");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("sprint-backlog"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("in-review"));
        }
        assertEquals("the rejected advance changes nothing",
                "requirements", store.get(PROJECT, id).stage);
    }
}
