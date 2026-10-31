package com.opencode.ide.client;

/**
 * The shared emoji language (U-093, owner direction: entity-typed and consistent - "bug emoji is
 * used for bugs, agent emojis for agents, session emojis for sessions and so on"). ONE home for
 * every glyph the views render, so the Board and the Server view speak identically: the ticket type
 * and pipeline vocabularies moved here from the board's {@code TicketRow} (which now delegates),
 * and the Server view's entities and session states joined them. Pure data - no SWT, no OSGi - so
 * it is unit-testable and usable from any bundle.
 */
public final class Emojis {

  private Emojis() {}

  /**
   * The ticket type emoji (board U-065 owner direction: emoji over image files - colored, visible,
   * DPI-proof). Unknown/blank reads as {@code ""} (untagged, no stray spaces).
   */
  public static String typeEmoji(String type) {
    return switch (type == null ? "" : type.trim().toLowerCase()) {
      case "bug" -> "\uD83D\uDC1E"; // 🐞 lady beetle
      case "story" -> "\uD83D\uDCD6"; // 📖 open book
      case "task" -> "\uD83D\uDEE0\uFE0F"; // 🛠️ hammer and wrench
      case "spike" -> "\u26A1\uFE0F"; // ⚡ high voltage
      default -> "";
    };
  }

  /**
   * The pipeline status symbol (board vocabulary; the board view colors it - done green, running
   * blue, review amber, backlog gray). Unknown reads as {@code ""}.
   */
  public static String statusSymbol(String status) {
    return switch (status == null ? "" : status) {
      case "product-backlog" -> "\uD83D\uDCCC"; // 📌
      case "sprint-backlog" -> "\uD83D\uDCCB"; // 📋
      case "in-progress" -> "\uD83C\uDFC3"; // 🏃 running
      case "in-review" -> "\uD83D\uDC40"; // 👀
      case "done" -> "\u2705"; // ✅
      case "blocked" -> "\uD83D\uDEAB"; // 🚫
      default -> "";
    };
  }

  // ---------- Server view entities ----------

  /** An agent definition row. */
  public static final String AGENT = "\uD83E\uDD16"; // 🤖

  /** A session row (the Sessions category and agent-nested session rows alike). */
  public static final String SESSION = "\uD83D\uDCAC"; // 💬

  /** A server root row. */
  public static final String SERVER = "\uD83D\uDDA5\uFE0F"; // 🖥️

  /** An MCP server row. */
  public static final String MCP = "\uD83D\uDD0C"; // 🔌

  /** A skill row. */
  public static final String SKILL = "\u26A1\uFE0F"; // ⚡

  /** A file row (active files / working set). */
  public static final String FILE = "\uD83D\uDCC4"; // 📄

  /**
   * A pending agent question / permission ask (U-096: the collected questions surface - category,
   * rows and the session-row marker all carry it).
   */
  public static final String QUESTION = "\u2753"; // red question mark

  // ---------- session states ----------

  /** Working - the same symbol the board uses for in-progress, so "running" reads the same. */
  public static final String WORKING = "\uD83C\uDFC3"; // 🏃

  /** Thinking (the model is reasoning, no tool in flight). */
  public static final String THINKING = "\uD83E\uDDE0"; // 🧠

  /** The run ended failed. */
  public static final String FAILED = "\uD83D\uDD34"; // 🔴

  /**
   * Idle - alive but quiet. Unicode has no grey circle emoji, so this is the monochrome circle: it
   * renders in the label's text color, i.e. a grey ring on the white tree (owner choice 2026-10-12:
   * grey, after the white ⚪ proved invisible on the white background).
   */
  public static final String IDLE = "\u25CB"; // ○

  /**
   * Glyph for a running tool's name (the Details live label): shell work on a keyboard, edits on a
   * pencil, reads on a book, searches under a magnifier, web on a globe, subagent tasks as a robot,
   * todos on a list; anything unknown gets gears.
   */
  public static String toolGlyph(String tool) {
    String t = tool == null ? "" : tool.trim().toLowerCase();
    if (t.equals("bash") || t.equals("shell")) {
      return "\u2328\uFE0F"; // ⌨️
    }
    if (t.equals("edit") || t.equals("patch") || t.equals("write")) {
      return "\u270F\uFE0F"; // ✏️
    }
    if (t.equals("read")) {
      return "\uD83D\uDCD6"; // 📖
    }
    if (t.equals("glob") || t.equals("grep") || t.equals("list") || t.equals("find")) {
      return "\uD83D\uDD0D"; // 🔍
    }
    if (t.equals("webfetch") || t.equals("websearch")) {
      return "\uD83C\uDF10"; // 🌐
    }
    if (t.equals("task")) {
      return "\uD83E\uDD16"; // 🤖
    }
    if (t.equals("todo") || t.equals("todowrite")) {
      return "\uD83D\uDCCB"; // 📋
    }
    return "\u2699\uFE0F"; // ⚙️
  }
}
