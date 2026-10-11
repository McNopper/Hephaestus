package com.opencode.ide.chat.internal;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Bundle-log helpers; one place so all parts of the chat UI (view, page, session controller) log
 * identically to the Eclipse log.
 */
public final class ChatLog {

  private ChatLog() {}

  /** Logs {@code message} at INFO to this bundle's Eclipse log. */
  public static void info(String message) {
    Platform.getLog(Platform.getBundle(ChatActivator.PLUGIN_ID))
        .log(new Status(Status.INFO, ChatActivator.PLUGIN_ID, message));
  }

  /** Logs {@code message} at ERROR (with optional cause) to this bundle's Eclipse log. */
  public static void error(String message, Throwable throwable) {
    Platform.getLog(Platform.getBundle(ChatActivator.PLUGIN_ID))
        .log(new Status(Status.ERROR, ChatActivator.PLUGIN_ID, message, throwable));
  }
}
