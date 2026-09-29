package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.ShellTask;
import com.opencode.ide.ui.model.SessionSections.Section;

/**
 * Unit tests for {@link SessionSections}: the Session Details tree's section
 * composition (U-041) — subagents and shell tasks as collapsible sections
 * above the message rows, empty sections omitted. SWT-free.
 */
public class SessionSectionsTest {

    private static SessionSubagents.Row subagent(String id) {
        return new SessionSubagents.Row(id, "Child " + id, "explore", "idle", 0.01, 125L, true);
    }

    private static SessionShells.Row shell(String id) {
        return new SessionShells.Row(id, "make verify", "running", null, null, true);
    }

    @Test
    public void bothSectionsCarryCountsAndTheirRowsAsChildren() {
        List<Object> roots = SessionSections.roots(
                List.of(subagent("ses_a"), subagent("ses_b")),
                List.of(shell("sh_1")));

        assertEquals(2, roots.size());
        assertTrue(roots.get(0) instanceof Section);
        Section subagents = (Section) roots.get(0);
        assertEquals("Subagents (2)", subagents.label());
        assertEquals(2, subagents.children().size());
        assertTrue(roots.get(1) instanceof Section);
        Section shells = (Section) roots.get(1);
        assertEquals("Shell tasks (1)", shells.label());
        assertEquals(1, shells.children().size());
    }

    @Test
    public void emptySectionsAreOmitted() {
        assertTrue(SessionSections.roots(List.of(), List.of()).isEmpty());
        assertTrue(SessionSections.roots(null, null).isEmpty());

        List<Object> onlyShells = SessionSections.roots(List.of(), List.of(shell("sh_1")));
        assertEquals(1, onlyShells.size());
        assertEquals("Shell tasks (1)", ((Section) onlyShells.get(0)).label());

        List<Object> onlySubagents = SessionSections.roots(List.of(subagent("ses_a")), List.of());
        assertEquals(1, onlySubagents.size());
        assertEquals("Subagents (1)", ((Section) onlySubagents.get(0)).label());
    }

    @Test
    public void sectionChildrenAreCopiedDefensively() {
        List<SessionSubagents.Row> rows = new java.util.ArrayList<>(List.of(subagent("ses_a")));
        Section section = (Section) SessionSections.roots(rows, List.of()).get(0);

        rows.clear();

        assertEquals("mutating the input list cannot change a built section",
                1, section.children().size());
    }

    /** The shell-row fixture stays valid wire-wise (the record is the client's). */
    @Test
    public void shellRowFixtureMatchesTheClientRecord() {
        ShellTask task = new ShellTask("sh_1", "running", "make verify", "C:\\repo", null, null,
                new ShellTask.Time(1L, null));
        assertEquals("sh_1", task.id());
        assertTrue(task.isRunning());
    }
}
