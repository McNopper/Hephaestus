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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.TaskFleetLauncher;
import com.opencode.ide.board.internal.BoardPlugin;
import com.opencode.ide.board.internal.GitCli;
import com.opencode.ide.board.model.DiffSource;
import com.opencode.ide.board.model.SessionDiffSides;
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
 * Watch live / Open diff / Open folder / Take over actions — in the toolbar
 * AND in a row context menu that additionally offers Copy session id and
 * Abort… (abort asks for confirmation, then POSTs on a background thread).
 * "Watch live" (U-015, also on double-click of a row with a session) opens
 * the job's worker session in the Session Details view (opened by plain
 * view id, secondary id = session id — no ui-bundle dependency); while the
 * job is RUNNING the freshly opened view arms its Auto Refresh via a
 * one-shot property hand-off (see {@link #openLiveWatch}), and the user can
 * toggle that off once the job settles. Takeover
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

    private static final String SESSION_DETAILS_VIEW_ID =
            com.opencode.ide.core.context.SessionViewIds.SESSION_DETAILS_VIEW_ID;

    /**
     * One-shot Session Details auto-refresh hint, set right before
     * {@code showView} for a RUNNING job so the freshly created view opens
     * live-watching (both sides run on the same UI-thread call stack). The
     * spelling lives in {@code core.context.SessionViewIds} (T-009) - this
     * bundle used to mirror the literal.
     */
    private static final String SESSION_DETAILS_AUTO_REFRESH_HINT =
            com.opencode.ide.core.context.SessionViewIds.AUTO_REFRESH_HINT_PROPERTY;

    private static final String EMPTY_STATE =
            "No fleet jobs yet — launch from the Board view or a chat session's fleet server.";

    private TableViewer viewer;
    private Composite tableComposite;
    private Label emptyLabel;
    private Action watchLiveAction;
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
    /**
     * Peer-scan generation: incremented by every refreshFromModel, so a
     * background peer scan applies its rows only while it is still the
     * newest refresh (review M1 — the scan runs off the UI thread and must
     * not race a newer refresh's rows into the viewer).
     */
    private final AtomicInteger refreshGeneration = new AtomicInteger();
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

        // ---- the prominent fleet control (user requirement 2026-09-23): the
        // fleet is DISABLED by default - enable / pause / stop live HERE ----
        Composite fleetRow = new Composite(outer, SWT.NONE);
        GridLayout fleetRowLayout = new GridLayout(4, false);
        fleetRowLayout.marginWidth = 4;
        fleetRowLayout.marginHeight = 4;
        fleetRow.setLayout(fleetRowLayout);
        fleetRow.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, true, false));
        org.eclipse.swt.widgets.Label fleetState = new org.eclipse.swt.widgets.Label(fleetRow, SWT.NONE);
        fleetState.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        org.eclipse.swt.widgets.Button enableFleet = new org.eclipse.swt.widgets.Button(fleetRow, SWT.PUSH);
        enableFleet.setText("Enable Fleet");
        enableFleet.setToolTipText(
                "Arm the auto-dispatch pump (the recurring Waves mode stays the Board's opt-up). Disabled by default - the fleet is a token eater.");
        org.eclipse.swt.widgets.Button pauseFleet = new org.eclipse.swt.widgets.Button(fleetRow, SWT.PUSH);
        pauseFleet.setText("Pause");
        pauseFleet.setToolTipText("No NEW dispatches; running jobs settle normally (never killed)");
        org.eclipse.swt.widgets.Button stopFleet = new org.eclipse.swt.widgets.Button(fleetRow, SWT.PUSH);
        stopFleet.setText("Stop");
        stopFleet.setToolTipText("Pumps off and the engine down - back to the disabled default");

        com.opencode.ide.core.FleetLifecycle fleetControl = com.opencode.ide.core.FleetLifecycle.getDefault();
        Runnable renderFleetState = () -> {
            if (fleetState.isDisposed()) {
                return;
            }
            fleetState.setText(fleetControl.label());
            enableFleet.setEnabled(fleetControl.canEnable());
            pauseFleet.setEnabled(fleetControl.canPause());
            stopFleet.setEnabled(fleetControl.canStop());
        };
        renderFleetState.run();
        fleetControl.addListener(state -> getSite().getShell().getDisplay().asyncExec(renderFleetState));
        enableFleet.addListener(SWT.Selection, e -> fleetControl.enable());
        pauseFleet.addListener(SWT.Selection, e -> fleetControl.pause());
        stopFleet.addListener(SWT.Selection, e -> fleetControl.stop());
        // the pump switches live on the Board (sprint context): one bridge,
        // wired here where the control lives; enabling brings the Board up
        // first when it is closed
        fleetControl.setActions(new com.opencode.ide.core.FleetLifecycle.Actions() {
            @Override
            public void startPumping() {
                boardPumps("enable");
            }

            @Override
            public void stopPumping() {
                boardPumps("pause");
            }

            @Override
            public void stopEngine() {
                boardPumps("stop");
            }

            private void boardPumps(String command) {
                getSite().getShell().getDisplay().asyncExec(() -> {
                    org.eclipse.ui.IWorkbenchPage page = getSite().getPage();
                    org.eclipse.ui.IViewPart board = page.findView(com.opencode.ide.board.views.BoardView.ID);
                    if (board == null) {
                        if (!"enable".equals(command)) {
                            return; // no board open: nothing is pumping anyway
                        }
                        try {
                            board = page.showView(com.opencode.ide.board.views.BoardView.ID);
                        } catch (org.eclipse.ui.PartInitException e) {
                            return;
                        }
                    }
                    if (board instanceof com.opencode.ide.board.views.BoardView boardView) {
                        boardView.applyFleetControl(command);
                    }
                });
            }
        });

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

        // double-click a row that carries a session: watch the worker live
        // (U-015 — same view as the Watch live action)
        viewer.addDoubleClickListener(event -> {
            Object selection = event.getSelection();
            Object first = (selection instanceof IStructuredSelection structured)
                    ? structured.getFirstElement()
                    : null;
            if (first instanceof FleetJobHandle row && hasSession(row)) {
                openLiveWatch(row);
            }
        });

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

    /** Toolbar icon from the shared set in {@code com.opencode.ide.core} (see BoardView#icon). */
    private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
        return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "com.opencode.ide.core", "icons/actions/" + name + ".png");
    }

    private void contributeActions() {
        watchLiveAction = new Action("Watch live") {
            @Override
            public void run() {
                watchLive();
            }
        };
        watchLiveAction.setToolTipText(
                "Open the job's worker session in Session Details and follow it live while it runs");
        watchLiveAction.setImageDescriptor(icon("watch"));
        watchLiveAction.setEnabled(false);

        openDiffAction = new Action("Open diff") {
            @Override
            public void run() {
                openDiff();
            }
        };
        openDiffAction.setToolTipText(
                "Session diff from the opencode server; falls back to a git diff of the task branch");
        openDiffAction.setImageDescriptor(icon("open-diff"));
        openDiffAction.setEnabled(false);

        openFolderAction = new Action("Open folder") {
            @Override
            public void run() {
                openFolder();
            }
        };
        openFolderAction.setToolTipText("Open the job's worktree in the file explorer");
        openFolderAction.setImageDescriptor(icon("open-folder"));
        openFolderAction.setEnabled(false);

        takeOverAction = new Action("Take over") {
            @Override
            public void run() {
                takeOver();
            }
        };
        takeOverAction.setToolTipText(
                "Hand the session to the attached opencode TUI; without one, open the worktree and mark the job as taken over");
        takeOverAction.setImageDescriptor(icon("take-over"));
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
        permissionsAction.setImageDescriptor(icon("permissions"));
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
        eventsAction.setImageDescriptor(icon("events"));

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(permissionsAction);
        toolbar.add(eventsAction);
        toolbar.add(watchLiveAction);
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
        Action watchLive = new Action("Watch live") {
            @Override
            public void run() {
                watchLive();
            }
        };
        watchLive.setToolTipText(
                "Open the job's worker session in Session Details and follow it live while it runs");
        watchLive.setImageDescriptor(icon("watch"));
        watchLive.setEnabled(hasSession(row));
        manager.add(watchLive);
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
        watchLiveAction.setEnabled(hasSession(row));
        openDiffAction.setEnabled(hasSelection && !diffRunning.get());
        openFolderAction.setEnabled(hasSelection);
        // F-004: peer-engine rows are view-only — no take-over
        takeOverAction.setEnabled(hasSelection && !row.external());
    }

    /** @return true when the row carries a (non-blank) worker session id — the live view needs one. */
    private static boolean hasSession(FleetJobHandle row) {
        return row != null && row.sessionId() != null && !row.sessionId().isBlank();
    }

    /** Opens the SELECTED row's worker session live (the Watch live action). */
    private void watchLive() {
        FleetJobHandle row = selectedRow();
        if (row == null) {
            return;
        }
        openLiveWatch(row);
    }

    /**
     * Opens the job's worker session in the Session Details view by plain
     * view id (secondary id = session id, exactly ServerView's
     * openSessionDetails — no ui-bundle dependency); the shared primary
     * client serves the details view its {@code GET /session/:id/message},
     * just as it serves this view's session diff. While the job is RUNNING,
     * a one-shot system-property hint arms the freshly created view's Auto
     * Refresh (5s insurance on top of its always-on SSE reloads); the user
     * can toggle it off once the job settles. The hand-off is race-free
     * because {@code showView} runs {@code createPartControl} synchronously
     * on this same UI thread; the {@code finally} clears the hint for the
     * already-open case (createPartControl never ran to consume it), so it
     * can never leak into an unrelated view.
     */
    private void openLiveWatch(FleetJobHandle row) {
        String sessionId = row.sessionId();
        if (!hasSession(row)) {
            return;
        }
        if (row.state() == FleetJobHandle.State.RUNNING) {
            System.setProperty(SESSION_DETAILS_AUTO_REFRESH_HINT, sessionId);
        }
        try {
            getSite().getPage().showView(SESSION_DETAILS_VIEW_ID,
                    sessionId.replace('%', '_'), IWorkbenchPage.VIEW_ACTIVATE);
        } catch (PartInitException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            logWarn("Opening the live view of session " + sessionId + " failed: " + message);
            MessageDialog.openError(getSite().getShell(), "Watch live",
                    "Opening the live view of session " + sessionId + " failed:\n" + message);
        } finally {
            System.clearProperty(SESSION_DETAILS_AUTO_REFRESH_HINT);
        }
    }

    private void refreshFromModel() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        List<FleetJobHandle> own = FleetJobsModel.getDefault().jobs();
        // Own-engine rows are in-memory and render immediately; the peer scan
        // hits the on-disk store, whose cross-process FileLock can be held by
        // a writing peer engine for up to TaskStore.LOCK_TIMEOUT (30 s) — it
        // must never run on the UI thread (review M1: a peer write froze the
        // whole workbench). The scan merges back via asyncExec, guarded by a
        // generation counter so a stale scan cannot overwrite a newer refresh.
        applyRows(own);
        Set<String> liveAtScan = liveTaskIds(own);
        int generation = refreshGeneration.incrementAndGet();
        Thread peerScan = new Thread(() -> {
            List<FleetJobHandle> peer = peerRows(liveAtScan);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> applyPeerRows(generation, peer));
        }, "fleet-peer-scan");
        peerScan.setDaemon(true);
        peerScan.start();
    }

    /** Task ids of the given own-engine jobs (dedup set for the peer scan). */
    private static Set<String> liveTaskIds(List<FleetJobHandle> own) {
        Set<String> live = new LinkedHashSet<>();
        for (FleetJobHandle job : own) {
            live.add(job.taskId());
        }
        return live;
    }

    /**
     * Merges a finished peer scan into the view - only while its refresh is
     * still current and the view alive; own-engine rows are re-read so a job
     * that became live meanwhile wins over its reconstructed peer row.
     */
    private void applyPeerRows(int generation, List<FleetJobHandle> peer) {
        if (generation != refreshGeneration.get()
                || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        List<FleetJobHandle> own = FleetJobsModel.getDefault().jobs();
        Set<String> liveNow = liveTaskIds(own);
        List<FleetJobHandle> rows = new ArrayList<>(own);
        for (FleetJobHandle row : peer) {
            if (!liveNow.contains(row.taskId())) {
                rows.add(row);
            }
        }
        applyRows(rows);
    }

    /** Applies rows to the viewer on the UI thread (input, layout, actions). */
    private void applyRows(List<FleetJobHandle> rows) {
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
     * no peer rows and never breaks the view. BLOCKING (store locks): only
     * ever called off the UI thread.
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
            List<SessionDiffSides.Side> sides = null;
            if (preferServer) {
                try {
                    List<FileDiff> diffs = OpencodeConnection.getInstance().getClient()
                            .getSessionDiff(sessionId, null);
                    if (!diffs.isEmpty()) {
                        title = "Diff " + taskId + " (session " + sessionId + ")";
                        text = SessionDiffText.format(diffs);
                        // the built-in compare editor (user direction 2026-09-17):
                        // resolve before/after from the diffs' git revisions —
                        // falls back to the plain-text patch when they don't resolve
                        Path resolveRoot = repoRoot != null ? repoRoot : repoRootOf(row);
                        sides = SessionDiffSides.resolve(resolveRoot, diffs);
                        if (SessionDiffSides.resolved(sides) == 0) {
                            sides = null;
                        }
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
            List<SessionDiffSides.Side> compareSides = sides;
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
                } else if (compareSides != null) {
                    org.eclipse.compare.CompareUI.openCompareEditor(
                            new SessionDiffCompareInput(dialogTitle, compareSides));
                } else {
                    if (result != null) {
                        // breadcrumb: why the compare editor was not used
                        getViewSite().getActionBars().getStatusLineManager().setMessage(
                                "Diff sides unresolvable - showing the patch text instead");
                    }
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
