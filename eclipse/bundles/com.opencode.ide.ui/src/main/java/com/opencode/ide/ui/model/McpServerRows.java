package com.opencode.ide.ui.model;

import java.util.Comparator;
import java.util.List;

import com.opencode.ide.client.model.McpServerInfo;

/**
 * Rows for the "MCP servers" details dialog (Batch C of U-002): converts the
 * client's {@code GET /mcp} list into sorted, null-tolerant display rows.
 * SWT-free so the mapping is unit-testable; the dialog itself only renders.
 *
 * <p>The evaluation matrix asked for name + transport type per row, but the
 * pinned server (1.18.30) answers {@code GET /mcp} with a {@code {name:
 * {"status": …}}} map only — status (e.g. {@code connected}) is the one
 * per-server detail on the wire (see {@code HttpOpencodeClient} and
 * {@link McpServerInfo}), so that is what a row shows. Revisit if a newer
 * pin exposes the transport.</p>
 */
public final class McpServerRows {

    private McpServerRows() {
    }

    /**
     * @param servers the client's list (may be {@code null}; {@code null} or
     *                unnamed entries are skipped — the view's lenient loads
     *                can produce them)
     * @return rows sorted by name (case-insensitive) — stable for the dialog
     */
    public static List<Row> rows(List<McpServerInfo> servers) {
        if (servers == null) {
            return List.of();
        }
        return servers.stream()
                .filter(server -> server != null && server.id() != null && !server.id().isBlank())
                .map(server -> new Row(server.id(), server.status()))
                .sorted(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The dialog body for one opencode server: a count line plus one
     * {@code name  •  status} line per row, or a plain "none registered"
     * sentence for an empty list. Pure — unit-testable.
     *
     * @param serverLabel the server node's label (null/blank renders as
     *                    {@code "this server"})
     */
    public static String dialogText(String serverLabel, List<McpServerInfo> servers) {
        List<Row> rows = rows(servers);
        String label = serverLabel == null || serverLabel.isBlank() ? "this server" : serverLabel.strip();
        if (rows.isEmpty()) {
            return "No MCP servers are registered with " + label + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(rows.size() == 1 ? " MCP server is" : " MCP servers are")
                .append(" registered with ").append(label).append(':').append("\n\n");
        for (Row row : rows) {
            sb.append(row.name()).append("  \u2022  ").append(row.statusLabel()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** One dialog row: the MCP server's registration name and its status. */
    public record Row(String name, String status) {

        /** Status for display; a missing/blank wire status reads as {@code unknown}. */
        public String statusLabel() {
            return status == null || status.isBlank() ? "unknown" : status;
        }
    }
}
