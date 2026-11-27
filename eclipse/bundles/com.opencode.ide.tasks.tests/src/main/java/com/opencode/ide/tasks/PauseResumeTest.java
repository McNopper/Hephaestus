package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * U-038 pause semantics: a ticket parked for maintenance reads as {@code paused} (visible, never
 * blocked, never NEEDS-HUMAN) with its reason in history, and resume is a plain status update back
 * to in-progress.
 */
public class PauseResumeTest extends StoreTestHarness {

  @Test
  public void pausedIsVisibleNotBlockedAndCarriesItsReason() {
    Task t =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "work in flight",
                "d",
                "task",
                "developer",
                "high",
                3,
                List.of("it works"),
                List.of(),
                "T"),
            "implementation");
    t = store.setBlocked("p", t.id, "stalled", "agent");

    Task paused = store.setPaused("p", t.id, "JDK upgrade on the host", "shutdown");

    assertEquals("paused", paused.status);
    assertFalse("paused is never blocked (and never NEEDS-HUMAN)", paused.isBlocked());
    String history = paused.toJson().toString();
    assertTrue(
        "the reason is recorded: " + history, history.contains("paused: JDK upgrade on the host"));
  }

  @Test
  public void resumeIsAPlainStatusUpdate() {
    Task t =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "work in flight",
                "d",
                "task",
                "developer",
                "high",
                3,
                List.of("it works"),
                List.of(),
                "T"),
            "implementation");
    t = store.setPaused("p", t.id, "maintenance", "shutdown");

    Task resumed = store.update("p", t.id, Map.of("status", "in-progress"));

    assertEquals("in-progress", resumed.status);
    assertTrue("paused is a valid store status", Task.VALID_STATUSES.contains("paused"));
  }

  /**
   * T-225 (S3): setPaused mirrors setBlocked's direct-entry refusal (TaskStore 961-966) - a
   * terminal VERDICT is never paused (pausing a done ticket would silently un-done it while the
   * V-walk skips paused rows), and live work still parks and resumes.
   */
  @Test
  public void setPausedRefusesATerminalTicketButStillParksAndResumesLiveWork() {
    Task t = store.create("p", TaskStore.CreateSpec.of("finished work"));
    store.update("p", t.id, Map.of("status", "done"));

    TaskStore.Invalid refused =
        assertThrows(
            TaskStore.Invalid.class, () -> store.setPaused("p", t.id, "maintenance", "shutdown"));
    assertTrue(refused.getMessage(), refused.getMessage().contains("never paused"));
    assertTrue(refused.getMessage(), refused.getMessage().contains("done"));
    assertEquals("the refusal leaves the verdict alone", "done", store.get("p", t.id).status);

    Task ni = store.create("p", TaskStore.CreateSpec.of("retired work"));
    store.update("p", ni.id, Map.of("status", "not-implemented"));
    assertThrows(
        TaskStore.Invalid.class, () -> store.setPaused("p", ni.id, "maintenance", "shutdown"));
    assertEquals("not-implemented stays terminal", "not-implemented", store.get("p", ni.id).status);

    // the legal half (AC3): pause still parks live work, resume is still a plain status update
    Task live = store.create("p", TaskStore.CreateSpec.of("live work"));
    assertEquals(
        "paused", store.setPaused("p", live.id, "JDK upgrade on the host", "shutdown").status);
    assertEquals("ready", store.update("p", live.id, Map.of("status", "ready")).status);
  }
}
