package com.opencode.ide.ui.session;

/**
 * Pure formatting for v2 service payloads shown in read-only dialogs
 * (session log, session stats, controlled terminal). Wave A (2026-09-25):
 * the service's data is rendered as-is - no interpretation, no message-list
 * math. Unit-tested in {@code ServiceTextTest}.
 */
public final class ServiceText {

    private ServiceText() {
    }

    /**
     * {@code PersistentPty.ReadResult} -&gt; the rendered screen with its
     * context header. An empty input means the session has no controlled
     * terminal.
     */
    public static String terminal(java.util.Map<String, Object> readResult) {
        if (readResult == null || readResult.isEmpty()) {
            return "(no controlled terminal for this session)";
        }
        StringBuilder out = new StringBuilder();
        appendLine(out, "title", readResult.get("title"));
        appendLine(out, "cwd", readResult.get("cwd"));
        appendLine(out, "foreground", readResult.get("foregroundProcess"));
        Object screen = readResult.get("screen");
        if (screen != null) {
            out.append('\n').append(screen);
        }
        return out.toString();
    }

    /** Plugin rows (v2 {@code Plugin.Info}) -&gt; one line per plugin (id, source, state). */
    public static String plugins(java.util.List<java.util.Map<String, Object>> infos) {
        if (infos == null || infos.isEmpty()) {
            return "(no plugins installed)";
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map<String, Object> info : infos) {
            out.append(info.getOrDefault("id", "(unnamed)"));
            Object source = info.get("source");
            if (source != null) {
                out.append(" [").append(source).append(']');
            }
            Object state = info.get("state");
            if (state != null) {
                out.append(" - ").append(state);
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** A list of flat maps -&gt; one blank-line-separated {@code key: value} block per entry. */
    public static String list(java.util.List<java.util.Map<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map<String, Object> entry : entries) {
            out.append(keyValues(entry)).append("\n\n");
        }
        return out.toString().stripTrailing();
    }

    /** Any flat map -&gt; one {@code key: value} line per entry (session stats). */
    public static String keyValues(java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "(no data)";
        }
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
            appendLine(out, entry.getKey(), entry.getValue());
        }
        return out.toString().stripTrailing();
    }

    private static void appendLine(StringBuilder out, String key, Object value) {
        if (value != null) {
            out.append(key).append(": ").append(value).append('\n');
        }
    }
}
