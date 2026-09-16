package com.opencode.ide.board.views;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.TaskFleetLauncher;
import com.opencode.ide.board.internal.BoardPlugin;
import com.opencode.ide.board.internal.GitCli;
import com.opencode.ide.board.model.DiffSource;
import com.opencode.ide.board.model.EventsFeed;
import com.opencode.ide.board.model.FleetJobsModel;
import com.opencode.ide.board.model.PeerJobReconstructor;
import com.opencode.ide.board.model.SessionDiffText;
import com.opencode.ide.board.model.TakeoverRouter;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.FileDiff;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.ManagedConnection;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.fleet.GlobalEventsAggregator;
import com.opencode.ide.fleet.GlobalEventsAggregator.ObservedEvent;
import com.opencode.ide.tasks.TaskStore;

/**
 * The Fleet view: one row per fleet job in the shared {@link FleetJobsModel}
 * (fed by the Board view's "Launch task"), with state coloring and
 * Open diff / Open folder / Take over actions — in the toolbar AND in a row
 * context menu that additionally offers Copy session id and Abort…
 * (abort asks for confirmation, then POSTs on a background thread). Takeover
 * is TUI-first: the
 * session is handed to the attached opencode TUI via {@link TakeoverRouter}
 * when one answers, else the worktree opens and the job is marked taken
 * over). Refreshes automatically on
 * model changes ({@code asyncExec} from the model listener). "Open diff"
 * shows the server's authoritative session diff when the job carries a
 * session id ({@link DiffSource}), falling back to the local git branch
 * diff; both run on a background thread (the client may spawn/wait for the
 * server, git can block for up to a minute) and open the dialog from
 * {@code asyncExec}; the action stays disabled while a diff is running.
 *
 * <p>F-004 (interim until V-006's daemon+attach): every refresh also shows
 * read-only rows for jobs launched by a PEER engine (a chat session's
 * fleet server), rebuilt from the shared on-disk truth — store claims plus
 * fleet worktrees — by {@link PeerJobReconstructor}. External rows are grey
 * and view-only: no Abort, no Take over; Open diff / Open folder / Copy
 * ticket id still work. Part activation re-reads them ({@link #setFocus}),
 * so no extra poller thread is introduced.</p>
 *
 * <p>The "Permissions (n)" toolbar action (enabled when n &gt; 0, count kept
 * live via the shared permission queue's listener) opens
 * {@link FleetPermissionsDialog} where unattended sessions' permission
 * requests are answered (approve once / always / reject).</p>
 *
 * <p>The "Events" toolbar action (live {@code n connections (m failed)}
 * suffix) opens {@link EventsDialog}: the merged global event feed of
 * every configured connection (primary + remotes). The view owns one
 * {@link GlobalEventsAggregator} — all configured connections subscribed
 * on a background thread (the primary's client may block while spawning
 * the server), re-synced when the connection set changes, unsubscribed
 * when removed, closed with the view.</p>
 */
public class FleetView extends ViewPart {

    public static final String ID = "com.opencode.ide.board.views.FleetView";

    private static final String EMPTY_STATE =
            "No fleet jobs yet — launch from the Board view or a chat session's fleet server.";

