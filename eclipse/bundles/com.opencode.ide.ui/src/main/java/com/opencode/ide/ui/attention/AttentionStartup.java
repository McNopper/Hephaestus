package com.opencode.ide.ui.attention;

import org.eclipse.ui.IStartup;

/**
 * Early-startup hook that wires the U-047 attention notifications to the
 * primary connection's global {@code /event} stream as soon as the workbench
 * is up — independent of whether any OpenCode view has been opened (same
 * pattern as {@code AgentToolsStartup}; the ui bundle is lazy, but
 * {@code org.eclipse.ui.startup} forces it alive). Notifications stay
 * disabled until the user opts in ({@code attention.enabled}, TUI parity),
 * so this registration is silent and costless by default.
 */
public class AttentionStartup implements IStartup {

    @Override
    public void earlyStartup() {
        AttentionNotifications.install();
    }
}
