package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-025 archive semantics (user direction 2026-09-19): archived tickets
 * move to {@code _archive/}, leave the live listing (board, readiness,
 * dispatch), stay readable via {@code archived()}, and a closing wave
 * archives its done tickets automatically.
 */
public class TaskStoreArchiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private TaskStore store;
    private Path projectDir;

    @Before
    public void setUp() throws Exception {
        store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
        projectDir = store.root().resolve(TaskStore.sanitizeProject("p"));
        Files.createDirectories(projectDir);
    }

    private String create() {
        return store.create("p", TaskStore.CreateSpec.of("some work")).id;
    }

    @Test
    public void archivedTicketLeavesTheLiveListingButStaysReadable() {
        String id = create();
        store.update("p", id, java.util.Map.of("status", (Object) "done"));

        store.archive("p", id, "test");

        assertTrue("live file moved", Files.notExists(projectDir.resolve(id + ".md")));
        assertTrue("archive file written", Files.isRegularFile(projectDir.resolve("_archive").resolve(id + ".md")));
        assertTrue("live listing is empty", store.list("p", null, null, null, null).isEmpty());
        List<Task> archived = store.archived("p");
        assertEquals(1, archived.size());
        assertEquals(id, archived.get(0).id);
        assertEquals("done", archived.get(0).status);
        assertTrue("history records the archive",
                archived.get(0).history.stream()
                        .anyMatch(h -> h.action() != null && h.action().contains("archived")));
    }

    @Test
    public void archiveOfUnknownTicketFails() {
        try {
            store.archive("p", "T-999", "test");
            org.junit.Assert.fail("expected NotFound");
        } catch (RuntimeException expected) {
            // NotFound from require()
        }
    }

    @Test
    public void closingAWaveKeepsDoneTicketsVisibleForTheReadinessChain() {
        // U-025 lesson (live 2026-09-19): archiving on wave close orphaned
        // WAIT_UPSTREAM children - the done upstream must stay visible to
        // the readiness epic chain; archiving stays manual until readiness
        // consults the archive
        String done = create();
        String wip = create();
        store.planSprint("p", "S-01", List.of(done, wip), "goal");
        store.update("p", done, java.util.Map.of("status", (Object) "done"));
        store.update("p", wip, java.util.Map.of("status", (Object) "in-progress"));

        var out = store.closeSprint("p", "S-01");

        assertEquals(List.of(wip), out.get("returned_to_backlog"));
        assertTrue("done ticket stays live for the chain",
                store.list("p", null, null, null, null).stream().anyMatch(t -> t.id.equals(done)));
        assertTrue(store.archived("p").isEmpty());
    }

    @Test
    public void absentArchiveDirectoryReadsAsEmpty() {
        assertTrue(store.archived("p").isEmpty());
    }
}
