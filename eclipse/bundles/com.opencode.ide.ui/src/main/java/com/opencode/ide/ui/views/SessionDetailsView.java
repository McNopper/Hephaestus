package com.opencode.ide.ui.views;

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
import com.opencode.ide.core.context.SessionViewIds;
import com.opencode.ide.ui.console.ShellTasksConsole;
import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.internal.UiActivator;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.SessionSections;
import com.opencode.ide.ui.model.SessionSections.Section;
import com.opencode.ide.ui.model.SessionShells;
import com.opencode.ide.ui.model.SessionSubagents;
import com.opencode.ide.ui.model.SkillRows;
import com.opencode.ide.ui.session.EnvironmentDialog;
import com.opencode.ide.ui.session.SessionDetailsController;
import com.opencode.ide.ui.session.SessionEventFilter;
import com.opencode.ide.ui.session.SessionImportPreview;
import com.opencode.ide.ui.session.SessionTranscript;
import com.opencode.ide.ui.session.SessionTranscriptFiles;
import com.opencode.ide.ui.session.ServiceText;
import com.opencode.ide.ui.session.SessionDetailsController.LifecycleResult;
import com.opencode.ide.ui.session.SessionDetailsController.MessageRow;
import com.opencode.ide.ui.session.SessionDetailsController.SessionDetails;
import com.opencode.ide.ui.session.SessionDetailsController.ToolLine;

/**
 * Session details: the message history of ONE session (header aggregates +
 * one row per message, with reasoning and tool calls as children). The
 * session is carried in the view's <b>secondary id</b>
 * ({@code SessionDetailsView:some-session-id}, optionally suffixed
 * {@code ~live} to open live-watching), so several sessions can be open at
 * once; opened without a secondary id it shows a notice.
 *
 * <p>Loading mirrors ServerView/ProvidersView: the (blocking)
 * {@link SessionDetailsController#load()} runs in a system job via
 * {@link ViewLoadSupport}; results land on the UI thread, failures become a
 * status message — never an exception. A toolbar toggle auto-refreshes every
 * 5s while the view is open.</p>
 *
 * <p>Openers that cannot see this bundle's classes (the board bundle's
 * Fleet view opens this view by plain id, U-015 "Watch live") pass the
 * live-watch parameter through the secondary id: an id built by
 * {@code SessionViewIds.secondaryId(sessionId, true)} ends in the
 * live-watch segment, and {@link #createPartControl} parses it back into
 * the explicit (session, auto-refresh) pair. A view opened that way starts
 * with Auto Refresh checked (the 5s insurance on top of the always-on SSE
 * reloads); the user can toggle it off once the watched job settles. The
 * parameter lives in the view's OWN secondary id, so it can never reach an
 * unrelated view.</p>
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
 * <p>U-046 slice 2 adds "Attach skill" (toolbar + context: pick one of the
 * session's skills, then the EXPERIMENTAL {@code POST .../session/{id}/skill};
 * success is the notice "skill attached") and "Suggest title" (one transient
 * {@code POST .../generate} completion offered PREFILLED in the rename
 * dialog — the session is never renamed without the user accepting).</p>
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
 *
 * <p>U-041: the tree opens with two live sections above the transcript —
 * the session's SUBAGENT children ({@code parentID} nesting, each row opens
 * its own details view) and its SHELL TASKS (transcript-derived, refreshed
 * by the {@code session.shell.*} events; each row shows status + command,
 * opens its output in a real Eclipse console via
 * {@link ShellTasksConsole}, and can be removed with a confirm). The same
 * surface serves chat sessions and fleet worker sessions — both are ordinary
 * sessions of their server.</p>
 *
 * <p>U-048: every successful load marks the session viewed
 * ({@code POST .../session/{id}/view} — the read marker behind the server's
 * unread/idle badges; fire-and-forget, a server without the route stays
 * silent). The two-phase revert is explicit: "Revert to here…" stages the
 * boundary (and restores the touched working-tree files — the server's
 * default), "Undo revert" clears the stage and puts the files back, and
 * only "Commit revert…" cuts the message history — each behind a confirm
 * that states the phase truth. The session environment is edited in a
 * dialog that says the PUT REPLACES the whole map, and "Import session…"
 * walks a deliberate pick -&gt; preview -&gt; import flow over the export
 * format.</p>
 */
public class SessionDetailsView extends ViewPart implements Refreshable {

    public static final String ID = "com.opencode.ide.ui.views.SessionDetailsView";

