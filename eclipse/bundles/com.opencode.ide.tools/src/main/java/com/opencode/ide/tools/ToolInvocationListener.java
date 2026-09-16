package com.opencode.ide.tools;

/**
 * Consumer of completed {@code eclipse-build} tool calls, notified through
 * the static {@link ToolInvocationHub} registry. The {@link McpDispatcher}
 * publishes one {@link ToolInvocation} per routed {@code tools/call}.
 *
 * <p>Implementations must be cheap and non-blocking: they are invoked on the
 * dispatching thread (an {@code mcp-http} executor thread for the HTTP
 * transport, the reading thread for the stdio transports) while the agent's
 * request is being answered. Any exception a listener throws is swallowed
 * and logged by the hub — listeners can never break dispatch. UI consumers
 * hop to the UI thread themselves (the hub is transport- and thread-agnostic
 * on purpose).</p>
 *
 * <p>This interface lives in the Eclipse-free tools bundle so both OSGi
 * embedders (the Eclipse {@code eclipse-build} endpoint) and plain-Java
 * embedders (the tasks/fleet stdio mains) can observe the same traffic.</p>
 */
@FunctionalInterface
public interface ToolInvocationListener {

    /** Called once per completed tool call with the invocation summary (never {@code null}). */
    void onToolInvocation(ToolInvocation invocation);
}
