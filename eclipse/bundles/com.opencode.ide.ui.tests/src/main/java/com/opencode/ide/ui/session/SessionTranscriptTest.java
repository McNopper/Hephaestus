package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.ui.session.SessionDetailsController.MessageRow;
import com.opencode.ide.ui.session.SessionDetailsController.SessionDetails;
import com.opencode.ide.ui.session.SessionDetailsController.ToolLine;
import com.opencode.ide.ui.session.SessionDetailsController.TokenTotals;

/**
 * Unit tests for {@link SessionTranscript} (the SWT-free formatting behind
 * the read-only "Open in Editor" actions, Batch C of U-002): no SWT, no
 * HTTP — exact-string where practical so format regressions surface.
 */
public class SessionTranscriptTest {

    // ---------- editor names ----------

    @Test
    public void editorNamesSanitizeAndFallback() {
        assertEquals("ses_abc-1.2.txt", SessionTranscript.editorName("ses_abc-1.2"));
        // path/colon/percent are not safe in an editor tab name
        assertEquals("ses_x_y_z.txt", SessionTranscript.editorName("ses/x:y%z"));
        assertEquals("session.txt", SessionTranscript.editorName(null));
        assertEquals("session.txt", SessionTranscript.editorName("  "));
    }

    @Test
    public void messageEditorNameIsOneBasedAndClamps() {
        assertEquals("ses_1-message-2.txt", SessionTranscript.messageEditorName("ses_1", 2));
        assertEquals("ses_1-message-1.txt", SessionTranscript.messageEditorName("ses_1", 0));
        assertEquals("ses_1-message-1.txt", SessionTranscript.messageEditorName("ses_1", -1));
        assertEquals("session-message-1.txt", SessionTranscript.messageEditorName(null, 1));
    }

    // ---------- single message ----------

    @Test
    public void messageRendersLabelBlankLineAndText() {
        MessageRow row = new MessageRow("user", null, "", "2025-08-12T12:00:00Z",
                "Fix the build", null, List.of());

        assertEquals("user  \u2022  2025-08-12T12:00:00Z\n\nFix the build", SessionTranscript.message(row));
    }

    @Test
    public void messageIncludesReasoningToolsAndUnknownState() {
        MessageRow row = new MessageRow("assistant", "build", "zai/glm-5.3 (high)", "12:00",
                "Done.", "Because", List.of(new ToolLine("read", "completed"), new ToolLine("bash", null)));

        assertEquals("assistant  \u2022  build  \u2022  zai/glm-5.3 (high)  \u2022  12:00"
                + "\n\nDone."
                + "\n\nreasoning:\nBecause"
                + "\n\ntools:\n- read \u2014 completed\n- bash \u2014 unknown",
                SessionTranscript.message(row));
    }

    @Test
    public void messageWithoutRoleOrTextStaysUsable() {
        assertEquals("message\n\n" + SessionTranscript.NO_TEXT,
                SessionTranscript.message(new MessageRow(null, null, "", "", "  ", null, List.of())));
        assertEquals(SessionTranscript.NO_TEXT, SessionTranscript.message(null));
    }

    // ---------- full transcript ----------

    @Test
    public void transcriptOfSmallSnapshotIsExact() {
        SessionDetails snapshot = new SessionDetails("ses_1", "T", null, null, null, null,
                List.of(new MessageRow("user", null, "", "12:00", "hi", null, List.of())), null);

        assertEquals("T  \u2022  ses_1\n\n---- [1] user  \u2022  12:00 ----\n\nhi",
                SessionTranscript.transcript(snapshot));
    }

    @Test
    public void transcriptCarriesHeaderAggregatesAndNumberedBlocks() {
        SessionDetails snapshot = new SessionDetails("ses_1", "Session One", null, "zai/glm-5.3 (high)",
                1.75, new TokenTotals(101L, 51L, null, 5L, 2L),
                List.of(
                        new MessageRow("user", null, "", "12:00", "go", null, List.of()),
                        new MessageRow("assistant", "build", "zai/glm-5.3 (high)", "12:01", "Done.",
                                null, List.of())),
                null);

        String transcript = SessionTranscript.transcript(snapshot);

        assertTrue(transcript.startsWith("Session One  \u2022  ses_1  \u2022  zai/glm-5.3 (high)"
                + "  \u2022  cost $1.7500  \u2022  tokens in 101 \u2022 out 51 \u2022 cache 5r/2w"));
        assertTrue(transcript.contains("---- [1] user  \u2022  12:00 ----\n\ngo"));
        assertTrue(transcript.contains("---- [2] assistant  \u2022  build  \u2022  zai/glm-5.3 (high)"
                + "  \u2022  12:01 ----\n\nDone."));
    }

    @Test
    public void untitledSessionHeaderFallsBack() {
        SessionDetails snapshot = new SessionDetails("ses_1", null, null, null, null, null,
                List.of(new MessageRow("assistant", null, "", "", "hi", null, List.of())), null);

        assertTrue(SessionTranscript.transcript(snapshot).startsWith("(untitled)  \u2022  ses_1"));
    }

    @Test
    public void emptyOrFailedSnapshotsRenderTheirNote() {
        assertEquals("boom", SessionTranscript.transcript(
                new SessionDetails("ses_1", null, null, null, null, null, List.of(), "boom")));
        assertEquals(SessionDetailsController.EMPTY_NOTE, SessionTranscript.transcript(
                new SessionDetails("ses_1", null, null, null, null, null, List.of(), null)));
        assertEquals(SessionDetailsController.EMPTY_NOTE, SessionTranscript.transcript(null));
    }
}
