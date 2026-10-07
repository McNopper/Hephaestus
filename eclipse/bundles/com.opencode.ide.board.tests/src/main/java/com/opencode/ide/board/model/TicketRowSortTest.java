package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The card-column sort contract behind the board's click-to-sort (owner
 * direction 2026-10-07): pipeline order for status, case-insensitive text
 * for type/title, numeric points - unset keys (unknown status, blank value,
 * zero points) sort LAST in both directions, and the ticket id breaks ties
 * ascending so neither direction scrambles equal rows.
 */
public class TicketRowSortTest {

    private static TicketRow row(String id, String status, String type, String title, int points) {
        return new TicketRow(id, title, type, "developer", points, null, false, null,
                status, "design", "medium", null);
    }

    private static List<String> ids(String column, boolean ascending, TicketRow... rows) {
        List<TicketRow> sorted = new ArrayList<>(List.of(rows));
        sorted.sort(TicketRow.cardComparator(column, ascending));
        List<String> ids = new ArrayList<>();
        for (TicketRow row : sorted) {
            ids.add(row.id());
        }
        return ids;
    }

    @Test
    public void statusSortsInPipelineOrderWithUnknownStatusLast() {
        TicketRow done = row("T-4", "done", "bug", "t", 0);
        TicketRow backlog = row("T-1", "product-backlog", "bug", "t", 0);
        TicketRow progress = row("T-3", "in-progress", "bug", "t", 0);
        TicketRow unknown = row("T-9", "mystery", "bug", "t", 0);
        assertEquals(List.of("T-1", "T-3", "T-4", "T-9"),
                ids("status", true, done, backlog, progress, unknown));
        // descending reverses the SET values; the unknown status stays last
        assertEquals(List.of("T-4", "T-3", "T-1", "T-9"),
                ids("status", false, done, backlog, progress, unknown));
    }

    @Test
    public void typeIsCaseInsensitiveAndBlankTypeSortsLast() {
        TicketRow bug = row("T-1", "sprint-backlog", "Bug", "t", 0);
        TicketRow spike = row("T-2", "sprint-backlog", "spike", "t", 0);
        TicketRow untyped = row("T-3", "sprint-backlog", null, "t", 0);
        assertEquals(List.of("T-1", "T-2", "T-3"), ids("type", true, bug, spike, untyped));
        assertEquals(List.of("T-2", "T-1", "T-3"), ids("type", false, bug, spike, untyped));
    }

    @Test
    public void titleIsCaseInsensitiveAndBlankTitleSortsLast() {
        TicketRow zebra = row("T-1", "sprint-backlog", "task", "Zebra", 0);
        TicketRow apple = row("T-2", "sprint-backlog", "task", "apple", 0);
        TicketRow mango = row("T-3", "sprint-backlog", "task", "Mango", 0);
        TicketRow untitled = row("T-4", "sprint-backlog", "task", "  ", 0);
        assertEquals(List.of("T-2", "T-3", "T-1", "T-4"),
                ids("title", true, zebra, apple, mango, untitled));
        assertEquals(List.of("T-1", "T-3", "T-2", "T-4"),
                ids("title", false, zebra, apple, mango, untitled));
    }

    @Test
    public void pointsSortNumericallyAndZeroPointsSortsLast() {
        TicketRow five = row("T-5", "in-progress", "task", "t", 5);
        TicketRow three = row("T-3", "in-progress", "task", "t", 3);
        TicketRow none = row("T-0", "in-progress", "task", "t", 0);
        TicketRow eight = row("T-8", "in-progress", "task", "t", 8);
        assertEquals(List.of("T-3", "T-5", "T-8", "T-0"),
                ids("points", true, five, three, none, eight));
        assertEquals(List.of("T-8", "T-5", "T-3", "T-0"),
                ids("points", false, five, three, none, eight));
    }

    @Test
    public void tiesBreakByIdAscendingInBothDirections() {
        TicketRow later = row("T-2", "in-review", "bug", "same", 3);
        TicketRow earlier = row("T-1", "in-review", "bug", "same", 3);
        assertEquals(List.of("T-1", "T-2"), ids("title", true, later, earlier));
        assertEquals(List.of("T-1", "T-2"), ids("title", false, later, earlier));
    }

    @Test
    public void unknownColumnSortsLikePoints() {
        TicketRow four = row("T-4", "in-progress", "task", "t", 4);
        TicketRow zero = row("T-0", "in-progress", "task", "t", 0);
        assertEquals(List.of("T-4", "T-0"), ids("bogus", true, four, zero));
        assertEquals(List.of("T-4", "T-0"), ids("bogus", false, four, zero));
    }

    @Test
    public void allNullFieldsCompareWithoutThrowing() {
        TicketRow empty = new TicketRow(null, null, null, null, 0, null, false, null,
                null, null, null, null);
        TicketRow set = row("T-1", "in-progress", "task", "title", 2);
        assertTrue(TicketRow.cardComparator("title", true).compare(empty, set) > 0);
        // descending still ranks the unset row LAST: set before empty = negative
        assertTrue(TicketRow.cardComparator("title", false).compare(set, empty) < 0);
        assertEquals(0, TicketRow.cardComparator("status", true).compare(empty, empty));
    }
}
