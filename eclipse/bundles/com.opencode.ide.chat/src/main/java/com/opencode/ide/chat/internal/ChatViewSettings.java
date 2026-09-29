package com.opencode.ide.chat.internal;

import org.eclipse.jface.dialogs.IDialogSettings;

/**
 * Chat-view continuity persistence (U-039): the id of the last chat session
 * and the last deliberately selected model, stored in the chat plugin's
 * dialog settings (the same mechanism the Board view uses, implemented
 * locally in this bundle). The primary Chat view offers to restore them on
 * the next workspace start, so a restart does not lose the conversation.
 *
 * <p>Everything is best-effort: an unreadable or unwritable settings file
 * degrades to "nothing stored" (the chat simply starts fresh), never to an
 * error. SWT-free so it is unit-testable.</p>
 */
public final class ChatViewSettings {

    private static final String SECTION = "chatView";
    private static final String KEY_LAST_SESSION = "lastSessionId";
    private static final String KEY_LAST_MODEL = "lastModel";

    private ChatViewSettings() {
    }

    /** @return the id of the last active chat session, or {@code null} when none is stored. */
    public static String lastSessionId() {
        return get(KEY_LAST_SESSION);
    }

    /**
     * Stores (non-blank) or clears ({@code null}/blank) the last chat
     * session id; a {@code null} answer means "no session to restore".
     */
    public static void storeLastSession(String sessionId) {
        put(KEY_LAST_SESSION, sessionId);
    }

    /** @return the last selected model as {@code provider/model}, or {@code null} when none is stored. */
    public static String lastModel() {
        return get(KEY_LAST_MODEL);
    }

    /** Stores (non-blank) or clears ({@code null}/blank) the last selected model. */
    public static void storeLastModel(String model) {
        put(KEY_LAST_MODEL, model);
    }

    private static String get(String key) {
        ChatActivator plugin = ChatActivator.getDefault();
        if (plugin == null) {
            return null;
        }
        try {
            IDialogSettings settings = plugin.getDialogSettings().getSection(SECTION);
            if (settings != null) {
                String value = settings.get(key);
                return value == null || value.isBlank() ? null : value;
            }
        } catch (RuntimeException ignored) {
            // defaults survive an unreadable dialog settings file
        }
        return null;
    }

    private static void put(String key, String value) {
        ChatActivator plugin = ChatActivator.getDefault();
        if (plugin == null) {
            return; // not in an OSGi runtime (tests): persistence is a no-op
        }
        try {
            IDialogSettings all = plugin.getDialogSettings();
            IDialogSettings settings = all.getSection(SECTION);
            if (settings == null) {
                settings = all.addNewSection(SECTION);
            }
            settings.put(key, value == null ? "" : value);
            plugin.persistDialogSettings();
        } catch (RuntimeException ignored) {
            // persistence is best-effort
        }
    }
}
