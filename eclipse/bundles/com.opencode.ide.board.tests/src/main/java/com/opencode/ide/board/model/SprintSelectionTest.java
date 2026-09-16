package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link SprintSelection}: the peer-aware auto-select truth
 * table behind the Board's sprint selector (B-002) — a peer-planned sprint
 * must surface when the board sits unpicked on an empty (backlog) default,
 * and must NEVER override an explicit user pick, a real selection, or a
 * backlog that still shows work.
 */
public class SprintSelectionTest {

    private static final List<String> SPRINTS = List.of("sprint-2026-09-01", "sprint-2026-09-15",
            BoardModel.BACKLOG);

    @Test
    public void unpickedEmptyBacklogAutoSelectsTheNewestSprint() {
        assertEquals("sprint-2026-09-15",
                SprintSelection.autoSelect(BoardModel.BACKLOG, false, SPRINTS, 0));
    }

    @Test
    public void explicitPickIsNeverOverridden() {
        assertNull("the user chose — even an empty board keeps their pick",
                SprintSelection.autoSelect(BoardModel.BACKLOG, true, SPRINTS, 0));
        assertNull(SprintSelection.autoSelect("sprint-2026-09-01", true, SPRINTS, 0));
    }

    @Test
    public void aRealSprintSelectionIsKeptEvenWhenEmpty() {
        assertNull(SprintSelection.autoSelect("sprint-2026-09-01", false, SPRINTS, 0));
        assertNull(SprintSelection.autoSelect("gone-sprint", false, SPRINTS, 0));
    }

    @Test
    public void backlogWithVisibleWorkIsKept() {
        assertNull("backlog shows tickets — switching would hide them",
                SprintSelection.autoSelect(BoardModel.BACKLOG, false, SPRINTS, 3));
    }

    @Test
    public void sprintLessStoreNeverAutoSelects() {
        assertNull(SprintSelection.autoSelect(BoardModel.BACKLOG, false,
                List.of(BoardModel.BACKLOG), 0));
    }

    @Test
    public void emptyOrNullListIsDefensivelyIgnored() {
        assertNull(SprintSelection.autoSelect(BoardModel.BACKLOG, false, List.of(), 0));
        assertNull(SprintSelection.autoSelect(BoardModel.BACKLOG, false, null, 0));
    }

    @Test
    public void newestMeansLastRealSprintRegardlessOfBacklogPosition() {
        // the store appends (backlog) last, but the rule must not depend on it
        List<String> backlogFirst = List.of(BoardModel.BACKLOG, "sprint-a", "sprint-b");
        assertEquals("sprint-b",
                SprintSelection.autoSelect(BoardModel.BACKLOG, false, backlogFirst, 0));

        // single real sprint: that one
        assertEquals("only-one",
                SprintSelection.autoSelect(BoardModel.BACKLOG, false,
                        List.of("only-one", BoardModel.BACKLOG), 0));
    }
}
