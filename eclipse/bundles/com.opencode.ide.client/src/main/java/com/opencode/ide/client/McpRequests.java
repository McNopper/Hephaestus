package com.opencode.ide.client;

import com.google.gson.JsonObject;

/**
 * Builds opencode MCP registration bodies (pure JSON building, no I/O - unit-testable).
 * Used by {@code HttpOpencodeClient} for {@code POST /mcp}.
 */
public final class McpRequests {

    private McpRequests() {
    }

    /**
     * @param name   the MCP server name agents will see (carried in the PUT
     *        path - v2's body is only the {@code config} object)
     * @param config the remote server endpoint (see {@link McpServerConfig})
     * @return the JSON request body
     */
    public static String registerBody(String name, McpServerConfig config) {
        JsonObject remote = new JsonObject();
        remote.addProperty("type", "remote");
        remote.addProperty("url", config.url());
        if (config.headers() != null && !config.headers().isEmpty()) {
            JsonObject headers = new JsonObject();
            config.headers().forEach(headers::addProperty);
            remote.add("headers", headers);
        }
        if (config.enabled() != null) {
            remote.addProperty("enabled", config.enabled());
        }
        // our endpoint has no OAuth; opt out so the client never starts a flow on 401/404
        remote.addProperty("oauth", false);
        JsonObject body = new JsonObject();
        body.add("config", remote);
        return body.toString();
    }
}
