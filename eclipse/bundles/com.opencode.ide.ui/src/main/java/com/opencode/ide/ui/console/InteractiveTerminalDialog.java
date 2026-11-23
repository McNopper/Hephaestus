package com.opencode.ide.ui.console;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.PtyLink;
import com.opencode.ide.client.PtyStdin;
import com.opencode.ide.client.PtyStream;
import com.opencode.ide.client.model.ShellExecutable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * The interactive terminal pane (T-201, FR-CP-13): a modeless SWT window bound to one PTY - the
 * PTY's raw output stream renders above, a single-line command input below, Enter submits the line
 * as PTY stdin ({@code line + CR}, the payload rule live-probed on opencode 2.4.0).
 *
 * <p><b>Why a pane and not the Eclipse console:</b> shell-task consoles cannot take input at all
 * ({@code POST /api/session/{id}/shell} spawns NON-interactive commands - the route set has no
 * stdin write), the {@code IOConsole} editable-input machinery would render local echo PLUS the
 * PTY's own echo for every keystroke (with divergent local backspace), and raw key capture over the
 * console page fights SWT's re-entrant verify events ({@code IOConsoleViewer.handleVerifyEvent}
 * re-enters {@code StyledText.replaceTextRange}, which fires verify again - no sound dedup signal).
 * This pane is the smallest surface where keystroke routing is exact: line editing happens locally
 * (which is what the line-based shells cmd/pwsh/sh expect anyway), the PTY echoes the committed
 * line once, output streams back verbatim. A real terminal-emulator host ({@code
 * org.eclipse.tm.terminal}) stays parked on target-platform work - see the "Adopt next" row in
 * docs/opencode-v2-adoption.md.
 *
 * <p>Lifecycle: {@link #start} creates the PTY and connects the stream (BLOCKING - run it on a
 * background job; safe under {@link com.opencode.ide.ui.internal.ViewLoadSupport}'s retries: a
 * failed attempt removes the PTY it created and no pane exists yet); {@link #open} runs on the UI
 * thread, attaches the renderer (output that arrived meanwhile is buffered by the {@link PtyLink}
 * and flushed in order) and closes the link when the pane is closed - socket close + PTY removal
 * exactly once.
 */
public final class InteractiveTerminalDialog {

  /** Output characters kept in the pane (bounded like the console watermarks). */
  private static final int OUTPUT_LIMIT = 256 * 1024;

  private final Shell shell;
  private final Text output;
  private final Text input;
  private final PtyLink link;

  /** One connected terminal: the link the pane renders/drives plus its id for status lines. */
  public record Terminal(String ptyId, PtyLink link) {}

  private InteractiveTerminalDialog(Shell shell, Text output, Text input, PtyLink link) {
    this.shell = shell;
    this.output = output;
    this.input = input;
    this.link = link;
  }

  /**
   * Creates this session's PTY and connects its interactive stream. BLOCKS (REST create + WebSocket
   * handshake) - run on a background job; the caller opens the pane in its success callback (the UI
   * thread, ViewLoadSupport's delivery contract).
   *
   * @param client the server connection
   * @param sessionId the owning session; its working directory becomes the PTY cwd when known
   * @param title pane title / PTY title ({@code null} falls back to "terminal")
   * @return the connected terminal
   * @throws OpencodeException create/connect failures - a PTY created before a failed connect is
   *     removed here, so the caller's retry cannot leak one
   */
  public static Terminal start(OpencodeClient client, String sessionId, String title)
      throws OpencodeException {
    String paneTitle = (title == null || title.isBlank()) ? "terminal" : title;
    String cwd = sessionDirectory(client, sessionId);
    Map<String, Object> created = client.createPty(shellCommand(client), List.of(), cwd, paneTitle);
    Object idValue = created.get("id");
    String ptyId = (idValue == null) ? null : String.valueOf(idValue);
    if (ptyId == null || ptyId.isBlank()) {
      throw new OpencodeException("opencode POST /api/pty succeeded without a pty id");
    }
    PtyLink link = new PtyLink();
    try {
      PtyStream stream = client.connectPty(ptyId, link);
      link.attach(stream, () -> removeQuietly(client, ptyId));
    } catch (OpencodeException | RuntimeException e) {
      removeQuietly(client, ptyId);
      throw e;
    }
    return new Terminal(ptyId, link);
  }

  /**
   * Opens the pane over an already-connected terminal. MUST run on the UI thread (the view's
   * success callback does).
   *
   * @param parent the parent shell (the Session Details view's)
   * @param terminal the result of {@link #start}
   * @param title pane title ({@code null} falls back to the pty id)
   * @return the open dialog, or {@code null} when the parent is gone (the link is closed then)
   */
  public static InteractiveTerminalDialog open(Shell parent, Terminal terminal, String title) {
    if (parent == null || parent.isDisposed()) {
      terminal.link().close();
      return null;
    }
    Shell shell = new Shell(parent, SWT.SHELL_TRIM | SWT.MODELESS);
    String paneTitle = (title == null || title.isBlank()) ? terminal.ptyId() : title;
    shell.setText("Interactive terminal - " + paneTitle);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 6;
    layout.marginHeight = 6;
    shell.setLayout(layout);

    Text output =
        new Text(shell, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
    output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Label hint = new Label(shell, SWT.NONE);
    hint.setText("Enter sends the line to the PTY (Ctrl+C interrupts); the answer streams above.");
    hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Text input = new Text(shell, SWT.BORDER);
    input.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    InteractiveTerminalDialog dialog =
        new InteractiveTerminalDialog(shell, output, input, terminal.link());
    dialog.wire();
    shell.setSize(900, 600);
    shell.open();
    input.setFocus();
    return dialog;
  }

  /**
   * @return the pane's shell (tests/callers that need to check it is still open).
   */
  public Shell shell() {
    return shell;
  }

  /** Renderer, key routing and teardown - all on the UI thread (callers open on it). */
  private void wire() {
    link.renderer(this::append);
    input.addSelectionListener(
        new org.eclipse.swt.events.SelectionAdapter() {
          @Override
          public void widgetSelected(org.eclipse.swt.events.SelectionEvent event) {
            submit();
          }
        });
    input.addListener(
        SWT.KeyDown,
        event -> {
          if ((event.stateMask & SWT.CTRL) != 0) {
            // interrupt trio (Ctrl+C/D/Z) as raw stdin; local copy/paste bindings keep working
            link.keystroke(PtyStdin.payload(null, event.character, true));
          }
        });
    shell.addListener(
        SWT.Dispose,
        event -> link.close()); // pane closed: socket close + PTY removal, exactly once
  }

  /** Enter: clear the input line and send {@code line + CR} as one stdin frame. */
  private void submit() {
    String line = input.getText();
    input.setText("");
    link.keystroke(PtyStdin.encode(line) + "\r");
  }

  /** Appends one output chunk (WS thread -> UI thread, ordered; bounded buffer). */
  private void append(String text) {
    Display display = Display.getDefault();
    if (display == null || display.isDisposed() || output.isDisposed()) {
      return;
    }
    display.asyncExec(
        () -> {
          if (output.isDisposed()) {
            return;
          }
          StringBuilder next = new StringBuilder(output.getText());
          next.append(text);
          if (next.length() > OUTPUT_LIMIT) {
            next.delete(0, next.length() - OUTPUT_LIMIT);
          }
          output.setText(next.toString());
          output.setTopIndex(output.getLineCount() - 1);
        });
  }

  /**
   * The session's working directory (session list lookup); {@code null} on any failure - the
   * service then falls back to its own directory, which is the PTY default.
   */
  private static String sessionDirectory(OpencodeClient client, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    try {
      for (var session : client.getSessions()) {
        if (session != null && sessionId.equals(session.id())) {
          return session.directory();
        }
      }
    } catch (Exception e) {
      // discovery is a nicety - the PTY still starts without the session's cwd
    }
    return null;
  }

  /**
   * The first acceptable shell from {@code GET /api/config/shell}, else the platform default
   * ({@code cmd.exe} on Windows, {@code $SHELL}/{@code /bin/sh} elsewhere).
   */
  private static String shellCommand(OpencodeClient client) {
    try {
      List<ShellExecutable> shells = client.getShellConfig();
      for (ShellExecutable shell : shells) {
        if (shell != null
            && shell.acceptable()
            && shell.path() != null
            && !shell.path().isBlank()) {
          return shell.path();
        }
      }
    } catch (Exception e) {
      // discovery optional - fall through to the platform default
    }
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
      return "cmd.exe";
    }
    String shell = System.getenv("SHELL");
    return (shell == null || shell.isBlank()) ? "/bin/sh" : shell;
  }

  /** Removes the PTY off the UI thread (teardown - failures are logged, never thrown). */
  private static void removeQuietly(OpencodeClient client, String ptyId) {
    Thread cleanup =
        new Thread(
            () -> {
              try {
                client.removePty(ptyId);
              } catch (Exception e) {
                com.opencode.ide.ui.internal.UiActivator.getDefault()
                    .getLog()
                    .log(
                        new org.eclipse.core.runtime.Status(
                            org.eclipse.core.runtime.Status.WARNING,
                            com.opencode.ide.ui.internal.UiActivator.PLUGIN_ID,
                            "Failed to remove pty " + ptyId + " on close: " + e.getMessage(),
                            e));
              }
            },
            "pty-cleanup");
    cleanup.setDaemon(true);
    cleanup.start();
  }
}
