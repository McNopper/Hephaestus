package com.opencode.ide.board.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the {@link DefaultFleetLauncher} stub: every launch fails with the "pending
 * TaskFleet wiring" detail and echoes the ticket id — failures come back as handles, never
 * exceptions.
 */
public class DefaultFleetLauncherTest {

  @Test
  public void stubFailsWithPendingDetail() {
    FleetLauncher launcher = new DefaultFleetLauncher();
    FleetJobHandle handle = launcher.launch("hephaestus", "T-001");

    assertTrue(handle.failed());
    assertEquals("T-001", handle.taskId());
    assertNull(handle.sessionId());
    assertNull(handle.worktree());
    assertTrue(handle.detail().contains("pending"));
    assertTrue(handle.detail().contains("TaskFleet"));
  }
}
