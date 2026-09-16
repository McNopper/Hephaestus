package com.opencode.ide.ui.console;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.opencode.ide.tools.ToolInvocation;
import com.opencode.ide.tools.ToolInvocationHub;
import com.opencode.ide.tools.ToolInvocationListener;
import com.opencode.ide.ui.internal.UiActivator;

/**
 * The Eclipse "Agent Tools" console: the UI half of the tool-invocation
 * seam ({@link ToolInvocationHub}). One shared {@link MessageConsole} shows
 * every {@code eclipse-build} tool call an agent makes — name, short
 * argument summary and output tail, formatted by {@link AgentToolsFormat} —
 * so the human watches agent-driven builds, test runs and debug batches
 * live while they happen (U-009).
 *
 * <p>Lifecycle: {@link #install()} (from {@code AgentToolsStartup})
 * registers the single listener on the hub; {@link #uninstall()} (bundle
 * stop) detaches it. The console itself is created lazily on the first
 * published invocation — an Eclipse that never runs an agent tool never
 * gets the console — and is revealed in the Console view once, on that
 * first invocation (reveal-on-first-use, not on every call, so an active
 * agent does not keep stealing focus). The workbench owns the console after
 * creation; nothing is disposed here.</p>
 *
 * <p>Threading: hub callbacks arrive on the MCP HTTP executor / stdio
 * reader threads; the listener hops to the UI thread via
 * {@code Display.asyncExec} before touching the console. Bounded memory:
 * the console is water-marked ({@link #WATERMARK_LOW}/{@link #WATERMARK_HIGH})
 * so a long debug session cannot grow it without bound.</p>
 */
public final class AgentToolsConsole {

    /** Console name as it appears in the Console view's drop-down. */
    public static final String CONSOLE_NAME = "Agent Tools";

    /** Char water-marks for the console document (low/high): the console sheds the oldest text beyond the high mark. */
    public static final int WATERMARK_LOW = 128 * 1024;
    public static final int WATERMARK_HIGH = 256 * 1024;

    private static final AgentToolsConsole INSTANCE = new AgentToolsConsole();

    private final ToolInvocationListener listener = this::onToolInvocation;
    private final AtomicBoolean attached = new AtomicBoolean();

    private MessageConsole console;
    private MessageConsoleStream stream;
    private boolean revealed;

    private AgentToolsConsole() {
    }

    /** Registers the shared listener on the {@link ToolInvocationHub} (idempotent). */
    public static void install() {
        if (INSTANCE.attached.compareAndSet(false, true)) {
            ToolInvocationHub.addListener(INSTANCE.listener);
        }
    }

    /** Detaches the listener (bundle stop); the console itself stays with the workbench. */
    public static void uninstall() {
        if (INSTANCE.attached.compareAndSet(true, false)) {
            ToolInvocationHub.removeListener(INSTANCE.listener);
        }
    }

    private void onToolInvocation(ToolInvocation invocation) {
        String block = AgentToolsFormat.block(invocation);
        if (block.isEmpty()) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> append(block));
    }

    private void append(String block) {
        MessageConsole c = console();
        if (c == null) {
            return;
        }
        if (stream == null) {
            stream = c.newMessageStream();
        }
        stream.print(block);
        if (!revealed) {
            revealed = true;
            ConsolePlugin.getDefault().getConsoleManager().showConsoleView(c);
        }
    }

    private MessageConsole console() {
        if (console != null) {
            return console;
        }
        MessageConsole created = new MessageConsole(CONSOLE_NAME,
                AbstractUIPlugin.imageDescriptorFromPlugin(
                        UiActivator.PLUGIN_ID, UiActivator.ICON_MCP));
        created.setWaterMarks(WATERMARK_LOW, WATERMARK_HIGH);
        ConsolePlugin.getDefault().getConsoleManager()
                .addConsoles(new IConsole[] {created});
        console = created;
        return created;
    }

    /** @return whether the listener is currently attached to the hub (tests and diagnostics). */
    public static boolean isInstalled() {
        return INSTANCE.attached.get();
    }

    /** Visible for tests only: how many consoles the manager currently knows. */
    static IConsoleManager consoleManager() {
        return ConsolePlugin.getDefault().getConsoleManager();
    }
}
