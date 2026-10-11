package com.opencode.ide.board.model;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.client.activity.PermissionRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * SWT-free row values for the Fleet permissions dialog: joins the fleet's pending {@link
 * PermissionRequest}s (from the fleet bundle's {@code PermissionQueue}) with the {@link
 * FleetJobHandle} rows of {@link FleetJobsModel} by session id, so each request shows which
 * ticket's job raised it. The dialog only renders; this mapping is unit-tested.
 */
public final class FleetPermissions {

  /** One pending permission request as shown in the dialog. */
  public record Row(String sessionId, String taskId, String permissionId, String detail) {}

  private FleetPermissions() {}

  /**
   * Joins pending requests with their fleet job rows.
   *
   * @param pending unanswered requests, oldest first (the queue's order is kept)
   * @param jobs the current fleet job rows (session -&gt; task)
   * @return immutable rows in the given pending order
   */
  public static List<Row> rows(List<PermissionRequest> pending, List<FleetJobHandle> jobs) {
    List<Row> rows = new ArrayList<>();
    for (PermissionRequest request : pending) {
      rows.add(
          new Row(
              request.sessionId(),
              taskOf(request.sessionId(), jobs),
              request.permissionId(),
              request.display()));
    }
    return List.copyOf(rows);
  }

  /**
   * @return the task id of the job running the session, or {@code null} when no job row carries
   *     that session
   */
  public static String taskOf(String sessionId, List<FleetJobHandle> jobs) {
    if (sessionId == null) {
      return null;
    }
    for (FleetJobHandle job : jobs) {
      if (sessionId.equals(job.sessionId()) && job.taskId() != null) {
        return job.taskId();
      }
    }
    return null;
  }
}
