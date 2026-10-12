package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The shared emoji language contract (U-093): the board's ticket type and pipeline vocabularies
 * (moved here from TicketRow - the board delegates, values byte-identical), the Server view entity
 * emojis, session state glyphs, and the per-tool glyph mapping. One home, so every view speaks
 * identically.
 */
public class EmojisTest {

  @Test
  public void ticketTypesMapToTheirEmojiAndUnknownStaysUntagged() {
    // escape form: the variation selectors (U+FE0F) must survive exactly - a literal
    // pasted here lost them in the tooling round-trip (U-093 gate lesson)
    assertEquals("\uD83D\uDC1E", Emojis.typeEmoji("bug"));
    assertEquals("\uD83D\uDCD6", Emojis.typeEmoji("story"));
    assertEquals("\uD83D\uDEE0\uFE0F", Emojis.typeEmoji("task"));
    assertEquals("\u26A1\uFE0F", Emojis.typeEmoji("spike"));
    assertEquals("", Emojis.typeEmoji("unknown"));
    assertEquals("", Emojis.typeEmoji(null));
    assertEquals("", Emojis.typeEmoji("  "));
    assertEquals("case is normalized", "\uD83D\uDC1E", Emojis.typeEmoji("BUG"));
  }

  @Test
  public void pipelineStatusesMapToTheirSymbols() {
    assertEquals("📌", Emojis.statusSymbol("product-backlog"));
    assertEquals("📋", Emojis.statusSymbol("sprint-backlog"));
    assertEquals("🏃", Emojis.statusSymbol("in-progress"));
    assertEquals("👀", Emojis.statusSymbol("in-review"));
    assertEquals("✅", Emojis.statusSymbol("done"));
    assertEquals("🚫", Emojis.statusSymbol("blocked"));
    assertEquals("", Emojis.statusSymbol("paused"));
    assertEquals("", Emojis.statusSymbol(null));
  }

  @Test
  public void serverEntitiesHaveTheirEmoji() {
    assertEquals("\uD83E\uDD16", Emojis.AGENT);
    assertEquals("\uD83D\uDCAC", Emojis.SESSION);
    assertEquals("\uD83D\uDD0C", Emojis.MCP);
    assertEquals("\u26A1\uFE0F", Emojis.SKILL);
    assertEquals("\uD83D\uDCC4", Emojis.FILE);
    assertEquals("\uD83D\uDDA5\uFE0F", Emojis.SERVER);
  }

  @Test
  public void sessionStatesHaveTheirGlyphs() {
    assertEquals("🏃", Emojis.WORKING);
    assertEquals("🧠", Emojis.THINKING);
    assertEquals("🔴", Emojis.FAILED);
    assertEquals("\u25CB", Emojis.IDLE);
  }

  @Test
  public void toolsMapToTheirGlyphsWithAGearDefault() {
    assertEquals("⌨️", Emojis.toolGlyph("bash"));
    assertEquals("⌨️", Emojis.toolGlyph("shell"));
    assertEquals("✏️", Emojis.toolGlyph("edit"));
    assertEquals("✏️", Emojis.toolGlyph("patch"));
    assertEquals("📖", Emojis.toolGlyph("read"));
    assertEquals("🔍", Emojis.toolGlyph("grep"));
    assertEquals("🔍", Emojis.toolGlyph("glob"));
    assertEquals("🌐", Emojis.toolGlyph("webfetch"));
    assertEquals("🤖", Emojis.toolGlyph("task"));
    assertEquals("📋", Emojis.toolGlyph("todowrite"));
    assertEquals("⚙️", Emojis.toolGlyph("mystery_tool"));
    assertEquals("⚙️", Emojis.toolGlyph(null));
    assertEquals("case is normalized", "⌨️", Emojis.toolGlyph("Bash"));
  }
}
