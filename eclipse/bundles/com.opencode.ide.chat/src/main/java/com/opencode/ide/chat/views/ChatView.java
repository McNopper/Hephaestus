package com.opencode.ide.chat.views;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.chat.internal.ChatLog;
import com.opencode.ide.chat.internal.ChatPage;
import com.opencode.ide.chat.internal.ChatServerConnection;
import com.opencode.ide.chat.internal.ChatSessionController;
import com.opencode.ide.chat.internal.ChatSelectorState;
import com.opencode.ide.chat.internal.ChatViewSettings;
import com.opencode.ide.chat.internal.CommandComposer;
import com.opencode.ide.chat.internal.FileReferenceToken;
import com.opencode.ide.chat.internal.SelectorGuard;
import com.opencode.ide.client.ChatCapabilities;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.OpencodePreferences;

/**
 * Native chat view into the opencode server: markdown (code blocks, tables),
 * LaTeX math ($…$, $$…$$) and mermaid diagrams rendered by an embedded SWT
 * Browser (WebView2/Edge) whose assets are served by a local
 * {@link com.opencode.ide.chat.internal.ChatWebServer} (see there for why
 * file:// URLs do not work for jar'd bundles). The browser side lives in
 * {@link ChatPage}, the session logic in {@link ChatSessionController}; this
 * view is the SWT wiring (layout, selectors, input, toolbar, lifecycle).
 *
 * <p>Multiple instances are supported ({@code allowMultiple=true}); the Eclipse
 * secondary id carries the session to resume ({@code ses_…}) or is unique for a
 * fresh window. Reply text streams in via {@code session.text.delta} (and
 * {@code session.reasoning.delta}) events and is finalized with the
 * authoritative render from the completed
 * {@code POST /session/:id/prompt} reply.</p>
 *
 * <p>TUI-parity streaming controls: an inline Stop button appears in the input
 * row while a reply is in flight (same path as the toolbar Abort and the
 * {@code Ctrl+Alt+Shift+A} binding), and messages typed while a reply streams
 * are queued in a pending list (editable/removable) that auto-sends when the
 * reply completes - see {@link ChatSessionController#submit}. A queued request
 * can also be FORKED into a new session before dispatch (the queue row's Fork
 * button), and every message with a server id carries a hover Fork button that
 * forks the session AT that message - see
 * {@link ChatSessionController#forkAt}/{@link ChatSessionController#forkQueued}.
 * Both fork paths open the fork here in the chat (resumed, history rendered);
 * the original session stays untouched.</p>
 *
 * <p>U-064 backgrounding (TUI parity): the composer takes the TUI's Ctrl+B,
 * the input row carries an always-visible <i>Background</i> button, the view
 * toolbar its Background action, and Ctrl+Alt+Shift+B is the global binding
 * ({@code com.opencode.ide.chat.background} + {@code BackgroundSessionHandler}).
 * Backgrounded sessions keep running and are listed in the Background view.</p>
 *
 * <p>TUI-parity undo/redo: the toolbar Undo/Redo actions and the built-in
 * {@code /undo} / {@code /redo} slash commands revert the last exchange
 * (user message plus replies) through the server's revert endpoint and
 * restore it via unrevert - file changes made by the reverted turn come back
 * from the opencode git snapshot, with a plain warning when the project is
 * not a git repo. Enablement follows the server state - see
 * {@link ChatSessionController#undoLastTurn}/{@link ChatSessionController#redoReverted}.</p>
 *
 * <p>U-047 TUI parity commands: {@code /init} runs a guided AGENTS.md setup
 * (a canned prompt into the session), {@code /help} lists every built-in
 * slash command (derived from {@link ChatSessionController#BUILT_IN_COMMANDS},
 * so the list cannot drift), {@code /thinking} toggles reasoning visibility,
 * and {@code /share} / {@code /unshare} surface the v2 verdict (no share
 * endpoint exists - see
 * {@link ChatSessionController#shareNotAvailable(boolean)}). U-012 + its
 * U-047 remainder: typing a token starting with {@code @} in the composer
 * opens the page's autocomplete - ALIAS reference roots (the server's
 * reference catalog, fetched once and cached) above the fuzzy-matched FILES
 * (arrow keys/Enter/Esc/click; the picked {@code @path} or {@code @name}
 * stays plain text). U-039: the last session and the last deliberately
 * picked model are persisted and restored on the next workspace start (see
 * {@link #restorePreviousSession()}).</p>
 *
 * <p>U-014 interactive asks: a {@code permission.asked} of the CURRENT
 * session opens the in-chat permission dialog (once/always/reject, once per
 * request id - {@link ChatPermissionDialog}, the same decision mapping as
 * the T-004 banner row below the toolbar) and both surfaces persist the
 * ask until answered (banner row plus a transcript notice - no silent
 * hang). The session's open QUESTION forms render as answerable cards in
 * the page ({@link ChatSessionController#refreshForms} polls while a send
 * is in flight - a run blocked on a form never settles its POST - plus on
 * resume and after each settle; Submit/Cancel go through
 * {@link ChatSessionController#replyForm}/
 * {@link ChatSessionController#cancelForm}).</p>
 *
 * <p>T-005 queue management: prompts parked with the toolbar's Send to
 * Queue (the session inbox, v2 Alt+Enter) are MANAGEABLE - the composer
 * queue row under the transcript lists every parked prompt with
 * <b>Steer now</b> / <b>Deliver next</b> / <b>Cancel</b> buttons, wired
 * through the page's {@code __javaInboxAction} bridge to
 * {@link ChatSessionController#steerInbox}/
 * {@link ChatSessionController#deliverInboxNext}/
 * {@link ChatSessionController#cancelInbox}; the row re-reads the server
 * after every action, so a failed action keeps the item and says why.</p>
 */
public class ChatView extends ViewPart {

    public static final String ID = "com.opencode.ide.chat.views.ChatView";

    private static final AtomicLong FRESH_COUNTER = new AtomicLong();

    /** "(default)" entry of the variant combo - means "do not send a variant". */
    private static final String VARIANT_DEFAULT = "(default)";

    /** Visible rows of the slash-command picker (it shows up to 8 proposals). */
    private static final int PICKER_ROWS = 5;

    /** Visible rows of the pending-message queue. */
    private static final int QUEUE_ROWS = 4;

    /** The queue table's resting tooltip (row hover replaces it with the full prompt). */
    private static final String QUEUE_HELP =
            "Pending messages - sent automatically when the current reply finishes.\n"
            + "ENTER while a reply streams queues the typed message here.";

    private ChatPage page;
    private ChatSessionController controller;
    private CommandComposer composer;
    private Text input;
    private Composite inputRow;
    private Button sendButton;
    private Button stopButton;
    private Button backgroundButton;
    private Action abortAction;
    private Action undoAction;
    private Action redoAction;
    /** Thinking toggle (toolbar) - synced when {@code /thinking} flips the state. */
    private Action reasoningAction;
    private Combo agentCombo;
    private final ChatSelectorState selectors = new ChatSelectorState();
    private Combo modelCombo;
    private Combo variantCombo;
    /** Deliberate-pick guards: un-armed selector drift (wheel/pointer traffic) reverts. */
    private SelectorGuard agentGuard;
    private SelectorGuard modelGuard;
    private SelectorGuard variantGuard;
    private org.eclipse.swt.widgets.List commandPicker;
    private org.eclipse.swt.widgets.Table queueTable;

    /** Current picker proposals (empty = picker hidden). */
    private List<CommandInfo> pickerMatches = List.of();

    /** Escape dismissed the picker until the input text changes again. */
    private boolean pickerDismissed;

    /**
     * Current @-file proposals - the dropdown's FILE group (empty = no file
     * rows; U-012). The keyboard selection spans the MERGED list (aliases
     * first), see {@link #mergedProposalAt(int)}.
     */
    private List<String> fileMatches = List.of();

    /**
     * Current @-alias reference-root proposals - the dropdown's ALIAS group
     * above the files (U-047); a pick inserts the alias NAME like a path.
     */
    private List<ChatSessionController.ReferenceProposal> fileAliases = List.of();

