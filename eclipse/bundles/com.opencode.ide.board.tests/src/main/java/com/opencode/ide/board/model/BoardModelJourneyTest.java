package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

/**
 * Unit tests for the U-026 journey projection in {@link BoardModel}: every
 * refresh carries per-ticket {@link StageJourney}s derived from the store's
 * recorded history (real advance/send-back markers through the real store),
 * and the refresh is a pure projection — the ticket files on disk are
 * byte-identical before and after (AC-004).
 */
public class BoardModelJourneyTest extends BoardModelTestHarness {

    /** A staged ticket that advanced once and was sent back once. */
    private String flowedTicket() {
        var t = store.create("p", spec("flow", "developer"));
        store.update("p", t.id, Map.of("stage", "requirements"));
        store.update("p", t.id, Map.of("status", "in-review"));
        store.advance("p", t.id, "tester");
        store.sendBack("p", t.id, "rework the interfaces", "reviewer");
        return t.id;
    }

    @Test
    public void refreshCarriesJourneysDerivedFromRecordedHistory() {
        String id = flowedTicket();

        BoardSnapshot snapshot = new BoardModel(root, "p").refresh();
        StageJourney journey = snapshot.journeyOf(id);

        assertNotNull(journey);
        assertEquals("requirements (entry) + system (advance)", "2/10", journey.progressLabel());
        assertEquals("SET + ADVANCE + SEND_BACK", 3, journey.movements().size());
        assertEquals(StageJourney.Kind.SEND_BACK, journey.latestSetback().kind());
        assertEquals("sent back from system: rework the interfaces",
                "rework the interfaces", journey.latestSetback().reason());
        assertNull("unknown ids read as no journey", snapshot.journeyOf("T-999"));
    }

    @Test
    public void journeysCoverRoleFallbackAndUntrackedTickets() {
        var legacy = store.create("p", spec("legacy", "developer"));
        var untracked = store.create("p", spec("odd", "research"));

        BoardSnapshot snapshot = new BoardModel(root, "p").refresh();

        assertEquals("role-derived current stage counts once entered",
                "1/10", snapshot.journeyOf(legacy.id).progressLabel());
        assertEquals("untracked tickets entered nothing", "0/10",
                snapshot.journeyOf(untracked.id).progressLabel());
    }

    @Test
    public void refreshIsAProjectionTheStoreFilesStayByteIdentical() throws IOException {
        String id = flowedTicket();
        BoardModel model = new BoardModel(root, "p");
        model.setMode(BoardModel.BoardMode.PIPELINE);
        Map<String, String> before = ticketFiles();

        BoardSnapshot snapshot = model.refresh();
        assertNotNull(snapshot.journeyOf(id));

        assertEquals("AC-004: rendering the visible surfaces writes nothing",
                before, ticketFiles());
    }

    /** The project's ticket files as text (the projection witness). */
    private Map<String, String> ticketFiles() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (var stream = Files.list(root.resolve("p"))) {
            for (Path file : stream.filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                out.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return out;
    }
}
