package com.opencode.ide.client.model;

/**
 * One MCP server registered with the opencode server. The live v1.18 wire shape of {@code GET /mcp}
 * is a <b>map</b> keyed by server name with {@code {"status":"connected"}} values (verified against
 * a live server) — the client converts it into this list form. Nullable-tolerant.
 */
public record McpServerInfo(String id, String status) {}
