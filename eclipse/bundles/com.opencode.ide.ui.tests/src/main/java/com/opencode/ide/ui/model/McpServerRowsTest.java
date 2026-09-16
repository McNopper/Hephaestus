package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.ui.model.McpServerRows.Row;

/**
 * Unit tests for {@link McpServerRows} (the SWT-free row building behind
 * the MCP servers dialog, Batch C of U-002): no SWT, no HTTP.
 */
public class McpServerRowsTest {

    @Test
    public void rowsSortCaseInsensitivelyAndSkipUnusableEntries() {
        List<Row> rows = McpServerRows.rows(Arrays.asList(
                null,
                new McpServerInfo(null, "connected"),
                new McpServerInfo("   ", "connected"),
                new McpServerInfo("zeta", null),
                new McpServerInfo("eclipse-build", "connected"),
                new McpServerInfo("Graphics", "error")));

        assertEquals(List.of(
                new Row("eclipse-build", "connected"),
                new Row("Graphics", "error"),
                new Row("zeta", null)), rows);
    }

    @Test
    public void nullListYieldsEmptyRows() {
        assertTrue(McpServerRows.rows(null).isEmpty());
    }

    @Test
    public void missingStatusReadsAsUnknown() {
        assertEquals("unknown", new Row("a", null).statusLabel());
        assertEquals("unknown", new Row("a", "  ").statusLabel());
        assertEquals("connected", new Row("a", "connected").statusLabel());
    }

    @Test
    public void dialogTextListsSortedServersWithStatus() {
        String text = McpServerRows.dialogText("primary",
                List.of(new McpServerInfo("zeta", "connected"), new McpServerInfo("eclipse-build", "connected")));

        assertTrue(text.startsWith("2 MCP servers are registered with primary:"));
        assertTrue(text.contains("eclipse-build  \u2022  connected"));
        assertTrue(text.contains("zeta  \u2022  connected"));
        // sorted: eclipse-build row above zeta
        assertTrue(text.indexOf("eclipse-build") < text.indexOf("zeta"));
    }

    @Test
    public void dialogTextUsesSingularForOneServer() {
        String text = McpServerRows.dialogText("primary", List.of(new McpServerInfo("tasks", "connected")));

        assertTrue(text.startsWith("1 MCP server is registered with primary:"));
    }

    @Test
    public void dialogTextForEmptyListAndMissingLabel() {
        assertEquals("No MCP servers are registered with this server.",
                McpServerRows.dialogText(null, List.of()));
        assertEquals("No MCP servers are registered with remote.",
                McpServerRows.dialogText("remote", null));
    }
}
