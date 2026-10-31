package com.opencode.ide.tools;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * SPI for one tool pack of the {@code eclipse-build} MCP server. A pack is usually a language
 * domain (C/C++ today, further languages later); it may also be a cross-cutting domain such as the
 * task board ({@code tasks}). The {@link McpDispatcher} unions {@link #tools()} for tools/list and
 * routes each tools/call to the provider that declared the tool name, so a pack fully owns its tool
 * names; keep them distinct across packs (e.g. prefix by domain).
 *
 * <p>Wiring is a plain constructor-injected list - {@code new McpDispatcher(List.of(new
 * CppToolProvider(), new TaskToolProvider(root), ...))}, assembled in the MCP bundle's {@code
 * McpServiceComponent}. A future {@code PythonToolProvider} plugs in by (a) implementing this
 * interface in its own pack (e.g. {@code com.opencode.ide.tools.python}), (b) appending it to the
 * provider list where the MCP bundle builds the dispatcher - or, once several packs ship, by
 * replacing that list with {@code ServiceLoader.load(ToolProvider.class,
 * getClass().getClassLoader())} plus a {@code META-INF/services} registration in each pack.
 *
 * <p>The SPI and the JSON-RPC dispatch engine are exported from this Eclipse-free tools bundle, so
 * a pack may live in any bundle that depends on it.
 *
 * <p>Implementations raise {@link ParamError} for structurally invalid arguments (mapped by the
 * dispatcher to JSON-RPC -32602) and return {@link McpToolResult#error(String)} for domain problems
 * such as a missing task or a bad state transition.
 */
public interface ToolProvider {

  /**
   * @return the domain identifier of the pack, e.g. {@code "cpp"}, {@code "python"} or {@code
   *     "tasks"}.
   */
  String language();

  /**
   * @return the tools contributed to tools/list (union across all providers).
   */
  List<McpTool> tools();

  /**
   * Executes one tool declared by this pack.
   *
   * @param toolName one of the names returned by {@link #tools()}
   * @param arguments the tools/call arguments object (never {@code null}, may be empty)
   * @return the MCP tool-call outcome (text content + isError flag)
   */
  McpToolResult call(String toolName, JsonObject arguments);
}
