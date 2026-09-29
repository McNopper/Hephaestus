package com.opencode.ide.ui.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure KEY=VALUE handling for the session environment dialog (U-048): the
 * wire has NO read route for the environment — {@code PUT
 * /api/session/{id}/environment} FULLY REPLACES the variable map — so the
 * dialog is a plain text editor of {@code KEY=VALUE} lines and this class is
 * its whole model. SWT-free and unit-testable; the dialog only renders.
 */
public final class EnvironmentText {

    private EnvironmentText() {
    }

    /**
     * @param variables the map to render (may be {@code null})
     * @return one {@code KEY=VALUE} line per entry, sorted by key (stable
     *         round-trip: an untouched dialog parses back to the same map)
     */
    public static String format(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        variables.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey()).append('=')
                        .append(entry.getValue() == null ? "" : entry.getValue()).append('\n'));
        return sb.toString().stripTrailing();
    }

    /**
     * @param text the dialog's edited text
     * @return the parsed map (insertion order kept); blank lines and
     *         {@code #} comments are skipped
     * @throws IllegalArgumentException naming the first invalid line — the
     *                                  dialog shows it instead of saving a
     *                                  silently-wrong map
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return variables;
        }
        int lineNumber = 0;
        for (String line : text.split("\\R")) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(
                        "line " + lineNumber + " is not KEY=VALUE: " + trimmed);
            }
            String key = trimmed.substring(0, separator).strip();
            String value = trimmed.substring(separator + 1).strip();
            if (key.isBlank() || !key.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException(
                        "line " + lineNumber + " has an invalid variable name: " + key);
            }
            variables.put(key, value);
        }
        return variables;
    }
}
