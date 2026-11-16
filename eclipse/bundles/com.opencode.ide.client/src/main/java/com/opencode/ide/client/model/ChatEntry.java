package com.opencode.ide.client.model;

import java.util.List;

/**
 * One message entry from {@code GET /session/:id/message}: the message info plus its parts. {@code
 * role} on {@link ChatMessageInfo} is {@code user} or {@code assistant} ({@code shell} for shell
 * runs, chat parity CP-02 - a shell row carries its {@code command} and its captured {@code
 * shellOutput} tail, both {@code null} on every other role; {@code compaction} rows carry their
 * {@link Compaction} payload instead, both {@code null} on every other role).
 */
public record ChatEntry(
    ChatMessageInfo info,
    List<ChatPart> parts,
    String command,
    String shellOutput,
    Compaction compaction,
    Notice notice) {

  public ChatEntry {
    parts = (parts == null) ? List.of() : List.copyOf(parts);
  }

  /**
   * The historical two-field shape ({@code command}/{@code shellOutput}/{@code compaction} null);
   * kept for callers.
   */
  public ChatEntry(ChatMessageInfo info, List<ChatPart> parts) {
    this(info, parts, null, null, null, null);
  }

  /** The shell-run shape ({@code compaction} null); kept for callers. */
  public ChatEntry(ChatMessageInfo info, List<ChatPart> parts, String command, String shellOutput) {
    this(info, parts, command, shellOutput, null, null);
  }

  /**
   * The compaction shape ({@code notice} null); kept for callers (T-147's row builder and its
   * fixtures).
   */
  public ChatEntry(
      ChatMessageInfo info,
      List<ChatPart> parts,
      String command,
      String shellOutput,
      Compaction compaction) {
    this(info, parts, command, shellOutput, compaction, null);
  }

  /**
   * The notice payload (T-149): everything a {@code type=synthetic} message carries for the TUI's
   * inline notice row ({@code routes/session/index.tsx} SessionNoticeMessage - icon + {@code
   * description}): {@code source} from {@code metadata.source} ({@code goal} picks the &#9678;
   * glyph), {@code goalId} from {@code metadata.goalID} (live-append dedupe), {@code description}
   * the row's display text ({@code null}/blank rows are dropped, TUI {@code rows.ts:392}). The
   * synthetic message's full {@code text} - the injected objective prompt - is NEVER a part: the
   * TUI never renders it (same no-fabricated-text discipline as {@link Compaction}).
   */
  public record Notice(String source, String goalId, String description) {}

  /**
   * The compaction payload (T-147): everything a {@code type=compaction} message carries for the
   * TUI's CompactionMessage row - the lifecycle {@code status} ({@code running}/{@code completed}/
   * {@code failed}), the markdown {@code summary} (completed/running), the failure's {@code
   * errorType}/{@code errorMessage}, and {@code providerNative} ({@code native.messages} present -
   * the wire flag behind the TUI's "Provider compaction" label).
   */
  public record Compaction(
      String status,
      String summary,
      String errorType,
      String errorMessage,
      boolean providerNative) {

    /** TUI cancelled(): a failed compaction whose error type is {@code aborted}. */
    public boolean cancelled() {
      return "failed".equals(status) && "aborted".equals(errorType);
    }

    /**
     * TUI text(): what the divider row's body shows - the failure message (empty when cancelled),
     * otherwise the summary.
     */
    public String content() {
      if ("failed".equals(status)) {
        return cancelled() ? "" : (errorMessage == null ? "" : errorMessage);
      }
      return summary == null ? "" : summary;
    }

    /**
     * TUI label: provider-native compactions say "Provider compaction", everything else
     * "Compaction".
     */
    public String label() {
      return label(status, providerNative);
    }

    /**
     * Label for a payload assembled from the live events (no {@link Compaction} instance at hand).
     */
    public static String label(String status, boolean providerNative) {
      return "completed".equals(status) && providerNative ? "Provider compaction" : "Compaction";
    }
  }

  /**
   * @return the concatenated text of all {@code text} parts (rendered markdown); a compaction entry
   *     returns its TUI content ({@link Compaction#content()}), a notice entry its display {@code
   *     description} - the parts list is empty by design on both, the payload carries the content
   *     (the injected synthetic {@code text} is never a part, T-149).
   */
  public String text() {
    if (compaction != null) {
      return compaction.content();
    }
    if (notice != null) {
      return notice.description() == null ? "" : notice.description();
    }
    StringBuilder sb = new StringBuilder();
    for (ChatPart part : parts) {
      if (part.isText() && part.text() != null) {
        sb.append(part.text());
      }
    }
    return sb.toString();
  }

  /**
   * @return the concatenated reasoning text (shown collapsed in the chat UI).
   */
  public String reasoning() {
    StringBuilder sb = new StringBuilder();
    for (ChatPart part : parts) {
      if (part.isReasoning() && part.text() != null) {
        sb.append(part.text());
      }
    }
    return sb.toString();
  }

  /** Convenience: is this a user message? */
  public boolean isUser() {
    return info != null && "user".equals(info.role());
  }

  /** Convenience: is this a shell run (chat parity CP-02, the '!'-mode transcript rows)? */
  public boolean isShell() {
    return info != null && "shell".equals(info.role());
  }

  /**
   * Convenience: is this a context-compaction row (T-145/T-147)? Role-based, never payload-based -
   * a {@code compaction} role must always route to the compaction row, never fall through to an
   * empty assistant bubble (the T-145 defect).
   */
  public boolean isCompaction() {
    return info != null && "compaction".equals(info.role());
  }

  /**
   * Convenience: is this a synthetic notice row (T-149)? Role-based like {@link #isCompaction()} -
   * a {@code synthetic} role always routes to the notice row, never to an assistant bubble (the
   * T-149 defect: the injected objective prompt rendered in full).
   */
  public boolean isNotice() {
    return info != null && "synthetic".equals(info.role());
  }
}
