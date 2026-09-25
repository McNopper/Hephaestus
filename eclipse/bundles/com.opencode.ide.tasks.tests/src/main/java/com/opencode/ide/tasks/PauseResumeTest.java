package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * U-038 pause semantics: a ticket parked for maintenance reads as
 * {@code paused} (visible, never blocked, never NEEDS-HUMAN) with its reason
 * in history, and resume is a plain status update back to in-progress.
 */
public class PauseResumeTest extends StoreTestHarness {

    @Test
    public void pausedIsVisibleNotBlockedAndCarriesItsReason() {
        Task t = store.create("p", new TaskStore.CreateSpec(
                "work in flight", "d", "task", "developer", "high", 3,
                List.of("it works"), List.of(), null, "T"), "implementation");
        t = store.setBlocked("p", t.id, "stalled", "agent");

        Task paused = store.setPaused("p", t.id, "JDK upgrade on the host", "shutdown");

        assertEquals("paused", paused.status);
        assertFalse("paused is never blocked (and never NEEDS-HUMAN)", paused.blocked);
        String history = paused.toJson().toString();
        assertTrue("the reason is recorded: " + history,
                history.contains("paused: JDK upgrade on the host"));
    }

    @Test
    public void resumeIsAPlainStatusUpdate() {
        Task t = store.create("p", new TaskStore.CreateSpec(
                "work in flight", "d", "task", "developer", "high", 3,
                List.of("it works"), List.of(), null, "T"), "implementation");
        t = store.setPaused("p", t.id, "maintenance", "shutdown");

        Task resumed = store.update("p", t.id, Map.of("status", "in-progress"));

        assertEquals("in-progress", resumed.status);
        assertTrue("paused is a valid store status", Task.VALID_STATUSES.contains("paused"));
    }
}