    /** Highlighted row of the @-file dropdown. */
    private int fileSelection;

    /**
     * Bumped by every {@link #hideFileCompletions()}: an @-file answer that
     * comes back for an earlier generation (the dropdown closed meanwhile -
     * a pick, Esc, or the token changing) is dropped instead of re-opening
     * the closed dropdown.
     */
    private int fileQueryGeneration;

    /** Escape dismissed the @-file dropdown until the query changes again. */
    private boolean filePickerDismissed;

    /** The @-token the dropdown was last shown for (dismiss-rearm logic). */
    private String lastFileToken;

    /** Ambient services for the controller: background jobs, UI dispatch, logging, status. */
    private final ChatSessionController.Host host = new ChatSessionController.Host() {
        @Override
        public void runInBackground(String jobName, Runnable task) {
            Job job = Job.create(jobName, monitor -> {
                task.run();
                return Status.OK_STATUS;
            });
            job.setSystem(true);
            job.schedule();
        }

        @Override
        public void runOnUi(Runnable task) {
            Display.getDefault().asyncExec(task);
        }

        @Override
        public void schedulePoll(long delayMillis, Runnable task) {
            // the U-014 form-poll tick: a UI timer, so the tick itself never
            // blocks and the HTTP refresh runs as a normal background job
            Display.getDefault().timerExec((int) Math.max(0, delayMillis), task);
        }

        @Override
        public void info(String message) {
            ChatLog.info(message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            ChatLog.error(message, throwable);
        }

        @Override
        public void statusChanged(String description) {
            setContentDescription(description);
        }

        @Override
        public void sendingChanged(boolean sending) {
            // Re-read the controller's truth: this notification arrives via
            // asyncExec, and a user submission can slip in after the flag was
            // flipped but before the task runs - the stale argument would
            // then flip the Stop control off while a reply is in flight.
            boolean inFlight = controller != null ? controller.isSending() : sending;
            if (sendButton != null && !sendButton.isDisposed()) {
                sendButton.setEnabled(!inFlight);
            }
            if (stopButton != null && !stopButton.isDisposed()) {
                // inline Stop control: appears in the input row only while a
                // reply is in flight (TUI parity)
                boolean visible = stopButton.isVisible();
                stopButton.setVisible(inFlight);
                ((GridData) stopButton.getLayoutData()).exclude = !inFlight;
                if (visible != inFlight && inputRow != null && !inputRow.isDisposed()) {
                    inputRow.layout(true);
                }
            }
            if (abortAction != null) {
                abortAction.setEnabled(inFlight);
            }
            // every controller-side queue change (auto-dispatch of the next
            // pending submission) coincides with a sending transition
            refreshQueue();
        }

        @Override
        public void forked(String forkSessionId, String fromSessionId, String draftPrompt) {
            // Switch to the fork in its own chat window (resumed, history
            // rendered): the original session - THIS view - stays untouched,
            // even while a reply is still streaming into it.
            ChatView fork = ChatView.openResume(getSite().getPage(), forkSessionId);
            if (fork != null && draftPrompt != null && !draftPrompt.isBlank()) {
                fork.setInputDraft(draftPrompt);
            }
        }

        @Override
        public void queueChanged() {
            refreshQueue();
        }

        @Override
        public void undoRedoChanged(boolean canUndo, boolean canRedo) {
            // the flags were computed from the server state at fire time on
            // the UI thread - straight into the toolbar actions
            if (undoAction != null) {
                undoAction.setEnabled(canUndo);
            }
            if (redoAction != null) {
                redoAction.setEnabled(canRedo);
            }
        }

        @Override
        public void sessionChanged(String sessionId) {
            // U-039 continuity: remember the conversation so the next
            // workspace start can restore it (null = cleared by New Session)
            ChatViewSettings.storeLastSession(sessionId);
        }

        @Override
        public void reasoningVisibilityChanged(boolean visible) {
            // /thinking flipped the controller state (already pushed to the
            // page): persist the preference and keep the toolbar toggle in
            // sync, exactly as a manual toggle would
            new OpencodePreferences().setShowReasoning(visible);
            if (reasoningAction != null) {
                reasoningAction.setChecked(visible);
            }
        }
    };

    /** The connection adapter over the core singleton (client + SSE events). */
    private final ChatServerConnection connection = new ChatServerConnection() {
        @Override
        public OpencodeClient getClient() throws OpencodeException {
            return OpencodeConnection.getInstance().getClient();
        }

        @Override
        public String workingDirectory() {
            return OpencodeConnection.getInstance().getWorkingDirectory();
        }

        @Override
        public void addEventListener(OpencodeEventListener listener) {
            OpencodeConnection.getInstance().addEventListener(listener);
        }

        @Override
        public void removeEventListener(OpencodeEventListener listener) {
            OpencodeConnection.getInstance().removeEventListener(listener);
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        Composite outer = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 3;
        outer.setLayout(layout);

        page = ChatPage.create(outer);
        if (page == null) {
            return; // fallback label already shown; no chat UI without the browser
        }
        controller = new ChatSessionController(connection, page, host);
        // the page's per-message Fork buttons fork the session at that message
        page.setForkHandler(messageId -> controller.forkAt(messageId));
        // the page's composer queue row (session inbox) manages parked
        // prompts: Steer now / Deliver next / Cancel (T-005 management surface)
        page.setInboxHandler((action, messageId) -> {
            if (controller == null) {
                return;
            }
            if ("steer".equals(action)) {
                controller.steerInbox(messageId);
            } else if ("queue".equals(action)) {
                controller.deliverInboxNext(messageId);
            } else if ("cancel".equals(action)) {
                controller.cancelInbox(messageId);
            } else {
                ChatLog.info("inbox action '" + action + "' ignored (unknown)");
            }
        });
        // the page's @-dropdown (U-012 files + U-047 alias reference roots)
        // asks Java for proposals (__javaFileQuery) and reports clicked rows
        // (__javaFilePick)
        page.setFileQueryHandler(query -> {
            if (controller == null) {
                return;
            }
            final int generation = fileQueryGeneration;
            controller.findProposals(query, proposals -> {
                if (generation != fileQueryGeneration) {
                    return; // stale: the dropdown closed while the search ran
                }
                if (proposals.aliases().isEmpty() && proposals.paths().isEmpty()) {
                    hideFileCompletions(); // nothing propose-able: close
                    return;
                }
                fileAliases = proposals.aliases();
                fileMatches = proposals.paths(); // the FILE group (the merged selection spans both)
                fileSelection = 0;
                page.setFileCompletions(proposals.aliases(), proposals.paths(), 0);
            });
        });
        // the page's form cards (U-014) answer/cancel the session's open
        // question forms through the controller (which re-reads the forms
        // afterwards - a failed answer keeps the card)
        page.setFormReplyHandler((formId, answers) -> {
            if (controller != null) {
                controller.replyForm(formId, answers);
            }
        });
        page.setFormCancelHandler(formId -> {
            if (controller != null) {
                controller.cancelForm(formId);
            }
        });
        page.setFilePickHandler(this::pickFileCompletion);
        composer = new CommandComposer(connection);
        ChatLog.info("chat view created (browser: " + page.browserType() + ", secondary id: "
                + getViewSite().getSecondaryId() + ")");

        // row 1: agent + model selectors
        Composite selectorRow = new Composite(outer, SWT.NONE);
        // one row: agent | model | variant  (the variant belongs directly after the
        // model it applies to, as in opencode)
        GridLayout selectorLayout = new GridLayout(3, false);
        selectorLayout.marginWidth = 0;
        selectorLayout.marginHeight = 0;
        selectorRow.setLayout(selectorLayout);
        selectorRow.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));

        agentCombo = new Combo(selectorRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        agentCombo.setToolTipText("Agent");
        agentCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        agentGuard = wireGuardedCombo(agentCombo, text -> selectors.selectAgent(text));
        modelCombo = new Combo(selectorRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        modelCombo.setToolTipText("Model (provider/model) - pre-set to your preferred default (Preferences → OpenCode)");
        modelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelGuard = wireGuardedCombo(modelCombo, text -> {
            selectors.selectModel(selectedModel());
            rememberSelectedModel();
            ChatViewSettings.storeLastModel(selectors.model()); // U-039: last deliberate pick
            fillVariants();
        });
        variantCombo = new Combo(selectorRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        variantCombo.setToolTipText("Reasoning effort (model variant) - as in opencode");
        variantCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        // the variant is read at send time (selectedVariant) - the guard's job
        // here is only reverting wheel/keyboard drift on the combo itself
        variantGuard = wireGuardedCombo(variantCombo, text -> {
            // read at send time; nothing to commit
        });

        // row 1.5: slash-command proposals (inline above the input; excluded
        // from the layout until a "/" trigger shows it)
        commandPicker = new org.eclipse.swt.widgets.List(outer, SWT.BORDER | SWT.V_SCROLL);
        GridData pickerData = new GridData(GridData.FILL, GridData.CENTER, true, false);
        pickerData.exclude = true;
        commandPicker.setLayoutData(pickerData);
        commandPicker.setVisible(false);
        // Double-click / Enter inside the list commits the highlighted proposal;
        // without this the list would be decorative (Enter in the input always
        // took the first match).
        commandPicker.addListener(SWT.DefaultSelection, e -> commitPickerSelection());

        // row 1.7: pending queue - messages typed while a reply streams wait
        // here (TUI parity) and auto-send when it completes. Excluded from the
        // layout until the first entry appears, like the picker above it.
        // ---- T-004: the permission-ask banner (answerable in place) ----
        Composite askRow = new Composite(outer, SWT.NONE);
        GridLayout askLayout = new GridLayout(4, false);
        askLayout.marginWidth = 0;
        askLayout.marginHeight = 0;
        askRow.setLayout(askLayout);
        GridData askRowData = new GridData(GridData.FILL, GridData.CENTER, true, false);
        askRowData.exclude = true; // hidden until an ask arrives
        askRow.setLayoutData(askRowData);
        askRow.setVisible(false);
        askLabel = new org.eclipse.swt.widgets.Label(askRow, SWT.WRAP);
        askLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button askOnce = new Button(askRow, SWT.PUSH);
        askOnce.setText("Allow once");
        askOnce.addListener(SWT.Selection, e -> answerAsk(
                currentAsk, askClient, com.opencode.ide.chat.ChatPermissionDecision.ONCE, null));
        Button askAlways = new Button(askRow, SWT.PUSH);
        askAlways.setText("Allow always");
        askAlways.addListener(SWT.Selection, e -> answerAsk(
                currentAsk, askClient, com.opencode.ide.chat.ChatPermissionDecision.ALWAYS, null));
        Button askReject = new Button(askRow, SWT.PUSH);
        askReject.setText("Reject\u2026");
        askReject.setToolTipText("Reject with optional feedback for the agent");
        askReject.addListener(SWT.Selection, e -> rejectAskWithFeedback(currentAsk, askClient));
        askRowComposite = askRow;
        com.opencode.ide.chat.ChatPermissions.addSink(askSink);
        scheduleAskRecovery();

        Composite queueRow = new Composite(outer, SWT.NONE);
        GridLayout queueLayout = new GridLayout(4, false);
        queueLayout.marginWidth = 0;
        queueLayout.marginHeight = 0;
        queueRow.setLayout(queueLayout);
        GridData queueRowData = new GridData(GridData.FILL, GridData.CENTER, true, false);
        queueRowData.exclude = true;
        queueRow.setLayoutData(queueRowData);
        queueRow.setVisible(false);

        // U-071: the queue is a numbered TABLE, not a squeezed list - header
        // (# + message with the live count), grid lines, send order visible,
        // full-text tooltip per row (cells truncate inline)
        queueTable = new org.eclipse.swt.widgets.Table(queueRow,
                SWT.SINGLE | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        queueTable.setLinesVisible(true);
        queueTable.setHeaderVisible(true);
        org.eclipse.swt.widgets.TableColumn orderColumn =
                new org.eclipse.swt.widgets.TableColumn(queueTable, SWT.NONE);
        orderColumn.setText("#");
        orderColumn.setWidth(26);
        orderColumn.setResizable(false);
        org.eclipse.swt.widgets.TableColumn messageColumn =
                new org.eclipse.swt.widgets.TableColumn(queueTable, SWT.NONE);
        messageColumn.setText("message");
        messageColumn.setWidth(360);
        queueTable.setToolTipText(QUEUE_HELP);
        queueTable.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // the message column follows the row width (the table fills its cell)
        queueTable.addListener(SWT.Resize, e -> messageColumn.setWidth(Math.max(120,
                queueTable.getClientArea().width - orderColumn.getWidth() - 8)));
        // Enter / double-click edits the selected pending message (remove +
        // load into the input; ENTER re-queues or sends it)
        queueTable.addListener(SWT.DefaultSelection, e -> editSelectedQueued());
        // full text on hover: SWT TableItem has no tooltip API, so the row
        // UNDER THE CURSOR supplies the table tooltip (help text otherwise)
        queueTable.addListener(SWT.MouseMove, e -> {
            org.eclipse.swt.widgets.TableItem under =
                    queueTable.getItem(new org.eclipse.swt.graphics.Point(e.x, e.y));
            String full = under == null ? QUEUE_HELP : under.getText(1);
            if (!full.equals(queueTable.getToolTipText())) {
                queueTable.setToolTipText(full);
            }
        });

        Button queueEditButton = new Button(queueRow, SWT.PUSH);
        queueEditButton.setText("\u270F\uFE0F Edit");
        queueEditButton.setToolTipText("Edit the selected pending message");
        queueEditButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueEditButton.addListener(SWT.Selection, e -> editSelectedQueued());

        Button queueRemoveButton = new Button(queueRow, SWT.PUSH);
        queueRemoveButton.setText("\uD83D\uDDD1\uFE0F Remove");
        queueRemoveButton.setToolTipText("Drop the selected pending message");
        queueRemoveButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueRemoveButton.addListener(SWT.Selection, e -> removeSelectedQueued());

        // Fork (TUI parity - "fork a session from a queued request"): forks
        // the session at its current head and MOVES the queued prompt into the
        // fork's input, before it is dispatched here.
        Button queueForkButton = new Button(queueRow, SWT.PUSH);
        queueForkButton.setText("\uD83D\uDD00 Fork");
        queueForkButton.setToolTipText(
                "Fork the session at its current head and move this queued prompt into the fork (it is sent there, never here)");
        queueForkButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueForkButton.addListener(SWT.Selection, e -> forkSelectedQueued());

        // row 2: prompt input + send/stop/background buttons (separate row below the transcript)
        inputRow = new Composite(outer, SWT.NONE);
        GridLayout inputLayout = new GridLayout(4, false);
        inputLayout.marginWidth = 0;
        inputLayout.marginHeight = 0;
        inputRow.setLayout(inputLayout);
        inputRow.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));

        input = new Text(inputRow, SWT.MULTI | SWT.WRAP | SWT.BORDER);
        input.setToolTipText("Prompt (ENTER sends - while a reply streams, ENTER queues the message; "
                + "Shift+ENTER newline, / commands)");
        GridData inputData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        inputData.heightHint = 52;
        input.setLayoutData(inputData);
        input.addModifyListener(e -> {
            pickerDismissed = false;
            updateCommandPicker();
            updateFileCompletions();
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                boolean plainEnter = e.character == SWT.CR && (e.stateMask & SWT.SHIFT) == 0;
                if ((e.keyCode == SWT.ARROW_DOWN || e.keyCode == SWT.ARROW_UP)
                        && fileCompletionsOpen()) {
                    e.doit = false;
                    moveFileSelection(e.keyCode == SWT.ARROW_DOWN ? 1 : -1);
                } else if (plainEnter && fileCompletionsOpen()) {
                    e.doit = false;
                    commitFileSelection();
                } else if (e.character == SWT.ESC && fileCompletionsOpen()) {
                    e.doit = false;
                    filePickerDismissed = true;
                    hideFileCompletions();
                } else if (plainEnter && pickerHasMatches()) {
                    e.doit = false;
                    commitPickerSelection();
                } else if (e.character == SWT.TAB && pickerHasMatches()) {
                    e.doit = false;
                    completeSelectedMatch();
                } else if (e.character == SWT.ESC && pickerHasMatches()) {
                    e.doit = false;
                    pickerDismissed = true;
                    updateCommandPicker();
                } else if ((e.keyCode == SWT.ARROW_DOWN || e.keyCode == SWT.ARROW_UP)
                        && pickerHasMatches()) {
                    e.doit = false;
                    movePickerSelection(e.keyCode == SWT.ARROW_DOWN ? 1 : -1);
                } else if (com.opencode.ide.chat.BackgroundKeys.isBackgroundKey(e.stateMask, e.keyCode)) {
                    e.doit = false;
                    backgroundRequested();
                } else if (plainEnter) {
                    e.doit = false;
                    send();
                }
            }
        });

        sendButton = new Button(inputRow, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, false, false));
        sendButton.addListener(SWT.Selection, e -> send());

        // Inline Stop control: the toolbar-only Abort is undiscoverable, so a
        // Stop button sits in the input row while a reply is in flight - same
        // path as the toolbar Abort and the Ctrl+Alt+Shift+A key binding.
        stopButton = new Button(inputRow, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setToolTipText("Stop generating (same as Abort - Ctrl+Alt+Shift+A)");
        GridData stopData = new GridData(GridData.FILL, GridData.CENTER, false, false);
        stopData.exclude = true;
        stopButton.setLayoutData(stopData);
        stopButton.setVisible(false);
        stopButton.addListener(SWT.Selection, e -> abortRequested());

        // U-064: the Background affordance sits in the input row (always
        // visible) - same path as the toolbar action and the Ctrl+B /
        // Ctrl+Alt+Shift+B bindings.
        backgroundButton = new Button(inputRow, SWT.PUSH);
        backgroundButton.setText("⤵ Background");
        backgroundButton.setToolTipText("Background this session (Ctrl+B in the composer, "
                + "Ctrl+Alt+Shift+B globally): it keeps working while you do other things "
                + "- watch it in the Background view");
        backgroundButton.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, false, false));
        backgroundButton.addListener(SWT.Selection, e -> backgroundRequested());

