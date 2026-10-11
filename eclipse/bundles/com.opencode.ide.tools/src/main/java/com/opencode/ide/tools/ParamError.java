package com.opencode.ide.tools;

/**
 * Structurally invalid tool parameters (missing or malformed argument); the dispatcher maps this to
 * JSON-RPC -32602. Part of the {@link ToolProvider} contract: providers raise it for bad arguments,
 * the dispatcher for malformed tools/call envelopes.
 */
public class ParamError extends RuntimeException {

  public ParamError(String message) {
    super(message);
  }
}
