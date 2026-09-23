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
import com.opencode.ide.chat.internal.CommandComposer;
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
 * <p>TUI-parity undo/redo: the toolbar Undo/Redo actions and the built-in
 * {@code /undo} / {@code /redo} slash commands revert the last exchange
 * (user message plus replies) through the server's revert endpoint and
 * restore it via unrevert - file changes made by the reverted turn come back
 * from the opencode git snapshot, with a plain warning when the project is
 * not a git repo. Enablement follows the server state - see
 * {@link ChatSessionController#undoLastTurn}/{@link ChatSessionController#redoReverted}.</p>
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

    private ChatPage page;
    private ChatSessionController controller;
    private CommandComposer composer;
    private Text input;
    private Composite inputRow;
    private Button sendButton;
    private Button stopButton;
    private Action abortAction;
    private Action undoAction;
    private Action redoAction;
    private Combo agentCombo;
    private final ChatSelectorState selectors = new ChatSelectorState();
    private Combo modelCombo;
    private Combo variantCombo;
    /** Deliberate-pick guards: un-armed selector drift (wheel/pointer traffic) reverts. */
    private SelectorGuard agentGuard;
    private SelectorGuard modelGuard;
    private SelectorGuard variantGuard;
    private org.eclipse.swt.widgets.List commandPicker;
    private org.eclipse.swt.widgets.List queueList;

    /** Current picker proposals (empty = picker hidden). */
    private List<CommandInfo> pickerMatches = List.of();

    /** Escape dismissed the picker until the input text changes again. */
    private boolean pickerDismissed;

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
        askOnce.addListener(SWT.Selection, e -> answerAsk("once", false, null));
        Button askAlways = new Button(askRow, SWT.PUSH);
        askAlways.setText("Allow always");
        askAlways.addListener(SWT.Selection, e -> answerAsk("always", true, null));
        Button askReject = new Button(askRow, SWT.PUSH);
        askReject.setText("Reject\u2026");
        askReject.setToolTipText("Reject with optional feedback for the agent");
        askReject.addListener(SWT.Selection, e -> rejectAskWithFeedback());
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

        queueList = new org.eclipse.swt.widgets.List(queueRow, SWT.BORDER | SWT.V_SCROLL);
        queueList.setToolTipText(
                "Pending messages - sent automatically when the current reply finishes.\nENTER while a reply streams queues the typed message here.");
        queueList.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Enter / double-click edits the selected pending message (remove +
        // load into the input; ENTER re-queues or sends it)
        queueList.addListener(SWT.DefaultSelection, e -> editSelectedQueued());

        Button queueEditButton = new Button(queueRow, SWT.PUSH);
        queueEditButton.setText("Edit");
        queueEditButton.setToolTipText("Edit the selected pending message");
        queueEditButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueEditButton.addListener(SWT.Selection, e -> editSelectedQueued());

        Button queueRemoveButton = new Button(queueRow, SWT.PUSH);
        queueRemoveButton.setText("Remove");
        queueRemoveButton.setToolTipText("Drop the selected pending message");
        queueRemoveButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueRemoveButton.addListener(SWT.Selection, e -> removeSelectedQueued());

        // Fork (TUI parity - "fork a session from a queued request"): forks
        // the session at its current head and MOVES the queued prompt into the
        // fork's input, before it is dispatched here.
        Button queueForkButton = new Button(queueRow, SWT.PUSH);
        queueForkButton.setText("Fork");
        queueForkButton.setToolTipText(
                "Fork the session at its current head and move this queued prompt into the fork (it is sent there, never here)");
        queueForkButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        queueForkButton.addListener(SWT.Selection, e -> forkSelectedQueued());

        // row 2: prompt input + send/stop buttons (separate row below the transcript)
        inputRow = new Composite(outer, SWT.NONE);
        GridLayout inputLayout = new GridLayout(3, false);
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
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                boolean plainEnter = e.character == SWT.CR && (e.stateMask & SWT.SHIFT) == 0;
                if (plainEnter && pickerHasMatches()) {
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

        contributeActions();
        page.load();
        controller.subscribe();
        loadSelectors();
        host.runInBackground("Loading opencode commands", composer::loadCommands);
        maybeResumeFromSecondaryId();
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
        // history) - a persisted preference, re-applied when the page reloads
        Action reasoningAction = new Action("Thinking", org.eclipse.jface.action.IAction.AS_CHECK_BOX) {
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
        backgroundAction.setToolTipText("Background this session's blocking tools and keep working (v2 Ctrl+B)");
        Action queueSendAction = new Action("Send to Queue") {
            @Override
            public void run() {
                queueCurrentInput();
            }
        };
        queueSendAction.setToolTipText("Park this prompt in the session inbox - delivered after the current run (v2 Alt+Enter)");
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
            if ("undo".equals(builtIn)) {
                controller.undoLastTurn();
            } else {
                controller.redoReverted();
            }
            return;
        }
        submitSelection(composer.resolve(text));
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
        if (queueList == null || queueList.isDisposed() || controller == null) {
            return;
        }
        List<String> pending = controller.queuedPrompts();
        int keep = queueList.getSelectionIndex();
        queueList.removeAll();
        for (String text : pending) {
            queueList.add(text);
        }
        boolean show = !pending.isEmpty();
        if (show) {
            int itemHeight = Math.max(queueList.getItemHeight(), 18);
            ((GridData) queueList.getLayoutData()).heightHint =
                    Math.min(pending.size(), QUEUE_ROWS) * itemHeight + 4;
            if (keep >= 0 && keep < pending.size()) {
                queueList.setSelection(keep);
            }
        }
        boolean visibilityChanged = show != queueList.isVisible();
        Composite row = queueList.getParent();
        queueList.setVisible(show);
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
        int index = queueList == null || queueList.isDisposed() ? -1 : queueList.getSelectionIndex();
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
        int index = queueList == null || queueList.isDisposed() ? -1 : queueList.getSelectionIndex();
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
        int index = queueList == null || queueList.isDisposed() ? -1 : queueList.getSelectionIndex();
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

    // ---------- T-004 / T-005: ask banner + session actions ----------

    private Composite askRowComposite;
    private org.eclipse.swt.widgets.Label askLabel;
    private volatile com.opencode.ide.client.activity.PermissionRequest currentAsk;
    private volatile com.opencode.ide.client.OpencodeClient askClient;
    private final java.util.Set<String> answeredAsks = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            Display.getDefault().asyncExec(() -> hideAsk());
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

    /** The single answer path (once/always/reject) with optional reject feedback (T-004). */
    private void answerAsk(String decision, boolean remember, String feedback) {
        com.opencode.ide.client.activity.PermissionRequest ask = currentAsk;
        com.opencode.ide.client.OpencodeClient client = askClient;
        if (ask == null || client == null) {
            return;
        }
        answeredAsks.add(ask.permissionId());
        hideAsk();
        com.opencode.ide.client.WorkerPools.submit("chat-permission-answer", () -> {
            String message;
            try {
                message = client.respondToPermission(ask.sessionId(), ask.permissionId(), decision, remember, feedback)
                        ? "answered '" + decision + "'" : "answer not accepted";
            } catch (Exception e) {
                message = "answer failed: " + e.getMessage();
            }
            String finalMessage = message;
            Display.getDefault().asyncExec(() -> {
                if (page != null) {
                    page.notice(finalMessage);
                }
            });
        });
    }

    private void rejectAskWithFeedback() {
        org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                getSite().getShell(), "Reject with feedback",
                "Optional feedback for the agent (why the request is rejected):", "", null);
        if (dialog.open() == org.eclipse.jface.window.Window.OK) {
            String feedback = dialog.getValue();
            answerAsk("reject", false, feedback == null || feedback.isBlank() ? null : feedback.trim());
        }
    }

    /**
     * Reconnect recovery (T-004): SSE events are not replayed, so pending
     * asks are re-read from GET /permission/request when the view appears.
     */
    private void scheduleAskRecovery() {
        com.opencode.ide.client.WorkerPools.submit("chat-ask-recovery", () -> {
            try {
                com.opencode.ide.core.OpencodeConnection connection =
                        com.opencode.ide.core.OpencodeConnection.getInstance();
                com.opencode.ide.client.OpencodeClient client = connection.getClient();
                for (com.opencode.ide.client.activity.PermissionRequest request
                        : client.listPermissionRequests(connection.getWorkingDirectory())) {
                    String sid = controller == null ? null : controller.sessionId();
                    if (request.pending() && !answeredAsks.contains(request.permissionId())
                            && (sid == null || sid.equals(request.sessionId()))) {
                        Display.getDefault().asyncExec(() -> showAsk(request, client));
                    }
                }
            } catch (Exception ignored) {
                // older server / not connected - the banner simply stays hidden
            }
        });
    }

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
                message = "session backgrounded - long tools keep running while you work";
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
