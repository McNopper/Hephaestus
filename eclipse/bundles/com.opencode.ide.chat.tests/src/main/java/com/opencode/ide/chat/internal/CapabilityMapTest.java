package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for CP-14 / FR-CP-14 - the {@code /help} capability map: coverage of the TUI command
 * inventory (so /help cannot stay silent about a command), a location per FR category, and no
 * command that maps to nothing.
 */
public class CapabilityMapTest {

  /**
   * Inventory A3 of docs/chat-parity-requirements.md - the TUI built-in set (13 named + the five V1
   * additions) plus /unshare from this harness's registry and the two Inventory A command surfaces
   * (/diff, /goal). The test pins the map against THE DOCUMENT's inventory: adding a TUI command
   * there without a location here fails this test (anti-drift).
   */
  private static final List<String> INVENTORY_A3 =
      List.of(
          "/new",
          "/sessions",
          "/models",
          "/agents",
          "/undo",
          "/redo",
          "/editor",
          "/compact",
          "/btw",
          "/init",
          "/help",
          "/thinking",
          "/highlights",
          "/connect",
          "/export",
          "/themes",
          "/update",
          "/share",
          "/unshare",
          "/diff",
          "/goal");

  @Test
  public void everyInventoryCommandHasACapabilityLocation() {
    for (String command : INVENTORY_A3) {
      assertTrue("missing location for " + command, CapabilityMap.covers(command));
      String location = CapabilityMap.commands().get(command);
      assertTrue("blank location for " + command, location != null && !location.isBlank());
    }
    assertEquals("no silent extras either", INVENTORY_A3.size(), CapabilityMap.commands().size());
  }

  @Test
  public void theMapStatesEveryCategoryTheFrNames() {
    List<String> locations = List.copyOf(CapabilityMap.commands().values());
    assertTrue("in-chat", locations.stream().anyMatch(v -> v.startsWith("chat")));
    assertTrue("Session Details", locations.stream().anyMatch(v -> v.contains("Session Details")));
    assertTrue("Server/Providers view", locations.stream().anyMatch(v -> v.contains("view")));
    assertTrue("host tier", locations.stream().anyMatch(v -> v.startsWith("host tier")));
    assertTrue(
        "upstream-blocked", locations.stream().anyMatch(v -> v.startsWith("upstream-blocked")));
    assertTrue(
        "the honest not-yet answer",
        locations.stream().anyMatch(v -> v.startsWith("not in the harness yet")));
  }

  @Test
  public void upstreamBlockedEntriesArePresent() {
    assertTrue(CapabilityMap.covers("/share"));
    assertTrue(CapabilityMap.commands().get("/share").contains("upstream-blocked"));
    assertTrue(CapabilityMap.commands().get("/unshare").contains("upstream-blocked"));
  }

  @Test
  public void theRenderedBlockCarriesEveryLineAndStaysOrderStable() {
    String block = CapabilityMap.block();
    for (String command : INVENTORY_A3) {
      assertTrue("block misses " + command, block.contains("\n" + command + " - "));
    }
    assertTrue(block.startsWith("Where TUI commands live in this harness:"));
    // display order = Inventory A3 order (insertion order), not alphabetical
    assertEquals("/new", CapabilityMap.coveredCommands().get(0));
    assertEquals(
        INVENTORY_A3.get(INVENTORY_A3.size() - 1),
        CapabilityMap.coveredCommands().get(CapabilityMap.coveredCommands().size() - 1));
  }

  @Test
  public void unknownCommandsAreNotCoveredAndTheMapIsUnmodifiable() {
    assertFalse(CapabilityMap.covers("/does-not-exist"));
    assertFalse(CapabilityMap.covers(null));
    try {
      CapabilityMap.commands().put("/x", "y");
      throw new AssertionError("the map must be unmodifiable");
    } catch (UnsupportedOperationException expected) {
      // the help text must not be corruptible at runtime
    }
    Map<String, String> snapshot = CapabilityMap.commands();
    assertEquals(snapshot.size(), CapabilityMap.coveredCommands().size());
  }
}
