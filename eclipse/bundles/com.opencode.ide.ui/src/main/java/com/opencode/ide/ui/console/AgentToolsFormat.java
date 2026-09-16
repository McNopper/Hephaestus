package com.opencode.ide.ui.console;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.opencode.ide.tools.ToolInvocation;

/**
 * Renders one {@link ToolInvocation} as the text block appended to the
 * "Agent Tools" console. SWT-free and JFace-free on purpose so it is
 * unit-testable without a {@code Display} (see {@code AgentToolsFormatTest}
 * in {@code com.opencode.ide.ui.tests}); the console glue
 * ({@link AgentToolsConsole}) is a thin adapter over this format and holds
 * no formatting logic of its own.
 *
 * <p>Block shape — one header line, one arguments line, then the (already
 * tail-truncated by the seam) output verbatim, or a one-line failure note:</p>
 * <pre>
 * [14:32:07] cmake_build (3.4s)
 *   args: build_dir=C:\b, target=app
 *   [ 50%] Building CXX object ...
 * </pre>
 *
 * <p>Failures are visually loud: a dispatch-level failure (the provider
 * threw; the agent got a JSON-RPC error) prints {@code ! FAILED} on the
 * header plus the failure message, an MCP {@code isError} result prints
 * {@code ! ERROR} — the two flavours the {@code ToolInvocation} record
 * distinguishes.</p>
 *
 * <p>All methods tolerate {@code null} arguments and never throw; blocks
 * end with a trailing newline so appending blocks back-to-back yields a
 * clean log.</p>
 */
public final class AgentToolsFormat {

    /** Timestamp of the invocation start, wall-clock (matches the other views' local-time display). */
    public static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private AgentToolsFormat() {
    }

    /**
     * Renders the full console block for one invocation, terminated by a
     * newline. {@code null} renders an empty string (the glue skips it).
     */
    public static String block(ToolInvocation invocation) {
        if (invocation == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(header(invocation)).append('\n');
        sb.append("  args: ").append(invocation.argumentsSummary()).append('\n');
        if (invocation.dispatchFailure()) {
            sb.append("  failed: ").append(invocation.failure()).append('\n');
        } else if (!invocation.output().isEmpty()) {
            String output = invocation.output();
            sb.append(indent(output));
            if (!output.endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The one-line header: {@code [HH:mm:ss] tool (duration)}, with a loud
     * {@code ! FAILED} / {@code ! ERROR} marker for the two failure
     * flavours.
     */
    public static String header(ToolInvocation invocation) {
        if (invocation == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(TIME_FORMAT.format(Instant.ofEpochMilli(invocation.startedAtMillis())))
                .append("] ");
        if (invocation.dispatchFailure()) {
            sb.append("! FAILED ");
        } else if (invocation.errorResult()) {
            sb.append("! ERROR ");
        }
        sb.append(invocation.tool());
        sb.append(" (").append(duration(invocation.durationMillis())).append(')');
        return sb.toString();
    }

    /**
     * Human duration: {@code 123ms} under a second, {@code 3.4s} above —
     * build and debug runs live in the seconds range, quick calls read
     * better in milliseconds.
     */
    public static String duration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        if (millis < 1000) {
            return millis + "ms";
        }
        return (millis / 1000) + "." + (millis % 1000 / 100) + "s";
    }

    /**
     * Indents every output line by two spaces so the verbatim tool output
     * is visually nested under its header. Empty renders empty.
     */
    public static String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            if (end > start) {
                sb.append("  ").append(text, start, end);
            }
            if (nl < 0) {
                break;
            }
            sb.append('\n');
            start = nl + 1;
        }
        return sb.toString();
    }
}
