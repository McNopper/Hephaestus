package com.opencode.ide.core.internal;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Registers the local {@code eclipse-build} MCP endpoint with an opencode server. Pure logic, no
 * OSGi: remembers the last successfully registered (client, url) pair so repeated triggers are
 * no-ops; failed registrations are logged and not remembered, so a later trigger retries.
 */
public final class McpRegistration {

  /** Name under which the IDE-side MCP endpoint is registered. */
  /** The wire name the dispatcher answers as — one source of truth (via the mcp bundle's API). */
  public static final String SERVER_NAME = com.opencode.ide.mcp.McpInfo.SERVER_NAME;

  private final ClientLog log;

  private OpencodeClient registeredClient;
  private String registeredUrl;

  public McpRegistration(ClientLog log) {
    this.log = Objects.requireNonNull(log, "log");
  }

  /**
   * Register {@code endpointUrl} with {@code client} unless that exact pair was already registered
   * successfully. Failures are logged and non-fatal.
   */
  public synchronized void registerIfNeeded(OpencodeClient client, String endpointUrl) {
    if (client == null || endpointUrl == null || endpointUrl.isEmpty()) {
      return;
    }
    if (client == registeredClient && endpointUrl.equals(registeredUrl)) {
      return;
    }
    try {
      client.registerMcp(SERVER_NAME, McpServerConfig.enabled(endpointUrl));
      registeredClient = client;
      registeredUrl = endpointUrl;
    } catch (OpencodeException | RuntimeException e) {
      log.log(
          Level.SEVERE, "failed to register MCP server '" + SERVER_NAME + "' at " + endpointUrl, e);
    }
  }
}
