package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.model.FleetPermissions.Row;
import com.opencode.ide.client.activity.PermissionRequest;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link FleetPermissions}: joining pending permission requests with fleet job rows
 * by session id (order kept, unknown sessions yield a null task, display details precomputed).
 */
public class FleetPermissionsTest {

  private static PermissionRequest asked(String sessionId, String permissionId, String title) {
    return new PermissionRequest(
        sessionId,
        permissionId,
        "bash",
        List.of("git push"),
        title,
        PermissionRequest.Status.PENDING);
  }

  private static FleetJobHandle job(String taskId, String sessionId) {
    return new FleetJobHandle(
        taskId, sessionId, "C:/wt/" + taskId, FleetJobHandle.State.RUNNING, null);
  }

  @Test
  public void rowsJoinPendingWithJobsBySessionInOrder() {
    List<Row> rows =
        FleetPermissions.rows(
            List.of(asked("ses_1", "per_1", "git push"), asked("ses_2", "per_2", null)),
            List.of(job("T-2", "ses_2"), job("T-1", "ses_1")));

    assertEquals(2, rows.size());
    assertEquals("per_1", rows.get(0).permissionId());
    assertEquals("T-1", rows.get(0).taskId());
    assertEquals("ses_1", rows.get(0).sessionId());
    assertEquals("bash: git push", rows.get(0).detail());
    assertEquals("T-2", rows.get(1).taskId());
    assertEquals("falls back to patterns when no title", "bash: git push", rows.get(1).detail());
  }

  @Test
  public void unknownSessionYieldsNullTask() {
    List<Row> rows =
        FleetPermissions.rows(
            List.of(asked("ses_other", "per_1", null)), List.of(job("T-1", "ses_1")));

    assertEquals(1, rows.size());
    assertNull(rows.get(0).taskId());
  }

  @Test
  public void emptyInputsGiveEmptyRows() {
    assertEquals(List.of(), FleetPermissions.rows(List.of(), List.of()));
    assertEquals(List.of(), FleetPermissions.rows(List.of(), List.of(job("T-1", "ses_1"))));
  }

  @Test
  public void taskOfMatchesFirstJobCarryingTheSession() {
    assertEquals(
        "T-1", FleetPermissions.taskOf("ses_1", List.of(job("T-1", "ses_1"), job("T-9", "ses_1"))));
    assertNull(FleetPermissions.taskOf(null, List.of(job("T-1", "ses_1"))));
    assertNull(FleetPermissions.taskOf("ses_x", List.of()));
    assertNull(
        "jobs without a session never match",
        FleetPermissions.taskOf(
            "ses_1",
            List.of(
                new FleetJobHandle("T-1", null, null, FleetJobHandle.State.FAILED, "no session"))));
  }
}
