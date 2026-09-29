package com.opencode.ide.ui.console;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import com.opencode.ide.ui.internal.UiActivator;

/**
 * The shell-task consoles (U-041): one Eclipse {@link MessageConsole} per
 * shell task opened from the Session Details view's "Open in Console" —
 * the same ConsoleManager surface {@link AgentToolsConsole} uses for agent
 * tool invocations, but per-task (a console per {@code sh_} id, so several
 * tasks can be watched side by side).
 *
 * <p>Lifecycle: consoles are created lazily on {@link #open} and stay with
 * the workbench afterwards (nothing is disposed here — the Console view owns
 * them). Each console is revealed once, on creation, so a refresh does not
 * keep stealing focus. {@link #refresh} replaces an OPEN console's content
 * with the newest output tail — the Session Details view calls it from its
 * refresh cycle (auto-refresh timer / SSE reload), which is what keeps a
 * running task's tail live.</p>
 *
 * <p>Threading: every method may be called from any thread; document writes
 * hop to the UI thread via {@code Display.asyncExec}. Bounded memory: the
 * consoles are water-marked like the Agent Tools console.</p>
 */
public final class ShellTasksConsole {

    /** Char water-marks for a console document (bounded output history). */
    public static final int WATERMARK_LOW = 128 * 1024;
    public static final int WATERMARK_HIGH = 256 * 1024;

    private static final Map<String, MessageConsole> CONSOLES = new ConcurrentHashMap<>();

    private ShellTasksConsole() {
    }

    /**
     * Creates (or finds) the console of one shell task and shows its current
     * output tail.
     *
     * @param shellId the {@code sh_} task id (the console's identity)
     * @param command the command line (rendered as the console's prompt line)
     * @param output  the task's output tail ({@code null} renders as empty)
     */
    public static void open(String shellId, String command, String output) {
        render(shellId, command, output, null);
    }

    /**
     * Replaces the content of an OPEN console (a no-op for tasks whose
     * console was never opened or was closed by the user).
     *
     * @param shellId the {@code sh_} task id
     * @param command the command line (re-rendered as the prompt line)
     * @param output  the newest output tail
     * @param status  the task's lifecycle label ({@code null} to omit)
     */
    public static void refresh(String shellId, String command, String output, String status) {
        if (shellId == null || !CONSOLES.containsKey(shellId)) {
            return;
        }
        render(shellId, command, output, status);
    }

    /** @return whether the task's console is currently tracked as open. */
    public static boolean isOpen(String shellId) {
        return shellId != null && CONSOLES.containsKey(shellId);
    }

    /** @return the ids of all open shell consoles (the refresh loop's work list). */
    public static List<String> openShellIds() {
        return List.copyOf(CONSOLES.keySet());
    }

    private static void render(String shellId, String command, String output, String status) {
        if (shellId == null || shellId.isBlank()) {
            return;
        }
        String text = document(command, output, status);
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            MessageConsole console = CONSOLES.get(shellId);
            boolean created = false;
            if (console == null) {
                console = new MessageConsole(consoleName(shellId),
                        AbstractUIPlugin.imageDescriptorFromPlugin(
                                UiActivator.PLUGIN_ID, UiActivator.ICON_MCP));
                console.setWaterMarks(WATERMARK_LOW, WATERMARK_HIGH);
                ConsolePlugin.getDefault().getConsoleManager()
                        .addConsoles(new IConsole[] {console});
                CONSOLES.put(shellId, console);
                created = true;
            }
            MessageConsoleStream stream = console.newMessageStream();
            console.clearConsole(); // the public clear; reset before the new tail
            stream.print(text);
            try {
                stream.close();
            } catch (java.io.IOException e) {
                // closing a console stream never carries meaning for the caller
            }
            if (created) {
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
            }
        });
    }

    /** {@code $ command} + the output tail + an optional status line. */
    private static String document(String command, String output, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command == null || command.isBlank() ? "(unknown command)" : command).append('\n');
        if (output != null && !output.isBlank()) {
            sb.append(output.stripTrailing()).append('\n');
        }
        if (status != null && !status.isBlank()) {
            sb.append("\n[").append(status).append("]\n");
        }
        return sb.toString();
    }

    private static String consoleName(String shellId) {
        return "Shell " + shellId;
    }

    /** Visible for tests only: the workbench's console manager. */
    static IConsoleManager consoleManager() {
        return ConsolePlugin.getDefault().getConsoleManager();
    }
}
