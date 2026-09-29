package com.opencode.ide.ui.model;

import java.util.Comparator;
import java.util.List;

import com.opencode.ide.client.model.IntegrationInfo;

/**
 * Rows for the "Integrations" surface of the Server view (U-046 slice 2,
 * extended by U-048): converts the client's typed {@code GET /integration}
 * catalog into sorted, null-tolerant display rows (id, name, methods as
 * key/env badges, connection count) and the per-method badge shared with the
 * connect dialog. SWT-free so the mapping is unit-testable; the dialogs only
 * render. The connect flows themselves live in {@link ConnectFlows} and the
 * Integrations dialog.
 */
public final class IntegrationRows {

    private IntegrationRows() {
    }

    /**
     * @param integrations the client's list (may be {@code null}; entries
     *                     without a usable id are skipped)
     * @return rows sorted by id (case-insensitive) - stable for the dialog
     */
    public static List<Row> rows(List<IntegrationInfo> integrations) {
        return usable(integrations).stream()
                .map(integration -> new Row(integration.id(),
                        integration.name(),
                        badges(integration.methods()),
                        Math.max(0, integration.connections())))
                .toList();
    }

    /**
     * The catalog's usable entries (non-null, id present), sorted by id
     * case-insensitively - the shared derivation behind the summary rows and
     * the connect dialog's per-method rows, so both order integrations
     * alike.
     */
    public static List<IntegrationInfo> usable(List<IntegrationInfo> integrations) {
        if (integrations == null) {
            return List.of();
        }
        return integrations.stream()
                .filter(integration -> integration != null
                        && integration.id() != null && !integration.id().isBlank())
                .sorted(Comparator.comparing(IntegrationInfo::id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The dialog body for one opencode server: a count line plus one line per
     * integration, or the empty-state sentence. Pure - unit-testable.
     *
     * @param serverLabel the server node's label (null/blank renders as
     *                    {@code "this server"})
     */
    public static String dialogText(String serverLabel, List<IntegrationInfo> integrations) {
        List<Row> rows = rows(integrations);
        String label = serverLabel == null || serverLabel.isBlank() ? "this server" : serverLabel.strip();
        if (rows.isEmpty()) {
            return "No integrations are available on " + label + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(rows.size() == 1 ? " integration is" : " integrations are")
                .append(" available on ").append(label).append(':').append("\n\n");
        for (Row row : rows) {
            sb.append(row.id());
            if (row.nameLabel() != null) {
                sb.append(" (").append(row.nameLabel()).append(')');
            }
            sb.append("  \u2022  ").append(row.badgesLabel()).append("  \u2022  ")
                    .append(row.connectionsLabel()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** {@code key/env} method badges -> {@code "key: A, B  •  env: C"}; empty methods read as {@code no auth methods}. */
    private static List<String> badges(List<IntegrationInfo.IntegrationMethod> methods) {
        if (methods == null) {
            return List.of();
        }
        return methods.stream()
                .filter(method -> method != null && method.type() != null && !method.type().isBlank())
                .map(method -> methodBadge(method.type(), method.names()))
                .toList();
    }

    /**
     * The badge of ONE auth method: {@code "type: A, B"} ({@code "(any)"} when
     * it carries no names). Shared by the catalog summary above and the
     * connect dialog's per-method rows ({@link ConnectFlows}) so both render
     * methods alike; a blank type degrades to {@code "(no type)"}.
     */
    public static String methodBadge(String type, List<String> names) {
        String kind = type == null || type.isBlank() ? "(no type)" : type.strip();
        return kind + ": " + joinNames(names);
    }

    private static String joinNames(List<String> names) {
        if (names == null) {
            return "(any)";
        }
        List<String> usable = names.stream().filter(name -> name != null && !name.isBlank()).toList();
        return usable.isEmpty() ? "(any)" : String.join(", ", usable);
    }

    /** One dialog row: the integration id/name, its method badges and the connection count. */
    public record Row(String id, String name, List<String> badges, int connections) {

        /** Display name; {@code null} when the wire carried none. */
        public String nameLabel() {
            return name == null || name.isBlank() ? null : name;
        }

        /** The badge line: {@code "key: A  •  env: B"} or {@code no auth methods}. */
        public String badgesLabel() {
            return badges == null || badges.isEmpty() ? "no auth methods" : String.join("  \u2022  ", badges);
        }

        /** The connection count, pluralized. */
        public String connectionsLabel() {
            return connections + (connections == 1 ? " connection" : " connections");
        }
    }
}
