package com.opencode.ide.ui.views;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.internal.UiActivator;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.session.SessionDetailsController;
import com.opencode.ide.ui.session.SessionEventFilter;
import com.opencode.ide.ui.session.SessionTranscript;
import com.opencode.ide.ui.session.SessionTranscriptFiles;
import com.opencode.ide.ui.session.SessionDetailsController.LifecycleResult;
import com.opencode.ide.ui.session.SessionDetailsController.MessageRow;
import com.opencode.ide.ui.session.SessionDetailsController.SessionDetails;
import com.opencode.ide.ui.session.SessionDetailsController.ToolLine;

/**
 * Session details: the message history of ONE session (header aggregates +
 * one row per message, with reasoning and tool calls as children). The
 * session is carried in the view's <b>secondary id</b>
 * ({@code SessionDetailsView:some-session-id}), so several sessions can be
 * open at once; opened without a secondary id it shows a notice.
 *
 * <p>Loading mirrors ServerView/ProvidersView: the (blocking)
 * {@link SessionDetailsController#load()} runs in a system job via
 * {@link ViewLoadSupport}; results land on the UI thread, failures become a
 * status message — never an exception. A toolbar toggle auto-refreshes every
 * 5s while the view is open.</p>
 *
 * <p>Openers that cannot see this bundle's classes (the board bundle's
 * Fleet view opens this view by plain id, U-015 "Watch live") can still arm
 * Auto Refresh: they set the one-shot {@link #AUTO_REFRESH_HINT_PROPERTY}
 * system property to the session id right before {@code showView}; because
 * {@code showView} runs {@link #createPartControl} synchronously on the same
 * UI thread, the hand-off is race-free. A matching session opens with Auto
 * Refresh checked (the 5s insurance on top of the always-on SSE reloads);
 * the user can toggle it off once the watched job settles. The hint is
 * always consumed, so it can never leak into an unrelated view.</p>
 *
 * <p>Live updates: the view subscribes to the primary connection's
 * {@code /event} SSE fan-out and reloads (debounced, see
 * {@link #EVENT_REFRESH_DEBOUNCE_MILLIS}) when an event affects THIS
 * session ({@link SessionEventFilter}); the 5s timer stays as insurance.
 * Only the newest load may render, so an older in-flight result can never
 * overwrite a newer one.</p>
 *
 * <p>Session lifecycle actions (Fork, Summarize) run the controller's
 * SWT-free actions in background jobs via {@link ViewLoadSupport} — the
 * controller returns a {@link LifecycleResult} instead of throwing, so a
 * mutating POST is never blindly retried. Success lands as a status line
 * message, failures as the view's usual error pattern.
 * Forking works at the LATEST message (toolbar) and at any SELECTED history
 * message (context menu, TUI parity) — both switch to the fork by opening it
 * in the chat, resumed with its history; the original session is untouched.
 * (Share/Unshare is gone: opencode v2 has no session share endpoint.)</p>
 *
 * <p>The context menu also opens one message or the whole transcript in a
 * read-only workbench text editor (Batch C): tier-0 view-only, formatted by
 * the SWT-free {@link SessionTranscript}, written to a delete-on-exit temp
 * file by {@link SessionTranscriptFiles} and opened through the platform's
 * {@code FileStoreEditorInput} — the modern workbench removed
 * {@code IStorageEditorInput}, so an EFS file store is the supported
 * read-only editor surface. The editor shows a snapshot — edits change only
 * the delete-on-exit temp copy, never the session — and it does not
 * follow live updates.</p>
 */
public class SessionDetailsView extends ViewPart implements Refreshable {

    public static final String ID = "com.opencode.ide.ui.views.SessionDetailsView";

    /**
     * System-property key of the one-shot live-watch hand-off: an opener in
     * a bundle that cannot depend on this one (the Fleet view) sets it to
     * the session id it is about to open, immediately before
     * {@code showView}; {@link #createPartControl} consumes any value on the
     * same UI-thread call stack (see
     * {@link #applyAutoRefreshHint(String)}). The key is mirrored as a plain
     * literal in that bundle — keep both spellings in sync.
     */
    public static final String AUTO_REFRESH_HINT_PROPERTY =
            com.opencode.ide.core.context.SessionViewIds.AUTO_REFRESH_HINT_PROPERTY;

    /**
     * The workbench's built-in default text editor, opened by id (registry
     * lookup — no class reference, so no editor bundle dependency). It
     * renders the temp-file {@code FileStoreEditorInput} natively.
     */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor";

