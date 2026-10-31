package com.opencode.ide.client;

/**
 * Thrown when the opencode server cannot be reached or responds with a connection-level failure.
 */
public class OpencodeConnectionException extends OpencodeException {
  private static final long serialVersionUID = 1L;

  public OpencodeConnectionException(String message) {
    super(message);
  }

  public OpencodeConnectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
