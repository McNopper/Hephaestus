package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for {@link TicketRow}'s stage handling: effectiveStage
 * derivation (stored stage wins, role fallback, nulls), the compact status
 * prefix legend, the type field mapping, and the null-safe label shapes.
 */
public class TicketRowStageTest {

    private static TicketRow row(String stage, String role) {
        return new TicketRow("T-001", "title", "task", role, 2, null, false, null, "in-progress", stage, "medium");
    }

    @Test
    public void storedStageWinsOverRole() {
        assertEquals("design", row("design", "tester").effectiveStage());
    }

    @Test
    public void nullStageFallsBackToRole() {
        assertEquals("implementation", row(null, "developer").effectiveStage());
        assertEquals("requirements", row(null, "pm").effectiveStage());
        assertEquals("architecture", row(null, "architect").effectiveStage());
        assertEquals("test-implementation", row(null, "tester").effectiveStage());
    }

    @Test
    public void unknownOrNullRoleYieldsNullEffectiveStage() {
        assertNull(row(null, "research").effectiveStage());
        assertNull(row(null, null).effectiveStage());
    }

    @Test
    public void fromMapsTheStageField() {
        Task task = new Task();
        task.id = "T-009";
        task.title = "staged";
        task.role = "developer";
        task.type = "bug";
        task.status = "in-progress";
        task.stage = "system";
        TicketRow mapped = TicketRow.from(task);

        assertEquals("system", mapped.stage());
        assertEquals("system", mapped.effectiveStage());
        assertEquals("bug", mapped.type());
        assertTrue(mapped.isBug());
        assertNull(TicketRow.from(null));
    }

    @Test
    public void statusPrefixCoversTheLegend() {
        assertEquals("[PB]", TicketRow.statusPrefix("product-backlog"));
        assertEquals("[SB]", TicketRow.statusPrefix("sprint-backlog"));
        assertEquals("[IP]", TicketRow.statusPrefix("in-progress"));
        assertEquals("[IR]", TicketRow.statusPrefix("in-review"));
        assertEquals("[D]", TicketRow.statusPrefix("done"));
        assertEquals("", TicketRow.statusPrefix("mystery"));
        assertEquals("", TicketRow.statusPrefix(null));
    }

    @Test
    public void pipelineLabelIsCompact() {
        TicketRow blocked = new TicketRow("T-1", "do things", "bug", "developer", 1, null,
                true, "why", "in-review", "design", "medium");
        assertEquals("[IR] [BLOCKED] [bug] do things", blocked.pipelineLabel());

        TicketRow plain = new TicketRow("T-2", "plain", "task", "developer", 1, null,
                false, null, "done", null, "medium");
        assertEquals("[D] [task] plain", plain.pipelineLabel());
    }

    @Test
    public void nullFieldsAreLabelSafe() {
        TicketRow empty = new TicketRow(null, null, null, null, 0, null, false, null, null, null, null);
        assertEquals("", empty.pipelineLabel());
        assertEquals("", empty.label());
        assertNull(empty.effectiveStage());
    }
}
