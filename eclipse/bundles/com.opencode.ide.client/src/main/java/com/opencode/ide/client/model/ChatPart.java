package com.opencode.ide.client.model;

/**
 * One part of a chat message. Only the fields needed for rendering are mapped; the {@code type}
 * discriminates ({@code text}, {@code reasoning}, {@code tool}, {@code step-start}, {@code
 * step-finish}, ...). {@code text} is present on {@code text} and {@code reasoning} parts; {@code
 * tool} parts carry the tool NAME in {@code name} plus a nested {@code state} object ({@code
 * {"status":"running"|"completed"|"error", ...}}, {@code state} an OBJECT on the wire, never a
 * string) - the live openapi schema {@code Session.Message.Assistant.Tool} declares {@code name}
 * (verified against the live 2.3.2 service 2026-11-20: 52/52 tool parts carry {@code name}; the
 * pre-2.x {@code tool} key is gone - reading it made every tool line render "unknown" while the
 * hand-built fixtures still passed, T-181). Other types are ignored by the chat renderer.
 */
public record ChatPart(String type, String text, String name, ToolState state) {

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
