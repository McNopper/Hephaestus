package com.opencode.ide.core.context;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The ONE home for view ids and the session-id encoding (T-009): every pane
 * that opens a per-session view (Chat, Session Details) goes through these
 * constants and this encoding. Before this class, four call sites carried
 * three different encodings ({@code replace('%','_')}, {@code replace("%","")},
 * {@code URLEncoder.encode}) - the same session opened DIFFERENT secondary
 * ids, i.e. duplicate view instances per session, each with its own
 * submission state. UI-free on purpose (core must not require the
 * workbench), so any bundle can use it.
 */
public final class SessionViewIds {

    /** The chat view (chat bundle; {@code allowMultiple}, secondary id = resume convention). */
    public static final String CHAT_VIEW_ID = "com.opencode.ide.chat.views.ChatView";

    /** The session history inspector (ui bundle; {@code allowMultiple}, secondary id = session id). */
    public static final String SESSION_DETAILS_VIEW_ID = "com.opencode.ide.ui.views.SessionDetailsView";

    /**
     * One-shot auto-refresh hand-off for a freshly opened Session Details
     * view: the opener sets it right before {@code showView} for a RUNNING
     * session (same UI-thread call stack), the view consumes and clears it.
     * Centralized here so no bundle mirrors the spelling (FleetView used to).
     */
    public static final String AUTO_REFRESH_HINT_PROPERTY =
            "com.opencode.ide.ui.sessionDetails.autoRefreshHint";

    /**
     * The per-session secondary id: URL-encoded session id. Opencode ids are
     * plain {@code ses_…} strings, so this is an identity in practice - the
     * encoding exists for exactly the characters the workbench escapes.
     */
    public static String secondaryId(String sessionId) {
        return sessionId == null ? "" : URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
    }

    /**
     * The session id behind a secondary id (the inverse of
     * {@link #secondaryId(String)}; tolerant of legacy unencoded forms).
     */
    public static String sessionId(String secondaryId) {
        if (secondaryId == null || secondaryId.isBlank()) {
            return null;
        }
        return URLDecoder.decode(secondaryId, StandardCharsets.UTF_8);
    }

    private SessionViewIds() {
    }
}
