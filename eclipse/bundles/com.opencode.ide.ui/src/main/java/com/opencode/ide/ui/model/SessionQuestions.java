package com.opencode.ide.ui.model;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Session;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The in-session projection of the collected agent questions (U-096): the pending asks raised by
 * ONE session's subtree - the session itself plus every subagent nested under it by {@code
 * parentID}, recursively. This is the AC1 join: a SUBAGENT's question becomes visible on the parent
 * session's surfaces (the Session Details questions section, the Server view's session-row marker)
 * without opening the subagent.
 *
 * <p>Rows come from the same {@link PendingQuestions#rowOf} builder as the collected list, so one
 * question reads identically on every surface. Pure list surgery over the caller's already-loaded
 * sessions and pending asks - SWT-free, unit-testable ({@code ui.tests} convention).
 */
public final class SessionQuestions {

  private SessionQuestions() {}

  /**
   * The session's own id plus every descendant id ({@code parentID} nesting, breadth-first); {@code
   * null}-tolerant - an unknown or {@code null} session list yields the bare id set.
   */
  public static Set<String> subtreeIds(String sessionId, List<Session> sessions) {
    Set<String> ids = new HashSet<>();
    if (sessionId == null || sessionId.isBlank()) {
      return ids;
    }
    Deque<String> frontier = new ArrayDeque<>();
    frontier.add(sessionId);
    while (!frontier.isEmpty()) {
      String current = frontier.removeFirst();
      if (!ids.add(current)) {
        continue; // already collected (defends a parentID cycle)
      }
      if (sessions != null) {
        for (Session session : sessions) {
          if (session != null && current.equals(session.parentID()) && session.id() != null) {
            frontier.addLast(session.id());
          }
        }
      }
    }
    return Set.copyOf(ids);
  }

  /**
   * The pending questions raised inside the session's subtree, oldest first (the pending list's
   * order is kept), attributed like the collected list. {@code firstSeen} may supply older
   * first-seen ages (pass the shared registry when it feeds the list); {@code null} stamps every
   * ask at {@code nowMillis}.
   */
  public static List<PendingQuestions.Row> rows(
      String sessionId,
      List<Session> sessions,
      List<PermissionRequest> pending,
      PendingQuestions firstSeen,
      long nowMillis) {
    Set<String> ids = subtreeIds(sessionId, sessions);
    if (ids.isEmpty() || pending == null) {
      return List.of();
    }
    Map<String, Session> byId = PendingQuestions.byId(sessions);
    List<PendingQuestions.Row> rows = new ArrayList<>();
    for (PermissionRequest request : pending) {
      if (request == null || !request.pending() || !ids.contains(request.sessionId())) {
        continue;
      }
      long askedAt =
          firstSeen == null ? nowMillis : firstSeen.firstSeenAt(request.permissionId(), nowMillis);
      rows.add(PendingQuestions.rowOf(request, byId, askedAt, nowMillis));
    }
    return List.copyOf(rows);
  }

  /**
   * How many pending questions wait inside the session's subtree - the count behind the Server
   * view's session-row marker.
   */
  public static int countForSubtree(
      String sessionId, List<Session> sessions, List<PermissionRequest> pending) {
    if (pending == null || pending.isEmpty()) {
      return 0;
    }
    Set<String> ids = subtreeIds(sessionId, sessions);
    int count = 0;
    for (PermissionRequest request : pending) {
      if (request != null && request.pending() && ids.contains(request.sessionId())) {
        count++;
      }
    }
    return count;
  }

  /**
   * The OLDEST pending question of the session's subtree (the pending list is oldest first), or
   * {@code null} when none waits - the one-line question behind the session row's Details prefix.
   */
  public static PermissionRequest firstForSubtree(
      String sessionId, List<Session> sessions, List<PermissionRequest> pending) {
    if (pending == null) {
      return null;
    }
    Set<String> ids = subtreeIds(sessionId, sessions);
    for (PermissionRequest request : pending) {
      if (request != null && request.pending() && ids.contains(request.sessionId())) {
        return request;
      }
    }
    return null;
  }
}
