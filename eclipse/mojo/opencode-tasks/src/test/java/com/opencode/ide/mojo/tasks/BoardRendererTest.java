package com.opencode.ide.mojo.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.tasks.TaskStore;

/**
 * Board rendering from a real fixture store: goal line, the canonical
 * status columns with correct counts, blocked marker, epics, totals; plus the
 * no-sprint fallback, the explicit sprint parameter, and HTML escaping.
 */
public class BoardRendererTest {

    private static final List<String> STATUSES = List.of(
            "product-backlog", "sprint-backlog", "in-progress", "in-review",
            "paused", "done", "blocked");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("store").toPath();
        TaskStore store = new TaskStore(root);
        store.create("demo", TaskStore.CreateSpec.of("Epic: ship the capacitor"));
        store.update("demo", "T-001", Map.of("story_points", 5));
        store.create("demo", TaskStore.CreateSpec.of("Wire the flux"));
        store.update("demo", "T-002", Map.of("story_points", 2, "epic", "T-001"));
        store.create("demo", TaskStore.CreateSpec.of("Test the flux"));
        store.update("demo", "T-003", Map.of("story_points", 3, "epic", "T-001", "assignee", "agent-2"));
        store.create("demo", TaskStore.CreateSpec.of("Unplanned backlog item"));
        store.planSprint("demo", "S-01", List.of("T-001", "T-002", "T-003"), "Ship the flux capacitor");
        store.update("demo", "T-002", Map.of("status", "done"));
        store.update("demo", "T-003", Map.of("status", "in-review"));
        store.setBlocked("demo", "T-001", "waiting on parts", "pm");
    }

    @Test
    public void markdownHasGoalColumnsCountsBlockedEpicsAndTotals() throws Exception {
        String md = render(null);
        assertTrue(md.contains("Ship the flux capacitor"));
        assertTrue(md.contains("Sprint: **S-01** (active)"));

        int previous = -1;
        for (String status : STATUSES) {
            int at = md.indexOf("## " + status + " ");
            assertTrue("missing column header " + status, at >= 0);
            assertTrue("column " + status + " out of canonical order", at > previous);
            previous = at;
            assertEquals(headerCount(md, status), ticketCount(md, status));
        }
        assertEquals(0, ticketCount(md, "product-backlog"));
        // U-067: the blocked ticket left sprint-backlog for the blocked column
        assertEquals(0, ticketCount(md, "sprint-backlog"));
        assertEquals(0, ticketCount(md, "in-progress"));
        assertEquals(1, ticketCount(md, "in-review"));
        assertEquals(1, ticketCount(md, "done"));
        assertEquals(0, ticketCount(md, "paused"));
        assertEquals(1, ticketCount(md, "blocked"));

        assertTrue(md.contains("- [T-001] Epic: ship the capacitor — developer, 5 points, "
                + "⚠ blocked: waiting on parts"));
        assertTrue(md.contains("- [T-003] Test the flux — developer, 3 points, agent-2"));
        assertFalse("unblocked ticket must not carry the blocked marker",
                md.contains("[T-003] Test the flux — developer, 3 points, agent-2, ⚠"));
        assertFalse("tickets outside the sprint must not appear", md.contains("Unplanned backlog item"));

        assertTrue(md.contains("## Epics"));
        assertTrue(md.contains("[T-001] Epic: ship the capacitor — 1 open of 2"));
        assertTrue(md.contains("**Totals:** 10 points total, 2 points done."));
    }

    @Test
    public void noActiveSprintFallsBackToUnassignedTickets() throws Exception {
        Path solo = tmp.newFolder("solo-store").toPath();
        TaskStore store = new TaskStore(solo);
        store.create("solo", TaskStore.CreateSpec.of("Lonely backlog item"));

        String md = BoardRenderer.markdown(StoreBoards.load(solo, null, s -> { }));
        assertTrue(md.contains("Sprint: **" + BoardRenderer.NO_SPRINT + "**"));
        assertEquals(1, ticketCount(md, "product-backlog"));
        assertTrue(md.contains("[T-001] Lonely backlog item"));
    }

    @Test
    public void explicitSprintParameterSelectsTheSprint() throws Exception {
        String md = render("S-01");
        assertTrue(md.contains("Ship the flux capacitor"));
        assertEquals(1, ticketCount(md, "done"));

        String unknown = render("S-99");
        assertTrue(unknown.contains("Sprint: **S-99**"));
        assertTrue(unknown.contains("no sprint metadata in _meta.json"));
        for (String status : STATUSES) {
            assertEquals(0, ticketCount(unknown, status));
        }
    }

    @Test
    public void htmlIsStandaloneAndEscapesContent() throws Exception {
        TaskStore store = new TaskStore(root);
        store.update("demo", "T-002", Map.of("title", "Escape <b> & \"quotes\""));
        String html = BoardRenderer.html(StoreBoards.load(root, null, s -> { }));

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<style>"));
        assertFalse("no external assets allowed", html.contains("http://") || html.contains("https://"));
        for (String status : STATUSES) {
            assertTrue(html.contains("id=\"" + status + "\""));
        }
        assertTrue(html.contains("Ship the flux capacitor"));
        assertTrue(html.contains("&lt;b&gt;"));
        assertTrue(html.contains("&amp;"));
        assertTrue(html.contains("&quot;quotes&quot;"));
        assertFalse("raw markup must not survive escaping", html.contains("<b>"));
        assertTrue(html.contains("⚠ blocked: waiting on parts"));
    }

    private String render(String sprint) throws IOException {
        return BoardRenderer.markdown(StoreBoards.load(root, sprint, s -> { }));
    }

    private static int headerCount(String md, String status) {
        int at = md.indexOf("## " + status + " (");
        int close = md.indexOf(')', at);
        return Integer.parseInt(md.substring(at + ("## " + status + " (").length(), close));
    }

    private static int ticketCount(String md, String status) {
        int count = 0;
        boolean inColumn = false;
        for (String line : md.split("\n", -1)) {
            if (line.startsWith("## ")) {
                inColumn = line.startsWith("## " + status + " ");
            } else if (inColumn && line.startsWith("- [")) {
                count++;
            }
        }
        return count;
    }
}
