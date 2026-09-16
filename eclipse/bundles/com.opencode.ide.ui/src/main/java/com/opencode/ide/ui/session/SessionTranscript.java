package com.opencode.ide.ui.session;

import java.util.Locale;

import com.opencode.ide.ui.session.SessionDetailsController.MessageRow;
import com.opencode.ide.ui.session.SessionDetailsController.SessionDetails;
import com.opencode.ide.ui.session.SessionDetailsController.ToolLine;

/**
 * Plain-text renderings of a session's history for the read-only
 * "Open in Editor" actions (Batch C of U-002): one message or the whole
 * transcript become editor content here. SWT-free and pure, so the format
 * is unit-testable and the view stays a thin shell around it.
 *
 * <p>The rendered text is a snapshot: the editor shows what the last load
 * saw, it never follows live updates (refresh and re-open for newer
 * content).</p>
 */
public final class SessionTranscript {

    /** Placeholder body when a message carries no text at all. */
    public static final String NO_TEXT = "(no text)";

    private SessionTranscript() {
    }

    /**
     * Editor name for a full transcript: the session id, reduced to safe
     * name characters, plus {@code .txt} (so the text editor picks a sane
     * mode). Null/blank ids fall back to {@code session.txt}.
     */
    public static String editorName(String sessionId) {
        return baseName(sessionId) + ".txt";
    }

    /**
     * Editor name for one message: transcript base, {@code -message-N} with
     * the 1-based position, {@code .txt}. Numbers below 1 clamp to 1 so an
     * unresolvable position still yields a usable, distinct name.
     */
    public static String messageEditorName(String sessionId, int messageNumber) {
        return baseName(sessionId) + "-message-" + Math.max(1, messageNumber) + ".txt";
    }

    /** Session id reduced to editor-safe characters; {@code session} when nothing usable remains. */
    private static String baseName(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "session";
        }
        String cleaned = sessionId.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "session" : cleaned;
    }

    /**
     * One message as editor content: the label line (role, agent, model,
     * time — blank parts skipped, mirroring the Details column), a blank
     * line, then text, reasoning and tool lines.
     */
    public static String message(MessageRow row) {
        if (row == null) {
            return NO_TEXT;
        }
        return label(row) + "\n\n" + body(row);
    }

    /**
     * The whole snapshot as editor content: the aggregate header line
     * (title, id, model, cost, tokens — the same fields as the view
     * header), then every message under a numbered separator.
     *
     * <p>A snapshot without rows renders as its {@code errorNote} (or the
     * empty-history note) — opening the editor on a failed load still
     * shows what went wrong instead of an empty shell.</p>
     */
    public static String transcript(SessionDetails snapshot) {
        if (snapshot == null) {
            return SessionDetailsController.EMPTY_NOTE;
        }
        if (snapshot.rows().isEmpty()) {
            return snapshot.errorNote() == null ? SessionDetailsController.EMPTY_NOTE : snapshot.errorNote();
        }
        StringBuilder sb = new StringBuilder(header(snapshot));
        for (int i = 0; i < snapshot.rows().size(); i++) {
            MessageRow row = snapshot.rows().get(i);
            sb.append("\n\n---- [").append(i + 1).append("] ").append(label(row)).append(" ----\n\n");
            sb.append(body(row));
        }
        return sb.toString();
    }

    private static String header(SessionDetails snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(snapshot.title() == null || snapshot.title().isBlank() ? "(untitled)" : snapshot.title());
        sb.append("  \u2022  ").append(snapshot.sessionId() == null ? "?" : snapshot.sessionId());
        if (snapshot.modelLabel() != null && !snapshot.modelLabel().isBlank()) {
            sb.append("  \u2022  ").append(snapshot.modelLabel());
        }
        if (snapshot.totalCost() != null) {
            sb.append("  \u2022  cost $").append(String.format(Locale.ROOT, "%.4f", snapshot.totalCost()));
        }
        if (snapshot.tokens() != null && !snapshot.tokens().isEmpty()) {
            sb.append("  \u2022  tokens ").append(snapshot.tokens().summary());
        }
        return sb.toString();
    }

    /** {@code role  •  agent  •  model  •  time} with blank parts skipped; role defaults to {@code message}. */
    private static String label(MessageRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.role() == null || row.role().isBlank() ? "message" : row.role());
        for (String part : new String[] { row.agent(), row.modelLabel(), row.timeLabel() }) {
            if (part != null && !part.isBlank()) {
                sb.append("  \u2022  ").append(part);
            }
        }
        return sb.toString();
    }

    private static String body(MessageRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.text() == null || row.text().isBlank() ? NO_TEXT : row.text());
        if (row.reasoning() != null && !row.reasoning().isBlank()) {
            sb.append("\n\nreasoning:\n").append(row.reasoning());
        }
        if (!row.tools().isEmpty()) {
            sb.append("\n\ntools:");
            for (ToolLine tool : row.tools()) {
                String state = tool.state() == null || tool.state().isBlank() ? "unknown" : tool.state();
                sb.append("\n- ").append(tool.name()).append(" \u2014 ").append(state);
            }
        }
        return sb.toString();
    }
}
