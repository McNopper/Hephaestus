package com.opencode.ide.client;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal logging seam so this bundle stays Eclipse-free: a host (e.g. the Eclipse core bundle) can
 * install a backend via {@link #install(ClientLog)}; the default logs to {@code java.util.logging}.
 */
@FunctionalInterface
public interface ClientLog {

  /** Default backend: plain {@code java.util.logging}. */
  ClientLog DEFAULT =
      (level, message, cause) ->
          Logger.getLogger("com.opencode.ide.client").log(level, message, cause);

  AtomicReference<ClientLog> BACKEND = new AtomicReference<>(DEFAULT);

  /** Installs the backend; {@code null} restores the default. */
  static void install(ClientLog backend) {
    BACKEND.set(backend != null ? backend : DEFAULT);
  }

  static void warning(String message) {
    BACKEND.get().log(Level.WARNING, message, null);
  }

  /**
   * Stage-level tracing: JUL's default INFO level prints to the console, so engine progress (e.g.
   * fleet launch stages) is observable without extra config.
   */
  static void info(String message) {
    BACKEND.get().log(Level.INFO, message, null);
  }

  static void error(String message, Throwable cause) {
    BACKEND.get().log(Level.SEVERE, message, cause);
  }

  void log(Level level, String message, Throwable cause);
}
