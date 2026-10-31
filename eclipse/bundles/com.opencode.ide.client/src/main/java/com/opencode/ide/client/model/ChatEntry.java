package com.opencode.ide.client.model;

import java.util.List;

/**
 * One message entry from {@code GET /session/:id/message}: the message info plus its parts. {@code
 * role} on {@link ChatMessageInfo} is {@code user} or {@code assistant}.
 */
public record ChatEntry(ChatMessageInfo info, List<ChatPart> parts) {

  public ChatEntry {
    parts = (parts == null) ? List.of() : List.copyOf(parts);
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
}
