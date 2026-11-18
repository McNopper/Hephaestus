package com.opencode.ide.chat.internal;

import com.opencode.ide.client.model.Session;
import java.util.List;

/**
 * T-180 ("one ground of truth"): decides which conversation a surface should land on when it opens
 * without an explicit session — the MOST RECENTLY ACTIVE session of the project, whichever client
 * wrote it (TUI, chat, fleet). Eclipse's own remembered session (U-039) is only the fallback, so
 * two surfaces can no longer grow two separate live threads.
 *
 * <p>Pure (no I/O, no SWT): the caller fetches the scoped session list and falls back to its
 * remembered id when this returns {@code null}.
 */
public final class ActiveSession {

  private ActiveSession() {}

  /**
   * @return the id of the session with the newest {@code time.updated}, or {@code null} when the
   *     list is empty or no session carries a positive update stamp (a session without a real
   *     timestamp is never a reason to jump conversations).
   */
  public static String mostRecent(List<Session> sessions) {
    if (sessions == null) {
      return null;
    }
    String best = null;
    long bestUpdated = 0L;
    for (Session session : sessions) {
      if (session == null
          || session.id() == null
          || session.id().isBlank()
          || session.time() == null) {
        continue;
      }
      long updated = session.time().updated();
      if (updated > bestUpdated) {
        bestUpdated = updated;
        best = session.id();
      }
    }
    return best;
  }
}
