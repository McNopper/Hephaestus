package com.opencode.ide.client;

import java.util.Map;

/**
 * A remote (Streamable HTTP) MCP server registration for the opencode server ({@code POST /mcp}).
 * Used to expose IDE-side tools (build, run, debug) to opencode agents.
 *
 * @param url the MCP endpoint URL (must accept POST JSON-RPC)
 * @param headers optional static headers (may be {@code null})
 * @param enabled registration enabled flag (may be {@code null} = server default)
 */
public record McpServerConfig(String url, Map<String, String> headers, Boolean enabled) {

  /** A minimal enabled registration without headers. */
  public static McpServerConfig enabled(String url) {
    return new McpServerConfig(url, null, Boolean.TRUE);
  }
}
