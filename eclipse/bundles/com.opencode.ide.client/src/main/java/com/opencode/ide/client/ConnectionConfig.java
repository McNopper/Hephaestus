package com.opencode.ide.client;

import java.net.URI;
import java.util.Objects;

/**
 * Connection parameters for an opencode server in {@code connect} mode (an externally-started
 * {@code opencode serve}).
 *
 * @param baseUrl server base URL, e.g. {@code http://127.0.0.1:4096}
 * @param username basic-auth username (may be {@code null}); opencode defaults to {@code
 *     "opencode"} when a password is set
 * @param password basic-auth password (may be {@code null} for an unprotected server)
 */
public record ConnectionConfig(URI baseUrl, String username, String password) {

  public ConnectionConfig {
    Objects.requireNonNull(baseUrl, "baseUrl");
    String scheme = baseUrl.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': missing scheme - expected http://host[:port] or https://host[:port]");
    }
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': scheme '"
              + scheme
              + "' is not supported - expected http or https");
    }
    if (baseUrl.getHost() == null || baseUrl.getHost().isBlank()) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': missing host - expected http://host[:port] or https://host[:port]");
    }
    if (baseUrl.getRawUserInfo() != null) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': userinfo is not allowed - expected http://host[:port] or https://host[:port]");
    }
    if (baseUrl.getRawQuery() != null) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': query is not allowed - expected http://host[:port] or https://host[:port]");
    }
    if (baseUrl.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': fragment is not allowed - expected http://host[:port] or https://host[:port]");
    }
    String path = baseUrl.getRawPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) {
      throw new IllegalArgumentException(
          "Invalid opencode server URL '"
              + baseUrl
              + "': path '"
              + path
              + "' is not allowed - expected http://host[:port] or https://host[:port]");
    }
    if ("/".equals(path)) {
      baseUrl = URI.create(baseUrl.toString().substring(0, baseUrl.toString().length() - 1));
    }
  }

  /** Whether HTTP basic auth should be sent (a password is configured). */
  public boolean hasAuth() {
    return password != null && !password.isEmpty();
  }

  /** A display-friendly identifier for this connection (host:port). */
  public String displayName() {
    int port = baseUrl.getPort();
    return baseUrl.getHost() + (port > 0 ? ":" + port : "");
  }
}