    private TableViewer viewer;
    private Composite tableComposite;
    private Label emptyLabel;
    private Action openDiffAction;
    private Action openFolderAction;
    private Action takeOverAction;
    private Action permissionsAction;
    private Action eventsAction;
    /** Single daemon thread for git diff processes (OSGi-light, never blocks the UI thread). */
    private ExecutorService diffExecutor;
    /** Single daemon thread for TUI takeover routing (blocking client POSTs). */
    private ExecutorService takeoverExecutor;
    /** Single daemon thread for global-event subscriptions (the primary client may block spawning the server). */
    private ExecutorService eventsExecutor;
    /** The view-owned merged global event feed over all configured connections. */
    private GlobalEventsAggregator events;
    /** SWT-free row/badge formatting for the Events action and dialog. */
    private EventsFeed eventsFeed;
    private final AtomicBoolean diffRunning = new AtomicBoolean();
    private final Runnable modelListener = () -> {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(this::refreshFromModel);
        }
    };
    /** Live pending-count hint: the permission queue notifies on every change (SSE thread). */
    private final Runnable permissionsListener = () -> {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(this::updatePermissionsAction);
        }
    };
    /** Event delivered (any stream thread): timestamp it for row formatting (see {@link EventsFeed#remember}). */
    private final Consumer<ObservedEvent> eventsListener = this::onGlobalEvent;
    /** Connection set changed (any thread): re-sync the event subscriptions off the UI thread. */
    private final Runnable connectionsListener = () -> {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(this::scheduleEventSync);
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        diffExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "board-fleet-diff");
            thread.setDaemon(true);
            return thread;
        });
        takeoverExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "board-fleet-takeover");
            thread.setDaemon(true);
            return thread;
        });
        events = new GlobalEventsAggregator();
        eventsFeed = new EventsFeed();
        events.addListener(eventsListener);
        eventsExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "board-fleet-events");
            thread.setDaemon(true);
            return thread;
        });

        Composite outer = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        outer.setLayout(layout);

        tableComposite = new Composite(outer, SWT.NONE);
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableComposite.setLayout(tableLayout);
        tableComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

        viewer = new TableViewer(tableComposite,
                SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.addSelectionChangedListener((ISelectionChangedListener) e -> updateActionEnablement());
        hookContextMenu();
        createColumn("Task", 40, row -> row.taskId(), false);
        createColumn("Session", 40, row -> row.sessionId(), false);
        createColumn("Worktree", 120, row -> row.worktree(), false);
        createColumn("State", 30, row -> row.state() == null ? "" : row.state().toString(), true);
        createColumn("Detail", 120, row -> row.detail(), false);

        emptyLabel = new Label(outer, SWT.CENTER | SWT.WRAP);
        emptyLabel.setText(EMPTY_STATE);
        GridData emptyData = new GridData(GridData.FILL_BOTH);
        emptyLabel.setLayoutData(emptyData);

        contributeActions();
        FleetJobsModel.getDefault().addListener(modelListener);
        TaskFleetLauncher.permissions().addListener(permissionsListener);
        TaskFleetLauncher.connectChatPermissions();
        ConnectionsManager.getDefault().addListener(connectionsListener);
        scheduleEventSync();
        refreshFromModel();
        updatePermissionsAction();
    }

    private interface RowText {
        String text(FleetJobHandle row);
    }

    /** @param stateColumn true only for the State column — keys the coloring on the column, not its header text */
    private void createColumn(String title, int weight, RowText value, boolean stateColumn) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                FleetJobHandle row = asRow(element);
                return row == null ? "" : value.text(row);
            }

            @Override
            public Color getForeground(Object element) {
                FleetJobHandle row = asRow(element);
                if (row == null) {
                    return null;
                }
                Display display = viewer.getControl().getDisplay();
                if (row.external()) {
                    // F-004: peer-engine rows are read-only — grey in every column
                    return display.getSystemColor(SWT.COLOR_DARK_GRAY);
                }
                if (row.state() == null || !stateColumn) {
                    return null;
                }
                return switch (row.state()) {
                    case FAILED -> display.getSystemColor(SWT.COLOR_RED);
                    case MERGED -> display.getSystemColor(SWT.COLOR_DARK_GREEN);
                    case RUNNING -> display.getSystemColor(SWT.COLOR_DARK_BLUE);
                    case COMPLETED -> null;
                };
            }
        });
        ((TableColumnLayout) tableComposite.getLayout())
                .setColumnData(column.getColumn(), new ColumnWeightData(weight, 60, true));
    }

    private static FleetJobHandle asRow(Object element) {
        return element instanceof FleetJobHandle row ? row : null;
    }

    private void contributeActions() {
        openDiffAction = new Action("Open diff") {
            @Override
            public void run() {
                openDiff();
            }
        };
        openDiffAction.setToolTipText(
                "Session diff from the opencode server; falls back to a git diff of the task branch");
        openDiffAction.setEnabled(false);

        openFolderAction = new Action("Open folder") {
            @Override
            public void run() {
                openFolder();
            }
        };
        openFolderAction.setToolTipText("Open the job's worktree in the file explorer");
        openFolderAction.setEnabled(false);

        takeOverAction = new Action("Take over") {
            @Override
            public void run() {
                takeOver();
            }
        };
        takeOverAction.setToolTipText(
                "Hand the session to the attached opencode TUI; without one, open the worktree and mark the job as taken over");
        takeOverAction.setEnabled(false);

        permissionsAction = new Action("Permissions") {
            @Override
            public void run() {
                new FleetPermissionsDialog(getSite().getShell(),
                        TaskFleetLauncher.permissions()).open();
            }
        };
        permissionsAction.setToolTipText(
                "Pending permission requests of unattended fleet sessions (approve once / always / reject)");
        permissionsAction.setEnabled(false);

        eventsAction = new Action("Events") {
            @Override
            public void run() {
                GlobalEventsAggregator aggregator = events;
                if (aggregator != null && eventsFeed != null) {
                    new EventsDialog(getSite().getShell(), aggregator, eventsFeed).open();
                }
            }
        };
        eventsAction.setToolTipText(
                "The merged global event feed of all configured connections (newest 50, live while open)");

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(permissionsAction);
        toolbar.add(eventsAction);
        toolbar.add(openDiffAction);
        toolbar.add(openFolderAction);
        toolbar.add(takeOverAction);
    }

    /** Refreshes the "Permissions (n)" hint from the queue (UI thread). */
    private void updatePermissionsAction() {
        if (permissionsAction == null || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        int pending = TaskFleetLauncher.permissions().pendingCount();
        permissionsAction.setText(pending == 0 ? "Permissions" : "Permissions (" + pending + ")");
        permissionsAction.setEnabled(pending > 0);
    }

    /** Event delivered (any stream thread): timestamps it for row formatting. */
    private void onGlobalEvent(ObservedEvent event) {
        EventsFeed feed = eventsFeed;
        if (feed != null) {
            feed.remember(event);
        }
    }

    /** Re-syncs event subscriptions off the UI thread (the primary client may block on server spawn). */
    private void scheduleEventSync() {
        ExecutorService executor = eventsExecutor;
        if (executor != null) {
            executor.execute(this::syncEventSubscriptions);
        }
    }

    /**
     * Subscribes every configured connection (primary + remotes) to the
     * merged event feed and unsubscribes removed ones. Runs on the events
     * executor — the primary's {@code client()} may block while spawning
     * the server; a refused subscribe only lands in the failed badge.
     */
    private void syncEventSubscriptions() {
        GlobalEventsAggregator aggregator = events;
        if (aggregator == null) {
            return;
        }
        try {
            Set<String> desired = new LinkedHashSet<>();
            for (ManagedConnection connection : ConnectionsManager.getDefault().connections()) {
                desired.add(connection.id());
                if (!aggregator.connections().contains(connection.id())) {
                    try {
                        aggregator.subscribe(connection.id(), connection.client());
                    } catch (OpencodeException | RuntimeException e) {
                        logWarn("Subscribing global events of " + connection.label()
                                + " failed: " + e.getMessage());
                    }
                }
            }
            for (String id : aggregator.connections()) {
                if (!desired.contains(id)) {
                    aggregator.unsubscribe(id);
                }
            }
        } finally {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(this::updateEventsAction);
            }
        }
    }

    /** Refreshes the "Events · n connections (m failed)" liveness hint (UI thread). */
    private void updateEventsAction() {
        if (eventsAction == null || events == null || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        String badge = EventsFeed.liveness(events.connections().size(), events.failedConnections().size());
        String text = badge.isEmpty() ? "Events" : "Events \u00b7 " + badge;
        if (!text.equals(eventsAction.getText())) {
            eventsAction.setText(text);
        }
    }

    private FleetJobHandle selectedRow() {
        IStructuredSelection selection = viewer.getStructuredSelection();
        if (selection == null || selection.isEmpty()
                || !(selection.getFirstElement() instanceof FleetJobHandle row)) {
            return null;
        }
        return row;
    }

    /** Context menu on job rows (BoardView pattern): per-show enablement from the current selection. */
    private void hookContextMenu() {
        MenuManager manager = new MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(this::fillContextMenu);
        viewer.getControl().setMenu(manager.createContextMenu(viewer.getControl()));
    }

    private void fillContextMenu(IContributionManager manager) {
        FleetJobHandle row = selectedRow();
        Action openDiff = new Action("Open diff") {
            @Override
            public void run() {
                openDiff();
            }
        };
        openDiff.setEnabled(row != null && !diffRunning.get());
        manager.add(openDiff);
        Action openFolder = new Action("Open folder") {
            @Override
            public void run() {
                openFolder();
            }
        };
        openFolder.setEnabled(row != null);
        manager.add(openFolder);
        Action takeOver = new Action("Take over") {
            @Override
            public void run() {
                takeOver();
            }
        };
        takeOver.setEnabled(row != null && !row.external());
        manager.add(takeOver);
        Action copyTicketId = new Action("Copy ticket id") {
            @Override
            public void run() {
                if (row != null) {
                    copyText(row.taskId());
                }
            }
        };
        copyTicketId.setEnabled(row != null);
        manager.add(copyTicketId);
        Action copySessionId = new Action("Copy session id") {
            @Override
            public void run() {
                if (row != null) {
                    copyText(row.sessionId());
                }
            }
        };
        copySessionId.setEnabled(row != null && row.sessionId() != null && !row.sessionId().isBlank());
        manager.add(copySessionId);
        manager.add(new Separator());
        Action abort = new Action("Abort\u2026") {
            @Override
            public void run() {
                abortSelected(row);
            }
        };
        abort.setEnabled(row != null && !row.external()
                && row.sessionId() != null && !row.sessionId().isBlank());
        manager.add(abort);
    }

    /** Copies non-blank text to the clipboard (UI thread — the context menu). */
    private void copyText(String text) {
        if (text == null || text.isBlank() || viewer == null || viewer.getControl().isDisposed()) {
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
     * Aborts the selected row's session: a confirm dialog, then the abort
     * POST on the takeover executor (blocking client call, off the UI thread
     * — the takeOver pattern); the outcome dialog opens via asyncExec.
     */
    private void abortSelected(FleetJobHandle row) {
        if (row == null || row.sessionId() == null || row.sessionId().isBlank()) {
            return;
        }
        String sessionId = row.sessionId();
        boolean confirmed = MessageDialog.openConfirm(getSite().getShell(), "Abort session",
                "Abort the running agent of session " + sessionId + "?\n(ticket " + row.taskId() + ")");
        if (!confirmed) {
            return;
        }
        ExecutorService executor = takeoverExecutor;
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            String error = null;
            try {
                OpencodeConnection.getInstance().getClient().abortSession(sessionId);
            } catch (OpencodeException | RuntimeException e) {
                error = e.getMessage();
            }
            final String failure = error;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (viewer == null || viewer.getControl().isDisposed()) {
                    return;
                }
                if (failure != null) {
                    MessageDialog.openError(getSite().getShell(), "Abort session",
                            "Aborting session " + sessionId + " failed: " + failure);
                } else {
                    MessageDialog.openInformation(getSite().getShell(), "Abort session",
                            "Abort sent for session " + sessionId + ".");
                }
            });
        });
    }

    private void updateActionEnablement() {
        FleetJobHandle row = selectedRow();
        boolean hasSelection = row != null;
        openDiffAction.setEnabled(hasSelection && !diffRunning.get());
        openFolderAction.setEnabled(hasSelection);
        // F-004: peer-engine rows are view-only — no take-over
        takeOverAction.setEnabled(hasSelection && !row.external());
    }

    private void refreshFromModel() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        List<FleetJobHandle> own = FleetJobsModel.getDefault().jobs();
        Set<String> live = new LinkedHashSet<>();
        for (FleetJobHandle job : own) {
            live.add(job.taskId());
        }
        List<FleetJobHandle> rows = new ArrayList<>(own);
        rows.addAll(peerRows(live));
        viewer.setInput(rows);
        boolean empty = rows.isEmpty();
        setLaidOut(tableComposite, !empty);
        setLaidOut(emptyLabel, empty);
        // Both exclude flags live on children of tableComposite's PARENT, so that
        // is the composite whose layout must be recomputed (the viewer's own
        // parent is tableComposite itself, laid out by a TableColumnLayout).
        tableComposite.getParent().layout(true, true);
        updateActionEnablement();
        updatePermissionsAction();
    }

    /**
     * F-004: read-only rows for jobs launched by a peer engine (a chat
     * session's fleet server), rebuilt from the shared on-disk truth via
     * {@link PeerJobReconstructor} — store claims plus fleet worktrees,
     * deduplicated against the live engine's own rows. Any failure yields
     * no peer rows and never breaks the view.
     */
    private static List<FleetJobHandle> peerRows(Set<String> liveTaskIds) {
        try {
            Path storeRoot = BoardView.tasksRoot();
            if (storeRoot == null) {
                return List.of();
            }
            Path opencode = storeRoot.getParent();
            Path repoRoot = opencode == null ? null : opencode.getParent();
            return PeerJobReconstructor.externalJobs(new TaskStore(storeRoot), repoRoot, liveTaskIds);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void setLaidOut(Control control, boolean visible) {
        control.setVisible(visible);
        GridData data = (GridData) control.getLayoutData();
        data.exclude = !visible;
    }

    /**
     * Runs the diff off the UI thread and opens the dialog via asyncExec.
     * Server-first: a job with a session id tries the authoritative
     * {@code GET /session/:id/diff} via the shared connection (whose client
     * may block while spawning the server — hence the background thread); an
     * empty or failing server diff falls back to the local git branch diff.
     */
    private void openDiff() {
        FleetJobHandle row = selectedRow();
        if (row == null || diffRunning.get()) {
            return;
        }
        boolean preferServer = DiffSource.of(row) == DiffSource.Source.SERVER;
        Path repo = repoRootOf(row);
        if (!preferServer && (repo == null || !Files.isDirectory(repo))) {
            MessageDialog.openInformation(getSite().getShell(), "Open diff",
                    "No main repo known for " + row.taskId()
                            + " (the job has no fleet worktree yet).");
            return;
        }
        diffRunning.set(true);
        updateActionEnablement();
        String taskId = row.taskId();
        String sessionId = row.sessionId();
        Path repoRoot = repo;
        ExecutorService executor = diffExecutor;
        if (executor == null) {
            diffRunning.set(false);
            return;
        }
        executor.execute(() -> {
            String title = null;
            String text = null;
            String failure = null;
            if (preferServer) {
                try {
                    List<FileDiff> diffs = OpencodeConnection.getInstance().getClient()
                            .getSessionDiff(sessionId, null);
                    if (!diffs.isEmpty()) {
                        title = "Diff " + taskId + " (session " + sessionId + ")";
                        text = SessionDiffText.format(diffs);
                    }
                } catch (OpencodeException | RuntimeException e) {
                    // no authoritative diff (server down, unknown session) — try git
                }
            }
            if (text == null) {
                if (repoRoot == null || !Files.isDirectory(repoRoot)) {
                    failure = "No server diff for session " + sessionId
                            + " and no fleet worktree to run a local git diff against.";
                } else {
                    title = "Diff " + taskId + " ("
                            + com.opencode.ide.git.FleetGit.branchFor(taskId) + ")";
                    try {
                        String diff = GitCli.diff(repoRoot, taskId);
                        text = diff.isBlank() ? "(no differences)" : diff;
                    } catch (RuntimeException e) {
                        failure = e.getMessage();
                    }
                }
            }
            String dialogTitle = title;
            String result = text;
            String error = failure;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (viewer == null || viewer.getControl().isDisposed()) {
                    return;
                }
                diffRunning.set(false);
                updateActionEnablement();
                if (error != null) {
                    MessageDialog.openError(getSite().getShell(), "Open diff", error);
                } else {
                    new TextDialog(getSite().getShell(), dialogTitle, result).open();
                }
            });
        });
    }

    private void openFolder() {
        FleetJobHandle row = selectedRow();
        if (row == null) {
            return;
        }
        Path worktree = worktreeOf(row);
        if (worktree != null && Files.isDirectory(worktree)) {
            Program.launch(worktree.toString());
        } else {
            MessageDialog.openInformation(getSite().getShell(), "Open folder",
                    "Worktree not found: " + (worktree == null ? "(none)" : worktree.toString()));
        }
    }

    /**
     * TUI-first takeover (ROADMAP H5 item 3): the routing runs off the UI
     * thread (the client may spawn/wait for the server; every TUI action is
     * a blocking POST) and the outcome applies via {@code asyncExec}. On
     * TUI the session is handed to the attached opencode TUI; on CHAT (no
     * TUI attached, no session) the pre-TUI behavior stands — open the
     * worktree and mark the job taken over.
     */
    private void takeOver() {
        FleetJobHandle row = selectedRow();
        if (row == null) {
            return;
        }
        if (row.external()) {
            return; // F-004: peer-engine rows are view-only
        }
        if (row.sessionId() == null || row.sessionId().isBlank()) {
            openWorktreeTakeOver(row);
            return;
        }
        String sessionId = row.sessionId();
        String prompt = TakeoverRouter.takeoverPrompt(row.taskId(), null);
        ExecutorService executor = takeoverExecutor;
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            TakeoverRouter.Result result = routeTakeover(sessionId, prompt);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (viewer == null || viewer.getControl().isDisposed()) {
                    return;
                }
                if (result.outcome() == TakeoverRouter.Outcome.TUI) {
                    MessageDialog.openInformation(getSite().getShell(), "Take over",
                            "Session handed to the attached TUI.\n" + result.detail());
                    FleetJobsModel.getDefault().update(new FleetJobHandle(row.taskId(), row.sessionId(),
                            row.worktree(), row.state(), "taken over by user (TUI)", row.external()));
                } else {
                    openWorktreeTakeOver(row);
                }
            });
        });
    }

    /** Routes the takeover; a client-acquisition failure is a CHAT result, never an exception. */
    private static TakeoverRouter.Result routeTakeover(String sessionId, String prompt) {
        try {
            return TakeoverRouter.route(OpencodeConnection.getInstance().getClient(), sessionId, prompt);
        } catch (OpencodeException | RuntimeException e) {
            return TakeoverRouter.Result.chat("no opencode server: " + e.getMessage());
        }
    }

    /** The pre-TUI takeover: open the worktree in the file explorer and mark the job taken over. */
    private void openWorktreeTakeOver(FleetJobHandle row) {
        Path worktree = worktreeOf(row);
        if (worktree != null && Files.isDirectory(worktree)) {
            Program.launch(worktree.toString());
            FleetJobsModel.getDefault().update(new FleetJobHandle(row.taskId(), row.sessionId(),
                    row.worktree(), row.state(), "taken over by user", row.external()));
        } else {
            MessageDialog.openInformation(getSite().getShell(), "Take over",
                    "Worktree not found: " + (worktree == null ? "(none)" : worktree.toString()));
        }
    }

    private static Path worktreeOf(FleetJobHandle row) {
        String worktree = row.worktree();
        return worktree == null || worktree.isBlank() ? null : Path.of(worktree);
    }

    private static Path repoRootOf(FleetJobHandle row) {
        Path worktree = worktreeOf(row);
        if (worktree == null) {
            return null;
        }
        Path fleetDir = worktree.getParent();
        Path gitDir = fleetDir == null ? null : fleetDir.getParent();
        return gitDir == null ? null : gitDir.getParent();
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
            // F-004: part activation is the existing cadence that picks up
            // peer-engine rows — the model listener only fires for own jobs,
            // and no extra poller thread may be introduced
            refreshFromModel();
        }
    }

    @Override
    public void dispose() {
        FleetJobsModel.getDefault().removeListener(modelListener);
        TaskFleetLauncher.permissions().removeListener(permissionsListener);
        ConnectionsManager.getDefault().removeListener(connectionsListener);
        if (events != null) {
            events.close();
            events = null;
        }
        if (diffExecutor != null) {
            diffExecutor.shutdown();
            diffExecutor = null;
        }
        if (takeoverExecutor != null) {
            takeoverExecutor.shutdown();
            takeoverExecutor = null;
        }
        if (eventsExecutor != null) {
            eventsExecutor.shutdown();
            eventsExecutor = null;
        }
        super.dispose();
    }

    private static void logWarn(String message) {
        Platform.getLog(Platform.getBundle(BoardPlugin.PLUGIN_ID))
                .log(new Status(Status.WARNING, BoardPlugin.PLUGIN_ID, message));
    }
}
