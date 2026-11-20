package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.Session;

/**
 * The shared session-label rule ({@link #name(Session)}): title, falling back to the agent name and
 * the id. It outlived the Background view (removed 2026-11-19 - the fleet's "what is going on"
 * overview is opencode's own job now, inspectable in the Server view) because the collected
 * questions registry ({@link PendingQuestions}) and {@link SessionSubagents} both label a session
 * with exactly this rule - one session, one label, on every surface.
 *
 * <p>Pure functions over the client surface, SWT-free ({@code ui.tests} convention).
 */
public final class BackgroundModel {

  private BackgroundModel() {}

  /** Title, falling back to the agent name and the id (v2 titles are lazy). */
  public static String name(Session session) {
    if (session.title() != null && !session.title().isBlank()) {
      return session.title();
    }
    if (session.agent() != null && !session.agent().isBlank()) {
      return "<" + session.agent() + ">";
    }
    return session.id();
  }
}
