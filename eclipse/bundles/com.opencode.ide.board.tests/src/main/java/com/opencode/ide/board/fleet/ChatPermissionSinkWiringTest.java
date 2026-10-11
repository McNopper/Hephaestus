package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.activity.PermissionRequest;
import java.util.List;
import org.junit.Test;

/**
 * {@link TaskFleetLauncher#chatPermissionSink()} feeds chat-session (non-fleet) permission asks
 * into the same process-wide {@link TaskFleetLauncher#permissions() queue} the Fleet view drains
 * (H5 item 1 completion — chat sessions covered).
 */
public class ChatPermissionSinkWiringTest {

  private static PermissionRequest ask(String sessionId, String permissionId) {
    return new PermissionRequest(
        sessionId,
        permissionId,
        "bash",
        List.of("docs/**"),
        "rm docs",
        PermissionRequest.Status.PENDING);
  }

  private static boolean pendingContains(String permissionId) {
    return TaskFleetLauncher.permissions().pending().stream()
        .anyMatch(request -> permissionId.equals(request.permissionId()));
  }

  @Test
  public void chatAskLandsInTheSharedQueue() {
    TaskFleetLauncher.chatPermissionSink().asked(ask("chat-s1", "chat-p1"), null);
    assertTrue(pendingContains("chat-p1"));
    assertTrue(TaskFleetLauncher.permissions().remove("chat-s1"));
  }

  @Test
  public void chatReplyMarksTheRequestAnswered() {
    TaskFleetLauncher.chatPermissionSink().asked(ask("chat-s2", "chat-p2"), null);
    assertTrue(pendingContains("chat-p2"));
    TaskFleetLauncher.chatPermissionSink().replied("chat-s2", "chat-p2");
    assertFalse(pendingContains("chat-p2"));
    TaskFleetLauncher.permissions().remove("chat-s2");
  }

  @Test
  public void replyForUnknownRequestIsHarmless() {
    TaskFleetLauncher.chatPermissionSink().replied("chat-s3", "chat-p3");
    assertFalse(pendingContains("chat-p3"));
    TaskFleetLauncher.permissions().remove("chat-s3");
  }

  @Test
  public void connectIsIdempotentAndKeepsTheSinkUsable() {
    TaskFleetLauncher.connectChatPermissions();
    TaskFleetLauncher.connectChatPermissions();
    TaskFleetLauncher.chatPermissionSink().asked(ask("chat-s4", "chat-p4"), null);
    assertEquals(
        1,
        TaskFleetLauncher.permissions().pending().stream()
            .filter(request -> "chat-p4".equals(request.permissionId()))
            .count());
    TaskFleetLauncher.permissions().remove("chat-s4");
  }
}
