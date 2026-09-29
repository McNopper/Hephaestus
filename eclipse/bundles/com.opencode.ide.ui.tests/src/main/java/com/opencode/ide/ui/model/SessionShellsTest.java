package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.opencode.ide.client.model.ShellTask;
import com.opencode.ide.ui.model.SessionShells.Row;

/**
 * Unit tests for {@link SessionShells}: the per-session shell-task rows of
 * the Session Details view (U-041). The transcript is the association —
 * {@code type:"shell"} messages carry the {@code sh_} id — and the live task
 * list overlays them. The wire shape follows
 * {@code SessionObserverTest.shellRunsCarryCommandExitAndOutputTail} and the
 * v2.0.19 {@code Session.Message.Shell} schema. SWT-free.
 */
public class SessionShellsTest {

    private static JsonArray messages(String... entries) {
        JsonArray out = new JsonArray();
        for (String entry : entries) {
            out.add(JsonParser.parseString(entry));
        }
        return out;
    }

    private static ShellTask task(String id, String status) {
        return new ShellTask(id, status, "git status", "C:\\repo", 0, 4242L,
                new ShellTask.Time(1_000L, null));
    }

    @Test
    public void shellMessagesBecomeRowsCarryingTheirShellId() {
        JsonArray messages = messages(
                """
                {"id":"msg_2","type":"shell","shellID":"sh_1","command":"git status",
                 "status":"exited","exit":0,"output":{"output":"clean","cursor":5,"size":5,"truncated":false}}
                """,
                """
                {"id":"msg_1","type":"shell","shellID":"sh_2","command":"npm test","status":"running"}
                """);

        List<Row> rows = SessionShells.rows(messages, List.of());

        assertEquals(2, rows.size());
        // running tasks float to the top regardless of transcript order
        assertEquals("sh_2", rows.get(0).shellId());
        assertEquals("npm test", rows.get(0).command());
        assertTrue(rows.get(0).isRunning());
        assertFalse(rows.get(0).live());
        assertEquals("sh_1", rows.get(1).shellId());
        assertEquals(Integer.valueOf(0), rows.get(1).exit());
        assertEquals("clean", rows.get(1).outputTail());
    }

    @Test
    public void liveTasksOverlayTheirRowById() {
        JsonArray messages = messages(
                """
                {"id":"msg_1","type":"shell","shellID":"sh_1","command":"make verify","status":"running"}
                """);

        List<Row> rows = SessionShells.rows(messages, List.of(task("sh_1", "running")));

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).live());
        assertEquals("running", rows.get(0).status());
        assertTrue(rows.get(0).detailLabel().contains("live"));
    }

    /**
     * Association honesty (U-041): a live task with no shell message in this
     * session's transcript is NOT this session's — the client's ShellTask
     * record does not map Shell.Info.metadata (which carries the originating
     * session id), so the row list never guesses.
     */
    @Test
    public void liveTasksWithoutATranscriptRowAreDropped() {
        List<Row> rows = SessionShells.rows(messages(), List.of(task("sh_foreign", "running")));

        assertTrue(rows.isEmpty());
    }

    @Test
    public void exitedLiveTasksKeepTheTranscriptStatus() {
        JsonArray messages = messages(
                """
                {"id":"msg_1","type":"shell","shellID":"sh_1","command":"ls","status":"exited","exit":1}
                """);

        List<Row> rows = SessionShells.rows(messages, List.of(task("sh_1", "exited")));

        assertEquals("exited", rows.get(0).status());
        assertFalse(rows.get(0).isRunning());
        assertTrue(rows.get(0).detailLabel().contains("exit 1"));
    }

    @Test
    public void junkEntriesAndNonShellMessagesAreSkipped() {
        JsonArray junk = messages(
                "42",
                "{\"type\":\"assistant\",\"text\":\"not a shell\"}",
                "{\"type\":\"shell\"}",                            // no shellID/command
                "{\"type\":\"shell\",\"shellID\":\"sh_x\"}",       // no command
                "null");
        junk.add(com.google.gson.JsonNull.INSTANCE);

        List<Row> rows = SessionShells.rows(junk, List.of());

        assertTrue(rows.isEmpty());
    }

    @Test
    public void longOutputTailsAreCappedToTheLastCharacters() {
        String tail = "x".repeat(SessionShells.OUTPUT_TAIL + 50);
        JsonArray messages = messages(
                """
                {"id":"msg_1","type":"shell","shellID":"sh_1","command":"big","status":"exited",
                 "output":{"output":"%s","cursor":1,"size":1,"truncated":true}}
                """.formatted(tail));

        List<Row> rows = SessionShells.rows(messages, List.of());

        assertEquals(SessionShells.OUTPUT_TAIL, rows.get(0).outputTail().length());
        assertTrue("the kept tail is the END of the output",
                rows.get(0).outputTail().endsWith("x"));
    }

    @Test
    public void nullInputsAndMissingStatusDegrade() {
        assertTrue(SessionShells.rows(null, null).isEmpty());
        List<Row> rows = SessionShells.rows(messages(
                "{\"type\":\"shell\",\"shellID\":\"sh_1\",\"command\":\"ls\"}"), null);
        assertEquals(1, rows.size());
        assertEquals("unknown", rows.get(0).status());
        assertFalse(rows.get(0).isRunning());
    }
}
