package com.opencode.ide.tools;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * Outcome of one tool call: the text content of the MCP result plus the {@code isError} flag.
 * Providers return these instead of throwing so that machine-dependent problems reach the caller as
 * explanations, not as JSON-RPC internal errors.
 */
public record McpToolResult(String text, boolean isError) {

  private static final Gson GSON = new Gson();

  /** Serializes a JSON payload as the text content of a successful call. */
  public static McpToolResult json(JsonElement payload) {
    return json(payload, false);
  }

  /** Serializes a JSON payload as the text content, with an explicit isError flag. */
  public static McpToolResult json(JsonElement payload, boolean isError) {
    return new McpToolResult(GSON.toJson(payload), isError);
  }

  /** A failed call whose text explains the problem. */
  public static McpToolResult error(String message) {
    return new McpToolResult(message, true);
  }
}
