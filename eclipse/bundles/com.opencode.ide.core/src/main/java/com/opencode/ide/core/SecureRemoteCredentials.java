package com.opencode.ide.core;

import com.opencode.ide.client.ClientLog;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;

/**
 * Production {@link RemoteCredentials} on top of Equinox secure storage: one node {@code
 * /com.opencode.ide/remote-connections/<hex-url>} per remote connection holding a single encrypted
 * {@code password} entry. The URL is hex-encoded so preference-path-hostile characters ({@code / :
 * # ?}) never end up in node names.
 *
 * <p>Every operation degrades gracefully: any secure-storage failure is logged as a {@code
 * ClientLog} warning and becomes a {@code null} / no-op result; this class never throws.
 */
final class SecureRemoteCredentials implements RemoteCredentials {

  private static final String NODE_PATH = "com.opencode.ide/remote-connections";
  private static final String KEY_PASSWORD = "password";

  @Override
  public String loadPassword(String url) {
    try {
      ISecurePreferences root = defaultRoot();
      if (root == null || !root.nodeExists(nodePath(url))) {
        return null;
      }
      return root.node(nodePath(url)).get(KEY_PASSWORD, null);
    } catch (Exception e) {
      ClientLog.warning("could not read remote-connection password from secure storage: " + e);
      return null;
    }
  }

  @Override
  public void storePassword(String url, String password) {
    if (password == null || password.isEmpty()) {
      removePassword(url);
      return;
    }
    try {
      ISecurePreferences root = defaultRoot();
      if (root == null) {
        return;
      }
      ISecurePreferences node = root.node(nodePath(url));
      node.put(KEY_PASSWORD, password, true);
      node.flush();
    } catch (Exception e) {
      ClientLog.warning("could not store remote-connection password in secure storage: " + e);
    }
  }

  @Override
  public void removePassword(String url) {
    try {
      ISecurePreferences root = defaultRoot();
      if (root == null || !root.nodeExists(nodePath(url))) {
        return;
      }
      root.node(nodePath(url)).removeNode();
      root.flush();
    } catch (Exception e) {
      ClientLog.warning("could not remove remote-connection password from secure storage: " + e);
    }
  }

  @Override
  public void removeAll() {
    try {
      ISecurePreferences root = defaultRoot();
      if (root == null || !root.nodeExists(NODE_PATH)) {
        return;
      }
      root.node(NODE_PATH).removeNode();
      root.flush();
    } catch (Exception e) {
      ClientLog.warning("could not clear remote-connection passwords in secure storage: " + e);
    }
  }

  private static ISecurePreferences defaultRoot() {
    return SecurePreferencesFactory.getDefault();
  }

  private static String nodePath(String url) {
    return NODE_PATH + "/" + nodeName(url);
  }

  private static String nodeName(String url) {
    return HexFormat.of().formatHex(url.getBytes(StandardCharsets.UTF_8));
  }
}
