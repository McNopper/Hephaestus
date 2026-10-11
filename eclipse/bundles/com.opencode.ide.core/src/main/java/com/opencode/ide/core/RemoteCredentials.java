package com.opencode.ide.core;

/**
 * Storage seam for remote-connection passwords. Production uses Equinox secure storage ({@link
 * SecureRemoteCredentials}); tests inject an in-memory fake via {@link
 * OpencodePreferences#OpencodePreferences(RemoteCredentials)}.
 *
 * <p>Implementations must <b>never throw</b>: any secure-storage failure (missing provider,
 * headless environment, IO) degrades to a {@code ClientLog} warning plus a {@code null} / no-op
 * result, so that preference reads stay exception-free in every runtime.
 */
public interface RemoteCredentials {

  /**
   * Loads the stored password for the given connection URL.
   *
   * @return the password, or {@code null} when none is stored or storage is unavailable
   */
  String loadPassword(String url);

  /**
   * Stores (encrypts) the password for the given URL. A {@code null} or empty password removes the
   * entry.
   */
  void storePassword(String url, String password);

  /** Removes the stored password for the given URL (no-op when absent). */
  void removePassword(String url);

  /** Removes every stored password. */
  void removeAll();
}
