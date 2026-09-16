package com.opencode.ide.tools;

/**
 * One completed {@code tools/call} as reported to the
 * {@link ToolInvocationListener}s registered with {@link ToolInvocationHub}.
 * The {@link McpDispatcher} publishes one invocation per routed call —
 * including unknown tool names, MCP error results and dispatch-level
 * failures — so a listener sees exactly the agent-visible tool traffic.
 *
 * <p>This is the visibility seam for UIs: the full output text already goes
 * back to the agent over the MCP transport; this record deliberately carries
 * only the <em>tail</em> (see {@link #MAX_OUTPUT_CHARS}) to keep listeners
 * cheap. The record is SWT-free, OSGi-free and immutable, and the factories
 * apply the truncation themselves so no caller can forget it.</p>
 *
 * <p>Two failure flavours are distinguished:</p>
 * <ul>
 *   <li>{@code errorResult=true, failure=null} — the provider returned an
 *       MCP {@code isError} result (domain problem; the agent receives the
 *       explanatory text).</li>
 *   <li>{@code failure != null} — dispatch-level failure: the provider threw
 *       ({@link ParamError} or any runtime exception); the agent receives a
 *       JSON-RPC error instead of a tool result.</li>
 * </ul>
 *
 * @param sequence         per-JVM monotonic id (from
 *                         {@link ToolInvocationHub#nextSequence()}); gaps
 *                         are possible when no listener cares, order is not
 * @param startedAtMillis  epoch millis when the dispatcher handed the call to
 *                         the provider
 * @param durationMillis   wall time the provider call took (0 for unrouted
 *                         calls such as unknown tool names)
 * @param tool             the MCP tool name (never blank)
 * @param argumentsSummary compact one-line rendering of the arguments (from
 *                         {@link ToolInvocationHub#summarizeArguments}),
 *                         never {@code null}; "(no arguments)" when empty
 * @param output           the tail of the tool result text (truncated to
 *                         {@link #MAX_OUTPUT_CHARS}), empty for
 *                         dispatch-level failures
 * @param errorResult      the MCP {@code isError} flag of the result
 * @param failure          dispatch-level failure message, or {@code null}
 */
public record ToolInvocation(
        long sequence,
        long startedAtMillis,
        long durationMillis,
        String tool,
        String argumentsSummary,
        String output,
        boolean errorResult,
        String failure) {

    /** Maximum characters of output kept per invocation; the agent still receives the full text. */
    public static final int MAX_OUTPUT_CHARS = 4 * 1024;

    /**
     * A routed call that produced an MCP tool result (including
     * {@code isError} results and the unknown-tool response).
     */
    public static ToolInvocation completed(long sequence, long startedAtMillis, long durationMillis,
            String tool, String argumentsSummary, String output, boolean errorResult) {
        return new ToolInvocation(sequence, startedAtMillis, durationMillis, tool,
                argumentsSummary == null ? "(no arguments)" : argumentsSummary,
                tail(output, MAX_OUTPUT_CHARS), errorResult, null);
    }

    /**
     * A call that failed at dispatch level (the provider threw); the agent
     * receives a JSON-RPC error (-32602 for {@link ParamError}, -32603
     * otherwise) instead of a tool result.
     */
    public static ToolInvocation failed(long sequence, long startedAtMillis, long durationMillis,
            String tool, String argumentsSummary, String failure) {
        String message = failure == null || failure.isBlank() ? "tool call failed" : failure;
        return new ToolInvocation(sequence, startedAtMillis, durationMillis, tool,
                argumentsSummary == null ? "(no arguments)" : argumentsSummary,
                "", true, message);
    }

    /** @return whether the provider threw (dispatch-level), as opposed to an MCP {@code isError} result. */
    public boolean dispatchFailure() {
        return failure != null;
    }

    /**
     * Keeps the last {@code maxChars} characters; when truncating, prefixes a
     * marker naming the number of dropped characters so the tail is visibly a
     * tail. {@code null} renders as the empty string. Public for the separate-bundle
     * tests (OSGi gives host and test bundle distinct class loaders).
     */
    public static String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int dropped = text.length() - maxChars;
        return "...[" + dropped + " earlier characters truncated]\n"
                + text.substring(text.length() - maxChars);
    }
}
