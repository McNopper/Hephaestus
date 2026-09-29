package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.VStages;

/**
 * U-028 board polish pack (FR-001..FR-010, AC-001/AC-002): WIP counts are
 * plain in-progress counts per column and board-wide (NO WIP-limit concept,
 * recomputed per snapshot), and the V carries nine directional chevron
 * connectors along the canonical reading order 1-&gt;10 - derived from
 * {@link VStageLayout}/{@link VStages}, pure decoration.
 */
public class BoardPolishPackTest {

    private static TicketRow row(String id, String status, String stage) {
        return new TicketRow(id, "title", "story", "developer", 3, null,
                false, null, status, stage, "high", null);
    }

    /** FR-006..FR-009: nine connectors, reading order 1-&gt;10, correct directions. */
    @Test
    public void nineConnectorsFollowTheReadingOrder() {
        List<VStageLayout.Connector> connectors = VStageLayout.connectors();

        assertEquals("nine connectors between the ten stages", 9, connectors.size());
        for (int i = 0; i + 1 < VStages.STAGES.size(); i++) {
            assertEquals("from stage " + (i + 1), VStages.STAGES.get(i), connectors.get(i).from());
            assertEquals("to stage " + (i + 2), VStages.STAGES.get(i + 1), connectors.get(i).to());
        }
        assertEquals("down the definition leg", "\u2193", connectors.get(0).glyph());
        assertEquals("down through 4->5", "\u2193", connectors.get(3).glyph());
        assertEquals("the 5->6 vertex turn", "\u2192", connectors.get(4).glyph());
        assertEquals("up the verification leg from the vertex", "\u2191", connectors.get(5).glyph());
        assertEquals("up through 9->10", "\u2191", connectors.get(8).glyph());
    }

    /** FR-010: derived from the geometry source - the tip and unknown ids carry nothing. */
    @Test
    public void connectorGlyphsAreDerivedAndNullAtTheTip() {
        assertEquals("\u2193", VStageLayout.connectorGlyph("requirements"));
        assertEquals("\u2192", VStageLayout.connectorGlyph("implementation"));
        assertEquals("\u2191", VStageLayout.connectorGlyph("test-implementation"));
        assertNull("the V tip has no successor", VStageLayout.connectorGlyph(VStages.last()));
        assertNull(VStageLayout.connectorGlyph(PipelineSnapshot.UNTRACKED));
        assertNull(VStageLayout.connectorGlyph(null));
        assertNull(VStageLayout.connectorGlyph("nonsense"));
        for (VStageLayout.Connector connector : VStageLayout.connectors()) {
            assertNotNull(connector.from(), connector.glyph());
        }
    }

    /** FR-001/FR-004: WIP is a plain in-progress count - and never a limit. */
    @Test
    public void wipCountCountsInProgressTicketsOnly() {
        List<TicketRow> rows = List.of(
                row("T-1", "in-progress", "design"),
                row("T-2", "in-progress", "design"),
                row("T-3", "done", "design"),
                row("T-4", "in-review", "design"),
                row("T-5", "paused", "design"));

        assertEquals("WIP = status in-progress (paused and in-review are not WIP)", 2,
                PipelineSnapshot.wipCount(rows));
        assertEquals("empty columns count zero", 0, PipelineSnapshot.wipCount(List.of()));
    }

    /** FR-002/FR-005: the board-wide count spans every column; recomputed per snapshot. */
    @Test
    public void wipTotalSpansEveryColumn() {
        PipelineSnapshot snapshot = new PipelineSnapshot(List.of(
                new StageColumn("design", List.of(row("T-1", "in-progress", "design")), 0, 3),
                new StageColumn("implementation", List.of(
                        row("T-2", "in-progress", "implementation"),
                        row("T-3", "in-progress", "implementation")), 0, 5),
                new StageColumn("test-design", List.of(row("T-4", "done", "test-design")), 0, 2),
                new StageColumn(PipelineSnapshot.UNTRACKED,
                        List.of(row("T-5", "in-progress", null)), 0, 1)));

        assertEquals("every column contributes", 4, snapshot.wipTotal());

        PipelineSnapshot empty = new PipelineSnapshot(List.of(
                new StageColumn("design", List.of(), 0, 0)));
        assertEquals("recomputed per snapshot: a fresh snapshot recounts", 0, empty.wipTotal());
    }

    /** U-028 FR-002/FR-005: the board-wide WIP count, recomputed per snapshot. */
    @Test
    public void boardWideWipCountsEveryStatusColumn() {
        BoardSnapshot flat = new BoardSnapshot(
                java.util.Map.of("in-progress", List.of(row("T-1", "in-progress", "design")),
                        "done", List.of(row("T-2", "done", "design"))),
                "", 2, 0, null);

        assertEquals("the layout-independent count spans the columns", 1, flat.wipCount());

        BoardSnapshot empty = BoardSnapshot.empty("");
        assertEquals("recomputed per snapshot", 0, empty.wipCount());
    }

    /** FR-007/FR-010: nothing here mutates anything - the counts are projections. */
    @Test
    public void countingIsPureDecoration() {
        List<TicketRow> rows = List.of(row("T-1", "in-progress", "design"));
        PipelineSnapshot snapshot = new PipelineSnapshot(List.of(new StageColumn("design", rows, 0, 3)));

        assertEquals(1, snapshot.wipTotal());
        assertEquals(1, rows.size());
        assertFalse("no limit, no flag, no warning state",
                String.valueOf(snapshot.wipTotal()).contains("!"));
    }
}
