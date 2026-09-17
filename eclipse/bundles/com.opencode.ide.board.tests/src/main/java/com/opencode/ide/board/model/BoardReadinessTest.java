package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.TaskStore;

/**
 * U-018: the board snapshot carries the per-ticket dispatch-readiness
 * verdicts - the sprint's tickets evaluated through {@link StageReadiness}
 * (computed over the UNFILTERED task set, so epic chains and upstream
 * stages resolve), with ready/stale counts for the fleet-row badge.
 */
public class BoardReadinessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private Path root;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        store = new TaskStore(root);
    }

    private String ticket(String title, String stage, String priority) {
        TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                title, "", "task", "developer", priority, 0, List.of(), List.of(), null, "T");
        var t = store.create("p", spec);
        Map<String, Object> changes = new HashMap<>();
        changes.put("stage", stage);
        store.update("p", t.id, changes);
        return t.id;
    }

    private void plan(String id) {
        store.planSprint("p", "S-01", List.of(id), "goal");
    }

    @Test
    public void requirementsTicketsWithoutUpstreamAreReady() {
        String solo = ticket("first work", "requirements", "high");
        plan(solo);

        BoardSnapshot snapshot = modelRefresh();

        assertEquals(StageReadiness.Kind.READY, snapshot.readinessOf(solo).kind());
        assertEquals(1, snapshot.readyCount());
        assertEquals(0, snapshot.staleCount());
    }

    @Test
    public void verificationStageWithoutUpstreamWorkWaits() {
        String stuck = ticket("test nothing", "test-design", "medium");
        plan(stuck);

        BoardSnapshot snapshot = modelRefresh();

        assertEquals(StageReadiness.Kind.WAIT_UPSTREAM, snapshot.readinessOf(stuck).kind());
        assertTrue(snapshot.readinessOf(stuck).reason().contains("upstream"));
        assertEquals(0, snapshot.readyCount());
    }

    @Test
    public void blockedTicketReadsBlocked() {
        String blocked = ticket("walled", "requirements", "high");
        plan(blocked);
        store.setBlocked("p", blocked, "needs input", "test");

        BoardSnapshot snapshot = modelRefresh();

        assertEquals(StageReadiness.Kind.BLOCKED, snapshot.readinessOf(blocked).kind());
    }

    @Test
    public void unstagedTicketsAreNotApplicableButNeverNull() {
        String plain = ticket("no stage", null, "low");
        plan(plain);

        BoardSnapshot snapshot = modelRefresh();

        assertNotNull(snapshot.readinessOf(plain));
        assertEquals(StageReadiness.Kind.NOT_APPLICABLE, snapshot.readinessOf(plain).kind());
        assertEquals(0, snapshot.readyCount());
    }

    private BoardSnapshot modelRefresh() {
        BoardModel model = new BoardModel(root, "p");
        model.setSprint("S-01");
        return model.refresh();
    }
}
