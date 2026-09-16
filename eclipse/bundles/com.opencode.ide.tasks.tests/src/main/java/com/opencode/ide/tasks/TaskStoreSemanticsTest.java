package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Semantics-parity suite: every rule the retired Python pm MCP server
 * enforced (claim ordering/null, lax update, sprint force-set, close-sprint
 * returns, board columns, traceability pairing, id minting) must hold
 * one-for-one on the Markdown store.
 */
public class TaskStoreSemanticsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;

    @Before
    public void setUp() {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    }

    private String mkSprintBacklog(String role, String priority) {
        Task t = store.create("p", TaskStore.CreateSpec.of("t-" + System.nanoTime()));
        store.update("p", t.id, Map.of("role", role, "priority", priority));
        store.planSprint("p", "S-01", List.of(t.id), "g");
        return t.id;
    }

    @Test
    public void createMintsSequentialIdsPerPrefix() {
        Task a = store.create("p", TaskStore.CreateSpec.of("first"));
        Task b = store.create("p", TaskStore.CreateSpec.of("second"));
        assertEquals("T-001", a.id);
        assertEquals("T-002", b.id);
        Task fr = store.create("p", new TaskStore.CreateSpec("third", "", "task", "pm", "low", 1,
                List.of(), List.of(), null, "FR"));
        assertEquals("FR-001", fr.id);
        assertEquals("product-backlog", a.status);
        assertEquals(1, a.history.size());
        assertEquals("created", a.history.get(0).action());
        // prefix sanitization: "x y!" -> "xy"
        Task odd = store.create("p", new TaskStore.CreateSpec("odd", "", "task", "pm", "low", 0,
                List.of(), List.of(), null, "x y!"));
        assertEquals("xy-001", odd.id);
    }

    @Test
    public void createValidatesTypePriorityRole() {
        try {
            store.create("p", new TaskStore.CreateSpec("t", "", "banana", "dev", "low", 0, null, null, null, "T"));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("type"));
        }
        try {
            store.create("p", new TaskStore.CreateSpec("t", "", "task", "dev", "urgent", 0, null, null, null, "T"));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("priority"));
        }
        try {
            store.create("p", new TaskStore.CreateSpec("t", "", "task", "  ", "low", 0, null, null, null, "T"));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("role"));
        }
    }

    @Test
    public void getMissingFailsWithPmMessage() {
        try {
            store.get("p", "T-999");
            fail("expected NotFound");
        } catch (TaskStore.NotFound expected) {
            assertEquals("ticket T-999 not found in project p", expected.getMessage());
        }
    }

    @Test
    public void listFilters() {
        store.create("p", TaskStore.CreateSpec.of("a"));
        Task b = store.create("p", TaskStore.CreateSpec.of("b"));
        store.update("p", b.id, Map.of("role", "tester"));
        assertEquals(2, store.list("p", null, null, null, null).size());
        assertEquals(1, store.list("p", "tester", null, null, null).size());
        assertEquals(2, store.list("p", null, "product-backlog", null, null).size());
        store.update("p", b.id, Map.of("status", "in-review"));
        assertEquals(1, store.list("p", null, "product-backlog", null, null).size());
        store.setBlocked("p", b.id, "x", null);
        assertEquals(1, store.list("p", null, null, null, true).size());
        assertEquals(1, store.list("p", null, null, null, false).size());
    }

    @Test
    public void updateIsLaxAndProtectsFields() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        int historyBefore = t.history.size();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("title", "renamed");
        changes.put("status", "done");          // no transition graph - any status accepted
        changes.put("type", "banana");           // NOT validated (pm parity)
        changes.put("story_points", 13);
        changes.put("id", "HACKED");             // protected -> dropped
        changes.put("created_at", "2000-01-01"); // protected -> dropped
        changes.put("history", "gone");          // protected -> dropped
        changes.put("comments", "gone");         // protected -> dropped
        changes.put("unknown_field", "x");       // unknown -> dropped
        Task updated = store.update("p", t.id, changes);
        assertEquals("renamed", updated.title);
        assertEquals("done", updated.status);
        assertEquals("banana", updated.type);
        assertEquals(13, updated.storyPoints);
        assertEquals(t.id, updated.id);
        assertEquals(t.createdAt, updated.createdAt);
        assertEquals("updated:title,status,type,story_points", updated.history.get(historyBefore).action());
    }

    @Test
    public void updateValidatesRoleAndStatusOnly() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        try {
            store.update("p", t.id, Map.of("role", " "));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("role"));
        }
        try {
            store.update("p", t.id, Map.of("status", "limbo"));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("status"));
        }
    }

    @Test
    public void updateToDoneClearsBlockedFlagAndBlocker() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        store.setBlocked("p", t.id, "waiting on review", null);
        Task done = store.update("p", t.id, Map.of("status", "done"));
        assertEquals("done", done.status);
        assertFalse(done.blocked);
        assertNull(done.blocker);
        assertTrue(done.history.stream().anyMatch(e -> "unblocked (done)".equals(e.action())));
        Task reloaded = new TaskStore(store.root()).get("p", t.id);
        assertFalse("the clear is persisted, not just in-memory", reloaded.blocked);
        assertNull(reloaded.blocker);
        // legacy drift (set_blocked after done) heals on the next update touch
        store.setBlocked("p", t.id, "drift", null);
        Task touched = store.update("p", t.id, Map.of("title", "renamed"));
        assertFalse(touched.blocked);
        assertNull(touched.blocker);
    }

    @Test
    public void closeSprintClearsBlockedOnDoneTickets() {
        String id = mkSprintBacklog("developer", "high");
        store.update("p", id, Map.of("status", "done"));
        store.setBlocked("p", id, "stale flag", null);
        store.closeSprint("p", "S-01");
        Task after = store.get("p", id);
        assertEquals("done", after.status);
        assertEquals("done tickets keep their sprint on close", "S-01", after.sprint);
        assertFalse(after.blocked);
        assertNull(after.blocker);
    }

    @Test
    public void corruptHistoryLineKeepsTicketVisible() throws IOException {
        Task t = store.create("p", TaskStore.CreateSpec.of("live incident"));
        store.update("p", t.id, Map.of("status", "in-progress"));
        Path file = store.root().resolve("p").resolve(t.id + ".md");
        String corrupted = Files.readString(file).replace("## History\n",
                "## History\n`n{\"ts\":\"2026-09-14T11:00:00.000Z\",\"action\":\"injected\"}\n");
        Files.writeString(file, corrupted);
        Task got = store.get("p", t.id);
        assertEquals(t.id, got.id);
        assertEquals("in-progress", got.status);
        assertEquals(1, got.quarantinedLines.size());
        assertTrue(got.quarantinedLines.get(0).startsWith("History: `n"));
        assertTrue("the ticket stays listed", store.list("p", null, null, null, null)
                .stream().anyMatch(x -> t.id.equals(x.id)));
    }

    @Test
    public void inconsistenciesReportsDriftAndGoesQuietWhenHealed() {
        Task doneBlocked = store.create("p", TaskStore.CreateSpec.of("done but blocked"));
        store.update("p", doneBlocked.id, Map.of("status", "done"));
        store.setBlocked("p", doneBlocked.id, "worker produced no changes", null);
        Task sprintPb = store.create("p", TaskStore.CreateSpec.of("sprint but pb"));
        store.planSprint("p", "S-05", List.of(sprintPb.id), "g");
        store.update("p", sprintPb.id, Map.of("status", "product-backlog"));
        Task clean = store.create("p", TaskStore.CreateSpec.of("clean"));
        store.update("p", clean.id, Map.of("status", "done"));
        List<String> drift = store.inconsistencies("p");
        assertEquals(2, drift.size());
        assertEquals(doneBlocked.id + ": status=done but blocked (blocker: worker produced no changes)",
                drift.get(0));
        assertEquals(sprintPb.id + ": status=product-backlog but sprint=S-05", drift.get(1));
        store.clearBlocked("p", doneBlocked.id, null);
        store.update("p", sprintPb.id, Map.of("status", "in-progress"));
        assertEquals(List.of(), store.inconsistencies("p"));
    }

    @Test
    public void readsOnAMissingProjectNeverMaterializeIt() {
        // Review S5: task_doctor/task_list on a typo'd project used to
        // create the directory as a side effect of the read path.
        assertTrue(store.list("ghost", null, null, null, null).isEmpty());
        assertEquals(List.of(), store.inconsistencies("ghost"));
        try {
            store.get("ghost", "T-001");
            fail("get on a missing project must be NotFound, not an empty success");
        } catch (TaskStore.NotFound expected) {
            // wanted
        }
        assertFalse("reads must not create the project directory",
                java.nio.file.Files.isDirectory(store.root().resolve("ghost")));
    }

    @Test
    public void claimOrdersByPriorityThenCreatedThenReturnsNull() {
        String low = mkSprintBacklog("developer", "low");
        String high = mkSprintBacklog("developer", "high");
        String mid = mkSprintBacklog("developer", "medium");
        Task first = store.claim("p", "developer", null, "agent-a");
        assertEquals(high, first.id);
        assertEquals("in-progress", first.status);
        assertEquals("agent-a", first.assignee);
        Task second = store.claim("p", "developer", "sprint-backlog", null);
        assertEquals(mid, second.id);
        assertEquals("developer", second.assignee); // by defaults to role
        Task third = store.claim("p", "developer", null, null);
        assertEquals(low, third.id);
        assertNull("nothing left -> null (worker loops stop on this)", store.claim("p", "developer", null, null));
    }

    @Test
    public void claimFiltersRoleAndBlockedAndRejectsOtherStatus() {
        mkSprintBacklog("tester", "high");
        assertNull("role mismatch", store.claim("p", "developer", null, null));
        String id = mkSprintBacklog("developer", "high");
        store.setBlocked("p", id, "waiting", null);
        assertNull("blocked tickets are not claimable", store.claim("p", "developer", null, null));
        try {
            store.claim("p", "developer", "product-backlog", null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("sprint-backlog"));
        }
    }

    @Test
    public void releaseOnlyFromSprintBacklogOrInProgress() {
        String id = mkSprintBacklog("developer", "high");
        store.release("p", id, null);
        assertEquals("sprint-backlog", store.get("p", id).status);
        assertNull(store.get("p", id).assignee);
        store.update("p", id, Map.of("status", "in-review"));
        try {
            store.release("p", id, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("in-review"));
        }
    }

    @Test
    public void todosAddToggleRemoveWithIndexChecks() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        store.addTodo("p", t.id, "one", false, "pm");
        store.addTodo("p", t.id, "two", false, "pm");
        Task after = store.toggleTodo("p", t.id, 1, "pm");
        assertTrue(after.todos.get(1).done());
        assertFalse(after.todos.get(0).done());
        after = store.removeTodo("p", t.id, 0, "pm");
        assertEquals(1, after.todos.size());
        assertEquals("two", after.todos.get(0).text());
        try {
            store.toggleTodo("p", t.id, 9, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertEquals("todo index 9 out of range (have 1)", expected.getMessage());
        }
    }

    @Test
    public void multilineTodoTextIsRejectedNotCorrupting() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        try {
            store.addTodo("p", t.id, "step\n- [x] injected todo", false, null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("single line"));
        }
        // the file must still load cleanly afterwards
        assertEquals(0, store.get("p", t.id).todos.size());
    }

    @Test
    public void nullListItemIsRejectedNotCorrupting() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        try {
            store.update("p", t.id, Map.of("labels", java.util.Arrays.asList("ok", null)));
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("non-null"));
        }
        assertEquals(0, store.get("p", t.id).labels.size());
    }

    @Test
    public void closeSprintTwiceIsSafe() {
        String id = mkSprintBacklog("developer", "high");
        store.closeSprint("p", "S-01");
        Map<String, Object> second = store.closeSprint("p", "S-01");
        @SuppressWarnings("unchecked")
        List<String> returned = (List<String>) second.get("returned_to_backlog");
        assertEquals("second close returns nothing new", List.of(), returned);
        assertEquals("closed", ((Task.Sprint) second.get("sprint")).status());
        assertEquals("the unfinished ticket already sits in product-backlog after the first close",
                "product-backlog", store.get("p", id).status);
    }

    @Test
    public void importMalformedJsonThrowsBeforeAnyWrite() throws IOException {
        try {
            store.importPmJson("fresh", "{not json");
            fail("expected a parse failure");
        } catch (RuntimeException expected) {
            // clean failure, not a half-import
        }
        assertFalse("no project directory may be created for a failed import",
                Files.exists(store.root().resolve("fresh")));
    }

    @Test
    public void artifactKindValidated() {
        Task t = store.create("p", TaskStore.CreateSpec.of("a"));
        store.addArtifact("p", t.id, "git", "abc123", "the commit", "dev");
        assertEquals(1, store.get("p", t.id).artifacts.size());
        try {
            store.addArtifact("p", t.id, "sms", "x", "", null);
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertTrue(expected.getMessage().contains("kind"));
        }
    }

    @Test
    public void backlogSortedByPriorityThenCreated() {
        Task low = store.create("p", TaskStore.CreateSpec.of("low"));
        store.update("p", low.id, Map.of("priority", "low"));
        Task high = store.create("p", TaskStore.CreateSpec.of("high"));
        store.update("p", high.id, Map.of("priority", "high"));
        Task done = store.create("p", TaskStore.CreateSpec.of("done"));
        store.update("p", done.id, Map.of("status", "done"));
        List<Task> backlog = store.backlog("p");
        assertEquals(2, backlog.size());
        assertEquals(high.id, backlog.get(0).id);
        assertEquals(low.id, backlog.get(1).id);
    }

    @Test
    public void boardAlwaysHasAllFiveColumns() {
        String id = mkSprintBacklog("developer", "high");
        store.claim("p", "developer", null, null);
        Map<String, List<Task>> board = store.board("p", null);
        assertEquals(Task.VALID_STATUSES, List.copyOf(board.keySet()));
        assertEquals(1, board.get("in-progress").size());
        // sprint filter: no ticket in S-99
        assertTrue(store.board("p", "S-99").get("in-progress").isEmpty());
    }

    @Test
    public void planSprintForceSetsStatusAndValidatesFirst() throws IOException {
        Task a = store.create("p", TaskStore.CreateSpec.of("a"));
        Task b = store.create("p", TaskStore.CreateSpec.of("b"));
        store.update("p", b.id, Map.of("status", "done"));
        Task.Sprint s = store.planSprint("p", null, List.of(a.id, b.id), "the goal");
        assertEquals("S-01", s.id());
        assertEquals("the goal", s.goal());
        assertEquals("active", s.status());
        assertEquals("sprint-backlog", store.get("p", a.id).status); // even done ones (pm parity)
        assertEquals("sprint-backlog", store.get("p", b.id).status);
        assertEquals("S-01", store.get("p", a.id).sprint);

        // invalid batch: nothing is written at all (validate-before-write)
        Path dir = store.root().resolve("p");
        try {
            store.planSprint("p", "S-02", List.of(a.id, "T-999"), "g");
            fail("expected Invalid");
        } catch (TaskStore.Invalid expected) {
            assertEquals("ticket T-999 not found", expected.getMessage());
        }
        assertFalse("no S-02 sprint metadata must be created", Files.exists(dir.resolve("_meta.json"))
                && Files.readString(dir.resolve("_meta.json")).contains("S-02"));
    }

    @Test
    public void closeSprintReturnsUnfinishedToBacklog() throws IOException {
        String stay = mkSprintBacklog("developer", "high");
        String finish = mkSprintBacklog("developer", "high");
        store.claim("p", "developer", null, null); // claims the first created of equal priority
        store.update("p", finish, Map.of("status", "done"));
        Map<String, Object> out = store.closeSprint("p", "S-01");
        @SuppressWarnings("unchecked")
        List<String> returned = (List<String>) out.get("returned_to_backlog");
        assertEquals(List.of(stay), returned);
        assertEquals("product-backlog", store.get("p", stay).status);
        assertNull(store.get("p", stay).sprint);
        assertEquals("done", store.get("p", finish).status);
        assertEquals("closed", ((Task.Sprint) out.get("sprint")).status());
        try {
            store.closeSprint("p", "S-99");
            fail("expected NotFound");
        } catch (TaskStore.NotFound expected) {
            assertEquals("sprint S-99 not found", expected.getMessage());
        }
    }

    @Test
    public void traceabilityPairsDefinitionAndVerification() {
        Task parent = store.create("p", TaskStore.CreateSpec.of("arch work"));
        store.update("p", parent.id, Map.of("role", "architect"));
        Task test = store.create("p", TaskStore.CreateSpec.of("verifies arch"));
        store.update("p", test.id, Map.of("role", "tester", "epic", parent.id));
        Task orphanV = store.create("p", TaskStore.CreateSpec.of("orphan verification"));
        store.update("p", orphanV.id, Map.of("role", "tester", "epic", "T-999"));

        Map<String, Object> out = store.traceability("p");
        assertEquals(3, out.get("ticket_count"));
        assertEquals("parent has a verification child -> not an orphan", List.of(), out.get("orphan_definitions"));
        assertEquals(List.of(orphanV.id), out.get("orphan_verifications"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matrix = (List<Map<String, Object>>) out.get("matrix");
        for (Map<String, Object> row : matrix) {
            if (parent.id.equals(row.get("id"))) {
                assertEquals(List.of(test.id), row.get("verified_by"));
            }
            if (test.id.equals(row.get("id"))) {
                assertEquals(parent.id, row.get("verifies"));
            }
        }
    }

    @Test
    public void metaLossRecoversSeqFromFiles() {
        Task a = store.create("p", TaskStore.CreateSpec.of("a"));
        Task b = store.create("p", TaskStore.CreateSpec.of("b"));
        assertEquals("T-002", b.id);
        // simulate a lost sidecar: delete _meta.json, then create again
        try {
            Files.delete(store.root().resolve("p").resolve("_meta.json"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Task c = store.create("p", TaskStore.CreateSpec.of("c"));
        assertEquals("ids are never reused after meta loss", "T-003", c.id);
        // and the old files survive untouched
        assertEquals(a.id, store.get("p", a.id).id);
    }

    @Test
    public void projectNamesSanitizedAndTraversalRejected() {
        TaskStore.Invalid ex = null;
        try {
            TaskStore.sanitizeProject("..");
        } catch (TaskStore.Invalid e) {
            ex = e;
        }
        assertNotNull(ex);
        assertEquals("a_b", TaskStore.sanitizeProject("a/b"));
        assertEquals("win_path", TaskStore.sanitizeProject("win\\path"));
        // traversal is impossible: separators fold to underscores (".." is rejected, "../escape" folds)
        assertEquals(".._escape", TaskStore.sanitizeProject("../escape"));
        assertEquals(0, new TaskStore(tmp.getRoot().toPath()).list(".._escape", null, null, null, null).size());
    }

    @Test
    public void unparsableFileIsSkippedNotFatal() throws IOException {
        Task good = store.create("p", TaskStore.CreateSpec.of("good"));
        Path dir = store.root().resolve("p");
        Files.writeString(dir.resolve("T-900.md"), "not a task file at all");
        List<Task> all = store.list("p", null, null, null, null);
        assertEquals(1, all.size());
        assertEquals(good.id, all.get(0).id);
    }

    @Test
    public void persistenceAcrossStoreInstances() {
        Task t = store.create("p", new TaskStore.CreateSpec("persisted", "with description", "story",
                "tester", "critical", 5, List.of("GIVEN x"), List.of("l1"), "EP-1", "T"));
        TaskStore fresh = new TaskStore(store.root());
        Task back = fresh.get("p", t.id);
        assertEquals("persisted", back.title);
        assertEquals("with description", back.description);
        assertEquals("critical", back.priority);
        assertEquals(List.of("GIVEN x"), back.acceptanceCriteria);
        assertEquals(List.of("l1"), back.labels);
        assertEquals("EP-1", back.epic);
    }
}
