package com.opencode.ide.client.activity;

import java.util.List;

/**
 * One permission request an opencode session raised (parsed from the {@code /event} SSE stream by
 * {@link PermissionEvents}): the harness must surface it to the human, who answers it via {@code
 * POST /session/:id/permissions/:permissionID} (see {@code OpencodeClient#respondToPermission}).
 *
 * <p>Null-tolerant value: every field except the ids may be {@code null} when the server event did
 * not carry it; the queue logic must never throw on unknown or missing fields.
 *
 * @param sessionId the session that asks (never {@code null} after parsing)
 * @param permissionId the request id ({@code per_...}); the path parameter of the answer endpoint
 *     (never {@code null} after parsing)
 * @param permission the permission category/tool (e.g. {@code "bash"}, {@code "edit"}) — may be
 *     {@code null}
 * @param patterns the resource patterns the agent wants to touch (never {@code null}, possibly
 *     empty)
 * @param title lenient display hint extracted from the event metadata (e.g. the command or path) —
 *     may be {@code null}
 * @param status {@link Status#PENDING pending} when raised, {@link Status#ANSWERED answered} when
 *     replied (by us or anyone else)
 */
public record PermissionRequest(
    String sessionId,
    String permissionId,
    String permission,
    List<String> patterns,
    String title,
    Status status) {

  /** Lifecycle of a permission request (derived from the event type). */
  public enum Status {
    PENDING,
    ANSWERED
  }

  /** Compact constructor: null-safe, immutable patterns. */
  public PermissionRequest {
    patterns = patterns == null ? List.of() : List.copyOf(patterns);
  }

  /**
   * @return whether the request still awaits an answer.
   */
  public boolean pending() {
    return status == Status.PENDING;
  }

  /**
   * @return a human-readable one-liner, e.g. {@code "bash: git push"} — category plus the metadata
   *     title (or the patterns when no title came).
   */
  public String display() {
    String name = permission == null || permission.isBlank() ? "permission" : permission;
    String hint =
        title != null && !title.isBlank()
            ? title
            : patterns.isEmpty() ? null : String.join(", ", patterns);
    return hint == null ? name : name + ": " + hint;
  }
}
