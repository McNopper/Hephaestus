package com.opencode.ide.client;

/**
 * Entry point for obtaining {@link OpencodeClient} implementations; keeps the concrete classes in
 * the internal (non-exported) package.
 */
public final class OpencodeClients {

  private OpencodeClients() {}

  /**
   * @return an {@link OpencodeClient} talking HTTP/JSON to the server described by {@code config}.
   */
  public static OpencodeClient http(ConnectionConfig config) {
    return new com.opencode.ide.client.internal.HttpOpencodeClient(config);
  }
}
