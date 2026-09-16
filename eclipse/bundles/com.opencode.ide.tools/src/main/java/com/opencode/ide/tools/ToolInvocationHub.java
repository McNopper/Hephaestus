package com.opencode.ide.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Static listener registry for {@code eclipse-build} tool invocations — the
 * seam between the MCP dispatch engine and any number of observers (the
 * Eclipse "Agent Tools" console today; tests, loggers and future markers
 * tomorrow). The {@link McpDispatcher} publishes every routed
 * {@code tools/call} here via {@link #publish(ToolInvocation)}.
 *
 * <p>A static registry (not an OSGi service) on purpose: the tools bundle is
 * deliberately Eclipse/OSGi-free (enforced by its build) and the dispatcher
 * is plain-{@code new} constructed in several bundles — including the
 * tasks/fleet stdio mains that run without any OSGi framework at all. The
 * Eclipse UI attaches/detaches its console listener at workbench startup /
 * bundle stop (see the ui bundle's {@code AgentToolsStartup}), the same
 * singleton-manager style the repo already uses for
 * {@code ConnectionsManager} et al.</p>
 *
 * <p>Thread-safety: listeners are kept in a copy-on-write list;
 * {@link #publish} may be called from any thread (HTTP executor or stdio
 * reader); listener callbacks run on the publishing thread.</p>
 */
public final class ToolInvocationHub {

    /** Maximum characters of the one-line arguments summary. */
    public static final int MAX_SUMMARY_CHARS = 240;

    private static final Logger LOG = Logger.getLogger(ToolInvocationHub.class.getName());

    private static final CopyOnWriteArrayList<ToolInvocationListener> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ToolInvocationHub() {
    }

    /**
     * Registers a listener.
     *
     * @return whether the listener was added ({@code false} for {@code null}
     *         or an already-registered instance)
     */
    public static boolean addListener(ToolInvocationListener listener) {
        if (listener == null) {
            return false;
        }
        return LISTENERS.addIfAbsent(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @return whether the listener was registered (and is now removed)
     */
    public static boolean removeListener(ToolInvocationListener listener) {
        return LISTENERS.remove(listener);
    }

    /** @return a snapshot of the currently registered listeners (never {@code null}). */
    public static List<ToolInvocationListener> listeners() {
        return List.copyOf(LISTENERS);
    }

    /** @return the next per-JVM monotonic invocation id (used as the {@link ToolInvocation#sequence()}). */
    public static long nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    /**
     * Notifies every registered listener. Exception-safe by contract: a
     * throwing listener is logged and skipped, the other listeners still run,
     * and the publishing dispatcher is never affected.
     */
    public static void publish(ToolInvocation invocation) {
        if (invocation == null || LISTENERS.isEmpty()) {
            return;
        }
        for (ToolInvocationListener listener : LISTENERS) {
            try {
                listener.onToolInvocation(invocation);
            } catch (Throwable t) {
                // never let an observer break the agent-facing dispatch
                LOG.log(Level.WARNING, "tool invocation listener failed for " + invocation.tool(), t);
            }
        }
    }

    /**
     * Renders a tools/call arguments object as a compact one-line summary for
     * display: {@code build_dir=C:\b, target=app, defines=[3 items]}. Values
     * keep their declaration order; arrays and nested objects collapse to
     * their item count; the whole line is capped at
     * {@link #MAX_SUMMARY_CHARS} characters (with an ellipsis). {@code null}
     * or empty objects render as "(no arguments)".
     */
    public static String summarizeArguments(JsonObject arguments) {
        if (arguments == null || arguments.entrySet().isEmpty()) {
            return "(no arguments)";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : arguments.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(render(entry.getValue()));
            if (sb.length() > MAX_SUMMARY_CHARS) {
                sb.setLength(MAX_SUMMARY_CHARS);
                sb.append("...");
                return sb.toString();
            }
        }
        return sb.toString();
    }

    private static String render(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            String text = value.getAsJsonPrimitive().getAsString();
            return text.isBlank() ? "\"\"" : text;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            return "[" + array.size() + (array.size() == 1 ? " item]" : " items]");
        }
        if (value.isJsonObject()) {
            int fields = value.getAsJsonObject().size();
            return "{" + fields + (fields == 1 ? " field}" : " fields}");
        }
        return "?";   // gson JsonElement has no other concrete kinds
    }
}