    /**
     * The chat view opened after a fork, by id (registry lookup — the chat
     * bundle is NOT a compile-time dependency of this bundle, same pattern as
     * {@link #DEFAULT_TEXT_EDITOR_ID}). Its secondary id convention
     * ({@code ses_…}) makes it resume the forked session with its history.
     */
    private static final String CHAT_VIEW_ID = "com.opencode.ide.chat.views.ChatView";

    private static final int AUTO_REFRESH_MILLIS = 5000;
    private static final int PREVIEW_LENGTH = 120;
    /** Debounce for SSE-driven reloads: streaming bursts coalesce into ~3 loads/sec max. */
    private static final int EVENT_REFRESH_DEBOUNCE_MILLIS = 300;

    private Label headerLabel;
    private TreeViewer viewer;
    private SessionDetailsController controller;
    private boolean autoRefresh;
    private boolean viewDisposed;
    private OpencodeEventListener eventListener;
    /** True while the SSE debounce timer is armed (coalesces event bursts). */
    private boolean eventRefreshScheduled;
    /** Monotonic load counter: only the newest started load may render its result. */
    private int loadSequence;
    /** The last rendered snapshot (drives the editor actions' enablement/content). */
    private SessionDetails currentSnapshot;
    private Action forkAction;
    private Action summarizeAction;
    /** The Auto Refresh toolbar toggle (field so the live-watch hint can check it). */
    private Action autoRefreshAction;

    /** Tree child node carrying the (collapsed, dimmed) reasoning of a message. */
    private record ReasoningLine(String text) {
    }

