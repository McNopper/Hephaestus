package com.opencode.ide.client.model;

import java.util.List;

/**
 * One message entry from {@code GET /session/:id/message}: the message info plus its parts. {@code
 * role} on {@link ChatMessageInfo} is {@code user} or {@code assistant} ({@code shell} for shell
 * runs, chat parity CP-02 - a shell row carries its {@code command} and its captured {@code
 * shellOutput} tail, both {@code null} on every other role).
 */
public record ChatEntry(
    ChatMessageInfo info, List<ChatPart> parts, String command, String shellOutput) {

  public ChatEntry {
    parts = (parts == null) ? List.of() : List.copyOf(parts);
  }

  /**
   * The historical two-field shape ({@code command}/{@code shellOutput} null); kept for callers.
   */
  public ChatEntry(ChatMessageInfo info, List<ChatPart> parts) {
    this(info, parts, null, null);
  }

  /**
   * @return the concatenated text of all {@code text} parts (rendered markdown).
   */
  public String text() {
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

  /** Convenience: is this a context-compaction marker (T-145, transcript notice rows)? */
  public boolean isCompaction() {
    return info != null && "compaction".equals(info.role());
  }
}
