package com.opencode.ide.ui.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.opencode.ide.client.model.IntegrationInfo;
import com.opencode.ide.client.model.ProviderAuth;

/**
 * Pure connect-flow logic behind the Integrations dialog of the Server view
 * (U-048): maps the client's typed {@code GET /integration} catalog onto one
 * row per (integration, method) with the connect flow that method supports,
 * resolves the {@code methodID} the command/OAuth verbs post, tracks an
 * attempt's state transitions across the lenient status maps, and renders
 * outcomes (notices, attempt details, key masking) without ever inventing a
 * field.
 *
 * <p>SWT-free and JFace-free on purpose - records and static methods over
 * client model types and lenient maps only - so the whole flow model is
 * unit-testable without a {@code Display} (see {@code ConnectFlowsTest} in
 * {@code com.opencode.ide.ui.tests}). The dialog renders and drives the IO;
 * this class decides.</p>
 *
 * <p>Method ids: on the wire, command and OAuth methods carry a REQUIRED
 * {@code id} (v2.0.19 live probe: {@code openai} lists
 * {@code {id:"chatgpt-browser"}} and {@code {id:"chatgpt-headless"}}), but
 * the read-only client's typed catalog
 * ({@link IntegrationInfo.IntegrationMethod}) preserves only {@code type}
 * and {@code names} - the id is dropped. The only per-method identity this
 * model can derive is therefore the first {@code names} entry; when a method
 * carries none, the dialog asks the user for the id instead of guessing one
 * (a faked id is a guaranteed server rejection). Surfacing the wire id in
 * the typed catalog is a client-bundle change, outside this bundle.</p>
 */
public final class ConnectFlows {

    /**
     * The dialog's attempt poll cadence - the same rhythm the Server view's
     * busy poller uses; short enough to feel live, one trivial GET per tick.
     */
    public static final long POLL_INTERVAL_MILLIS = 2000L;

    /** The connect flow one auth method supports (drives the dialog's Connect action). */
    public enum Flow {
        KEY, COMMAND, OAUTH,
        /** No interactive connect exists for this method (env vars, unknown types). */
        NONE
    }

    /** Which attempt family a started connect belongs to (selects the poll and abort verbs). */
    public enum AttemptKind { COMMAND, OAUTH }

    /** A connect attempt's lifecycle; only {@link #PENDING} is worth polling. */
    public enum AttemptState { PENDING, COMPLETE, FAILED, EXPIRED, ABORTED }

    /**
     * One dialog row: one auth method of one integration, plus the flow it
     * supports and the method id the command/OAuth verbs need ({@code null}
     * when the typed catalog does not carry one - the dialog asks then).
     */
    public record MethodRow(String integrationId, String integrationName, String type,
            List<String> names, String label, int connections, Flow flow, String methodId) {

        /** Whether Connect does something for this row (env and unknown types read as no). */
        public boolean connectable() {
            return flow != Flow.NONE;
        }

        /** The Method cell: the wire label when present, else the catalog badge. */
        public String methodText() {
            if (label != null && !label.isBlank()) {
                return label.strip();
            }
            return IntegrationRows.methodBadge(type, names);
        }

        /** The Integration cell: {@code "id (name)"} or the bare id. */
        public String integrationText() {
            return integrationName == null || integrationName.isBlank()
                    ? integrationId : integrationId + " (" + integrationName.strip() + ")";
        }

        /** A copy with the wire label attached (see {@link #attachLabels}). */
        public MethodRow withLabel(String newLabel) {
            return new MethodRow(integrationId, integrationName, type, names,
                    newLabel, connections, flow, methodId);
        }
    }

    /**
     * One running or settled connect attempt: the poll handle, its state and
     * the honest rendering of the server's last answer. Immutable - every
     * poll produces a new value ({@link #on(Map)}); a settled attempt
     * ignores further statuses.
     */
    public record Attempt(AttemptKind kind, String integrationId, String attemptId,
            AttemptState state, String detail) {

        /** @return whether the attempt is still worth polling. */
        public boolean pending() {
            return state == AttemptState.PENDING;
        }

        /**
         * The next attempt state for a polled status map (lenient: unknown
         * or absent statuses keep the attempt pending - never a faked
         * verdict).
         */
        public Attempt on(Map<String, Object> status) {
            if (!pending()) {
                return this;
            }
            return new Attempt(kind, integrationId, attemptId,
                    stateOf(string(status, "status")), describe(status));
        }

        /** A settled copy - the abort and complete paths close an attempt locally. */
        public Attempt settled(AttemptState newState, String newDetail) {
            return new Attempt(kind, integrationId, attemptId, newState, newDetail);
        }
    }

