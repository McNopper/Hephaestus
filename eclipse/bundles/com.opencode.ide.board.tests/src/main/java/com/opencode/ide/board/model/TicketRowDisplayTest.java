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
 * the absence of title truncation, the stored-vs-derived stage precedence
 * with a diverging role, and the U-005 type badges — bug distinct from the
 * neutral story/task/spike tags, and the title itself stays verbatim (the
 * type tag decorates the labels, it must never leak into the title string).
 */
public class TicketRowDisplayTest {

    private static TicketRow row(String title, String type, String role, boolean blocked,
            String status, String stage) {
        return new TicketRow("T-001", title, type, role, 2, null, blocked,
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
        assertEquals("[IR] [BLOCKED] rework", row("rework", null, "developer", true, "in-review", "design")
                .pipelineLabel());
        assertEquals("[D] shipped", row("shipped", null, "developer", false, "done", null)
                .pipelineLabel());
        assertEquals("[IP] plain", row("plain", null, "pm", false, "in-progress", "requirements")
                .pipelineLabel());
    }

    @Test
    public void pipelineLabelBlockedWithoutKnownStatusIsJustTheBlockedTag() {
        assertEquals("[BLOCKED] odd one", row("odd one", null, "research", true, "mystery", null)
                .pipelineLabel());
        assertEquals("[BLOCKED] no status", row("no status", null, "developer", true, null, null)
                .pipelineLabel());
    }

    @Test
    public void pipelineLabelSkipsMissingPiecesWithoutStraySpaces() {
        assertEquals("[IR] [BLOCKED]", row(null, null, "developer", true, "in-review", "design")
                .pipelineLabel());
        assertEquals("[D]", row("  ", null, "developer", true, "done", null)
                .pipelineLabel());
    }

    @Test
    public void doneRowsNeverRenderBlockedEvenWithLegacyDrift() {
        TicketRow drifted = row("shipped long ago", null, "developer", true, "done", "test-system");
        assertFalse(drifted.displayBlocked());
        assertEquals("T-001 shipped long ago", drifted.label());
        assertEquals("[D] shipped long ago", drifted.pipelineLabel());
    }

    @Test
    public void displayBlockedStaysTrueForNonDoneStatuses() {
        assertTrue(row("wip", null, "developer", true, "in-progress", null).displayBlocked());
        assertTrue(row("review", null, "developer", true, "in-review", null).displayBlocked());
        assertTrue(row("odd", null, "research", true, "mystery", null).displayBlocked());
        assertFalse(row("plain", null, "developer", false, "in-progress", null).displayBlocked());
    }

    @Test
    public void pipelineLabelTrimsSurroundingTitleWhitespace() {
        TicketRow padded = new TicketRow("T-9", "  padded  ", null, "developer", 1, null,
                false, null, "in-progress", null);
        assertEquals("[IP] padded", padded.pipelineLabel());
    }

    @Test
    public void labelShapesWithAndWithoutBlocked() {
        assertEquals("[BLOCKED] T-001 fix it",
                row("fix it", null, "developer", true, "in-progress", "design").label());
        assertEquals("T-001 plain", row("plain", null, "developer", false, "done", null).label());
        assertEquals("T-1", new TicketRow("T-1", null, null, "pm", 1, null, false, null,
                "product-backlog", null).label());
    }

    @Test
    public void labelDoesNotTruncateLongTitles() {
        String longTitle = "x".repeat(500);
        TicketRow longRow = new TicketRow("T-42", longTitle, null, "developer", 3, null,
                false, null, "in-progress", "design");

        assertEquals("T-42 " + longTitle, longRow.label());
        assertEquals(longTitle.length() + "[IP] ".length(), longRow.pipelineLabel().length());
    }

    @Test
    public void storedStageWinsWhenRoleWouldDeriveADifferentStage() {
        assertEquals("design", row("diverging", null, "pm", false, "in-progress", "design").effectiveStage());
        assertEquals("requirements", row("diverging", null, "pm", false, "in-progress", null)
                .effectiveStage());
        assertNull(row("no stage unknown role", null, "research", false, "in-progress", null)
                .effectiveStage());
    }

    @Test
    public void pointsLabelIsThePlainNumber() {
        assertEquals("3", new TicketRow("T-1", "t", "task", "pm", 3, null, false, null,
                "done", null).pointsLabel());
        assertEquals("0", new TicketRow("T-1", "t", "task", "pm", 0, null, false, null,
                "done", null).pointsLabel());
    }

    // -- U-005: type badges ---------------------------------------------

    @Test
    public void typeTagCoversTheFourValidTypesUnknownAndNull() {
        assertEquals("[bug]", TicketRow.typeTag("bug"));
        assertEquals("[story]", TicketRow.typeTag("story"));
        assertEquals("[task]", TicketRow.typeTag("task"));
        assertEquals("[spike]", TicketRow.typeTag("spike"));
        // hand-edited frontmatter may carry anything: bracketed verbatim,
        // never hidden — but null/blank (legacy rows) reads as no tag
        assertEquals("[feature]", TicketRow.typeTag("feature"));
        assertEquals("", TicketRow.typeTag(null));
        assertEquals("", TicketRow.typeTag(""));
        assertEquals("", TicketRow.typeTag("   "));
    }

    @Test
    public void bugRowsCarryTheTypeTagInBothLayouts() {
        TicketRow bug = row("crash on start", "bug", "developer", false, "in-progress", "design");
        assertTrue(bug.isBug());
        assertEquals("[IP] [bug] crash on start", bug.pipelineLabel());
        assertEquals("T-001 [bug] crash on start", bug.label());
    }

    @Test
    public void storyTaskSpikeGetNeutralTagsAndAreNotBugs() {
        TicketRow story = row("as a user", "story", "pm", false, "product-backlog", null);
        TicketRow task = row("do work", "task", "developer", false, "sprint-backlog", null);
        TicketRow spike = row("probe it", "spike", "architect", false, "in-progress", null);

        assertEquals("[story]", story.typeTag());
        assertEquals("[task]", task.typeTag());
        assertEquals("[spike]", spike.typeTag());
        assertFalse(story.isBug());
        assertFalse(task.isBug());
        assertFalse(spike.isBug());
        assertFalse(story.label().contains("[bug]"));
        assertFalse(task.label().contains("[bug]"));
        assertFalse(spike.label().contains("[bug]"));
        // neutral types render uncolored: they carry no bug tag anywhere
        assertEquals("[PB] [story] as a user", story.pipelineLabel());
        assertEquals("[SB] [task] do work", task.pipelineLabel());
    }

    @Test
    public void titleStaysVerbatimTheTypeTagOnlyDecoratesTheLabels() {
        String title = "Fix: crash [bug] in parser"; // brackets in the title itself
        TicketRow bug = row(title, "bug", "developer", false, "in-progress", null);

        assertEquals(title, bug.title()); // the stored title is untouched
        // the decoration is a prefix on the rendered label; the title's own
        // "[bug]" text stays part of the title, the decoration separate
        assertEquals("[IP] " + "[bug] " + title, bug.pipelineLabel());
        assertEquals("T-001 [bug] " + title, bug.label());
    }

    @Test
    public void bugDecorationStacksWithBlocked() {
        TicketRow blockedBug = row("hotfix", "bug", "developer", true, "in-review", "design");
        assertTrue(blockedBug.displayBlocked()); // keeps the bold red blocked rendering
        assertTrue(blockedBug.isBug()); // ...plus the bug accent
        assertEquals("[IR] [BLOCKED] [bug] hotfix", blockedBug.pipelineLabel());
        assertEquals("[BLOCKED] T-001 [bug] hotfix", blockedBug.label());
    }

    @Test
    public void missingTypeRendersUntaggedWithoutStraySpaces() {
        TicketRow legacy = row("old ticket", null, "developer", false, "in-progress", null);
        assertEquals("", legacy.typeTag());
        assertEquals("[IP] old ticket", legacy.pipelineLabel());
        assertEquals("T-001 old ticket", legacy.label());
    }
}