    @Override
    public void createPartControl(Composite parent) {
        String sessionId = sanitize(getViewSite().getSecondaryId());

        Composite outer = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 2;
        outer.setLayout(layout);

        headerLabel = new Label(outer, SWT.WRAP);
        headerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite treeComposite = new Composite(outer, SWT.NONE);
        TreeColumnLayout treeLayout = new TreeColumnLayout();
        treeComposite.setLayout(treeLayout);
        treeComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        viewer = new TreeViewer(treeComposite,
                SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        viewer.setContentProvider(new DetailsContentProvider());

        TreeViewerColumn messageCol = new TreeViewerColumn(viewer, SWT.NONE);
        messageCol.getColumn().setText("Message");
        messageCol.setLabelProvider(new MessageLabelProvider());
        TreeViewerColumn detailCol = new TreeViewerColumn(viewer, SWT.NONE);
        detailCol.getColumn().setText("Details");
        detailCol.setLabelProvider(new DetailLabelProvider());

        treeLayout.setColumnData(messageCol.getColumn(), new ColumnWeightData(3, 220, true));
        treeLayout.setColumnData(detailCol.getColumn(), new ColumnWeightData(2, 160, true));

        viewer.setInput(List.of()); // never a tree element as input (dev rule)

        hookContextMenu();
        contributeActions();
        if (sessionId == null) {
            headerLabel.setText("No session selected — open this view via the Server view.");
            setContentDescription("Open via the Server view");
            setLifecycleActionsEnabled(false); // nothing to act on
        } else {
            controller = new SessionDetailsController(sessionId, this::supplyClient);
            registerEventListener();
            applyAutoRefreshHint(sessionId);
            refresh();
        }
    }

    /**
     * Consumes the one-shot {@link #AUTO_REFRESH_HINT_PROPERTY} hand-off
     * (U-015 "Watch live"): when the property names THIS session, Auto
     * Refresh arms (toggle checked, 5s timer running) so a running fleet
     * worker can be watched live; the always-on SSE reloads are unaffected.
     * Any value is consumed — a stale hint must never switch an unrelated
     * view into auto-refreshing. Both the setter (before {@code showView})
     * and this consumer run on the UI thread inside one call stack, so the
     * hand-off needs no synchronization.
     */
    private void applyAutoRefreshHint(String sessionId) {
        String hint = System.getProperty(AUTO_REFRESH_HINT_PROPERTY);
        if (hint == null) {
            return;
        }
        System.clearProperty(AUTO_REFRESH_HINT_PROPERTY);
        if (!sessionId.equals(hint)) {
            return;
        }
        if (autoRefreshAction != null) {
            autoRefreshAction.setChecked(true);
        }
        setAutoRefresh(true);
    }

    /**
     * The session id behind the secondary id - the ONE decoding
     * ({@link com.opencode.ide.core.context.SessionViewIds}, T-009: the
     * former per-view sanitizers produced different ids for the same
     * session and opened duplicate views).
     */
    private static String sanitize(String secondaryId) {
        return com.opencode.ide.core.context.SessionViewIds.sessionId(secondaryId);
    }

    private OpencodeClient supplyClient() {
        try {
            return OpencodeConnection.getInstance().getClient();
        } catch (OpencodeException e) {
            throw new RuntimeException(e); // unwrapped into an error note by the controller
        }
    }

    /** Toolbar icon from the shared set in {@code com.opencode.ide.core} (see ServerView#contributeActions). */
    private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
        return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "com.opencode.ide.core", "icons/actions/" + name + ".png");
    }

    private void contributeActions() {
        Action refreshAction = new Action("Refresh") {
            @Override
            public void run() {
                refresh();
            }
        };
        refreshAction.setToolTipText("Reload the session history");
        refreshAction.setImageDescriptor(icon("refresh"));
        autoRefreshAction = new Action("Auto Refresh", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                setAutoRefresh(isChecked());
            }
        };
        autoRefreshAction.setToolTipText("Refresh automatically every 5 seconds");
        autoRefreshAction.setImageDescriptor(icon("auto-refresh"));
        forkAction = new Action("Fork") {
            @Override
            public void run() {
                runLifecycleAction("Forking session", () -> controller.fork(null),
                        result -> showStatus("Forked to session " + result.detail()
                                + (openForkInChat(result.detail()) ? " - opened in chat" : "")));
            }
        };
        forkAction.setToolTipText("Fork this session at its latest message and open the fork in the chat");
        forkAction.setImageDescriptor(icon("fork"));
        summarizeAction = new Action("Summarize") {
            @Override
            public void run() {
                runLifecycleAction("Summarizing session", controller::summarize,
                        result -> showStatus("Summarized with " + result.detail()));
            }
        };
        summarizeAction.setToolTipText("Compact the session history into a summary");
        summarizeAction.setImageDescriptor(icon("summarize"));
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(refreshAction);
        toolBar.add(autoRefreshAction);
        toolBar.add(forkAction);
        toolBar.add(summarizeAction);
    }

    private void setLifecycleActionsEnabled(boolean enabled) {
        if (forkAction != null) {
            forkAction.setEnabled(enabled);
        }
        if (summarizeAction != null) {
            summarizeAction.setEnabled(enabled);
        }
    }

    /**
     * Runs one lifecycle action off the UI thread (system job via
     * {@link ViewLoadSupport}) and delivers its {@link LifecycleResult} on the
     * UI thread. The controller never throws, so failing POSTs are NOT
     * retried (a retry could e.g. fork twice); the error path is for
     * catastrophic failures only.
     */
    private void runLifecycleAction(String jobName, ViewLoadSupport.Loader<LifecycleResult> work,
            Consumer<LifecycleResult> onSuccess) {
        if (controller == null || viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        ViewLoadSupport.load(jobName, work,
                result -> {
                    if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
                        return;
                    }
                    if (result.success()) {
                        onSuccess.accept(result);
                    } else {
                        showActionError(jobName, result.error());
                    }
                },
                error -> showActionError(jobName, ViewLoadSupport.message(error)));
    }

    /** Mirrors {@link #showError(Throwable)} for action failures: description + log. */
    private void showActionError(String what, String error) {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        String detail = (error == null || error.isBlank()) ? "unknown error" : error;
        setContentDescription("Error: " + detail);
        statusLineMessage(what + " failed");
        UiActivator.getDefault().getLog().log(
                new Status(Status.ERROR, UiActivator.PLUGIN_ID, what + " failed: " + detail));
    }

    /** Transient action feedback goes to the workbench status line. */
    private void showStatus(String message) {
        statusLineMessage(message);
    }

    private void statusLineMessage(String message) {
        IStatusLineManager statusLine = getViewSite().getActionBars().getStatusLineManager();
        if (statusLine != null) {
            statusLine.setMessage(message);
        }
    }

    /** UI-thread clipboard copy (best-effort; empty/null text is ignored). */
    private void copyToClipboard(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Clipboard clipboard = new Clipboard(viewer.getControl().getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    /**
     * Context menu on message rows: fork the session AT the selected message
     * (TUI parity), copy the full message text, or open one message / the
     * whole transcript in a text editor (per-show enablement). The fork is the
     * only mutating action - the original session stays untouched; the fork
     * opens in the chat, resumed with its history.
     */
    private void hookContextMenu() {
        MenuManager manager = new MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            MessageRow row = selectedMessageRow();
            boolean canFork = row != null && row.id() != null && !row.id().isBlank();
            Action forkAtMessage = new Action("Fork at this message") {
                @Override
                public void run() {
                    MessageRow selected = selectedMessageRow();
                    if (selected == null || selected.id() == null || selected.id().isBlank()) {
                        return;
                    }
                    runLifecycleAction("Forking session at message", () -> controller.fork(selected.id()),
                            result -> showStatus("Forked to session " + result.detail()
                                    + (openForkInChat(result.detail()) ? " - opened in chat" : "")));
                }
            };
            forkAtMessage.setToolTipText(
                    "Fork this session at the selected message and open the fork in the chat");
            forkAtMessage.setEnabled(canFork);
            menu.add(forkAtMessage);
            menu.add(new Separator());
            Action copyText = new Action("Copy message text") {
                @Override
                public void run() {
                    MessageRow selected = selectedMessageRow();
                    if (selected != null) {
                        copyToClipboard(selected.text());
                        showStatus("Message text copied");
                    }
                }
            };
            copyText.setEnabled(row != null && row.text() != null && !row.text().isBlank());
            menu.add(copyText);
            Action openMessageEditor = new Action("Open message in Editor") {
                @Override
                public void run() {
                    MessageRow selected = selectedMessageRow();
                    if (selected == null) {
                        return;
                    }
                    String sessionId = currentSnapshot == null ? null : currentSnapshot.sessionId();
                    openInEditor(SessionTranscript.messageEditorName(sessionId, selectedMessageIndex()),
                            SessionTranscript.message(selected));
                }
            };
            openMessageEditor.setToolTipText("Open the selected message in a text editor (snapshot copy)");
            openMessageEditor.setEnabled(row != null && row.text() != null && !row.text().isBlank());
            menu.add(openMessageEditor);
            Action openTranscriptEditor = new Action("Open transcript in Editor") {
                @Override
                public void run() {
                    SessionDetails snapshot = currentSnapshot;
                    if (snapshot != null) {
                        openInEditor(SessionTranscript.editorName(snapshot.sessionId()),
                                SessionTranscript.transcript(snapshot));
                    }
                }
            };
            openTranscriptEditor.setToolTipText("Open the whole transcript in a text editor (snapshot copy)");
            openTranscriptEditor.setEnabled(currentSnapshot != null);
            menu.add(openTranscriptEditor);
        });
        viewer.getControl().setMenu(manager.createContextMenu(viewer.getControl()));
    }

    private MessageRow selectedMessageRow() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return null;
        }
        Object selection = viewer.getStructuredSelection();
        Object first = (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured)
                ? structured.getFirstElement()
                : null;
        return first instanceof MessageRow row ? row : null;
    }

    /**
     * 1-based position of the selected message row in the current snapshot
     * ({@code -1} when unknown); feeds the per-message editor name so two
     * editors for different messages get distinct tabs. Records compare
     * structurally, so {@code indexOf} matches the rendered row.
     */
    private int selectedMessageIndex() {
        MessageRow row = selectedMessageRow();
        if (row == null || currentSnapshot == null) {
            return -1;
        }
        int index = currentSnapshot.rows().indexOf(row);
        return index < 0 ? -1 : index + 1;
    }

    /**
     * Opens generated read-only text in the workbench's default text editor.
     * Every call writes a fresh snapshot temp file (a transcript grows while
     * the agent runs, and re-using an open editor would keep stale text).
     * Failures log and surface on the status line — never a dialog from a
     * menu run.
     */
    private void openInEditor(String name, String content) {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        try {
            Path file = SessionTranscriptFiles.write(name, content);
            getSite().getPage().openEditor(
                    new FileStoreEditorInput(EFS.getLocalFileSystem().fromLocalFile(file.toFile())),
                    DEFAULT_TEXT_EDITOR_ID);
        } catch (PartInitException e) {
            showStatus("Opening editor failed");
            UiActivator.getDefault().getLog().log(
                    new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Failed to open session text in editor", e));
        }
    }

    /**
     * Opens the forked session in a chat view and focuses it: the chat
     * resumes the fork ({@code ses_…} secondary id convention) and renders
     * its history — the ORIGINAL session (this view) stays untouched.
     * Failures log and surface on the status line, never a dialog.
     *
     * @return true when the chat view was opened (a {@code "?"} placeholder
     *         or a disposed view opens nothing)
     */
    private boolean openForkInChat(String forkSessionId) {
        if (forkSessionId == null || forkSessionId.isBlank() || "?".equals(forkSessionId)
                || viewDisposed) {
            return false;
        }
        try {
            String secondary = URLEncoder.encode(forkSessionId, StandardCharsets.UTF_8);
            IWorkbenchPage page = getSite().getPage();
            page.showView(CHAT_VIEW_ID, secondary, IWorkbenchPage.VIEW_ACTIVATE);
            return true;
        } catch (PartInitException e) {
            showStatus("Opening the fork in chat failed");
            UiActivator.getDefault().getLog().log(
                    new Status(Status.ERROR, UiActivator.PLUGIN_ID,
                            "Failed to open forked session " + forkSessionId + " in chat", e));
            return false;
        }
    }

    private void setAutoRefresh(boolean enabled) {
        autoRefresh = enabled;
        if (enabled) {
            scheduleAutoRefresh();
        }
    }

    /** Re-arming 5s timer loop; stops itself when toggled off or the view is disposed. */
    private void scheduleAutoRefresh() {
        if (!autoRefresh || viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        viewer.getControl().getDisplay().timerExec(AUTO_REFRESH_MILLIS, () -> {
            if (!autoRefresh || viewDisposed || viewer.getControl().isDisposed()) {
                return;
            }
            refresh();
            scheduleAutoRefresh();
        });
    }

    // ---------- live updates (driven by /event SSE via core) ----------

    /** Subscribes once to the primary connection's SSE fan-out (idempotent). */
    private void registerEventListener() {
        if (eventListener != null) {
            return;
        }
        eventListener = this::onEvent;
        OpencodeConnection.getInstance().addEventListener(eventListener);
    }

    /** Called on the SSE background thread: filter for THIS session, then hop to the UI thread. */
    private void onEvent(OpencodeEvent event) {
        if (viewDisposed || controller == null
                || !SessionEventFilter.shouldRefreshFor(controller.sessionId(), event)) {
            return;
        }
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(this::scheduleEventRefresh);
        }
    }

    /** Called on the UI thread: coalesce bursts (streaming part updates) into one debounced reload. */
    private void scheduleEventRefresh() {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed() || eventRefreshScheduled) {
            return;
        }
        eventRefreshScheduled = true;
        viewer.getControl().getDisplay().timerExec(EVENT_REFRESH_DEBOUNCE_MILLIS, () -> {
            eventRefreshScheduled = false;
            if (viewDisposed || viewer.getControl().isDisposed()) {
                return;
            }
            refresh();
        });
    }

    @Override
    public void refresh() {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        if (controller == null) {
            setContentDescription("Open via the Server view");
            return;
        }
        setContentDescription("Loading...");
        int sequence = ++loadSequence;
        ViewLoadSupport.load("Loading session details", controller::load,
                snapshot -> {
                    if (sequence == loadSequence) {
                        showSnapshot(snapshot);
                    }
                },
                error -> {
                    if (sequence == loadSequence) {
                        showError(error);
                    }
                });
    }

    private void showSnapshot(SessionDetails snapshot) {
        if (viewDisposed || viewer.getControl().isDisposed()) {
            return;
        }
        currentSnapshot = snapshot; // feeds the editor actions (content + enablement)
        headerLabel.setText(headerText(snapshot));
        if (snapshot.errorNote() != null) {
            viewer.setInput(List.of());
            setContentDescription(snapshot.errorNote());
            return;
        }
        viewer.setInput(snapshot.rows()); // a List, never a bare element (dev rule)
        setContentDescription(snapshot.rows().size() + " messages");
    }

    private void showError(Throwable e) {
        if (viewDisposed || viewer.getControl().isDisposed()) {
            return;
        }
        viewer.setInput(List.of());
        setContentDescription("Error: " + ViewLoadSupport.message(e));
        UiActivator.getDefault().getLog().log(
                new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Failed to load session details", e));
    }

    /** Null-tolerant one-line header: title • id • model • cost • tokens (+ error note). */
    private static String headerText(SessionDetails snapshot) {
        String header = com.opencode.ide.ui.session.SessionTranscript.header(snapshot);
        return snapshot.errorNote() == null ? header : header + "\n" + snapshot.errorNote();
    }

    // ---------- label helpers (all null-safe) ----------

    private static String preview(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String firstLine = text.strip().split("\\R", 2)[0];
        return firstLine.length() <= max ? firstLine : firstLine.substring(0, max - 1) + "\u2026";
    }

    private static String roleOf(MessageRow row) {
        return row.role() == null || row.role().isBlank() ? "message" : row.role();
    }

    private static String toolState(ToolLine tool) {
        return tool.state() == null || tool.state().isBlank() ? "unknown" : tool.state();
    }

    private static String toolLabel(ToolLine tool) {
        return "tool: " + tool.name() + " \u2014 " + toolState(tool);
    }

    /** Message column: role (bold for user) + first-line preview; tool/reasoning children styled. */
    private final class MessageLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof MessageRow row) {
                String preview = preview(row.text(), PREVIEW_LENGTH);
                return preview.isEmpty() ? roleOf(row) : roleOf(row) + ": " + preview;
            }
            if (element instanceof ReasoningLine line) {
                return "reasoning: " + preview(line.text(), PREVIEW_LENGTH);
            }
            if (element instanceof ToolLine tool) {
                return toolLabel(tool);
            }
            return "";
        }

        @Override
        public Font getFont(Object element) {
            if (element instanceof MessageRow row && "user".equals(row.role())) {
                return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
            }
            return null;
        }

        @Override
        public Color getForeground(Object element) {
            if (element instanceof ReasoningLine) {
                return systemColor(SWT.COLOR_DARK_GRAY);
            }
            if (element instanceof ToolLine tool) {
                return switch (toolState(tool)) {
                    case "error" -> systemColor(SWT.COLOR_RED);
                    case "running" -> systemColor(SWT.COLOR_BLUE);
                    case "completed" -> systemColor(SWT.COLOR_DARK_GRAY);
                    default -> null;
                };
            }
            return null;
        }

        @Override
        public String getToolTipText(Object element) {
            if (element instanceof MessageRow row) {
                return row.text() == null || row.text().isBlank() ? null : row.text().strip();
            }
            if (element instanceof ReasoningLine line) {
                return line.text() == null || line.text().isBlank() ? null : line.text().strip();
            }
            if (element instanceof ToolLine tool) {
                return toolLabel(tool);
            }
            return null;
        }
    }

    /** Details column: agent • model • time for messages; dimmed preview for reasoning. */
    private final class DetailLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof MessageRow row) {
                List<String> parts = new ArrayList<>();
                if (row.agent() != null && !row.agent().isBlank()) {
                    parts.add(row.agent());
                }
                if (row.modelLabel() != null && !row.modelLabel().isBlank()) {
                    parts.add(row.modelLabel());
                }
                if (row.timeLabel() != null && !row.timeLabel().isBlank()) {
                    parts.add(row.timeLabel());
                }
                return String.join("  \u2022  ", parts);
            }
            if (element instanceof ReasoningLine line) {
                return preview(line.text(), 40);
            }
            return "";
        }

        @Override
        public Color getForeground(Object element) {
            if (element instanceof ReasoningLine) {
                return systemColor(SWT.COLOR_DARK_GRAY);
            }
            return null;
        }

        @Override
        public Font getFont(Object element) {
            if (element instanceof ReasoningLine) {
                return JFaceResources.getFontRegistry().getItalic(JFaceResources.DEFAULT_FONT);
            }
            return null;
        }
    }

    private Color systemColor(int swtColor) {
        Display display = viewer.getControl().getDisplay();
        return display.isDisposed() ? null : display.getSystemColor(swtColor);
    }

    /** Message rows as roots; reasoning + tool calls as (collapsed by default) children. */
    private static final class DetailsContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object input) {
            if (input instanceof Object[] array) {
                return array;
            }
            if (input instanceof List<?> list) {
                return list.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof MessageRow row) {
                List<Object> children = new ArrayList<>();
                if (row.reasoning() != null && !row.reasoning().isBlank()) {
                    children.add(new ReasoningLine(row.reasoning()));
                }
                children.addAll(row.tools());
                return children.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object parent) {
            if (parent instanceof MessageRow row) {
                return (row.reasoning() != null && !row.reasoning().isBlank()) || !row.tools().isEmpty();
            }
            return false;
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            // rows are immutable records; nothing to resolve
        }

        @Override
        public void dispose() {
            // stateless
        }
    }

    @Override
    public void dispose() {
        viewDisposed = true; // also stops the auto-refresh timer loop
        autoRefresh = false;
        if (eventListener != null) {
            try {
                OpencodeConnection.getInstance().removeEventListener(eventListener);
            } catch (Throwable ignored) {
                // best-effort during dispose
            }
            eventListener = null;
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        } else if (headerLabel != null && !headerLabel.isDisposed()) {
            headerLabel.setFocus();
        }
    }
}
