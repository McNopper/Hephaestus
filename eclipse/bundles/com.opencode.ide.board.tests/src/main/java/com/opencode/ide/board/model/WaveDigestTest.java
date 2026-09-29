package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.tasks.Task;

/**
 * Unit tests for {@link WaveDigest} (U-026 FR-005/FR-006, AC-003): counts
 * by movement kind, the moved-back tickets with their reasons, the
 * never-moved tickets — all as plain text a comment or chat output could
 * carry verbatim, and without touching the tickets (the AC-004 projection
 * contract).
 */
public class WaveDigestTest {

    private static final Instant TS = Instant.parse("2026-09-29T10:00:00.000Z");

    private static Task ticket(String id, String stage, String... actions) {
        Task task = new Task();
        task.id = id;
        task.title = "ticket " + id;
        task.stage = stage;
        for (String action : actions) {
            task.history.add(new Task.HistoryEvent(TS, action, "agent"));
        }
        return task;
    }

    @Test
    public void countsMovementsByKindAcrossTheWave() {
        List<Task> wave = List.of(
                ticket("A", "architecture", "advanced to system", "advanced to architecture"),
                ticket("B", "requirements", "stage 2 passed: no system impact",
                        "reported to system: doubt", "sent back to requirements: fix it"),
                ticket("C", "design", "created"));

        String digest = WaveDigest.render("wave-1", wave);

        assertTrue(digest.startsWith("Wave wave-1 movement digest"));
        assertTrue("3 tickets · 2 advanced · 1 passed · 1 sent back · 1 reported",
                digest.contains("3 tickets · 2 advanced · 1 passed · 1 sent back · 1 reported"));
    }

    @Test
    public void namesMovedBackTicketsWithReasons() {
        List<Task> wave = List.of(
                ticket("B", "requirements", "advanced to system",
                        "sent back to requirements: fix it"));

        String digest = WaveDigest.render("wave-1", wave);

        assertTrue(digest.contains("Moved back:"));
        assertTrue(digest.contains("- B — ⚠ sent back from system: fix it — back to requirements"));
    }

    @Test
    public void namesNeverMovedTickets() {
        List<Task> wave = List.of(
                ticket("A", "design", "advanced to system"),
                ticket("C", "design", "created"),
                ticket("D", null, "created"));

        String digest = WaveDigest.render("wave-1", wave);

        assertTrue(digest.contains("Never moved:"));
        assertTrue(digest.contains("- C, D"));
        assertFalse("the moved ticket is not listed as never-moved", digest.contains("A,"));
    }

    @Test
    public void emptyCasesRenderTheirPlaceholders() {
        String quiet = WaveDigest.render("wave-1",
                List.of(ticket("A", "system", "advanced to system")));
        assertTrue(quiet.contains("(no send-backs or reports)"));
        assertTrue(quiet.contains("(every ticket moved)"));

        String empty = WaveDigest.render("wave-2", List.of());
        assertTrue(empty.contains("Wave wave-2 movement digest"));
        assertTrue(empty.contains("0 tickets · 0 advanced · 0 passed · 0 sent back · 0 reported"));
    }

    @Test
    public void nullTolerance() {
        String digest = WaveDigest.render(null, null);
        assertTrue(digest.startsWith("Wave  movement digest"));
        assertTrue(digest.contains("0 tickets"));

        List<Task> withNull = new ArrayList<>();
        withNull.add(ticket("A", "system", "advanced to system"));
        withNull.add(null);
        assertTrue(WaveDigest.render("w", withNull).contains("1 tickets"));
    }

    @Test
    public void renderingNeverMutatesTheTickets() {
        Task task = ticket("A", "system", "advanced to system");
        List<Task.HistoryEvent> before = List.copyOf(task.history);

        WaveDigest.render("wave-1", List.of(task));

        assertEquals("AC-004: the digest is a pure projection", before, task.history);
        assertEquals("system", task.stage);
    }
}
