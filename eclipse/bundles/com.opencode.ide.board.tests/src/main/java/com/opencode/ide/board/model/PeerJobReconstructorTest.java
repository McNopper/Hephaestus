package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.fleet.TaskFleet;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;

/**
 * Unit tests for {@link PeerJobReconstructor} (F-004): store claims plus
 * worktree directories (faked with temp folders — no git needed) become
 * external-engine rows; the live engine's rows win the dedup; done and
 * unclaimed tickets are ignored; a missing worktree is tolerated.
 */
public class PeerJobReconstructorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A fleet-claimed ticket (in-progress, assignee {@link TaskFleet#ASSIGNEE}) in the temp store. */
    private static Task claimed(TaskStore store, String project) {
        Task task = store.create(project, new TaskStore.CreateSpec("work", "", "task", "developer",
                "high", 1, List.of(), List.of(), null, "T"));
        store.update(project, task.id, Map.of("status", "in-progress", "assignee", TaskFleet.ASSIGNEE));
        return store.get(project, task.id);
    }

    /** A fake fleet worktree directory for a ticket under a fake repo root. */
    private static Path worktree(Path repoRoot, String taskId) throws Exception {
        Path dir = repoRoot.resolve(".git").resolve("opencode-fleet").resolve(taskId);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    public void storeClaimWithWorktreeBecomesExternalRow() throws Exception {
        Path repoRoot = tmp.newFolder("repo").toPath();
        TaskStore store = new TaskStore(tmp.newFolder("tasks").toPath());
        Task claim = claimed(store, "p");
        Path worktree = worktree(repoRoot, claim.id);

        List<FleetJobHandle> rows = PeerJobReconstructor.externalJobs(store, repoRoot, Set.of());

        assertEquals(1, rows.size());
        FleetJobHandle row = rows.get(0);
        assertEquals(claim.id, row.taskId());
        assertTrue(row.external());
        assertEquals(FleetJobHandle.State.RUNNING, row.state());
        // the reconstructor reports the CANONICAL path (FleetGit.fleetRoot
        // toRealPaths the git dir so 8.3/subst aliases share locks - B-001);
        // on CI runners the temp dir is C:\Users\RUNNER~1 while the real
        // path is C:\Users\runneradmin, so compare real-to-real
        assertEquals(worktree.toRealPath().toString(), row.worktree());
        assertNull(row.sessionId());
        assertTrue(row.detail(), row.detail().contains("external engine"));
    }

    @Test
    public void liveEngineOverlapDeduplicatesByTicketId() throws Exception {
        Path repoRoot = tmp.newFolder("repo").toPath();
        TaskStore store = new TaskStore(tmp.newFolder("tasks").toPath());
        Task claim = claimed(store, "p");
        worktree(repoRoot, claim.id);

        List<FleetJobHandle> rows = PeerJobReconstructor.externalJobs(store, repoRoot, Set.of(claim.id));

        assertTrue(rows.isEmpty());
    }

    @Test
    public void doneAndUnclaimedTicketsAreIgnored() throws Exception {
        Path repoRoot = tmp.newFolder("repo").toPath();
        TaskStore store = new TaskStore(tmp.newFolder("tasks").toPath());
        Task done = store.create("p", new TaskStore.CreateSpec("done work", "", "task", "developer",
                "medium", 1, List.of(), List.of(), null, "T"));
        store.update("p", done.id, Map.of("status", "done", "assignee", TaskFleet.ASSIGNEE));
        Task human = store.create("p", new TaskStore.CreateSpec("human work", "", "task", "developer",
                "medium", 1, List.of(), List.of(), null, "T"));
        store.update("p", human.id, Map.of("status", "in-progress", "assignee", "bob"));
        worktree(repoRoot, done.id);
        worktree(repoRoot, human.id);

        assertTrue(PeerJobReconstructor.externalJobs(store, repoRoot, Set.of()).isEmpty());
    }

    @Test
    public void missingWorktreeIsTolerated() throws Exception {
        Path repoRoot = tmp.newFolder("repo").toPath();
        TaskStore store = new TaskStore(tmp.newFolder("tasks").toPath());
        Task claim = claimed(store, "p");
        // no fleet worktree at all (not even a .git directory)

        List<FleetJobHandle> rows = PeerJobReconstructor.externalJobs(store, repoRoot, Set.of());

        assertEquals(1, rows.size());
        assertNull(rows.get(0).worktree());
        assertTrue(rows.get(0).detail(), rows.get(0).detail().contains("no worktree"));
        assertTrue(rows.get(0).external());
    }
}
