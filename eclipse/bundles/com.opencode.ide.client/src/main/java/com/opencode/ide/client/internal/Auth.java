package com.opencode.ide.client.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Small helpers shared by the HTTP client and the SSE stream. Kept in the internal (non-exported)
 * package.
 */
public final class Auth {

  private Auth() {}

  /**
   * Builds an HTTP basic-auth header value for opencode. The username defaults to {@code
   * "opencode"} (the server's default) when blank.
   *
   * @return {@code "Basic …"} or {@code null} when no password is set (no auth).
   */
  public static String basicHeader(String username, String password) {
    if (password == null || password.isEmpty()) {
      return null;
    }
    String user = (username == null || username.isEmpty()) ? "opencode" : username;
    byte[] bytes = (user + ":" + password).getBytes(StandardCharsets.UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(bytes);
  }
}
