package com.opencode.ide.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Stateless JSON-RPC 2.0 dispatcher for the minimal MCP Streamable HTTP
 * transport: initialize, ping, tools/list, tools/call. Tool sets come from
 * the injected {@link ToolProvider} list: tools/list is the union across
 * providers, tools/call routes by tool name to the provider that declared
 * it (unknown names yield an isError tool result listing the available
 * tools). Notifications (no id) produce no response; structural parameter
 * problems surface as -32602, parse failures as -32700, unknown methods as
 * -32601.
 *
 * <p>Every routed tools/call is also reported to the
 * {@link ToolInvocationHub} (one {@link ToolInvocation} per call: name,
 * argument summary, output tail, outcome) so UIs can mirror agent-driven
 * tool traffic. Reporting is best-effort: a throwing listener can never
 * change the agent-facing response.</p>
 */
public final class McpDispatcher {

    public static final String PROTOCOL_VERSION = "2025-03-26";
    public static final String SERVER_NAME = "eclipse-build";
    public static final String SERVER_VERSION = "0.1.0";

    private static final java.util.Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            java.util.Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final List<ToolProvider> providers;
    private final Map<String, ToolProvider> toolRouting;

    /** Single-provider convenience constructor. */
    public McpDispatcher(ToolProvider provider) {
        this(List.of(provider));
    }

    /** Multi-provider constructor: tools/list unions all providers in list order; first provider wins name clashes. */
    public McpDispatcher(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
        Map<String, ToolProvider> routing = new LinkedHashMap<>();
        for (ToolProvider provider : this.providers) {
            for (McpTool tool : provider.tools()) {
                routing.putIfAbsent(tool.name(), provider);
            }
        }
        this.toolRouting = routing;
    }

    /**
     * Handles one request body.
     *
     * @return the JSON-RPC response string, or {@code null} for notifications
     *         (the HTTP layer answers 202 with an empty body).
     */
    public String handle(String requestBody) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(requestBody);
        } catch (JsonSyntaxException e) {
            return error(null, -32700, "parse error");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return error(null, -32600, "invalid request: expected a single JSON-RPC 2.0 object");
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        boolean notification = id == null || id.isJsonNull();
        JsonElement methodElement = request.get("method");
        if (methodElement == null || !methodElement.isJsonPrimitive()) {
            return notification ? null : error(id, -32600, "invalid request: missing 'method'");
        }
        String method = methodElement.getAsString();
        JsonElement paramsElement = request.get("params");
        JsonObject params = paramsElement != null && paramsElement.isJsonObject()
                ? paramsElement.getAsJsonObject()
                : null;
        try {
            switch (method) {
                case "initialize":
                    return notification ? null : result(id, initialize(params));
                case "ping":
                    return notification ? null : result(id, new JsonObject());
                case "tools/list":
                    return notification ? null : result(id, listTools());
                case "tools/call":
                    return notification ? null : result(id, callTool(params));
                default:
                    if (method.startsWith("notifications/")) {
                        return null;
                    }
                    return notification ? null : error(id, -32601, "method not found: " + method);
            }
        } catch (ParamError e) {
            return notification ? null : error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return notification ? null : error(id, -32603, "internal error: " + e);
        }
    }

    private JsonObject initialize(JsonObject params) {
        String version = PROTOCOL_VERSION;
        if (params != null) {
            JsonElement requested = params.get("protocolVersion");
            if (requested != null && requested.isJsonPrimitive()
                    && SUPPORTED_PROTOCOL_VERSIONS.contains(requested.getAsString())) {
                version = requested.getAsString();
            }
        }
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject listTools() {
        JsonArray tools = new JsonArray();
        for (ToolProvider provider : providers) {
            for (McpTool tool : provider.tools()) {
                JsonObject t = new JsonObject();
                t.addProperty("name", tool.name());
                t.addProperty("description", tool.description());
                t.add("inputSchema", tool.inputSchema());
                tools.add(t);
            }
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject callTool(JsonObject params) {
        if (params == null) {
            throw new ParamError("tools/call requires params with a string 'name'");
        }
        JsonElement nameElement = params.get("name");
        if (nameElement == null || !nameElement.isJsonPrimitive()) {
            throw new ParamError("tools/call requires a string 'name'");
        }
        JsonElement argsElement = params.get("arguments");
        JsonObject args = argsElement != null && argsElement.isJsonObject()
                ? argsElement.getAsJsonObject()
                : new JsonObject();
        String name = nameElement.getAsString();
        long startedAt = System.currentTimeMillis();
        String summary = ToolInvocationHub.summarizeArguments(args);
        ToolProvider provider = toolRouting.get(name);
        if (provider == null) {
            String text = "unknown tool '" + name + "'; available tools: "
                    + String.join(", ", toolRouting.keySet());
            ToolInvocationHub.publish(ToolInvocation.completed(ToolInvocationHub.nextSequence(),
                    startedAt, 0L, name, summary, text, true));
            return textResult(text, true);
        }
        McpToolResult outcome;
        try {
            outcome = provider.call(name, args);
        } catch (RuntimeException e) {
            // -32602 for ParamError, -32603 otherwise (mapped by handle());
            // report first so the observer sees the failure either way
            ToolInvocationHub.publish(ToolInvocation.failed(ToolInvocationHub.nextSequence(),
                    startedAt, System.currentTimeMillis() - startedAt, name, summary,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            throw e;
        }
        ToolInvocationHub.publish(ToolInvocation.completed(ToolInvocationHub.nextSequence(),
                startedAt, System.currentTimeMillis() - startedAt, name, summary,
                outcome.text(), outcome.isError()));
        return textResult(outcome.text(), outcome.isError());
    }

    private static JsonObject textResult(String text, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray contentArray = new JsonArray();
        contentArray.add(content);
        JsonObject result = new JsonObject();
        result.add("content", contentArray);
        result.addProperty("isError", isError);
        return result;
    }

    private static String result(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result);
        return GSON.toJson(response);
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        return GSON.toJson(response);
    }
}
