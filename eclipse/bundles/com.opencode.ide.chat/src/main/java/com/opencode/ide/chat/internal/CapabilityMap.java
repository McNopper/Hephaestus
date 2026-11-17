package com.opencode.ide.chat.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code /help} capability map (CP-14 / FR-CP-14): every TUI built-in slash command and WHERE
 * its capability lives in this harness - so {@code /help} can never name a TUI command without
 * saying whether, and where, the harness actually has it.
 *
 * <p><b>Sources (anti-drift):</b> the command set is Inventory A3 of {@code
 * docs/chat-parity-requirements.md} (the TUI built-in set: the 13 named plus the five V1
 * additions), plus the two commands this harness's own registry carries (`/unshare`) or that
 * Inventory A names as command surfaces (`/diff`, `/goal`). The LOCATIONS come from Inventory B,
 * its Host-tier / Upstream-blocked paragraph and ROADMAP's upstream-blocked list - stated as of
 * 2026-11-17, i.e. AFTER this session's lanes (several Inventory B rows predate CP-01/02/04/05/06/
 * 08/10/11/12; the map carries today's shipped truth and names the ticket that landed it).
 *
 * <p>Location vocabulary (the FR's categories plus the honest sixth answer):
 *
 * <ul>
 *   <li>{@code chat …} - served inside the chat view;
 *   <li>{@code Session Details …} - one hop away in the Session Details tab;
 *   <li>{@code Server view …} / {@code Providers view …};
 *   <li>{@code host tier …} - deliberately Eclipse's (editors, themes, update);
 *   <li>{@code upstream-blocked …} - no v2 route/capability upstream;
 *   <li>{@code not in the harness yet (T-0xx)} - an open child; the capability exists nowhere here,
 *       which is the only honest answer until it ships.
 * </ul>
 *
 * <p>Probe outcomes land here too: {@code /btw} became {@code upstream-blocked} when T-069's
 * probe-first pass found no side-question verb anywhere in the live 2.3.0 contract (zero {@code
 * aside} hits in {@code /openapi.json}).
 */
public final class CapabilityMap {

  /**
   * command -> capability location, in Inventory A3 order (insertion order IS the display order).
   */
  private static final Map<String, String> COMMANDS = new LinkedHashMap<>();

  static {
    COMMANDS.put("/new", "chat - New Session action (session created on first prompt)");
    COMMANDS.put(
        "/sessions", "chat - the Sessions… switcher: list, switch in place, pin, delete (CP-05)");
    COMMANDS.put("/models", "chat - Model combo in the selector row (recents first, CP-09)");
    COMMANDS.put("/agents", "chat - Agent combo in the selector row");
    COMMANDS.put("/undo", "chat - toolbar Undo + `/undo` (git snapshot restore)");
    COMMANDS.put("/redo", "chat - toolbar Redo + `/redo`");
    COMMANDS.put("/editor", "host tier - Eclipse editors (VISUAL/EDITOR for the prompt)");
    COMMANDS.put(
        "/compact", "chat - `/compact` built-in, same call as Session Details 'Compact context'");
    COMMANDS.put(
        "/btw",
        "upstream-blocked - no side-question verb in the live contract (T-069 probe, openapi 2.3.0: 0 'aside' hits)");
    COMMANDS.put("/init", "chat - `/init` built-in");
    COMMANDS.put("/help", "chat - `/help` built-in (this capability map)");
    COMMANDS.put("/thinking", "chat - `/thinking` built-in + the Reasoning toggle");
    COMMANDS.put("/highlights", "not in the harness yet (no harness counterpart)");
    COMMANDS.put("/connect", "Providers view - OAuth connect");
    COMMANDS.put("/export", "Session Details - Export (plus Log / Stats there)");
    COMMANDS.put("/themes", "host tier - Eclipse themes");
    COMMANDS.put("/update", "host tier - Eclipse update path");
    COMMANDS.put(
        "/share",
        "upstream-blocked - no v2 route (upstream retired sharing); `/share` answers honestly");
    COMMANDS.put("/unshare", "upstream-blocked - same route gap as `/share`");
    COMMANDS.put(
        "/diff", "chat - the per-turn changed-files row and its in-chat diff surface (CP-04)");
    COMMANDS.put(
        "/goal",
        "chat - the goal chip + dialog (visible state, T-149); the loop itself is adopt-and-govern (U-105)");
  }

  private CapabilityMap() {}

  /** The map itself: command -> location, ordered for display; unmodifiable. */
  public static Map<String, String> commands() {
    return java.util.Collections.unmodifiableMap(COMMANDS);
  }

  /** Every command the map covers, in order (the test pins this against Inventory A3). */
  public static List<String> coveredCommands() {
    return List.copyOf(COMMANDS.keySet());
  }

  /** Whether the map covers the command (a TUI command /help must not be silent about). */
  public static boolean covers(String command) {
    return command != null && COMMANDS.containsKey(command);
  }

  /** The rendered block {@code /help} appends - one line per command, `-` separated. */
  public static String block() {
    StringBuilder out = new StringBuilder("Where TUI commands live in this harness:");
    COMMANDS.forEach(
        (command, where) -> out.append("\n").append(command).append(" - ").append(where));
    return out.toString();
  }
}