        contributeActions();
        page.load();
        controller.subscribe();
        // the persisted thinking preference is the controller's initial
        // state too (/thinking flips it from there)
        controller.setReasoningVisible(new OpencodePreferences().isShowReasoning());
        loadSelectors();
        host.runInBackground("Loading opencode commands", composer::loadCommands);
        maybeResumeFromSecondaryId();
        restorePreviousSession();
    }

    /**
     * Toolbar icon descriptor from the shared action-icon set vendored in
     * {@code com.opencode.ide.core} ({@code generate-action-icons.py},
     * original artwork). Text-only toolbar actions render as flat labels
     * with no button affordance (user feedback 2026-09-16), so every toolbar
     * action gets an icon.
     */
    private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
        return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "com.opencode.ide.core", "icons/actions/" + name + ".png");
    }

    private void contributeActions() {
        Action newSessionAction = new Action("New Session") {
            @Override
            public void run() {
                controller.startNewSession();
            }
        };
        newSessionAction.setToolTipText("Start a fresh chat session");
        newSessionAction.setImageDescriptor(icon("new-session"));

        abortAction = new Action("Abort") {
            @Override
            public void run() {
                abortRequested();
            }
        };
        abortAction.setToolTipText("Abort the reply currently being generated (Ctrl+Alt+Shift+A)");
        abortAction.setImageDescriptor(icon("abort"));
        abortAction.setEnabled(false); // enabled while a send is in flight

        // TUI-parity undo/redo: revert the last exchange / restore it. Both
        // are also reachable as the built-in /undo /redo slash commands (see
        // send()); enablement follows the server state via undoRedoChanged.
        undoAction = new Action("Undo") {
            @Override
            public void run() {
                controller.undoLastTurn();
            }
        };
        undoAction.setToolTipText(
                "Undo the last exchange: revert the last user message and its replies (/undo) - file changes are restored from the git snapshot");
        undoAction.setImageDescriptor(icon("undo"));
        undoAction.setEnabled(false); // enabled once the server has a user message to revert

        redoAction = new Action("Redo") {
            @Override
            public void run() {
                controller.redoReverted();
            }
        };
        redoAction.setToolTipText("Restore the reverted messages (/redo)");
        redoAction.setImageDescriptor(icon("redo"));
        redoAction.setEnabled(false); // enabled once the server holds reverted messages

        // Thinking toggle: shows/hides the reasoning progress (live and
        // history) - a persisted preference, re-applied when the page reloads.
        // /thinking drives the same path (the host callback syncs this action)
        reasoningAction = new Action("Thinking", org.eclipse.jface.action.IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                boolean show = isChecked();
                new OpencodePreferences().setShowReasoning(show);
                page.setReasoningVisible(show);
            }
        };
        reasoningAction.setChecked(new OpencodePreferences().isShowReasoning());
        reasoningAction.setToolTipText("Show or hide thinking/reasoning progress (persisted)");
        reasoningAction.setImageDescriptor(icon("thinking"));
        // push the persisted state into the page (queues until page-ready)
        page.setReasoningVisible(reasoningAction.isChecked());

        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(newSessionAction);
        toolBar.add(undoAction);
        toolBar.add(redoAction);
        toolBar.add(abortAction);
        Action backgroundAction = new Action("Background") {
            @Override
            public void run() {
                backgroundSession();
            }
        };
        backgroundAction.setToolTipText("Background this session's blocking tools and keep working "
                + "(Ctrl+B in the composer, Ctrl+Alt+Shift+B globally)");
        Action queueSendAction = new Action("Send to Queue") {
            @Override
            public void run() {
                queueCurrentInput();
            }
        };
        queueSendAction.setToolTipText("Park this prompt in the session inbox - delivered after the current run (v2 Alt+Enter); "
                + "the queue row lists parked prompts (steer / deliver / cancel)");
        toolBar.add(queueSendAction);
        toolBar.add(backgroundAction);
        toolBar.add(reasoningAction);
    }

    /**
     * Aborts the in-flight reply: the inline Stop button in the input row, the
     * toolbar Abort action and the {@code com.opencode.ide.chat.abort} key
     * binding ({@code Ctrl+Alt+Shift+A}) all end up here. The controller posts
     * the abort on a background thread - never the UI thread.
     */
    public void abortRequested() {
        if (controller != null) {
            controller.abort();
        }
    }

    /**
     * Backgrounds this session's long-running work (U-064): the inline
     * Background button in the input row, the composer's Ctrl+B, the toolbar
     * Background action and the {@code Ctrl+Alt+Shift+B} binding all end up
     * here. The session keeps running; the Background view lists it.
     */
    public void backgroundRequested() {
        backgroundSession();
    }

    /** @return true while a reply is being generated (used by the abort handler). */
    public boolean isGenerating() {
        return controller != null && controller.isSending();
    }

    // ---------- selectors ----------

    private void loadSelectors() {
        controller.loadSelectorData(new ChatSessionController.SelectorDataListener() {
            @Override
            public void loaded(List<Agent> agents, ProviderList providers, String[] defaultModel) {
                fillSelectors(agents, providers, defaultModel);
            }

            @Override
            public void failed(OpencodeException error) {
                setContentDescription("Error: " + error.getMessage());
            }
        });
    }

    private void fillSelectors(List<Agent> agents, ProviderList providers, String[] fallback) {
        if (agentCombo.isDisposed()) {
            return;
        }
        OpencodePreferences prefs = new OpencodePreferences();
        selectors.load(agents, providers, prefs.getDefaultModelParts(), fallback);
        agentCombo.setItems(selectors.agents().toArray(String[]::new));
        agentCombo.select(selectors.agent() == null ? -1 : selectors.agents().indexOf(selectors.agent()));
        sizeComboToContent(agentCombo);
        renderModelSelection();
        fillVariants();
        // preselect the preferred reasoning variant when the selected model exposes it
        String preferredVariant = prefs.getDefaultVariant();
        if (preferredVariant != null && !preferredVariant.isBlank() && !variantCombo.isDisposed()) {
            int variantIndex = variantCombo.indexOf(preferredVariant);
            if (variantIndex > 0) {
                variantCombo.select(variantIndex);
            }
        }
        resetSelectorGuards();
    }

    /** Programmatic selector updates move the guards' revert baselines (nothing commits). */
    private void resetSelectorGuards() {
        if (agentGuard != null && !agentCombo.isDisposed()) {
            agentGuard.reset(agentCombo.getText());
        }
        if (modelGuard != null && !modelCombo.isDisposed()) {
            modelGuard.reset(modelCombo.getText());
        }
        if (variantGuard != null && !variantCombo.isDisposed()) {
            variantGuard.reset(variantCombo.getText());
        }
    }

    private void renderModelSelection() {
        modelCombo.setItems(selectors.models().toArray(String[]::new));
        modelCombo.select(selectors.models().indexOf(selectors.model()));
        rememberSelectedModel();
        sizeComboToContent(modelCombo);
        if (modelGuard != null) {
            modelGuard.reset(modelCombo.getText());
        }
    }

    /**
     * Re-sizes one selector combo to its content after the items changed.
     * Non-grabbing combos (agent, variant) keep their initial empty-combo
     * width otherwise - long agent names and reasoning variants were cut off
     * (user report 2026-09-15). The width hint is clamped so one very long
     * model id cannot starve its neighbors; the grabbing combo treats the
     * hint as its minimum.
     */
    private static void sizeComboToContent(org.eclipse.swt.widgets.Combo combo) {
        if (combo == null || combo.isDisposed()) {
            return;
        }
        Object data = combo.getLayoutData();
        if (data instanceof GridData gd) {
            int width = combo.computeSize(org.eclipse.swt.SWT.DEFAULT, org.eclipse.swt.SWT.DEFAULT).x;
            gd.widthHint = Math.max(70, Math.min(width, 300));
            combo.getParent().layout(true);
        }
    }

    /**
     * Wires one selector combo for deliberate changes only (user report
     * 2026-09-16: pointer/wheel traffic over the selector row silently
     * flipped agent/model/variant - Windows read-only combos fire
     * {@code SWT.Selection} on mouse-wheel). A pick commits only when the
     * dropdown was explicitly opened (mouse down) or Enter was pressed;
     * un-armed drift (wheel, stray arrow keys) reverts the combo to the
     * committed text. The semantics live in the SWT-free {@link SelectorGuard}.
     */
    private static SelectorGuard wireGuardedCombo(Combo combo, java.util.function.Consumer<String> commit) {
        SelectorGuard guard = new SelectorGuard(combo.getText());
        combo.addListener(SWT.MouseDown, e -> guard.arm());
        // an opened-then-abandoned dropdown must not arm the NEXT drift
        combo.addListener(SWT.FocusOut, e -> guard.disarm());
        combo.addListener(SWT.DefaultSelection, e -> {
            guard.arm(); // Enter is the same deliberate intent as a pick
            String pick = guard.attempt(combo.getText());
            if (pick != null) {
                commit.accept(pick);
            }
        });
        combo.addListener(SWT.Selection, e -> {
            String pick = guard.attempt(combo.getText());
            if (pick == null) {
                combo.setText(guard.committed());
            } else {
                commit.accept(pick);
            }
        });
        return guard;
    }

    private void rememberSelectedModel() {
        String model = selectors.model();
        int slash = model.indexOf('/');
        controller.setDefaultModel(slash > 0 ? model.substring(0, slash) : null,
                slash > 0 ? model.substring(slash + 1) : null);
    }

    /**
     * Populates the variant combo from the selected model (opencode's
     * reasoning-effort variants, e.g. {@code none/low/medium/high/xhigh/max} or
     * {@code none/thinking}). Disabled for models that expose none.
     */
    private void fillVariants() {
        if (variantCombo == null || variantCombo.isDisposed()) {
            return;
        }
        String previous = selectedVariant();
        List<String> variants = selectors.variants();
        variantCombo.removeAll();
        variantCombo.add(VARIANT_DEFAULT);
        for (String variant : variants) {
            variantCombo.add(variant);
        }
        variantCombo.setEnabled(!variants.isEmpty());
        int keep = (previous != null) ? variantCombo.indexOf(previous) : -1;
        variantCombo.select(keep > 0 ? keep : 0);
        variantCombo.setToolTipText(variants.isEmpty()
                ? "This model has no reasoning variants"
                : "Reasoning effort (model variant): " + String.join(", ", variants));
        sizeComboToContent(variantCombo);
        if (variantGuard != null) {
            variantGuard.reset(variantCombo.getText());
        }
    }

    /** @return the selected {@code provider/model}, or {@code ""}. */
    private String selectedModel() {
        if (modelCombo == null || modelCombo.isDisposed() || modelCombo.getSelectionIndex() < 0) {
            return "";
        }
        return modelCombo.getItem(modelCombo.getSelectionIndex());
    }

    /** @return the selected variant, or {@code null} for the model default. */
    private String selectedVariant() {
        if (variantCombo == null || variantCombo.isDisposed() || variantCombo.getSelectionIndex() <= 0) {
            return null;
        }
        return variantCombo.getItem(variantCombo.getSelectionIndex());
    }

    /** Preselect a model. Used by the openChat command (new chat for a model). */
    public void preselectModel(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            return;
        }
        selectors.selectModel(providerId + "/" + modelId);
        if (modelCombo == null || modelCombo.isDisposed()) {
            return;
        }
        renderModelSelection();
        fillVariants();
    }

    /** Retained across the asynchronous selector load. */
    public void preselectAgent(String agentId) {
        selectors.selectAgent(agentId);
        if (agentId == null || agentId.isBlank() || agentCombo == null || agentCombo.isDisposed()) {
            return;
        }
        int index = agentCombo.indexOf(agentId);
        if (index >= 0) {
            agentCombo.select(index);
        }
    }

    // ---------- session resume / multiple windows ----------

    /** Secondary id convention: {@code ses_…} resumes that session; anything else = fresh. */
    private void maybeResumeFromSecondaryId() {
        String secondary = getViewSite().getSecondaryId();
        if (secondary == null || !secondary.startsWith("ses_")) {
            return;
        }
        setContentDescription("Resuming " + secondary);
        controller.resume(secondary); // already URL-decoded by the workbench
    }

    // ---------- U-039: continuity across restarts ----------

    /**
     * Restores the last conversation after a restart (primary view only - a
     * secondary-id view resumes ITS session, a fresh window is an explicit
     * new chat): the stored model is preselected right away (an explicit pick
     * survives the async catalog load by design), and the stored session is
     * probed on a background job once the connection answers - an existing
     * session resumes through the normal path (plus a small "Restored
     * session …" notice), a vanished one degrades silently: the stored id is
     * cleared and the view stays fresh.
     */
    private void restorePreviousSession() {
        if (getViewSite().getSecondaryId() != null) {
            return; // explicit resume or explicit new chat: continuity does not apply
        }
        String model = ChatViewSettings.lastModel();
        if (model != null && model.indexOf('/') > 0) {
            int slash = model.indexOf('/');
            preselectModel(model.substring(0, slash), model.substring(slash + 1));
        }
        String sid = ChatViewSettings.lastSessionId();
        if (sid == null || sid.isBlank()) {
            return;
        }
        host.runInBackground("Restoring last chat session " + sid, () -> {
            boolean exists;
            try {
                connection.getClient().getMessages(sid); // probe (blocks until connected)
                exists = true;
            } catch (OpencodeException e) {
                exists = false;
            }
            if (!exists) {
                ChatViewSettings.storeLastSession(null); // stale: never restore it again
                ChatLog.info("restore: session " + sid + " no longer exists - starting fresh");
                return;
            }
            Display.getDefault().asyncExec(() -> {
                if (controller == null || controller.isSending()) {
                    return;
                }
                controller.resume(sid);
                if (page != null) {
                    page.notice("Restored session " + sid + " (your last chat).");
                }
            });
        });
    }

    /** Opens a NEW chat window (fresh session) with an optional preselected model. */
    public static ChatView openNew(IWorkbenchPage page, String providerId, String modelId) {
        return open(page, "fresh-" + FRESH_COUNTER.incrementAndGet()
                + "-" + Long.toString(System.currentTimeMillis(), 36), providerId, modelId);
    }

    /** Opens (or focuses) the chat window RESUMING the given session. */
    public static ChatView openResume(IWorkbenchPage page, String sessionId) {
        // T-009: the ONE session-id encoding (core.context.SessionViewIds) -
        // the per-call-site encodings opened duplicate views per session
        return open(page, com.opencode.ide.core.context.SessionViewIds.secondaryId(sessionId), null, null);
    }

    private static ChatView open(IWorkbenchPage page, String secondaryId, String providerId, String modelId) {
        if (page == null) {
            return null;
        }
        try {
            IViewPart part = page.showView(ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
            if (part instanceof ChatView chat) {
                if (providerId != null && modelId != null) {
                    chat.preselectModel(providerId, modelId);
                }
                chat.setFocus();
                return chat;
            }
        } catch (PartInitException e) {
            ChatLog.error("Failed to open chat view", e);
        }
        return null;
    }

    // ---------- slash-command picker ----------

    /** @return true while the picker shows proposals. */
    private boolean pickerHasMatches() {
        return !pickerMatches.isEmpty();
    }

    /** Recomputes the proposals for the current input text and shows/hides the picker. */
    private void updateCommandPicker() {
        if (composer == null || commandPicker == null || commandPicker.isDisposed()
                || input == null || input.isDisposed()) {
            return;
        }
        String text = input.getText();
        pickerMatches = !pickerDismissed && composer.isPickerTrigger(text)
                ? composer.matches(text)
                : List.of();
        boolean show = !pickerMatches.isEmpty();
        if (show) {
            commandPicker.removeAll();
            for (CommandInfo match : pickerMatches) {
                String description = match.description();
                commandPicker.add("/" + match.name()
                        + (description == null || description.isBlank() ? "" : " - " + description));
            }
            commandPicker.setSelection(0);
            int itemHeight = Math.max(commandPicker.getItemHeight(), 18);
            ((GridData) commandPicker.getLayoutData()).heightHint =
                    Math.min(pickerMatches.size(), PICKER_ROWS) * itemHeight + 4;
        }
        boolean visibilityChanged = show != commandPicker.isVisible();
        commandPicker.setVisible(show);
        ((GridData) commandPicker.getLayoutData()).exclude = !show;
        if (show || visibilityChanged) {
            // Also re-layout while the picker STAYS visible: the row count (and
            // with it heightHint) changes as the user keeps typing.
            commandPicker.getParent().layout(true);
        }
    }

    /** The highlighted proposal, defaulting to the first one. */
    private CommandInfo selectedMatch() {
        int index = commandPicker == null || commandPicker.isDisposed()
                ? 0 : commandPicker.getSelectionIndex();
        if (index < 0 || index >= pickerMatches.size()) {
            index = 0;
        }
        return pickerMatches.get(index);
    }

    /** Moves the picker highlight by {@code delta}, clamped to the match list. */
    private void movePickerSelection(int delta) {
        int index = commandPicker.getSelectionIndex();
        if (index < 0) {
            index = 0;
        }
        int next = Math.max(0, Math.min(pickerMatches.size() - 1, index + delta));
        commandPicker.setSelection(next);
        commandPicker.showSelection();
    }

    /** Commits the highlighted proposal (Enter in the input, click/Enter in the list). */
    private void commitPickerSelection() {
        if (!pickerHasMatches()) {
            return;
        }
        submitSelection(composer.select(selectedMatch(), input.getText()));
    }

    /** Tab: completes the input to the highlighted match and puts the caret after it. */
    private void completeSelectedMatch() {
        CommandInfo top = selectedMatch();
        input.setText("/" + top.name() + " ");
        input.setSelection(input.getText().length());
    }

    // ---------- @-file autocomplete (U-012) ----------

    /** @return true while the @-dropdown shows proposals (either group). */
    private boolean fileCompletionsOpen() {
        return !fileAliases.isEmpty() || !fileMatches.isEmpty();
    }

    /**
     * Recomputes the @-file dropdown for the {@code @} token the caret is in:
     * a token (re)opens the dropdown by handing its query to the page (which
     * asks Java for the matches via {@code __javaFileQuery}); leaving the
     * token hides it. Escape closes the dropdown until the query changes.
     */
    private void updateFileCompletions() {
        if (input == null || input.isDisposed() || page == null || controller == null) {
            return;
        }
        String token = FileReferenceToken.tokenAt(input.getText(), input.getCaretPosition());
        if (token == null) {
            filePickerDismissed = false; // outside any token: re-arm for the next one
            lastFileToken = null;
            hideFileCompletions();
            return;
        }
        if (filePickerDismissed && token.equals(lastFileToken)) {
            return; // Esc closed this token's dropdown until the query changes
        }
        filePickerDismissed = false;
        lastFileToken = token;
        page.showFileQuery(FileReferenceToken.queryOf(token)); // the page asks Java for matches
    }

    /** Hides the @-dropdown (idempotent); in-flight answers turn stale. */
    private void hideFileCompletions() {
        fileQueryGeneration++; // answers still in flight are dropped on arrival
        fileAliases = List.of();
        fileMatches = List.of();
        fileSelection = 0;
        if (page != null) {
            page.hideFileCompletions();
        }
    }

    /**
     * The proposal value at the MERGED row {@code index} (aliases first, then
     * files - the order the page renders and highlights): an alias's NAME or
     * a file path; {@code null} when the index is out of range.
     */
    private String mergedProposalAt(int index) {
        if (index < 0 || index >= fileAliases.size() + fileMatches.size()) {
            return null;
        }
        return index < fileAliases.size()
                ? fileAliases.get(index).name()
                : fileMatches.get(index - fileAliases.size());
    }

    /** Moves the @-dropdown highlight by {@code delta}, clamped to the merged rows. */
    private void moveFileSelection(int delta) {
        int rows = fileAliases.size() + fileMatches.size();
        if (rows == 0) {
            return;
        }
        fileSelection = Math.max(0, Math.min(rows - 1, fileSelection + delta));
        page.setFileCompletions(fileAliases, fileMatches, fileSelection);
    }

    /** Enter: commits the highlighted completion into the composer. */
    private void commitFileSelection() {
        String value = mergedProposalAt(fileSelection);
        if (value != null) {
            pickFileCompletion(value);
        }
    }

    /**
     * Replaces the {@code @} token the caret is in with {@code @path } (a
     * picked completion - a file path or an alias reference name, via Enter
     * in the input or a click on a dropdown row) and hides the dropdown. The
     * hide runs LAST and bumps the query generation, so the re-query the
     * setText modify event issues for the just-inserted {@code @path} lands
     * stale and cannot re-open the closed dropdown. The reference stays
     * plain text: the server/model resolves it, nothing is injected into
     * the message.
     */
    private void pickFileCompletion(String path) {
        if (input == null || input.isDisposed() || path == null || path.isBlank()) {
            hideFileCompletions();
            return;
        }
        String text = input.getText();
        int caret = input.getCaretPosition();
        int start = caret;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        input.setText(FileReferenceToken.replaceToken(text, start, end, path));
        // caret right after the inserted "@path "
        input.setSelection(Math.min(input.getText().length(), start + path.length() + 2));
        input.setFocus();
        hideFileCompletions();
    }

    // ---------- sending / pending queue ----------

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        // Built-in slash commands are handled locally BEFORE the composer's
        // custom-command resolve (opencode TUI parity: /undo and /redo are
        // TUI actions there too, not custom commands). An exact match only -
        // anything else (including a custom command picked from the picker)
        // takes the normal paths below.
        String builtIn = ChatSessionController.builtInSlashCommand(text);
        if (builtIn != null) {
            input.setText(""); // fires the modify listener, hiding the picker
            dispatchBuiltInCommand(builtIn);
            return;
        }
        submitSelection(composer.resolve(text));
    }

    /** Runs one recognized built-in slash command (see ChatSessionController.BUILT_IN_COMMANDS). */
    private void dispatchBuiltInCommand(String builtIn) {
        if ("undo".equals(builtIn)) {
            controller.undoLastTurn();
        } else if ("redo".equals(builtIn)) {
            controller.redoReverted();
        } else if ("init".equals(builtIn)) {
            controller.runInitCommand();
        } else if ("help".equals(builtIn)) {
            controller.showHelp();
        } else if ("thinking".equals(builtIn)) {
            controller.toggleThinking();
        } else if ("share".equals(builtIn)) {
            controller.shareNotAvailable(false);
        } else if ("unshare".equals(builtIn)) {
            controller.shareNotAvailable(true);
        } else {
            ChatLog.info("built-in command '" + builtIn + "' ignored (unknown)");
        }
    }

    /**
     * Routes one resolved submission: sent immediately when idle, queued while
     * a reply is in flight (TUI parity - ENTER during generation types ahead;
     * the pending list shows the queue and it auto-sends on completion).
     */
    private void submitSelection(CommandComposer.CommandSelection selection) {
        if (selection == null || controller == null) {
            return;
        }
        input.setText(""); // fires the modify listener, hiding the picker
        boolean queued;
        if (selection.kind() == CommandComposer.Kind.COMMAND) {
            queued = controller.submit(selection, null);
        } else {
            queued = controller.submit(null, outgoingMessage(selection.message()));
        }
        if (queued) {
            refreshQueue();
        }
    }

    /** Rebuilds the pending list from the controller (hidden while empty). */
    private void refreshQueue() {
        if (queueTable == null || queueTable.isDisposed() || controller == null) {
            return;
        }
        List<String> pending = controller.queuedPrompts();
        int keep = queueTable.getSelectionIndex();
        queueTable.removeAll();
        for (int i = 0; i < pending.size(); i++) {
            String text = pending.get(i);
            org.eclipse.swt.widgets.TableItem item =
                    new org.eclipse.swt.widgets.TableItem(queueTable, SWT.NONE);
            item.setText(new String[] {String.valueOf(i + 1), text});
        }
        queueTable.getColumn(1).setText("message (" + pending.size() + " queued)");
        boolean show = !pending.isEmpty();
        if (show) {
            int itemHeight = Math.max(queueTable.getItemHeight(), 18);
            // header + up to QUEUE_ROWS rows (the header must not eat row one)
            ((GridData) queueTable.getLayoutData()).heightHint =
                    (Math.min(pending.size(), QUEUE_ROWS) + 1) * itemHeight + 8;
            if (keep >= 0 && keep < pending.size()) {
                queueTable.setSelection(keep);
            }
        }
        boolean visibilityChanged = show != queueTable.isVisible();
        Composite row = queueTable.getParent();
        queueTable.setVisible(show);
        ((GridData) row.getLayoutData()).exclude = !show;
        row.setVisible(show);
        if (show || visibilityChanged) {
            // Also re-layout while the queue STAYS visible: the row count (and
            // with it heightHint) changes as more messages queue up.
            row.getParent().layout(true);
        }
    }

    /** Edit: takes the selected pending message back into the input (ENTER re-queues or sends it). */
    private void editSelectedQueued() {
        int index = queueTable == null || queueTable.isDisposed() ? -1 : queueTable.getSelectionIndex();
        if (index < 0) {
            return;
        }
        String text = controller.removeQueuedPrompt(index);
        refreshQueue();
        if (text != null && !text.isEmpty() && input != null && !input.isDisposed()) {
            input.setText(text);
            input.setSelection(input.getText().length());
            input.setFocus();
        }
    }

    /** Remove: drops the selected pending message. */
    private void removeSelectedQueued() {
        int index = queueTable == null || queueTable.isDisposed() ? -1 : queueTable.getSelectionIndex();
        if (index >= 0) {
            controller.removeQueuedPrompt(index);
        }
        refreshQueue();
    }

    /**
     * Fork: moves the selected pending message into a fork of the session,
     * before it is dispatched here (TUI parity). The controller takes the
     * submission out of the queue, forks at the current head and reports back
     * through {@code forked} - which opens the fork in the chat with the text
     * loaded into its input.
     */
    private void forkSelectedQueued() {
        int index = queueTable == null || queueTable.isDisposed() ? -1 : queueTable.getSelectionIndex();
        if (index >= 0 && controller != null) {
            controller.forkQueued(index);
        }
        refreshQueue(); // the row disappears immediately (belt and braces:
                        // the controller also fires queueChanged)
    }

    /**
     * Loads text into the prompt input and focuses it - used to move a queued
     * prompt into a freshly opened fork's input.
     */
    public void setInputDraft(String text) {
        if (input == null || input.isDisposed() || text == null || text.isEmpty()) {
            return;
        }
        input.setText(text);
        input.setSelection(input.getText().length());
        input.setFocus();
    }

    // ---------- T-004 / T-005 / U-014: ask banner + dialog + session actions ----------

    private Composite askRowComposite;
    private org.eclipse.swt.widgets.Label askLabel;
    private volatile com.opencode.ide.client.activity.PermissionRequest currentAsk;
    private volatile com.opencode.ide.client.OpencodeClient askClient;
    private final java.util.Set<String> answeredAsks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * U-014 AC3: asks already noticed in the transcript - the pending-state
     * notice fires once per ask id, not on every (re)surfacing.
     */
    private final java.util.Set<String> noticedAsks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * U-014 AC1: decides which asks additionally open the permission dialog
     * (once per request id, current session only) - the banner always shows.
     */
    private final com.opencode.ide.chat.ChatPermissionDialogGate askDialogGate =
            new com.opencode.ide.chat.ChatPermissionDialogGate();
    /** The currently-open permission dialog (closed when its ask is answered elsewhere). */
    private volatile ChatPermissionDialog openAskDialog;

    /** Coexisting ChatPermissions listener (the multiplexer) - never steals the fleet queue's feed. */
    private final com.opencode.ide.chat.ChatPermissionSink askSink = new com.opencode.ide.chat.ChatPermissionSink() {
        @Override
        public void asked(com.opencode.ide.client.activity.PermissionRequest request,
                com.opencode.ide.client.OpencodeClient client) {
            if (request == null || !request.pending() || answeredAsks.contains(request.permissionId())) {
                return;
            }
            String sid = controller == null ? null : controller.sessionId();
            if (sid != null && request.sessionId() != null && !sid.equals(request.sessionId())) {
                return; // this banner = this session; the Background view shows every ask
            }
            Display.getDefault().asyncExec(() -> showAsk(request, client));
        }

        @Override
        public void replied(String sessionId, String requestId) {
            if (requestId != null) {
                answeredAsks.add(requestId);
            }
            Display.getDefault().asyncExec(() -> {
                hideAsk();
                // an ask answered elsewhere (another client) closes its dialog
                ChatPermissionDialog dialog = openAskDialog;
                if (dialog != null && requestId != null && requestId.equals(dialog.requestId())) {
                    dialog.close();
                }
            });
        }
    };

    private void showAsk(com.opencode.ide.client.activity.PermissionRequest request,
            com.opencode.ide.client.OpencodeClient client) {
        if (askRowComposite == null || askRowComposite.isDisposed()) {
            return;
        }
        currentAsk = request;
        askClient = client;
        askLabel.setText(request.display() == null ? request.permission() : request.display());
        setAskRowVisible(true);
        // U-014 AC3: one transcript notice per ask - the session is WAITING
        // on the user, visible even if the banner scrolls out of sight
        if (page != null && noticedAsks.add(request.permissionId())) {
            page.notice("\uD83D\uDD12 Permission requested - the session waits for your answer.");
        }
        // U-014 AC1: the ask's own dialog (once per id, current session only)
        String sid = controller == null ? null : controller.sessionId();
        if (askDialogGate.offer(sid, request)) {
            openAskDialogFor(request, client);
        }
    }

    /**
     * Opens the U-014 permission dialog for one ask. Runs on the UI thread
     * inside the ask's asyncExec: {@code open()} blocks in SWT's standard
     * modal nested event loop (deltas and other asyncExecs keep running),
     * never on the SSE or job threads. The picked decision goes through the
     * SAME answer path as the banner; dismissing the dialog without
     * answering leaves the ask on the banner row - the pending state survives.
     */
    private void openAskDialogFor(com.opencode.ide.client.activity.PermissionRequest request,
            com.opencode.ide.client.OpencodeClient client) {
        ChatPermissionDialog dialog = new ChatPermissionDialog(getSite().getShell(), request);
        openAskDialog = dialog;
        int code;
        try {
            code = dialog.open();
        } finally {
            openAskDialog = null;
        }
        if (code == ChatPermissionDialog.ALLOW_ONCE) {
            answerAsk(request, client, com.opencode.ide.chat.ChatPermissionDecision.ONCE, null);
        } else if (code == ChatPermissionDialog.ALLOW_ALWAYS) {
            answerAsk(request, client, com.opencode.ide.chat.ChatPermissionDecision.ALWAYS, null);
        } else if (code == ChatPermissionDialog.REJECT) {
            rejectAskWithFeedback(request, client);
        }
        // else: dismissed - the banner keeps the pending ask (no silent loss)
    }

    private void hideAsk() {
        if (askRowComposite != null && !askRowComposite.isDisposed()) {
            currentAsk = null;
            setAskRowVisible(false);
        }
    }

    private void setAskRowVisible(boolean visible) {
        ((GridData) askRowComposite.getLayoutData()).exclude = !visible;
        askRowComposite.setVisible(visible);
        askRowComposite.getParent().layout();
    }

    /**
     * The single answer path (once/always/reject, banner AND dialog - U-014)
     * with optional reject feedback (T-004): marks the ask answered, hides
     * the banner when it still shows THIS ask, and POSTs
     * {@code respondToPermission} off the UI thread through the decision's
     * wire mapping ({@link com.opencode.ide.chat.ChatPermissionDecision}).
     */
    private void answerAsk(com.opencode.ide.client.activity.PermissionRequest ask,
            com.opencode.ide.client.OpencodeClient client,
            com.opencode.ide.chat.ChatPermissionDecision decision, String feedback) {
        if (ask == null || client == null || decision == null) {
            return;
        }
        answeredAsks.add(ask.permissionId());
        if (ask == currentAsk) {
            hideAsk(); // a NEWER ask may own the banner meanwhile - keep that one
        }
        final com.opencode.ide.client.activity.PermissionRequest request = ask;
        final com.opencode.ide.client.OpencodeClient answerClient = client;
        com.opencode.ide.client.WorkerPools.submit("chat-permission-answer", () -> {
            String message;
            try {
                message = answerClient.respondToPermission(request.sessionId(), request.permissionId(),
                        decision.response(), decision.remember(), feedback)
                        ? "Permission answered '" + decision.response() + "'."
                        : "\u26A0 Permission answer was not accepted.";
            } catch (Exception e) {
                message = "\u26A0 Permission answer failed: " + e.getMessage();
            }
            String finalMessage = message;
            Display.getDefault().asyncExec(() -> {
                if (page != null) {
                    page.notice(finalMessage);
                }
            });
        });
    }

    private void rejectAskWithFeedback(com.opencode.ide.client.activity.PermissionRequest ask,
            com.opencode.ide.client.OpencodeClient client) {
        org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                getSite().getShell(), "Reject with feedback",
                "Optional feedback for the agent (why the request is rejected):", "", null);
        if (dialog.open() == org.eclipse.jface.window.Window.OK) {
            String feedback = dialog.getValue();
            answerAsk(ask, client, com.opencode.ide.chat.ChatPermissionDecision.REJECT,
                    feedback == null || feedback.isBlank() ? null : feedback.trim());
        }
    }

    /**
     * Reconnect recovery (T-004): SSE events are not replayed, so pending
     * asks are re-read from GET /permission/request when the view appears.
     * The re-read/filter/degrade logic lives in the SWT-free
     * {@link com.opencode.ide.chat.ChatPermissionRecovery} (pinned by
     * {@code ChatPermissionRecoveryTest}); this wrapper only moves it off the
     * UI thread and bounces the surfaced asks onto it.
     */
    private void scheduleAskRecovery() {
        com.opencode.ide.client.WorkerPools.submit("chat-ask-recovery", askRecovery::recover);
    }

    /**
     * The recovery seam's view side: live client + working directory from
     * the core connection, this view's session scope and answered ids, and a
     * listener that surfaces the ask banner on the UI thread.
     */
    private final com.opencode.ide.chat.ChatPermissionRecovery askRecovery =
            new com.opencode.ide.chat.ChatPermissionRecovery(
                    () -> {
                        try {
                            return com.opencode.ide.core.OpencodeConnection.getInstance().getClient();
                        } catch (OpencodeException e) {
                            return null; // not connected - recovery degrades to a no-op
                        }
                    },
                    () -> com.opencode.ide.core.OpencodeConnection.getInstance().getWorkingDirectory(),
                    () -> controller == null ? null : controller.sessionId(),
                    () -> answeredAsks,
                    (request, client) -> Display.getDefault().asyncExec(() -> showAsk(request, client)));

    /** T-005: background the session's blocking tools (POST /session/:id/background). */
    private void backgroundSession() {
        String sid = controller == null ? null : controller.sessionId();
        if (sid == null) {
            page.notice("No session to background yet");
            return;
        }
        com.opencode.ide.client.WorkerPools.submit("chat-background", () -> {
            String message;
            try {
                com.opencode.ide.core.OpencodeConnection.getInstance().getClient().backgroundSession(sid);
                message = "session backgrounded - long tools keep running while you work "
                        + "- watch it in the Background view (Window > Show View > Other... > Background)";
            } catch (Exception e) {
                message = "background failed: " + e.getMessage();
            }
            String finalMessage = message;
            Display.getDefault().asyncExec(() -> {
                if (page != null) {
                    page.notice(finalMessage);
                }
            });
        });
    }

    /** T-005 send-time queue: park the current input in the session inbox (v2 Alt+Enter). */
    private void queueCurrentInput() {
        String text = input.getText().trim();
        if (text.isEmpty() || controller == null) {
            return;
        }
        input.setText("");
        controller.send(outgoingMessage(text), "queue");
        // best-effort immediate row update: the send job re-reads the inbox
        // when the parking POST settles, which is the authoritative refresh
        controller.refreshInbox();
    }

    private ChatSessionController.OutgoingMessage outgoingMessage(String text) {
        final String agent = (agentCombo.getSelectionIndex() >= 0)
                ? agentCombo.getItem(agentCombo.getSelectionIndex())
                : null;
        String selection = selectedModel();
        final String pickedVariant = selectedVariant();
        final String pickedProvider;
        final String pickedModel;
        int slash = selection.indexOf('/');
        if (slash > 0 && slash < selection.length() - 1) {
            pickedProvider = selection.substring(0, slash);
            pickedModel = selection.substring(slash + 1);
        } else {
            pickedProvider = null;
            pickedModel = null;
        }
        // tells the model what this view renders (math, mermaid, highlighted code)
        final String system = new OpencodePreferences().isAdvertiseRendering()
                ? ChatCapabilities.RENDERER_SYSTEM_PROMPT
                : null;

        return new ChatSessionController.OutgoingMessage(agent, pickedProvider, pickedModel,
                pickedVariant, system, text);
    }

    @Override
    public void dispose() {
        com.opencode.ide.chat.ChatPermissions.removeSink(askSink);
        if (controller != null) {
            controller.dispose(); // unsubscribe the SSE event listener
        }
        if (page != null) {
            page.dispose();
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (input != null && !input.isDisposed()) {
            input.setFocus();
        }
    }
}
