package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Additional unit tests for {@link TicketRow}'s display projections,
 * complementing {@link TicketRowStageTest} without touching it: the full
 * status-prefix table, pipeline/flat label shapes with and without blocked,
 * the absence of title truncation, and the stored-vs-derived stage
 * precedence with a diverging role.
 */
public class TicketRowDisplayTest {

    private static TicketRow row(String title, String role, boolean blocked,
            String status, String stage) {
        return new TicketRow("T-001", title, role, 2, null, blocked,
                blocked ? "why" : null, status, stage);
    }

    @Test
    public void statusPrefixMapsEveryValidStatusPlusUnknown() {
        assertEquals("[PB]", TicketRow.statusPrefix("product-backlog"));
        assertEquals("[SB]", TicketRow.statusPrefix("sprint-backlog"));
        assertEquals("[IP]", TicketRow.statusPrefix("in-progress"));
        assertEquals("[IR]", TicketRow.statusPrefix("in-review"));
        assertEquals("[D]", TicketRow.statusPrefix("done"));
        assertEquals("", TicketRow.statusPrefix("unknown-status"));
        assertEquals("", TicketRow.statusPrefix(""));
    }

    @Test
    public void pipelineLabelSeparatesPrefixBlockedAndTitle() {
        assertEquals("[IR] [BLOCKED] rework", row("rework", "developer", true, "in-review", "design")
                .pipelineLabel());
        assertEquals("[D] shipped", row("shipped", "developer", false, "done", null)
                .pipelineLabel());
        assertEquals("[IP] plain", row("plain", "pm", false, "in-progress", "requirements")
                .pipelineLabel());
    }

    @Test
    public void pipelineLabelBlockedWithoutKnownStatusIsJustTheBlockedTag() {
        assertEquals("[BLOCKED] odd one", row("odd one", "research", true, "mystery", null)
                .pipelineLabel());
        assertEquals("[BLOCKED] no status", row("no status", "developer", true, null, null)
                .pipelineLabel());
    }

    @Test
    public void pipelineLabelSkipsMissingPiecesWithoutStraySpaces() {
        assertEquals("[IR] [BLOCKED]", row(null, "developer", true, "in-review", "design")
                .pipelineLabel());
        assertEquals("[D]", row("  ", "developer", true, "done", null)
                .pipelineLabel());
    }

    @Test
    public void doneRowsNeverRenderBlockedEvenWithLegacyDrift() {
        TicketRow drifted = row("shipped long ago", "developer", true, "done", "test-system");
        assertFalse(drifted.displayBlocked());
        assertEquals("T-001 shipped long ago", drifted.label());
        assertEquals("[D] shipped long ago", drifted.pipelineLabel());
    }

    @Test
    public void displayBlockedStaysTrueForNonDoneStatuses() {
        assertTrue(row("wip", "developer", true, "in-progress", null).displayBlocked());
        assertTrue(row("review", "developer", true, "in-review", null).displayBlocked());
        assertTrue(row("odd", "research", true, "mystery", null).displayBlocked());
        assertFalse(row("plain", "developer", false, "in-progress", null).displayBlocked());
    }

    @Test
    public void pipelineLabelTrimsSurroundingTitleWhitespace() {
        TicketRow padded = new TicketRow("T-9", "  padded  ", "developer", 1, null,
                false, null, "in-progress", null);
        assertEquals("[IP] padded", padded.pipelineLabel());
    }

    @Test
    public void labelShapesWithAndWithoutBlocked() {
        assertEquals("[BLOCKED] T-001 fix it",
                row("fix it", "developer", true, "in-progress", "design").label());
        assertEquals("T-001 plain", row("plain", "developer", false, "done", null).label());
        assertEquals("T-1", new TicketRow("T-1", null, "pm", 1, null, false, null,
                "product-backlog", null).label());
    }

    @Test
    public void labelDoesNotTruncateLongTitles() {
        String longTitle = "x".repeat(500);
        TicketRow longRow = new TicketRow("T-42", longTitle, "developer", 3, null,
                false, null, "in-progress", "design");

        assertEquals("T-42 " + longTitle, longRow.label());
        assertEquals(longTitle.length() + "[IP] ".length(), longRow.pipelineLabel().length());
    }

    @Test
    public void storedStageWinsWhenRoleWouldDeriveADifferentStage() {
        assertEquals("design", row("diverging", "pm", false, "in-progress", "design").effectiveStage());
        assertEquals("requirements", row("diverging", "pm", false, "in-progress", null)
                .effectiveStage());
        assertNull(row("no stage unknown role", "research", false, "in-progress", null)
                .effectiveStage());
    }

    @Test
    public void pointsLabelIsThePlainNumber() {
        assertEquals("3", new TicketRow("T-1", "t", "pm", 3, null, false, null,
                "done", null).pointsLabel());
        assertEquals("0", new TicketRow("T-1", "t", "pm", 0, null, false, null,
                "done", null).pointsLabel());
    }
}