    /** Outcome fields in rendering order; whatever else exists renders after them. */
    private static final List<String> RENDER_ORDER = List.of(
            "status", "exitCode", "message", "connectionID", "url", "code", "command", "mode",
            "instructions");

    /** Housekeeping members that never carry an outcome line. */
    private static final Set<String> NOT_OUTCOME = Set.of("attemptID", "time", "location");

    /** The constant echo of a secret: bullets only, so not even its length leaks. */
    private static final String MASK = "\u2022\u2022\u2022";

    private ConnectFlows() {
    }

    // ---------- catalog -> rows ----------

    /**
     * One row per (integration, method), integrations sorted by id
     * (case-insensitive, the {@link IntegrationRows} order), methods in
     * catalog order. Entries without a usable integration id are skipped.
     */
    public static List<MethodRow> methodRows(List<IntegrationInfo> integrations) {
        List<MethodRow> rows = new ArrayList<>();
        for (IntegrationInfo integration : IntegrationRows.usable(integrations)) {
            List<IntegrationInfo.IntegrationMethod> methods =
                    integration.methods() == null ? List.of() : integration.methods();
            for (IntegrationInfo.IntegrationMethod method : methods) {
                if (method == null) {
                    continue;
                }
                Flow flow = flowOf(method.type());
                rows.add(new MethodRow(integration.id(), integration.name(), method.type(),
                        method.names(), null, Math.max(0, integration.connections()), flow,
                        flow == Flow.COMMAND || flow == Flow.OAUTH ? methodIdOf(method) : null));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The method id the command/OAuth verbs post as {@code methodID}: the
     * first non-blank {@code names} entry - the only per-method identity the
     * typed catalog preserves (see the class comment) - or {@code null} when
     * the method carries none (the dialog asks the user then).
     */
    public static String methodIdOf(IntegrationInfo.IntegrationMethod method) {
        if (method == null || method.names() == null) {
            return null;
        }
        for (String name : method.names()) {
            if (name != null && !name.isBlank()) {
                return name.strip();
            }
        }
        return null;
    }

    /**
     * Fills the wire labels from {@code getProviderAuths()} into matching
     * rows: auth entries and methods flatten the same catalog array, so per
     * (integration, type) they pair up positionally. Rows without a match
     * keep a {@code null} label; a null/empty auth list returns the rows
     * unchanged.
     */
    public static List<MethodRow> attachLabels(List<MethodRow> rows, List<ProviderAuth> auths) {
        if (rows == null || rows.isEmpty() || auths == null || auths.isEmpty()) {
            return rows == null ? List.of() : rows;
        }
        Map<ProviderType, ArrayDeque<String>> labels = new LinkedHashMap<>();
        for (ProviderAuth auth : auths) {
            if (auth == null || auth.provider() == null || auth.type() == null) {
                continue;
            }
            labels.computeIfAbsent(new ProviderType(auth.provider(), auth.type()),
                    key -> new ArrayDeque<>()).add(auth.label());
        }
        List<MethodRow> out = new ArrayList<>(rows.size());
        for (MethodRow row : rows) {
            ArrayDeque<String> pending = labels.get(new ProviderType(row.integrationId(), row.type()));
            out.add(pending == null || pending.isEmpty() ? row : row.withLabel(pending.poll()));
        }
        return List.copyOf(out);
    }

    // ---------- attempts ----------

    /**
     * The attempt a connect verb started: reads the lenient answer's
     * {@code attemptID} (the poll and abort handle). An answer without one
     * cannot be polled and reads as FAILED with an honest detail - never a
     * faked pending.
     */
    public static Attempt started(AttemptKind kind, String integrationId, Map<String, Object> answer) {
        String attemptId = string(answer, "attemptID");
        String detail = describe(answer);
        if (attemptId == null || attemptId.isBlank()) {
            return new Attempt(kind, integrationId, null, AttemptState.FAILED,
                    "the server's answer carried no attempt id"
                            + (detail.isBlank() ? "" : " - " + detail));
        }
        return new Attempt(kind, integrationId, attemptId, AttemptState.PENDING, detail);
    }

    /**
     * The feedback line for a settled attempt: its verdict plus the server's
     * last answer (whatever fields it carried).
     */
    public static String outcomeNotice(Attempt attempt) {
        String verdict = switch (attempt.state()) {
            case COMPLETE -> "connected";
            case FAILED -> "connect failed";
            case EXPIRED -> "connect attempt expired";
            case ABORTED -> "connect cancelled";
            default -> "connect attempt ended";   // PENDING never reaches here
        };
        String detail = attempt.detail();
        return detail == null || detail.isBlank() ? verdict : verdict + " - " + detail;
    }

    /**
     * The honest rendering of a lenient attempt map: every scalar field it
     * carries, the known ones first ({@code status}, {@code exitCode}, …)
     * and any others after - nothing when it carries nothing. Never invents
     * a field; housekeeping members ({@code attemptID}, {@code time}) stay
     * out.
     */
    public static String describe(Map<String, Object> attempt) {
        if (attempt == null || attempt.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : RENDER_ORDER) {
            appendField(sb, key, attempt.get(key));
        }
        for (Map.Entry<String, Object> entry : attempt.entrySet()) {
            if (!RENDER_ORDER.contains(entry.getKey()) && !NOT_OUTCOME.contains(entry.getKey())) {
                appendField(sb, entry.getKey(), entry.getValue());
            }
        }
        return sb.toString();
    }

    /** Lenient status classification; unknown spellings stay PENDING (keep polling). */
    private static AttemptState stateOf(String status) {
        if (status == null || status.isBlank()) {
            return AttemptState.PENDING;
        }
        return switch (status.strip().toLowerCase(Locale.ROOT)) {
            case "complete", "completed", "done", "succeeded", "success", "connected" ->
                AttemptState.COMPLETE;
            case "failed", "failure", "error" -> AttemptState.FAILED;
            case "expired", "timeout", "timed out" -> AttemptState.EXPIRED;
            case "aborted", "cancelled", "canceled" -> AttemptState.ABORTED;
            default -> AttemptState.PENDING;   // "pending" and anything unrecognized: keep polling
        };
    }

    // ---------- key flow ----------

    /**
     * The notice after a key connect: the created connection as the server
     * described it - never the key material (it must not appear in any UI
     * text).
     */
    public static String keyNotice(Map<String, Object> connection) {
        String id = string(connection, "id");
        String type = string(connection, "type");
        String label = string(connection, "label");
        if (id == null && type == null && label == null) {
            return "connected (the server accepted the key)";
        }
        StringBuilder sb = new StringBuilder("connected");
        if (id != null && !id.isBlank()) {
            sb.append(": ").append(id.strip());
        }
        if (type != null && !type.isBlank()) {
            sb.append(" (").append(type.strip()).append(')');
        }
        if (label != null && !label.isBlank()) {
            sb.append(" - ").append(label.strip());
        }
        return sb.toString();
    }

    /**
     * The echo of a secret: a constant bullet mask. The secret is never
     * inspected - not its length, not its characters - so no fragment of it
     * can leak into a label.
     */
    public static String mask(String secret) {
        return MASK;
    }

    // ---------- lenient map access ----------

    /** A lenient map's string member ({@code null} when absent or not a string). */
    public static String string(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof String text ? text : null;
    }

    private static void appendField(StringBuilder sb, String key, Object value) {
        if (value instanceof String text) {
            if (!text.isBlank()) {
                append(sb, key + ": " + text.strip());
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            append(sb, key + ": " + value);
        }
        // nested objects (time, location) and nulls carry no outcome line
    }

    private static void append(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append("  \u2022  ");
        }
        sb.append(text);
    }

    private static Flow flowOf(String type) {
        if (type == null || type.isBlank()) {
            return Flow.NONE;
        }
        return switch (type.strip().toLowerCase(Locale.ROOT)) {
            case "key" -> Flow.KEY;
            case "command" -> Flow.COMMAND;
            case "oauth" -> Flow.OAUTH;
            default -> Flow.NONE;   // env (and unknown types): no connect route exists
        };
    }

    /** The label-join key: one queue of labels per (integration, method type). */
    private record ProviderType(String provider, String type) {
    }
}
