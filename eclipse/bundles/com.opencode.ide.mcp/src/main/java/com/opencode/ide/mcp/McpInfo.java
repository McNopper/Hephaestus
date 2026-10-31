package com.opencode.ide.mcp;

import com.opencode.ide.tools.McpDispatcher;

/**
 * Service interface for the state of the {@code eclipse-build} MCP server hosted by this bundle
 * (Streamable HTTP on 127.0.0.1, path {@code /mcp}).
 *
 * <p>Published as an OSGi service by the internal {@code McpServiceComponent} (Declarative
 * Services, service-driven) while the endpoint is running. This interface is the only supported API
 * for other bundles; everything else is internal.
 */
public interface McpInfo {

  /**
   * The MCP server name the dispatcher answers as — the single source of truth for registration and
   * wire responses (from {@link McpDispatcher}).
   */
  String SERVER_NAME = McpDispatcher.SERVER_NAME;

  /**
   * @return the port the MCP endpoint listens on (loopback only), or -1 if not running.
   */
  int getPort();

  /**
   * @return whether the MCP endpoint is currently listening.
   */
  boolean isRunning();

  /**
   * @return the endpoint URL ({@code http://127.0.0.1:<port>/mcp}), or {@code null} if not running.
   */
  String getEndpointUrl();
}
