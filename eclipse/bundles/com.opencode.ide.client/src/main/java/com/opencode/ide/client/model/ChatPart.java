package com.opencode.ide.client.model;

/**
 * One part of a chat message. Only the fields needed for rendering are mapped; the {@code type}
 * discriminates ({@code text}, {@code reasoning}, {@code tool}, {@code step-start}, {@code
 * step-finish}, ...). {@code text} is present on {@code text} and {@code reasoning} parts; {@code
 * tool} parts carry the tool name plus a nested {@code state} object ({@code
 * {"status":"running"|"completed"|"error", ...}}, verified against a live v1.18 server — {@code
 * state} is an OBJECT on the wire, never a string); other types are ignored by the chat renderer.
 */
public record ChatPart(String type, String text, String tool, ToolState state) {

  /** The {@code state} object of a tool part; {@code status} is the coarse lifecycle. */
  public record ToolState(String status) {}

  public boolean isText() {
    return "text".equals(type);
  }

  public boolean isReasoning() {
    return "reasoning".equals(type);
  }

  public boolean isTool() {
    return "tool".equals(type);
  }

  /** Coarse tool lifecycle ({@code running}/{@code completed}/{@code error}), or {@code null}. */
  public String stateName() {
    return state == null ? null : state.status();
  }
}