    /**
     * The workbench's built-in default text editor, opened by id (registry
     * lookup — no class reference, so no editor bundle dependency). It
     * renders the temp-file {@code FileStoreEditorInput} natively.
     */
    private static final String DEFAULT_TEXT_EDITOR_ID = "org.eclipse.ui.DefaultTextEditor";

    /**
     * The chat view opened after a fork, by id from the ONE seam
     * {@link SessionViewIds#CHAT_VIEW_ID} (registry lookup — the chat
     * bundle is NOT a compile-time dependency of this bundle, same pattern
     * as {@link #DEFAULT_TEXT_EDITOR_ID}). Its secondary id convention
     * ({@code ses_…}, built by {@link SessionViewIds#secondaryId(String)})
     * makes it resume the forked session with its history.
     */

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
    /** U-046 slice 2: attach a skill / suggest a title (both need a session). */
    private Action attachSkillAction;
    private Action suggestTitleAction;
    /** The Auto Refresh toolbar toggle (field so a live-watch open can check it). */
    private Action autoRefreshAction;

    /** Tree child node carrying the (collapsed, dimmed) reasoning of a message. */
    private record ReasoningLine(String text) {
    }

    @Override
    public void createPartControl(Composite parent) {
        com.opencode.ide.core.context.SessionViewIds.Parsed input =
                com.opencode.ide.core.context.SessionViewIds.parse(getViewSite().getSecondaryId());
        String sessionId = input.sessionId();

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
            armAutoRefresh(input.autoRefresh());
            refresh();
        }
    }

    /**
     * Arms Auto Refresh for a view opened with the live-watch segment in
     * its secondary id (U-015 "Watch live"): toggle checked, 5s timer
     * running, so a running fleet worker can be watched live; the always-on
     * SSE reloads are unaffected. The user can toggle it off once the
     * watched job settles. The parameter is this view's OWN secondary id
     * (parsed by {@link #createPartControl} via
     * {@code SessionViewIds.parse}), never shared global state, so it
     * cannot arm an unrelated view.
     */
    private void armAutoRefresh(boolean requested) {
        if (!requested) {
            return;
        }
        if (autoRefreshAction != null) {
            autoRefreshAction.setChecked(true);
        }
        setAutoRefresh(true);
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

    /** Toolbar icon from THIS bundle's own icon set (e.g. the skill icon). */
    private static org.eclipse.jface.resource.ImageDescriptor localIcon(String path) {
        return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(UiActivator.PLUGIN_ID, path);
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
        attachSkillAction = new Action("Attach skill") {
            @Override
            public void run() {
                chooseAndAttachSkill();
            }
        };
        attachSkillAction.setToolTipText(
                "Attach a skill to this session (experimental v2 POST .../session/{id}/skill)");
        attachSkillAction.setImageDescriptor(localIcon(UiActivator.ICON_SKILL));
        suggestTitleAction = new Action("Suggest title") {
            @Override
            public void run() {
                suggestTitle();
            }
        };
        suggestTitleAction.setToolTipText(
                "Suggest a title with a one-shot LLM call (v2 POST .../generate) - offered in the rename dialog");
        suggestTitleAction.setImageDescriptor(icon("thinking"));
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(refreshAction);
        toolBar.add(autoRefreshAction);
        toolBar.add(forkAction);
        toolBar.add(summarizeAction);
        toolBar.add(attachSkillAction);
        toolBar.add(suggestTitleAction);
    }

    private void setLifecycleActionsEnabled(boolean enabled) {
        if (forkAction != null) {
            forkAction.setEnabled(enabled);
        }
        if (summarizeAction != null) {
            summarizeAction.setEnabled(enabled);
        }
        if (attachSkillAction != null) {
            attachSkillAction.setEnabled(enabled);
        }
        if (suggestTitleAction != null) {
            suggestTitleAction.setEnabled(enabled);
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

    // ---------- U-046 slice 2: attach skill / suggest title ----------

    /**
     * "Attach skill": loads the session's skills off the UI thread, offers the
     * picker, then attaches the chosen id. Nothing happens without a pick.
     */
    private void chooseAndAttachSkill() {
        if (controller == null) {
            return;
        }
        ViewLoadSupport.load("Loading skills", controller::skills,
                skills -> {
                    if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
                        return;
                    }
                    List<SkillRows.Row> rows = SkillRows.rows(skills);
                    if (rows.isEmpty()) {
                        showStatus("No skills available for this session");
                        return;
                    }
                    SkillPickerDialog dialog = new SkillPickerDialog(getSite().getShell(), rows);
                    if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.selectedSkillId() != null) {
                        attachSkill(dialog.selectedSkillId());
                    }
                },
                error -> showActionError("Loading skills", ViewLoadSupport.message(error)));
    }

    /** Attaches one skill id; failure is a notice, success reads "skill attached". */
    private void attachSkill(String skillId) {
        runLifecycleAction("Attaching skill", () -> controller.attachSkill(skillId),
                result -> showStatus("skill attached"));
    }

    /**
     * "Suggest title": one transient generate completion on the CURRENT
     * session; the suggestion lands PREFILLED in the rename dialog - nothing
     * is renamed until the user accepts it there.
     */
    private void suggestTitle() {
        if (controller == null) {
            return;
        }
        runLifecycleAction("Suggesting title", controller::suggestTitle,
                result -> promptForTitle(result.detail()));
    }

    /**
     * The rename dialog (also the acceptance step for a suggested title):
     * pre-filled with the current title or the suggestion; OK renames via the
     * controller. Never auto-renames.
     */
    private void promptForTitle(String initial) {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed() || controller == null) {
            return;
        }
        org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                getSite().getShell(), "Rename session", "Session title:",
                initial == null ? "" : initial, null);
        if (dialog.open() == org.eclipse.jface.window.Window.OK) {
            String title = dialog.getValue();
            runLifecycleAction("Renaming session", () -> controller.rename(title),
                    result -> showStatus("Session renamed: " + title));
        }
    }

    /** Read-only document dialog (session log, session stats, terminal screen). */
    private void showTextDialog(String title, String text) {
        new com.opencode.ide.ui.session.TextDialog(getSite().getShell(), title, text).open();
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
            SessionSubagents.Row subagent = selectedElement() instanceof SessionSubagents.Row candidate
                    ? candidate : null;
            SessionShells.Row shell = selectedElement() instanceof SessionShells.Row candidate
                    ? candidate : null;
            if (subagent != null) {
                Action openDetails = new Action("Open session details") {
                    @Override
                    public void run() {
                        openSessionDetails(subagent.sessionId());
                    }
                };
                openDetails.setToolTipText(
                        "Open this subagent's own Session Details view (its transcript and actuals)");
                menu.add(openDetails);
                Action openChat = new Action("Open in chat") {
                    @Override
                    public void run() {
                        if (openForkInChat(subagent.sessionId())) {
                            showStatus("Opened subagent in chat");
                        }
                    }
                };
                openChat.setToolTipText("Resume this subagent in a chat view (secondary id = session id)");
                menu.add(openChat);
                menu.add(new Separator());
            }
            if (shell != null) {
                Action showOutput = new Action("Show output\u2026") {
                    @Override
                    public void run() {
                        showTextDialog("Shell " + shell.shellId(),
                                "$ " + shell.command() + "\n\n"
                                        + (shell.outputTail() == null ? "(no output captured)"
                                                : shell.outputTail()));
                    }
                };
                showOutput.setToolTipText("The task's captured output tail (from the session transcript)");
                showOutput.setEnabled(shell.outputTail() != null);
                menu.add(showOutput);
                Action openConsole = new Action("Open in Console") {
                    @Override
                    public void run() {
                        openShellInConsole(shell);
                    }
                };
                openConsole.setToolTipText(
                        "Open this task's output in an Eclipse console (live tail, refreshed with the view)");
                menu.add(openConsole);
                Action removeShell = new Action("Remove shell task\u2026") {
                    @Override
                    public void run() {
                        confirmAndRun("Remove shell task",
                                "Remove the shell task " + shell.shellId() + " from the server?\n"
                                        + "\n"
                                        + "$ " + shell.command() + "\n"
                                        + "\n"
                                        + "The captured output file is deleted; the session transcript\n"
                                        + "keeps its shell message.",
                                "Remove",
                                () -> runLifecycleAction("Removing shell task",
                                        () -> controller.removeShellTask(shell.shellId()),
                                        result -> {
                                            showStatus(result.detail());
                                            refresh();
                                        }));
                    }
                };
                removeShell.setToolTipText("Reap the finished task (v2 DELETE /api/shell/{id})");
                menu.add(removeShell);
                menu.add(new Separator());
            }
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
            Action renameSession = new Action("Rename session...") {
                @Override
                public void run() {
                    SessionDetails snapshot = currentSnapshot;
                    if (snapshot == null) {
                        return;
                    }
                    promptForTitle(snapshot.title() == null ? "" : snapshot.title());
                }
            };
            renameSession.setToolTipText("Rename this session (v2 PATCH /api/session/{id})");
            renameSession.setEnabled(currentSnapshot != null && currentSnapshot.sessionId() != null);
            menu.add(renameSession);
            Action suggestTitle = new Action("Suggest title") {
                @Override
                public void run() {
                    SessionDetailsView.this.suggestTitle();
                }
            };
            suggestTitle.setToolTipText(
                    "Suggest a title with a one-shot LLM call (v2 POST .../generate); nothing is renamed until you accept");
            suggestTitle.setEnabled(currentSnapshot != null && currentSnapshot.sessionId() != null);
            menu.add(suggestTitle);
            Action attachSkill = new Action("Attach skill\u2026") {
                @Override
                public void run() {
                    chooseAndAttachSkill();
                }
            };
            attachSkill.setToolTipText(
                    "Attach a skill to this session (experimental v2 POST .../session/{id}/skill)");
            attachSkill.setEnabled(currentSnapshot != null && currentSnapshot.sessionId() != null);
            menu.add(attachSkill);
            Action sendToBackground = new Action("Send to background") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    runLifecycleAction("Sending to background", controller::sendToBackground,
                            result -> showStatus(result.detail()));
                }
            };
            sendToBackground.setToolTipText("Push this session to the background (v2 POST .../background)");
            sendToBackground.setEnabled(currentSnapshot != null);
            menu.add(sendToBackground);
            Action runShell = new Action("Run shell command...") {
                @Override
                public void run() {
                    SessionDetails snapshot = currentSnapshot;
                    if (snapshot == null) {
                        return;
                    }
                    org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                            getSite().getShell(), "Run shell command", "Command:", "make test", null);
                    if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                        String command = dialog.getValue();
                        runLifecycleAction("Running shell", () -> controller.runShell(command),
                                result -> showStatus(result.detail()));
                    }
                }
            };
            runShell.setToolTipText("Run a shell command in this session's context (v2 POST .../shell)");
            runShell.setEnabled(currentSnapshot != null);
            menu.add(runShell);
            Action revertAtMessage = new Action("Revert to here\u2026") {
                @Override
                public void run() {
                    MessageRow selected = selectedMessageRow();
                    if (selected == null || selected.id() == null || selected.id().isBlank()) {
                        return;
                    }
                    confirmAndRun("Revert to here",
                            "Stage a revert of this session to BEFORE the selected message?\n"
                                    + "\n"
                                    + "Phase 1 of 2 (stage): the boundary is recorded and the working-tree\n"
                                    + "files the later messages touched are restored immediately (the v2\n"
                                    + "default; the client verb cannot stage without files).\n"
                                    + "The transcript is NOT cut yet - nothing is final until you commit.",
                            "Stage revert",
                            () -> runLifecycleAction("Staging revert",
                                    () -> controller.stageRevert(selected.id()),
                                    result -> showStatus(result.detail())));
                }
            };
            revertAtMessage.setToolTipText(
                    "Stage the two-phase revert at the selected message (v2 revert/stage; files restored by default)");
            revertAtMessage.setEnabled(canFork);
            menu.add(revertAtMessage);
            Action undoRevert = new Action("Undo revert\u2026") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    confirmAndRun("Undo revert",
                            "Undo the staged revert?\n"
                                    + "\n"
                                    + "The working-tree files are put back from the snapshot taken at\n"
                                    + "staging time and the staged boundary is cleared. The transcript was\n"
                                    + "never cut, so nothing else changes. (A no-op when nothing is staged.)",
                            "Undo revert",
                            () -> runLifecycleAction("Undoing revert", controller::undoRevert,
                                    result -> showStatus(result.detail())));
                }
            };
            undoRevert.setToolTipText("Restore the staged files and clear the staged revert (v2 DELETE .../revert)");
            undoRevert.setEnabled(currentSnapshot != null);
            menu.add(undoRevert);
            Action commitRevert = new Action("Commit revert\u2026") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    confirmAndRun("Commit revert",
                            "Cut this session's message history back to the staged boundary?\n"
                                    + "\n"
                                    + "Phase 2 of 2 (commit): the messages after the boundary are removed\n"
                                    + "from the session. This is IRREVERSIBLE - undo is no longer possible\n"
                                    + "afterwards. Nothing happens when no revert is staged.",
                            "Commit revert",
                            () -> runLifecycleAction("Committing revert", controller::commitRevert,
                                    result -> showStatus(result.detail())));
                }
            };
            commitRevert.setToolTipText(
                    "Commit the staged revert: cut the history to the boundary (irreversible; v2 revert/commit)");
            commitRevert.setEnabled(currentSnapshot != null);
            menu.add(commitRevert);
            menu.add(new Separator());
            Action moveSession = new Action("Move to directory...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                            getSite().getShell(), "Move session", "Directory:", "", null);
                    if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                        String directory = dialog.getValue();
                        runLifecycleAction("Moving session", () -> controller.move(directory),
                                result -> showStatus(result.detail()));
                    }
                }
            };
            moveSession.setToolTipText("Move this session to another directory (v2 POST .../move)");
            moveSession.setEnabled(currentSnapshot != null);
            menu.add(moveSession);
            Action switchAgent = new Action("Switch agent...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                            getSite().getShell(), "Switch agent", "Agent:", "", null);
                    if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                        String agent = dialog.getValue();
                        runLifecycleAction("Switching agent", () -> controller.switchAgent(agent),
                                result -> showStatus(result.detail()));
                    }
                }
            };
            switchAgent.setToolTipText("Switch this session's agent mid-run (v2 POST .../agent)");
            switchAgent.setEnabled(currentSnapshot != null);
            menu.add(switchAgent);
            Action switchModel = new Action("Switch model...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                            getSite().getShell(), "Switch model", "Model (provider/model):", "", null);
                    if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                        String model = dialog.getValue();
                        runLifecycleAction("Switching model", () -> controller.switchModel(model),
                                result -> showStatus(result.detail()));
                    }
                }
            };
            switchModel.setToolTipText("Switch this session's model mid-run (v2 POST .../model) - the cost lever");
            switchModel.setEnabled(currentSnapshot != null);
            menu.add(switchModel);
            Action compactSession = new Action("Compact context") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    runLifecycleAction("Compacting context", controller::compact,
                            result -> showStatus(result.detail()));
                }
            };
            compactSession.setToolTipText("Compact this session's context (v2 POST .../compact)");
            compactSession.setEnabled(currentSnapshot != null);
            menu.add(compactSession);
            menu.add(new Separator());
            Action exportTranscript = new Action("Export transcript...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    org.eclipse.swt.widgets.FileDialog dialog = new org.eclipse.swt.widgets.FileDialog(
                            getSite().getShell(), org.eclipse.swt.SWT.SAVE);
                    dialog.setFileName("session-export.txt");
                    String target = dialog.open();
                    if (target == null) {
                        return;
                    }
                    String document = controller.export();
                    if (document == null) {
                        showActionError("Exporting transcript", "the service returned no export");
                        return;
                    }
                    try {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(target), document);
                        showStatus("Exported to " + target);
                    } catch (java.io.IOException e) {
                        showActionError("Exporting transcript", String.valueOf(e.getMessage()));
                    }
                }
            };
            exportTranscript.setToolTipText("Export this session (v2 GET .../experimental/session/{id}/export)");
            exportTranscript.setEnabled(currentSnapshot != null);
            menu.add(exportTranscript);
            Action sessionLogAction = new Action("Session log...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    String log = controller.sessionLog();
                    showTextDialog("Session log", log == null ? "(unavailable)" : log);
                }
            };
            sessionLogAction.setToolTipText("Read the session log (v2 GET .../experimental/session/{id}/log)");
            sessionLogAction.setEnabled(currentSnapshot != null);
            menu.add(sessionLogAction);
            Action sessionStatsAction = new Action("Session stats...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    showTextDialog("Session stats", ServiceText.keyValues(controller.stats()));
                }
            };
            sessionStatsAction.setToolTipText("Session statistics from the service (v2 GET .../experimental/session/stats)");
            sessionStatsAction.setEnabled(currentSnapshot != null);
            menu.add(sessionStatsAction);
            Action terminalAction = new Action("Terminal...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    showTextDialog("Session terminal", ServiceText.terminal(controller.terminal()));
                }
            };
            terminalAction.setToolTipText("The session's controlled terminal, read-only (v2 .../terminal/read)");
            terminalAction.setEnabled(currentSnapshot != null);
            menu.add(terminalAction);
            menu.add(new Separator());
            Action forms = new Action("Forms...") {
                @Override
                public void run() {
                    if (currentSnapshot == null) {
                        return;
                    }
                    java.util.List<java.util.Map<String, Object>> open = controller.openForms();
                    if (open.isEmpty()) {
                        showStatus("No open forms for this session");
                        return;
                    }
                    java.util.Map<String, Object> form = open.get(0);
                    String formTitle = String.valueOf(form.getOrDefault("title", "Form"));
                    com.opencode.ide.ui.session.FormsDialog dialog =
                            new com.opencode.ide.ui.session.FormsDialog(getSite().getShell(), formTitle,
                                    com.opencode.ide.ui.session.FormSchema.fieldsOf(form.get("fields")));
                    if (open.size() > 1) {
                        showStatus(open.size() + " open forms - showing the first");
                    }
                    if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                        String formID = String.valueOf(form.get("id"));
                        runLifecycleAction("Answering form",
                                () -> controller.replyForm(formID, dialog.answer()),
                                result -> showStatus(result.detail()));
                    }
                }
            };
            forms.setToolTipText("Open and answer the session's forms (v2 session.form.*)");
            forms.setEnabled(currentSnapshot != null);
            menu.add(forms);
            Action environment = new Action("Environment\u2026") {
                @Override
                public void run() {
                    editEnvironment();
                }
            };
            environment.setToolTipText(
                    "Replace the session's environment variables (v2 PUT .../environment - full replace, no read route)");
            environment.setEnabled(currentSnapshot != null);
            menu.add(environment);
            Action importSession = new Action("Import session\u2026") {
                @Override
                public void run() {
                    importSessionFromFile();
                }
            };
            importSession.setToolTipText(
                    "Import a session from an exported session JSON (pick, preview, then v2 POST .../session/import)");
            importSession.setEnabled(currentSnapshot != null);
            menu.add(importSession);
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
        Object element = selectedElement();
        return element instanceof MessageRow row ? row : null;
    }

    /** The tree's first selected element (message, subagent or shell row). */
    private Object selectedElement() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return null;
        }
        Object selection = viewer.getStructuredSelection();
        return (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured)
                ? structured.getFirstElement()
                : null;
    }

    /**
     * One confirmed action: the dialog states the whole truth (what phase
     * runs, what it changes) and the confirm button names the phase; OK is
     * the only path to the action. Cancel and a disposed view run nothing.
     */
    private void confirmAndRun(String title, String message, String confirmLabel, Runnable action) {
        if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        org.eclipse.jface.dialogs.MessageDialog dialog = new org.eclipse.jface.dialogs.MessageDialog(
                getSite().getShell(), title, null, message,
                org.eclipse.jface.dialogs.MessageDialog.QUESTION,
                new String[] { confirmLabel, "Cancel" }, 0);
        if (dialog.open() == 0) {
            action.run();
        }
    }

    /**
     * U-041: opens one session's details view by the shared
     * {@link SessionViewIds} seam (secondary id = encoded session id) — the
     * same open ServerView uses, so a subagent row and a Server-view row
     * focus the SAME view instance instead of duplicating it.
     */
    private void openSessionDetails(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || viewDisposed) {
            return;
        }
        try {
            getSite().getPage().showView(SessionViewIds.SESSION_DETAILS_VIEW_ID,
                    SessionViewIds.secondaryId(sessionId), IWorkbenchPage.VIEW_ACTIVATE);
        } catch (PartInitException e) {
            showStatus("Opening session details failed");
            UiActivator.getDefault().getLog().log(
                    new Status(Status.ERROR, UiActivator.PLUGIN_ID,
                            "Failed to open session details for " + sessionId, e));
        }
    }

    /**
     * U-041: opens one shell task's console. The current output tail loads
     * off the UI thread ({@code GET /api/shell/{id}/output}); the console
     * then shows it and stays live through {@link #refreshShellConsoles}.
     */
    private void openShellInConsole(SessionShells.Row shell) {
        if (controller == null || shell == null || shell.shellId() == null) {
            return;
        }
        ViewLoadSupport.load("Reading shell output", () -> controller.shellOutput(shell.shellId()),
                output -> {
                    if (viewDisposed || viewer == null || viewer.getControl().isDisposed()) {
                        return;
                    }
                    String text = output == null
                            ? (shell.outputTail() == null ? "(no output captured)" : shell.outputTail())
                            : output;
                    ShellTasksConsole.open(shell.shellId(), shell.command(), text);
                },
                error -> showActionError("Reading shell output", ViewLoadSupport.message(error)));
    }

    /** U-048: the full-replace environment dialog; OK writes the edited map. */
    private void editEnvironment() {
        if (controller == null || viewDisposed || viewer.getControl().isDisposed()) {
            return;
        }
        EnvironmentDialog dialog = new EnvironmentDialog(getSite().getShell(), controller.sessionId());
        if (dialog.open() == org.eclipse.jface.window.Window.OK && dialog.answer() != null) {
            runLifecycleAction("Replacing session environment",
                    () -> controller.replaceEnvironment(dialog.answer()),
                    result -> showStatus(result.detail()));
        }
    }

    /**
     * U-048: the deliberate import flow — pick an exported session JSON
     * (the format "Export transcript…" writes), preview it (title + message
     * count), then import into the connection's directory (this session's
     * own directory; unscoped when unknown). Reading and parsing happen off
     * the UI thread; a session-id conflict (the export's session already
     * exists) and every other server failure surface as a notice, never as
     * fake success.
     */
    private void importSessionFromFile() {
        if (controller == null || viewDisposed || viewer.getControl().isDisposed()) {
            return;
        }
        org.eclipse.swt.widgets.FileDialog dialog = new org.eclipse.swt.widgets.FileDialog(
                getSite().getShell(), org.eclipse.swt.SWT.OPEN);
        dialog.setText("Import session - pick an exported session JSON");
        dialog.setFilterExtensions(new String[] { "*.json", "*.*" });
        String source = dialog.open();
        if (source == null) {
            return;
        }
        // one background job reads the file, parses the preview and resolves
        // the import directory (both IO); the dialog chain stays on the UI thread
        ViewLoadSupport.load("Reading the export", () -> new ImportCandidate(
                SessionImportPreview.parse(java.nio.file.Files.readString(java.nio.file.Path.of(source))),
                controller.directory(), source),
                candidate -> {
                    if (viewDisposed || viewer.getControl().isDisposed()) {
                        return;
                    }
                    confirmAndImport(candidate);
                },
                error -> showActionError("Reading the export", ViewLoadSupport.message(error)));
    }

    /** The preview confirmation; OK runs the import into the resolved directory. */
    private void confirmAndImport(ImportCandidate candidate) {
        confirmAndRun("Import session",
                "Import this session into the current connection?\n"
                        + "\n"
                        + "Title:    " + candidate.preview().title() + "\n"
                        + "Messages: " + candidate.preview().messageCount() + "\n"
                        + "Source:   " + candidate.source() + "\n"
                        + "\n"
                        + "The service re-creates the session (including its cost/token actuals)\n"
                        + "in " + (candidate.directory() == null ? "the server's default location"
                                : candidate.directory()) + ".",
                "Import",
                () -> runLifecycleAction("Importing session",
                        () -> controller.importSession(candidate.preview().info(),
                                candidate.preview().messages(), candidate.directory()),
                        result -> showStatus("Imported as session " + result.detail())));
    }

    /** The background-read import bundle (preview + resolved target directory). */
    private record ImportCandidate(SessionImportPreview.Preview preview, String directory, String source) {
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
            IWorkbenchPage page = getSite().getPage();
            page.showView(SessionViewIds.CHAT_VIEW_ID, SessionViewIds.secondaryId(forkSessionId),
                    IWorkbenchPage.VIEW_ACTIVATE);
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
        viewer.setInput(snapshot.roots()); // a List, never a bare element (dev rule)
        expandSections(snapshot);           // the live sections open, messages stay collapsed
        setContentDescription(snapshot.rows().size() + " messages");
        markSessionViewed();                // U-048: the unread/idle read marker
        refreshShellConsoles(snapshot);     // U-041: keep open consoles' tails live
    }

    /**
     * Opens this snapshot's section roots (subagents, shell tasks) — the
     * ticket's point is that they are VISIBLE nested under the parent
     * session; the message rows keep their collapsed-by-default children.
     */
    private void expandSections(SessionDetails snapshot) {
        List<Object> sections = new ArrayList<>();
        for (Object root : SessionSections.roots(snapshot.subagents(), snapshot.shellTasks())) {
            if (root instanceof Section section) {
                sections.add(section);
            }
        }
        if (!sections.isEmpty()) {
            viewer.setExpandedElements(sections.toArray());
        }
    }

    /**
     * U-048: fire-and-forget read marker. A plain one-shot system job (NOT
     * {@link ViewLoadSupport} — its 3-attempt retry would hammer a server
     * without the route on every refresh); failures are swallowed on purpose:
     * the marker is bookkeeping, never worth error noise.
     */
    private void markSessionViewed() {
        if (controller == null || viewDisposed) {
            return;
        }
        org.eclipse.core.runtime.jobs.Job job = org.eclipse.core.runtime.jobs.Job.create(
                "Marking session viewed", monitor -> {
                    controller.markViewed(); // never throws; result ignored by design
                    return Status.OK_STATUS;
                });
        job.setSystem(true);
        job.schedule();
    }

    /**
     * U-041: pushes fresh output tails into the shell consoles this view
     * opened (any task of THIS snapshot with an open console). Runs on the
     * load cadence — auto-refresh timer and debounced SSE reloads — which is
     * what keeps a running task's console live.
     */
    private void refreshShellConsoles(SessionDetails snapshot) {
        if (controller == null || viewDisposed || viewer.getControl().isDisposed()) {
            return;
        }
        List<SessionShells.Row> open = new ArrayList<>();
        for (SessionShells.Row row : snapshot.shellTasks()) {
            if (ShellTasksConsole.isOpen(row.shellId())) {
                open.add(row);
            }
        }
        if (open.isEmpty()) {
            return;
        }
        ViewLoadSupport.load("Refreshing shell output", () -> {
            java.util.Map<String, String> outputs = new java.util.LinkedHashMap<>();
            for (SessionShells.Row row : open) {
                String output = controller.shellOutput(row.shellId());
                if (output != null) {
                    outputs.put(row.shellId(), output);
                }
            }
            return outputs;
        }, outputs -> {
            if (viewDisposed || viewer.getControl().isDisposed()) {
                return;
            }
            for (SessionShells.Row row : open) {
                String output = outputs.get(row.shellId());
                if (output != null) {
                    ShellTasksConsole.refresh(row.shellId(), row.command(), output,
                            row.detailLabel());
                }
            }
        }, error -> {
            // the next refresh retries; an open console keeps its last tail
        });
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

    /** Message column: role (bold for user) + first-line preview; section/subagent/shell rows styled. */
    private final class MessageLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof Section section) {
                return section.label();
            }
            if (element instanceof SessionSubagents.Row subagent) {
                return "subagent: " + subagent.title();
            }
            if (element instanceof SessionShells.Row shell) {
                String preview = preview(shell.command(), PREVIEW_LENGTH);
                return preview.isEmpty() ? "shell" : "shell: " + preview;
            }
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
            if (element instanceof Section) {
                return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
            }
            if (element instanceof MessageRow row && "user".equals(row.role())) {
                return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
            }
            return null;
        }

        @Override
        public Color getForeground(Object element) {
            if (element instanceof Section) {
                return systemColor(SWT.COLOR_DARK_GRAY);
            }
            if (element instanceof SessionSubagents.Row subagent && "busy".equals(subagent.status())) {
                return systemColor(SWT.COLOR_BLUE);
            }
            if (element instanceof SessionShells.Row shell) {
                return switch (shell.status() == null ? "" : shell.status()) {
                    case "running" -> systemColor(SWT.COLOR_BLUE);
                    case "timeout", "killed" -> systemColor(SWT.COLOR_RED);
                    case "exited" -> systemColor(SWT.COLOR_DARK_GRAY);
                    default -> null;
                };
            }
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
            if (element instanceof SessionSubagents.Row subagent) {
                return subagent.detailLabel();
            }
            if (element instanceof SessionShells.Row shell) {
                return shell.outputTail() == null || shell.outputTail().isBlank()
                        ? shell.command()
                        : shell.command() + "\n---\n" + shell.outputTail();
            }
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

    /** Details column: agent • model • time for messages; status/actuals for the U-041 rows. */
    private final class DetailLabelProvider extends ColumnLabelProvider {
        @Override
        public String getText(Object element) {
            if (element instanceof SessionSubagents.Row subagent) {
                return subagent.detailLabel();
            }
            if (element instanceof SessionShells.Row shell) {
                return shell.detailLabel();
            }
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

    /**
     * Section roots (subagents, shell tasks) with their nested rows; message
     * rows as roots; reasoning + tool calls as (collapsed by default)
     * children of their message.
     */
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
            if (parent instanceof Section section) {
                return section.children().toArray();
            }
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
            if (parent instanceof Section section) {
                return !section.children().isEmpty();
            }
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
