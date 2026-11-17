package com.opencode.ide.chat.internal;

import java.util.List;
import org.eclipse.jface.dialogs.IDialogSettings;

/**
 * Chat-view continuity persistence (U-039): the id of the last chat session and the last
 * deliberately selected model, stored in the chat plugin's dialog settings (the same mechanism the
 * Board view uses, implemented locally in this bundle). The primary Chat view offers to restore
 * them on the next workspace start, so a restart does not lose the conversation.
 *
 * <p>Everything is best-effort: an unreadable or unwritable settings file degrades to "nothing
 * stored" (the chat simply starts fresh), never to an error. SWT-free so it is unit-testable.
 */
public final class ChatViewSettings {

  private static final String SECTION = "chatView";
  private static final String KEY_LAST_SESSION = "lastSessionId";
  private static final String KEY_LAST_MODEL = "lastModel";
  private static final String KEY_PINNED_SESSIONS = "pinnedSessions";
  private static final String KEY_RECENT_MODELS = "recentModels";
  private static final String KEY_GOAL_TICKET = "goalTicketId";

  private ChatViewSettings() {}

  /**
   * @return the id of the last active chat session, or {@code null} when none is stored.
   */
  public static String lastSessionId() {
    return get(KEY_LAST_SESSION);
  }

  /**
   * Stores (non-blank) or clears ({@code null}/blank) the last chat session id; a {@code null}
   * answer means "no session to restore".
   */
  public static void storeLastSession(String sessionId) {
    put(KEY_LAST_SESSION, sessionId);
  }

  /**
   * @return the last selected model as {@code provider/model}, or {@code null} when none is stored.
   */
  public static String lastModel() {
    return get(KEY_LAST_MODEL);
  }

  /** Stores (non-blank) or clears ({@code null}/blank) the last selected model. */
  public static void storeLastModel(String model) {
    put(KEY_LAST_MODEL, model);
  }

  // ---------- CP-05 (FR-CP-05): the in-chat switcher's pinned sessions ----------

  /**
   * The pinned session ids (CP-05 AC2): a host-side set - the live v2 API has no pin verb or field
   * (probed 2026-11-17), so the chat persists its own marker here.
   */
  public static java.util.Set<String> pinnedSessions() {
    return SessionPins.parse(get(KEY_PINNED_SESSIONS));
  }

  /** Whether the session is pinned ({@code null} never is). */
  public static boolean isPinned(String sessionId) {
    return SessionPins.contains(pinnedSessions(), sessionId);
  }

  /** Pins/unpins the id and persists the set; returns the new set. */
  public static java.util.Set<String> setPinned(String sessionId, boolean pinned) {
    java.util.Set<String> next = SessionPins.with(pinnedSessions(), sessionId, pinned);
    put(KEY_PINNED_SESSIONS, SessionPins.format(next));
    return next;
  }

  // ---------- CP-09 (FR-CP-09): the model selector's recent models ----------

  /** How many recent models the selector remembers (FR-CP-09: "the last N used"). */
  public static final int RECENT_LIMIT = ModelRecents.LIMIT;

  /** The persisted recents, most-recent first; empty when none are stored. */
  public static List<String> recentModels() {
    return ModelRecents.parse(get(KEY_RECENT_MODELS));
  }

  /** Records a deliberately used model (it moves to the front, list trimmed) and persists. */
  public static void recordModelUsed(String model) {
    put(
        KEY_RECENT_MODELS,
        ModelRecents.format(ModelRecents.recentWith(recentModels(), model, RECENT_LIMIT)));
  }

  // ---------- U-105: the governed /goal seam's remembered goal ticket ----------

  /** The goal ticket the /goal verbs act on (the last captured one); {@code null} when none. */
  public static String goalTicket() {
    return get(KEY_GOAL_TICKET);
  }

  /** Remembers the active goal ticket; {@code null}/blank clears it (cleared or forgotten). */
  public static void storeGoalTicket(String ticketId) {
    put(KEY_GOAL_TICKET, ticketId);
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
