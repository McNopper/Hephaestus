package com.opencode.ide.core.context;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The ONE home for view ids and the session-id encoding (T-009): every pane
 * that opens a per-session view (Chat, Session Details) goes through these
 * constants and this encoding. It also owns the one explicit auto-refresh
 * parameter a Session Details open can carry: the {@link #LIVE_WATCH_SEGMENT}
 * in the secondary id (built by {@link #secondaryId(String, boolean)}, read
 * back by {@link #parse(String)}). Before this class, four call sites carried
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
     * The live-watch segment of a Session Details secondary id: the one
     * explicit channel a "show this session AND auto-refresh it" open has
     * (T-009 AC3 replaced the former one-shot System property with this).
     * An opener appends it via {@link #secondaryId(String, boolean)} and
     * the view reads it back with {@link #parse(String)} - a real
     * {@code (sessionId, autoRefresh)} parameter carried in the view's own
     * identity, not global state, so it can never leak into an unrelated
     * view. The segment cannot collide with an encoded session id:
     * {@link #secondaryId(String)} percent-escapes {@code ~}, so the
     * segment is never part of the encoded form (only a legacy unencoded
     * id that itself ends in the segment would be misread - opencode ids
     * are plain {@code ses_...} strings).
     */
    public static final String LIVE_WATCH_SEGMENT = "~live";

    /**
     * The per-session secondary id: URL-encoded session id. Opencode ids are
     * plain {@code ses_…} strings, so this is an identity in practice - the
     * encoding exists for exactly the characters the workbench escapes.
     */
    public static String secondaryId(String sessionId) {
        return sessionId == null ? "" : URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
    }

    /**
     * {@link #secondaryId(String)} with the explicit auto-refresh parameter
     * openers use ({@code openDetails(sessionId, autoRefresh)}): a
     * live-watching open appends the {@link #LIVE_WATCH_SEGMENT} to the
     * plain secondary id, every other open uses the plain form.
     */
    public static String secondaryId(String sessionId, boolean autoRefresh) {
        return autoRefresh ? secondaryId(sessionId) + LIVE_WATCH_SEGMENT : secondaryId(sessionId);
    }

    /** The explicit view input parsed from a secondary id (T-009 AC3). */
    public record Parsed(String sessionId, boolean autoRefresh) {
    }

    /**
     * Parses a secondary id back into its explicit input pair: the session
     * id (the inverse of {@link #secondaryId(String, boolean)}, tolerant of
     * legacy unencoded forms) and whether the opener requested
     * live-watching ({@link #LIVE_WATCH_SEGMENT} present).
     */
    public static Parsed parse(String secondaryId) {
        if (secondaryId == null || secondaryId.isBlank()) {
            return new Parsed(null, false);
        }
        boolean autoRefresh = secondaryId.endsWith(LIVE_WATCH_SEGMENT);
        String encoded = autoRefresh
                ? secondaryId.substring(0, secondaryId.length() - LIVE_WATCH_SEGMENT.length())
                : secondaryId;
        return new Parsed(URLDecoder.decode(encoded, StandardCharsets.UTF_8), autoRefresh);
    }

    /**
     * The session id behind a secondary id (the session-id half of
     * {@link #parse(String)}).
     */
    public static String sessionId(String secondaryId) {
        return parse(secondaryId).sessionId();
    }

    private SessionViewIds() {
    }
}
