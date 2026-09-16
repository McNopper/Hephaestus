package com.opencode.ide.ui.internal;

import org.eclipse.ui.IStartup;

import com.opencode.ide.ui.console.AgentToolsConsole;

/**
 * Early-startup hook that attaches the "Agent Tools" console listener to
 * the tool-invocation hub ({@code ToolInvocationHub}) as soon as the
 * workbench is up — independent of whether any OpenCode view has been
 * opened yet (the ui bundle is lazy-activated, and an agent can drive
 * {@code eclipse-build} tools without a single OpenCode view showing).
 * Detach happens on bundle stop ({@code UiActivator} delegates to
 * {@link AgentToolsConsole#uninstall()}).
 */
public class AgentToolsStartup implements IStartup {

    @Override
    public void earlyStartup() {
        AgentToolsConsole.install();
    }
}
