package com.opencode.ide.tools;

import com.google.gson.JsonObject;

/**
 * One MCP tool as advertised by tools/list: its name, human-readable description and JSON-schema
 * description of the arguments object.
 */
public record McpTool(String name, String description, JsonObject inputSchema) {}
