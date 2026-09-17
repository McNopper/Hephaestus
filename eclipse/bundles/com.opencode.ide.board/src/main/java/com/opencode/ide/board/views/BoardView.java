package com.opencode.ide.board.views;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ControlContribution;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.board.fleet.FleetJobHandle;
import com.opencode.ide.board.fleet.FleetLauncher;
import com.opencode.ide.board.fleet.TaskFleetLauncher;
import com.opencode.ide.board.internal.BoardPlugin;
import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.board.model.BoardModel;
import com.opencode.ide.board.model.BoardDispatch;
import com.opencode.ide.board.model.BoardModel.BoardMode;
import com.opencode.ide.board.model.BoardSnapshot;
import com.opencode.ide.board.model.DispatchPolicyStore;
import com.opencode.ide.fleet.dispatch.DispatchScheduler;
import com.opencode.ide.board.model.FleetJobsModel;
import com.opencode.ide.board.model.PipelineSnapshot;
import com.opencode.ide.board.model.SprintSelection;
import com.opencode.ide.board.model.StageColumn;
import com.opencode.ide.board.model.StageSelection;
import com.opencode.ide.board.model.TakeoverRouter;
import com.opencode.ide.board.model.TaskStoreWatcher;
import com.opencode.ide.board.model.TasksRootResolution;
import com.opencode.ide.board.model.TicketRow;
import com.opencode.ide.board.model.VStageLayout;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.fleet.Bootstrap;
import com.opencode.ide.git.FleetGit;
import com.opencode.ide.git.StoreGitStatus;
import com.opencode.ide.git.StoreSync;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;

/**
 * The PM Board: a kanban over the Markdown task store, in two layouts. The
 * header carries THREE dedicated rows grouped by meaning (U-017): the
 * store row (store-root and project inputs, persisted via dialog
 * settings, plus Refresh and Sync store - store-level concerns), the
 * scope row (the sprint selector, the "Group by" layout choice (None =
 * the flat five-column status kanban ordered by workflow progress with
 * cards priority-sorted within columns, V-model stages = the ten V-model
 * stage columns on a two-row boustrophedon - every column always
 * renders - plus a trailing untracked group; persisted too), and the
 * blocked-only / bugs-only (U-005 triage) / stage filters), and the
 * fleet row (Launch task / Auto-dispatch / Auto (the background loop) /
 * Dispatch settings / Cost overview / Take over - the fleet controls get
 * their own prominent row). Tickets drag between columns in both layouts
 * (U-016): a drop on a flat column changes the status, a drop on a stage
 * column changes the stage (backward drops carry the send-back reason
 * contract). The board refreshes live via {@link TaskStoreWatcher} on
 * {@code <root>/<project>} — including peer-agent writes and git-checkout
 * file replacements (B-002) — and survives a missing store (notice instead
 * of exception, polling continues). The watched root is the adopted repo
 * store ({@link TasksRootResolution}); a refresh that finds the unpicked
 * (backlog) default empty while real sprints exist auto-selects the newest
 * sprint instead of silently showing an empty board.
 *
 * <p>Blocked tickets are unmissable in both layouts: red bold rows, a red
 * blocked count in every V-model stage column header. The stage columns
 * always render — all ten, empty ones included, each at the fixed width —
 * and sit on the diagonal {@link VStageLayout} grid so the board reads as
 * a V (definition leg descending left, verification leg ascending right;
 * U-016). Bug tickets carry a red
 * {@code [bug]} type tag on the row (normal weight — blocked stays the
 * louder signal; U-005). The context menu on a ticket
 * row mirrors the toolbar (Launch task / Take over / Open ticket… / Copy
 * ticket id) and adds the V-pipeline moves "Advance stage →" / "Send back…"
 * (failures surface in the status line).</p>
 *
 * <p>Threading: refreshes are single-flight — the snapshot (and sprint list)
 * is computed on a background thread and only the apply runs on the UI thread
 * via {@code Display.asyncExec}. While a compute is in flight further refresh
 * requests just mark it dirty; the drain loop then re-computes once more
 * (latest-wins), so bursts of watcher events never queue up or overlap.</p>
 */
public class BoardView extends ViewPart {

    public static final String ID = "com.opencode.ide.board.views.BoardView";

    private static final String SETTINGS_SECTION = "BoardView";
    private static final String SETTING_ROOT = "tasksRoot";
    private static final String SETTING_PROJECT = "project";
    private static final String SETTING_MODE = "mode";
    private static final String SETTING_MODE_PIPELINE = "pipeline";
    private static final String SETTING_STAGES = "visibleStages";

    /**
     * Fixed width of every V-model stage column — empty ones keep it too:
     * all ten columns always render (U-016), so the V stays complete and
     * recognizable on an empty board. No collapse-to-header anymore.
     */
    private static final int PIPELINE_COLUMN_WIDTH = 190;

    /** Fixed height of a V-model stage column; fuller tables scroll internally. */
    private static final int V_COLUMN_HEIGHT = 170;

    /** The compact status-prefix legend (tooltip text on pipeline rows). */
    private static final String STATUS_LEGEND =
            "[PB] product-backlog · [SB] sprint-backlog · [IP] in-progress · [IR] in-review · [D] done";

    /** The background loop's tick period (H6 piece 4; calibrated later). */
    private static final Duration AUTO_DISPATCH_PERIOD = Duration.ofSeconds(30);

    private BoardModel model;
    private TaskStoreWatcher watcher;
    /** Assigned in createPartControl — null-checked before every use. */
    private FleetLauncher launcher;
    /** The persisted dispatch policy + bootstrap; null when preference persistence is unavailable. */
    private DispatchPolicyStore dispatchStore;
    /** The H6 background dispatch loop while "Auto" is on; null otherwise. */
    private DispatchScheduler dispatchScheduler;
    private AtomicBoolean dispatchCancelled = new AtomicBoolean();
    private boolean dispatchPending;

    private final Map<String, ColumnUi> columns = new LinkedHashMap<>();
    private final List<PipelineColumnUi> pipelineColumns = new ArrayList<>();
    /** U-018: every live row label provider, fed the readiness verdicts per snapshot apply. */
    private final List<BoardRowLabel> rowLabels = new ArrayList<>();
    /** Fleet-row readiness badge ("n ready · m stale"). */
    private Label readinessLabel;
    /** The most recent applied snapshot (column-launch picks the top READY ticket from it). */
    private BoardSnapshot lastSnapshot;
    /** Container that holds whichever layout the current mode builds. */
    private Composite boardArea;
    private Composite flatArea;
    private ScrolledComposite pipelineScroll;
    private Composite pipelineContent;
    private Text rootText;
    private Text projectText;
    private Combo sprintCombo;
    private Combo modeCombo;
    /** U-018: free-text filter input on the scope row. */
    private Text filterText;
    private Action refreshAction;

    private Action syncStoreAction;
    private Action costOverviewAction;
    private Action launchAction;
    private Action autoDispatchAction;
    private Action autoLoopAction;
    private Action dispatchSettingsAction;
    private Action takeOverAction;
    private Action blockedOnlyAction;
    /** U-005's optional triage filter: show only bug tickets (model: {@code bugsOnly}). */
    private Action bugsOnlyAction;
    private Action stageFilterAction;
    /** Selected stage ids for the visibility filter; {@code null} = all visible. */
    private Set<String> visibleStages;
    /**
     * True once the user picked a sprint in the selector this session; the
     * B-002 auto-select only acts while this is false (an explicit pick is
     * never overridden). Not persisted: a fresh session may auto-select
     * again.
     */
    private boolean sprintPicked;
    private boolean updatingSprintCombo;
    private boolean updatingModeCombo;
    private String rootOverride = "";
    private String projectName = BoardModel.DEFAULT_PROJECT;
    private BoardMode boardMode = BoardMode.FLAT;

    /** Single-thread daemon executor: serializes snapshot computes, one per view. */
    private ExecutorService refreshExecutor;
    /** Single daemon thread for TUI takeover routing (blocking client POSTs). */
    private ExecutorService takeoverExecutor;
    /** True while a drain loop owns the compute (single-flight guard). */
    private final AtomicBoolean draining = new AtomicBoolean();
    /** Set on every refresh request; consumed by the drain loop (latest-wins). */
    private final AtomicBoolean dirty = new AtomicBoolean();

    private static final class ColumnUi {
        final Label header;
        final TableViewer viewer;

        ColumnUi(Label header, TableViewer viewer) {
            this.header = header;
            this.viewer = viewer;
        }
    }

    private static final class PipelineColumnUi {
        final String stage;
        final Label headerLabel;
        final Label blockedLabel;
        final TableViewer viewer;

        PipelineColumnUi(String stage, Label headerLabel, Label blockedLabel, TableViewer viewer) {
            this.stage = stage;
            this.headerLabel = headerLabel;
            this.blockedLabel = blockedLabel;
            this.viewer = viewer;
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        refreshExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "board-refresh");
            thread.setDaemon(true);
            return thread;
        });
        takeoverExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "board-takeover");
            thread.setDaemon(true);
            return thread;
        });

        Composite outer = new Composite(parent, SWT.NONE);
        GridLayout outerLayout = new GridLayout(1, false);
        outerLayout.marginWidth = 0;
        outerLayout.marginHeight = 0;
        outer.setLayout(outerLayout);

        // U-017: the three header rows (store / scope / fleet) stack ABOVE
        // the board area, so they are created first. Settings load before
        // the rows so the embedded contributions seed from stored values.
        loadSettings();
        contributeToolbar(outer);

        boardArea = new Composite(outer, SWT.NONE);
        GridLayout boardLayout = new GridLayout(1, false);
        boardLayout.marginWidth = 0;
        boardLayout.marginHeight = 0;
        boardArea.setLayout(boardLayout);
        boardArea.setLayoutData(new GridData(GridData.FILL_BOTH));

        buildBoardArea();
        initModel();
        try {
            dispatchStore = DispatchPolicyStore.eclipse();
        } catch (RuntimeException e) {
            logError("Dispatch settings persistence unavailable; dispatch runs on the defaults", e);
        }
        createLauncher();
    }

    private void createLauncher() {
        Path root = model.root();
        DispatchPolicyStore settings = dispatchStore;
        launcher = new TaskFleetLauncher(
                BoardView::connectClient,
                FleetGit::defaultManager,
                () -> root,
                () -> {
                    var stored = settings == null ? DispatchPolicyStore.defaults() : settings.load();
                    return Bootstrap.of(stored.bootstrapAgent(), stored.bootstrapCommand());
                });
    }

    private static com.opencode.ide.client.OpencodeClient connectClient() {
        try {
            return OpencodeConnection.getInstance().getClient();
        } catch (com.opencode.ide.client.OpencodeException e) {
            throw new IllegalStateException("opencode server unavailable: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Layout: flat status kanban / V pipeline
    // ------------------------------------------------------------------

    /** Builds the layout matching {@link #boardMode} into {@link #boardArea}. */
    private void buildBoardArea() {
        columns.clear();
        pipelineColumns.clear();
        rowLabels.clear(); // the old labels belong to disposed columns
        flatArea = null;
        pipelineScroll = null;
        pipelineContent = null;
        if (boardMode == BoardMode.PIPELINE) {
            buildPipelineArea();
        } else if (boardMode == BoardMode.EPIC) {
            buildEpicArea();
        } else {
            buildFlatArea();
        }
        boardArea.layout(true, true);
    }

    /** Disposes the current layout and builds the (new) one. */
    private void rebuildBoardArea() {
        if (boardArea == null || boardArea.isDisposed()) {
            return;
        }
        for (Control child : boardArea.getChildren()) {
            child.dispose();
        }
        buildBoardArea();
    }

    /**
     * U-018 epic swimlanes: one section per epic (header + a single
     * status-prefixed, priority-sorted table). The area is rebuilt on every
     * apply because the lane set changes with the tickets (rebuilds are
     * cheap - a handful of epics - and refreshes are coalesced).
     */
    private void buildEpicArea() {
        flatArea = new Composite(boardArea, SWT.NONE);
        GridLayout lanes = new GridLayout(1, false);
        lanes.marginWidth = 0;
        lanes.marginHeight = 0;
        lanes.verticalSpacing = 6;
        flatArea.setLayout(lanes);
        flatArea.setLayoutData(new GridData(GridData.FILL_BOTH));
    }

    /** Rebuilds the epic lanes from the snapshot's lane map. */
    private void applyEpicSnapshot(BoardSnapshot snapshot) {
        if (flatArea == null || flatArea.isDisposed()) {
            return;
        }
        for (Control child : flatArea.getChildren()) {
            child.dispose();
        }
        rowLabels.clear(); // epic labels are rebuilt with the lanes
        Map<String, List<TicketRow>> lanes = snapshot.epicLanes();
        if (lanes.isEmpty()) {
            Label empty = new Label(flatArea, SWT.NONE);
            empty.setText("(no tickets match the current filters)");
            empty.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        }
        for (Map.Entry<String, List<TicketRow>> lane : lanes.entrySet()) {
            Label laneHeader = new Label(flatArea, SWT.NONE);
            List<TicketRow> rows = lane.getValue();
            laneHeader.setText(lane.getKey() + "  (" + rows.size() + ")");
            laneHeader.setFont(boldFont());
            laneHeader.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            // swimlane cards are status-prefixed (the lane mixes statuses);
            // readiness chips ride along via the shared label registry
            TableViewer viewer = createTicketViewer(flatArea, true);
            // lane tables size to their content (capped), not FILL_BOTH -
            // every lane stays visible in one scrollable stack
            GridData laneTable = new GridData(SWT.FILL, SWT.CENTER, true, false);
            laneTable.heightHint = Math.min(Math.max(rows.size(), 1), 8) * 18 + 8;
            viewer.getTable().getParent().setLayoutData(laneTable);
            viewer.setInput(rows);
        }
        for (BoardRowLabel label : rowLabels) {
            label.setReadiness(snapshot.readiness());
        }
        flatArea.layout(true, true);
    }

    private void buildFlatArea() {
        flatArea = new Composite(boardArea, SWT.NONE);
        GridLayout columnLayout = new GridLayout(Task.VALID_STATUSES.size(), true);
        columnLayout.marginWidth = 0;
        columnLayout.marginHeight = 0;
        columnLayout.horizontalSpacing = 3;
        flatArea.setLayout(columnLayout);
        flatArea.setLayoutData(new GridData(GridData.FILL_BOTH));

        for (String status : Task.VALID_STATUSES) {
            columns.put(status, createColumn(status));
        }
    }

    private void buildPipelineArea() {
        pipelineScroll = new ScrolledComposite(boardArea, SWT.H_SCROLL | SWT.V_SCROLL);
        pipelineScroll.setExpandHorizontal(true);
        pipelineScroll.setExpandVertical(true);
        pipelineScroll.setLayoutData(new GridData(GridData.FILL_BOTH));

        pipelineContent = new Composite(pipelineScroll, SWT.NONE);
        GridLayout gridLayout = new GridLayout(VStageLayout.GRID_COLUMNS, false);
        gridLayout.marginWidth = 2;
        gridLayout.marginHeight = 2;
        gridLayout.horizontalSpacing = 4;
        gridLayout.verticalSpacing = 4;
        pipelineContent.setLayout(gridLayout);

        // The V (U-016, redesigned after the rubberduck review 2026-09-17):
        // a two-row boustrophedon — definition leg left->right on top,
        // verification leg right->left below, each definition stage directly
        // above its verification pair. Null cells become zero-size spacers.
        // All ten stage columns render even when empty, at the fixed width;
        // nothing collapses to its header.
        for (List<String> row : VStageLayout.grid()) {
            for (String stage : row) {
                if (stage == null) {
                    Composite spacer = new Composite(pipelineContent, SWT.NONE);
                    spacer.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));
                } else {
                    pipelineColumns.add(createPipelineColumn(stage));
                }
            }
        }
        pipelineScroll.setContent(pipelineContent);
    }

    private ColumnUi createColumn(String status) {
        Composite column = new Composite(flatArea, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 0;
        column.setLayout(layout);
        column.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite headerLine = new Composite(column, SWT.NONE);
        GridLayout headerLayout = new GridLayout(
                "sprint-backlog".equals(status) ? 2 : 1, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerLayout.horizontalSpacing = 2;
        headerLine.setLayout(headerLayout);
        headerLine.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label header = new Label(headerLine, SWT.NONE);
        header.setText(status + " (0)");
        header.setFont(boldFont());
        header.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
        if ("sprint-backlog".equals(status)) {
            // U-018: one-click dispatch - launches the top READY ticket of
            // this column (the within-column order is priority-sorted)
            Button launchNext = new Button(headerLine, SWT.FLAT);
            launchNext.setText("\u25B6");
            launchNext.setToolTipText("Launch the top READY ticket in sprint-backlog");
            launchNext.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
            launchNext.addListener(SWT.Selection, e -> launchFirstReady("sprint-backlog"));
        }

        TableViewer viewer = createTicketViewer(column, false);
        hookStatusDrop(viewer.getTable(), status);
        return new ColumnUi(header, viewer);
    }

    private PipelineColumnUi createPipelineColumn(String stage) {
        Composite column = new Composite(pipelineContent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 0;
        column.setLayout(layout);
        // Fixed cell of the V grid (U-016): always rendered at the fixed
        // width — an empty column keeps its table instead of collapsing to
        // its header — and tall enough for a handful of rows; fuller tables
        // scroll internally, which keeps the V's rows stable.
        GridData cell = new GridData(SWT.FILL, SWT.FILL, false, true);
        cell.widthHint = PIPELINE_COLUMN_WIDTH;
        cell.heightHint = V_COLUMN_HEIGHT;
        column.setLayoutData(cell);

        Composite header = new Composite(column, SWT.NONE);
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerLayout.horizontalSpacing = 0;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Font bold = boldFont();
        Label headerLabel = new Label(header, SWT.NONE);
        headerLabel.setFont(bold);
        // Numbered snake order (1-10) so the two-row boustrophedon still
        // reads as ONE sequence; the turn at implementation carries the
        // down-arrow, pairing with test-implementation directly below
        // (U-016 rubberduck review). The untracked group is unnumbered.
        headerLabel.setText(numberedStageHeader(stage));
        int number = VStageLayout.stageNumber(stage);
        headerLabel.setToolTipText(number > 0
                ? "Stage " + number + " of 10 along the V (definition leg 1-5, verification leg 6-10)"
                : "Tickets without a V stage");
        Label blockedLabel = new Label(header, SWT.NONE);
        blockedLabel.setFont(bold);

        Composite tableComposite = new Composite(column, SWT.NONE);
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableComposite.setLayout(tableLayout);
        tableComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

        TableViewer viewer = new TableViewer(tableComposite,
                SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        hookViewerBehavior(viewer);
        TableViewerColumn ticket = new TableViewerColumn(viewer, SWT.NONE);
        BoardRowLabel pipelineRowLabel = new BoardRowLabel(true);
        ticket.setLabelProvider(pipelineRowLabel);
        rowLabels.add(pipelineRowLabel);
        tableLayout.setColumnData(ticket.getColumn(), new ColumnWeightData(100, 110, true));
        hookStageDrop(viewer.getTable(), stage);

        return new PipelineColumnUi(stage, headerLabel, blockedLabel, viewer);
    }

    /** Shared viewer wiring: selection/double-click/context menu/tooltips + the flat columns. */
    /**
     * Shared flat-column ticket viewer. {@code statusPrefixedLabels} picks
     * the card style: plain (status kanban) or status-prefixed (epic
     * swimlanes, which mix statuses inside a lane).
     */
    private TableViewer createTicketViewer(Composite column, boolean statusPrefixedLabels) {
        Composite tableComposite = new Composite(column, SWT.NONE);
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableComposite.setLayout(tableLayout);
        tableComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

        TableViewer viewer = new TableViewer(tableComposite,
                SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        hookViewerBehavior(viewer);

        TableViewerColumn ticket = new TableViewerColumn(viewer, SWT.NONE);
        BoardRowLabel rowLabel = new BoardRowLabel(statusPrefixedLabels);
        ticket.setLabelProvider(rowLabel);
        rowLabels.add(rowLabel);
        tableLayout.setColumnData(ticket.getColumn(), new ColumnWeightData(100, 110, true));

        TableViewerColumn points = new TableViewerColumn(viewer, SWT.RIGHT);
        points.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                TicketRow row = asRow(element);
                return row == null ? "" : row.pointsLabel();
            }
        });
        tableLayout.setColumnData(points.getColumn(), new ColumnWeightData(14, 26, true));
        return viewer;
    }

    private void hookViewerBehavior(TableViewer viewer) {
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            @Override
            public void doubleClick(DoubleClickEvent event) {
                openDetails();
            }
        });
        viewer.addSelectionChangedListener((ISelectionChangedListener) e -> updateActionEnablement());
        ColumnViewerToolTipSupport.enableFor(viewer);
        hookContextMenu(viewer);
        hookDrag(viewer);
    }

    /**
     * Drag-and-drop (U-016 rubberduck review: the kanban's core missing
     * affordance). Dragging carries the ticket id; each column's table is a
     * drop target — a flat column drops change the STATUS, a V-model stage
     * column drops change the STAGE (backward drops ask for the send-back
     * reason first, exactly like the context-menu send-back).
     */
    private void hookDrag(TableViewer viewer) {
        viewer.addDragSupport(DND.DROP_MOVE, new Transfer[] {TextTransfer.getInstance()},
                new DragSourceAdapter() {
                    @Override
                    public void dragSetData(DragSourceEvent event) {
                        TicketRow row = selectedRow();
                        if (row != null && row.id() != null) {
                            event.data = row.id();
                        }
                    }
                });
    }

    /** Drop target for a flat status column's table. */
    private void hookStatusDrop(Table table, String status) {
        new DropTarget(table, DND.DROP_MOVE).setTransfer(new Transfer[] {TextTransfer.getInstance()});
        table.addListener(DND.Drop, event ->
                moveDroppedTicket((String) event.data,
                        () -> model.setStatus((String) event.data, status), "status " + status));
    }

    /** Drop target for a V-model stage column's table ({@code null} stage = the untracked group). */
    private void hookStageDrop(Table table, String stage) {
        table.addListener(DND.Drop, event -> {
            String id = (String) event.data;
            if (stage == null || PipelineSnapshot.UNTRACKED.equals(stage)) {
                moveDroppedTicket(id, () -> model.setStage(id, null, null), "no stage");
                return;
            }
            TicketRow row = model == null ? null : model.row(id);
            int from = row == null ? -1 : VStages.STAGES.indexOf(row.effectiveStage());
            int to = VStages.STAGES.indexOf(stage);
            if (from >= 0 && to >= 0 && to < from) {
                String previous = row.effectiveStage();
                InputDialog dialog = new InputDialog(getSite().getShell(), "Send back " + id,
                        "Reason for moving " + id + " back from '" + previous + "' to '" + stage + "':",
                        "", text -> text == null || text.trim().isEmpty() ? "A reason is required" : null);
                if (dialog.open() != Window.OK || dialog.getValue() == null || dialog.getValue().trim().isEmpty()) {
                    return;
                }
                String reason = dialog.getValue().trim();
                moveDroppedTicket(id, () -> model.setStage(id, stage, reason), "stage " + stage);
            } else {
                moveDroppedTicket(id, () -> model.setStage(id, stage, null), "stage " + stage);
            }
        });
        new DropTarget(table, DND.DROP_MOVE).setTransfer(new Transfer[] {TextTransfer.getInstance()});
    }

    /** Runs a DnD-triggered model move and reports the outcome in the status line. */
    private void moveDroppedTicket(String id, java.util.function.Supplier<String> move, String target) {
        if (id == null || id.isBlank() || model == null) {
            return;
        }
        String error = move.get();
        IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
        if (error != null) {
            status.setErrorMessage("Move " + id + " failed: " + error);
        } else {
            status.setErrorMessage(null);
            status.setMessage("Moved " + id + " \u2192 " + target);
        }
        refresh();
    }

    private void hookContextMenu(TableViewer viewer) {
        MenuManager manager = new MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(this::fillContextMenu);
        Menu menu = manager.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
    }

    private void fillContextMenu(IContributionManager manager) {
        TicketRow row = selectedRow();
        Action launch = new Action("Launch task") {
            @Override
            public void run() {
                launchSelected();
            }
        };
        launch.setEnabled(canLaunch(row));
        manager.add(launch);
        Action takeOver = new Action("Take over") {
            @Override
            public void run() {
                takeOverSelected();
            }
        };
        takeOver.setEnabled(row != null);
        manager.add(takeOver);
        Action open = new Action("Open ticket\u2026") {
            @Override
            public void run() {
                openDetails();
            }
        };
        open.setEnabled(row != null);
        manager.add(open);
        Action copyId = new Action("Copy ticket id") {
            @Override
            public void run() {
                copyTicketId(row);
            }
        };
        copyId.setEnabled(row != null && row.id() != null);
        manager.add(copyId);
        manager.add(new Separator());
        Action advance = new Action("Advance stage \u2192") {
            @Override
            public void run() {
                advanceSelected(row);
            }
        };
        advance.setEnabled(canAdvance(row));
        manager.add(advance);
        Action sendBack = new Action("Send back\u2026") {
            @Override
            public void run() {
                sendBackSelected(row);
            }
        };
        sendBack.setEnabled(canSendBack(row));
        manager.add(sendBack);
    }

    /** Copies the ticket id to the clipboard and confirms in the status line. */
    private void copyTicketId(TicketRow row) {
        if (row == null || row.id() == null || boardArea == null || boardArea.isDisposed()) {
            return;
        }
        Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { row.id() }, new Transfer[] { TextTransfer.getInstance() });
            statusMessage("Copied: " + row.id());
        } finally {
            clipboard.dispose();
        }
    }

    private static boolean canAdvance(TicketRow row) {
        return row != null && row.stage() != null
                && ("in-review".equals(row.status()) || "done".equals(row.status()))
                && VStages.next(row.stage()) != null;
    }

    private static boolean canSendBack(TicketRow row) {
        return row != null && row.stage() != null && VStages.previous(row.stage()) != null;
    }

    private void advanceSelected(TicketRow row) {
        if (row == null || model == null) {
            return;
        }
        String error = model.advance(row.id());
        IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
        if (error != null) {
            status.setErrorMessage("Advance " + row.id() + " failed: " + error);
        } else {
            status.setErrorMessage(null);
            status.setMessage("Advanced " + row.id() + " \u2192 " + VStages.next(row.stage()));
        }
        refresh();
    }

    private void sendBackSelected(TicketRow row) {
        if (row == null || model == null) {
            return;
        }
        String previous = VStages.previous(row.stage());
        InputDialog dialog = new InputDialog(getSite().getShell(), "Send back " + row.id(),
                "Reason for sending " + row.id() + " back from '" + row.stage() + "' to '" + previous + "':",
                "", text -> text == null || text.trim().isEmpty() ? "A reason is required" : null);
        if (dialog.open() != Window.OK) {
            return;
        }
        String reason = dialog.getValue() == null ? "" : dialog.getValue().trim();
        if (reason.isEmpty()) {
            return;
        }
        String error = model.sendBack(row.id(), reason);
        IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
        if (error != null) {
            status.setErrorMessage("Send back " + row.id() + " failed: " + error);
        } else {
            status.setErrorMessage(null);
            status.setMessage("Sent " + row.id() + " back \u2192 " + previous + " (blocked)");
        }
        refresh();
    }

    private static Font boldFont() {
        return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
    }

    private static TicketRow asRow(Object element) {
        return element instanceof TicketRow row ? row : null;
    }

    /** Row rendering shared by both layouts: label + tooltip, red bold for blocked rows (never for done rows),
     * red (normal weight) for bug rows — the U-005 triage accent: blocked stays the louder bold-red signal,
     * bugs read as a steady red "[bug]" row in both light and dark themes. Readiness chips (U-018) ride on
     * the label tail: {@code · stale} / {@code · waiting} — problems only, READY stays untagged (the fleet
     * row's ready-count badge covers it), blocked keeps the louder signal. */
    private static final class BoardRowLabel extends ColumnLabelProvider {
        private final boolean pipeline;
        private Map<String, StageReadiness.Readiness> readiness = Map.of();

        BoardRowLabel(boolean pipeline) {
            this.pipeline = pipeline;
        }

        /** Latest per-ticket dispatch verdicts (UI thread, before setInput). */
        void setReadiness(Map<String, StageReadiness.Readiness> readiness) {
            this.readiness = readiness == null ? Map.of() : readiness;
        }

        @Override
        public String getText(Object element) {
            TicketRow row = asRow(element);
            if (row == null) {
                return "";
            }
            String base = pipeline ? row.pipelineLabel() : row.label();
            String chip = chipOf(readiness.get(row.id()));
            return chip.isEmpty() ? base : base + chip;
        }

        /** The problem chip for a verdict; empty for READY/RUNNING/absent. */
        private static String chipOf(StageReadiness.Readiness verdict) {
            if (verdict == null) {
                return "";
            }
            return switch (verdict.kind()) {
                case STALE -> " \u00b7 stale";
                case WAIT_UPSTREAM -> " \u00b7 waiting";
                case BLOCKED -> " \u00b7 blocked-upstream";   // blocked flag may be clear while the verdict still blames it
                default -> "";
            };
        }

        @Override
        public String getToolTipText(Object element) {
            TicketRow row = asRow(element);
            if (row == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder(STATUS_LEGEND);
            sb.append("\n").append(safe(row.id())).append(" \u2014 ").append(safe(row.title()));
            sb.append("\nstatus: ").append(safe(row.status()));
            sb.append(" · type: ").append(row.type() == null ? "(none)" : row.type());
            sb.append(" · stage: ").append(row.stage() == null ? "(none)" : row.stage());
            sb.append(" · priority: ").append(row.priority() == null || row.priority().isBlank()
                    ? "medium" : row.priority());
            if (row.displayBlocked()) {
                sb.append("\n[BLOCKED] ").append(safe(row.blocker()));
            }
            StageReadiness.Readiness verdict = readiness.get(row.id());
            if (verdict != null) {
                sb.append("\nreadiness: ").append(verdict.kind())
                        .append(" - ").append(safe(verdict.reason()));
            }
            return sb.toString();
        }

        @Override
        public Color getForeground(Object element) {
            TicketRow row = asRow(element);
            Display display = Display.getCurrent();
            if (row == null || display == null) {
                return null;
            }
            if (row.displayBlocked() || row.isBug()) {
                return display.getSystemColor(SWT.COLOR_RED);
            }
            return null;
        }

        @Override
        public Font getFont(Object element) {
            TicketRow row = asRow(element);
            return row != null && row.displayBlocked() ? boldFont() : null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

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

    /**
     * One header row (U-017): an embedded flat tool bar inside the view's
     * header stack — same {@link Action}/{@link ControlContribution}
     * objects the old single view toolbar held, just grouped by meaning.
     */
    private static ToolBarManager headerRow(Composite parent) {
        ToolBarManager manager = new ToolBarManager(SWT.HORIZONTAL | SWT.FLAT);
        ToolBar bar = manager.createControl(parent);
        bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return manager;
    }

    private void contributeToolbar(Composite parent) {
        ControlContribution inputsCc = new ControlContribution("com.opencode.ide.board.inputs") {
            @Override
            protected Control createControl(Composite parent) {
                Composite box = new Composite(parent, SWT.NONE);
                GridLayout layout = new GridLayout(4, false);
                layout.marginWidth = 0;
                layout.marginHeight = 0;
                layout.horizontalSpacing = 4;
                box.setLayout(layout);
                new Label(box, SWT.NONE).setText("Store:");
                rootText = new Text(box, SWT.BORDER);
                rootText.setToolTipText("Task store root (empty = auto-detect ../.opencode/tasks)");
                rootText.setTextLimit(400);
                rootText.setLayoutData(fixedSize(170));
                new Label(box, SWT.NONE).setText("Project:");
                projectText = new Text(box, SWT.BORDER);
                projectText.setToolTipText("Task store project (subdirectory of the root)");
                projectText.setTextLimit(60);
                projectText.setLayoutData(fixedSize(80));
                // The workbench builds toolbar contributions AFTER createPartControl
                // returns, so loadSettings() ran while these fields were still null.
                // Seed them here (like modeCombo below) - otherwise they render empty
                // and the first applyInputs() would persist that emptiness over the
                // stored store root / project.
                rootText.setText(rootOverride == null ? "" : rootOverride);
                projectText.setText(projectName == null ? BoardModel.DEFAULT_PROJECT : projectName);
                hookApply(rootText);
                hookApply(projectText);
                return box;
            }
        };

        ControlContribution sprintCc = new ControlContribution("com.opencode.ide.board.sprint") {
            @Override
            protected Control createControl(Composite parent) {
                Composite box = new Composite(parent, SWT.NONE);
                GridLayout layout = new GridLayout(2, false);
                layout.marginWidth = 0;
                layout.marginHeight = 0;
                layout.horizontalSpacing = 4;
                box.setLayout(layout);
                new Label(box, SWT.NONE).setText("Sprint:");
                sprintCombo = new Combo(box, SWT.DROP_DOWN | SWT.READ_ONLY);
                sprintCombo.setToolTipText("Selected sprint (works in both layouts)");
                sprintCombo.setLayoutData(fixedSize(110));
                sprintCombo.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        if (updatingSprintCombo) {
                            return;
                        }
                        int index = sprintCombo.getSelectionIndex();
                        if (index >= 0 && model != null) {
                            // explicit user choice — the B-002 auto-select
                            // must never override it afterwards
                            sprintPicked = true;
                            cancelDispatchForSelection();
                            model.setSprint(sprintCombo.getItem(index));
                            refresh();
                        }
                    }
                });
                return box;
            }
        };

        ControlContribution modeCc = new ControlContribution("com.opencode.ide.board.groupby") {
            @Override
            protected Control createControl(Composite parent) {
                Composite box = new Composite(parent, SWT.NONE);
                GridLayout layout = new GridLayout(4, false);
                layout.marginWidth = 0;
                layout.marginHeight = 0;
                layout.horizontalSpacing = 4;
                box.setLayout(layout);
                new Label(box, SWT.NONE).setText("Group by:");
                modeCombo = new Combo(box, SWT.DROP_DOWN | SWT.READ_ONLY);
                modeCombo.setItems("None", "V-model stages", "Epic");
                modeCombo.setToolTipText("None: five-column status kanban ordered by workflow progress. "
                        + "V-model stages: the ten V-model stage columns arranged as a V "
                        + "(requirements \u2192 test-requirements); every column shows, empty ones too. "
                        + "Epic: swimlanes per epic (status-prefixed cards, priority-sorted).");
                modeCombo.setLayoutData(fixedSize(140));
                modeCombo.select(boardMode == BoardMode.PIPELINE ? 1 : boardMode == BoardMode.EPIC ? 2 : 0);
                modeCombo.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        if (updatingModeCombo || modeCombo.isDisposed()) {
                            return;
                        }
                        BoardMode picked = switch (modeCombo.getSelectionIndex()) {
                            case 1 -> BoardMode.PIPELINE;
                            case 2 -> BoardMode.EPIC;
                            default -> BoardMode.FLAT;
                        };
                        if (picked == boardMode) {
                            return;
                        }
                        boardMode = picked;
                        if (model != null) {
                            model.setMode(picked);
                        }
                        saveSettings();
                        rebuildBoardArea();
                        refresh();
                    }
                });
                // U-018: free-text filter over id/title on the scope row
                filterText = new Text(box, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
                filterText.setMessage("Filter tickets\u2026");
                filterText.setToolTipText("Show only tickets whose id or title contains this text");
                filterText.setLayoutData(fixedSize(140));
                filterText.addModifyListener(e -> {
                    if (model != null && filterText != null && !filterText.isDisposed()) {
                        model.setTextFilter(filterText.getText());
                        refresh();
                    }
                });
                return box;
            }
        };

        blockedOnlyAction = new Action("Blocked only", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                if (model != null) {
                    model.setBlockedOnly(isChecked());
                    refresh();
                }
            }
        };
        blockedOnlyAction.setToolTipText("Show only blocked tickets (both layouts)");
        blockedOnlyAction.setImageDescriptor(icon("blocked-only"));

        bugsOnlyAction = new Action("Bugs only", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                if (model != null) {
                    model.setBugsOnly(isChecked());
                    refresh();
                }
            }
        };
        bugsOnlyAction.setToolTipText("Show only bug tickets (both layouts)");
        bugsOnlyAction.setImageDescriptor(icon("bugs-only"));

        stageFilterAction = new Action("Stages", Action.AS_DROP_DOWN_MENU) {
            @Override
            public void run() {
                // opening the dropdown shows the menu; nothing to do on click itself
            }
        };
        stageFilterAction.setToolTipText("Show/hide individual V stages (applies to both layouts)");
        stageFilterAction.setImageDescriptor(icon("stages"));
        stageFilterAction.setMenuCreator(new StageFilterMenuCreator());

        refreshAction = new Action("Refresh") {
            @Override
            public void run() {
                applyInputs();
            }
        };
        refreshAction.setToolTipText("Reload the board from the task store");
        refreshAction.setImageDescriptor(icon("refresh"));

        syncStoreAction = new Action("Sync store") {
            @Override
            public void run() {
                syncStore();
            }
        };
        syncStoreAction.setToolTipText(
                "Commit the task store and pull-rebase + push (distributed-fleet discipline: pull → claim → push)");
        syncStoreAction.setImageDescriptor(icon("sync-store"));

        costOverviewAction = new Action("Cost overview") {
            @Override
            public void run() {
                openCostOverview();
            }
        };
        costOverviewAction.setToolTipText(
                "Fleet cost/token actuals recorded on tickets (per sprint and per ticket)");
        costOverviewAction.setImageDescriptor(icon("cost-overview"));

        launchAction = new Action("Launch task") {
            @Override
            public void run() {
                launchSelected();
            }
        };
        launchAction.setToolTipText("Launch a fleet agent on the selected ticket (sprint-backlog / in-progress)");
        launchAction.setImageDescriptor(icon("launch"));
        launchAction.setEnabled(false);

        autoDispatchAction = new Action("Auto-dispatch") {
            @Override
            public void run() {
                autoDispatch();
            }
        };
        autoDispatchAction.setToolTipText(
                "Plan over the current sprint (readiness + cost budget) and launch every admitted ticket as a fleet agent");
        autoDispatchAction.setImageDescriptor(icon("auto-dispatch"));

        autoLoopAction = new Action("Auto ▶", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                toggleDispatchLoop();
            }
        };
        autoLoopAction.setToolTipText("Background auto-dispatch: re-plans the current sprint every 30s and "
                + "launches admitted tickets until it drains (stops on uncheck or view close)");
        autoLoopAction.setImageDescriptor(icon("auto-loop"));

        dispatchSettingsAction = new Action("Dispatch settings…") {
            @Override
            public void run() {
                openDispatchSettings();
            }
        };
        dispatchSettingsAction.setToolTipText(
                "The stored auto-dispatch policy (concurrency, cost budget, STALE re-runs) and the "
                        + "per-launch bootstrap command — both dispatch actions load it at action time");
        dispatchSettingsAction.setImageDescriptor(icon("dispatch-settings"));

        takeOverAction = new Action("Take over") {
            @Override
            public void run() {
                takeOverSelected();
            }
        };
        takeOverAction.setToolTipText(
                "Hand the ticket's fleet session to the attached opencode TUI; without one, open its fleet worktree in the file explorer");
        takeOverAction.setImageDescriptor(icon("take-over"));
        takeOverAction.setEnabled(false);

        // U-017: three dedicated rows replace the single cramped view
        // toolbar — grouped by meaning (user direction 2026-09-17, refined
        // by the rubberduck review: Refresh/Sync are store-level and sit
        // with the store inputs; Cost overview is spend — fleet row).
        ToolBarManager storeRow = headerRow(parent);
        storeRow.add(inputsCc);
        storeRow.add(refreshAction);
        storeRow.add(syncStoreAction);
        storeRow.update(true);

        ToolBarManager scopeRow = headerRow(parent);
        scopeRow.add(sprintCc);
        scopeRow.add(modeCc);
        scopeRow.add(blockedOnlyAction);
        scopeRow.add(bugsOnlyAction);
        scopeRow.add(stageFilterAction);
        scopeRow.update(true);

        // U-017+U-018: the fleet row carries the dispatch actions AND the
        // live readiness verdicts of the current sprint ("n ready · m stale")
        Composite fleetRow = new Composite(parent, SWT.NONE);
        GridLayout fleetLayout = new GridLayout(2, false);
        fleetLayout.marginWidth = 0;
        fleetLayout.marginHeight = 0;
        fleetLayout.horizontalSpacing = 10;
        fleetRow.setLayout(fleetLayout);
        fleetRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        ToolBarManager fleetBar = new ToolBarManager(SWT.HORIZONTAL | SWT.FLAT);
        ToolBar fleetToolBar = fleetBar.createControl(fleetRow);
        fleetToolBar.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        readinessLabel = new Label(fleetRow, SWT.NONE);
        readinessLabel.setText("0 ready · 0 stale");
        readinessLabel.setToolTipText("task_readiness verdicts over the current sprint - "
                + "what the fleet can dispatch now, and what went stale");
        readinessLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        fleetBar.add(launchAction);
        fleetBar.add(autoDispatchAction);
        fleetBar.add(autoLoopAction);
        fleetBar.add(dispatchSettingsAction);
        fleetBar.add(costOverviewAction);
        fleetBar.add(takeOverAction);
        fleetBar.update(true);

        // Icon-paint fix (user report 2026-09-17): embedded tool bars created
        // before the view is shown paint their item images zero-sized until a
        // user interaction forces a layout - pack each row and lay out the
        // whole header so every icon is visible from the first paint.
        for (Control child : parent.getChildren()) {
            if (child instanceof ToolBar bar) {
                bar.pack(true);
            }
        }
        parent.layout(true, true);
    }

    private static GridData fixedSize(int widthHint) {
        GridData data = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        data.widthHint = widthHint;
        return data;
    }

    private void hookApply(Text text) {
        text.addListener(SWT.DefaultSelection, e -> applyInputs());
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyInputs();
            }
        });
    }

    private void initModel() {
        model = new BoardModel(resolveTasksRoot(rootOverride), projectName);
        model.setMode(boardMode);
        model.setStageFilter(visibleStages);
        if (stageFilterAction != null) {
            stageFilterAction.setText("Stages: " + StageSelection.label(visibleStages));
        }
        restartWatcher();
        refresh();
    }

    private void applyInputs() {
        if (model == null || projectText == null || projectText.isDisposed()
                || rootText == null || rootText.isDisposed()) {
            return;
        }
        String newProject = projectText.getText().trim();
        if (newProject.isEmpty()) {
            newProject = BoardModel.DEFAULT_PROJECT;
        }
        String newRoot = rootText.getText().trim();
        boolean changed = !newProject.equals(model.project()) || !newRoot.equals(rootOverride);
        rootOverride = newRoot;
        projectName = newProject;
        if (changed) {
            cancelDispatchForSelection();
            // fresh project/root context: the sprint choice does not carry
            // over, so the auto-select may act again (B-002)
            sprintPicked = false;
            model.setProject(newProject);
            model.setRoot(resolveTasksRoot(rootOverride));
            createLauncher();
            restartWatcher();
        }
        saveSettings();
        refresh();
    }

    /**
     * Requests a refresh from any thread (watcher, toolbar, sprint combo,
     * initial load): marks dirty and ensures exactly one drain loop is
     * running. Never computes on the caller's thread.
     */
    private void refresh() {
        if (model == null || boardArea == null || boardArea.isDisposed() || refreshExecutor == null) {
            return;
        }
        dirty.set(true);
        if (draining.compareAndSet(false, true)) {
            refreshExecutor.execute(this::drainRefresh);
        }
    }

    /**
     * Runs on the refresh executor: computes snapshots (plus the sprint list)
     * while changes keep arriving, applying each on the UI thread. The final
     * re-check closes the mark-vs-release race so no change is ever lost.
     */
    private void drainRefresh() {
        while (dirty.compareAndSet(true, false)) {
            BoardSnapshot snapshot = null;
            List<String> sprints = List.of();
            CostOverview cost = null;
            StoreGitStatus store = StoreGitStatus.NONE;
            try {
                snapshot = model.refresh();
                sprints = model.sprints();
                cost = model.costOverview();
                store = StoreGitStatus.load(model.root());
            } catch (RuntimeException e) {
                logError("Board refresh failed", e);
            }
            if (snapshot == null) {
                continue; // error already logged; honor any change that arrived meanwhile
            }
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                break;
            }
            BoardSnapshot toApply = snapshot;
            List<String> sprintList = sprints;
            CostOverview costOverview = cost;
            StoreGitStatus storeStatus = store;
            display.asyncExec(() -> {
                if (boardArea == null || boardArea.isDisposed()) {
                    return;
                }
                try {
                    applySnapshot(toApply, sprintList, costOverview, storeStatus);
                } catch (RuntimeException e) {
                    logError("Applying board snapshot failed", e);
                }
            });
        }
        draining.set(false);
        if (dirty.get() && draining.compareAndSet(false, true)) {
            try {
                refreshExecutor.execute(this::drainRefresh);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                draining.set(false); // view disposed mid-drain — nothing left to refresh
            }
        }
    }

    /** UI-thread apply of a snapshot computed in the background (mode-aware). */
    private void applySnapshot(BoardSnapshot snapshot, List<String> sprints, CostOverview cost,
            StoreGitStatus store) {
        lastSnapshot = snapshot;
        // U-018: readiness chips on every card + the fleet-row verdict badge
        for (BoardRowLabel label : rowLabels) {
            label.setReadiness(snapshot.readiness());
        }
        if (readinessLabel != null && !readinessLabel.isDisposed()) {
            readinessLabel.setText(snapshot.readyCount() + " ready \u00b7 " + snapshot.staleCount()
                    + " stale" + (snapshot.staleCount() > 0 ? " (re-run needed)" : ""));
            readinessLabel.setToolTipText("task_readiness verdicts over the current sprint - "
                    + "what the fleet can dispatch now, and what went stale");
        }
        if (boardMode == BoardMode.PIPELINE) {
            applyPipelineSnapshot(snapshot);
        } else if (boardMode == BoardMode.EPIC) {
            applyEpicSnapshot(snapshot);
        } else {
            applyFlatSnapshot(snapshot);
        }
        refreshSprintCombo(sprints);
        if (maybeAutoSelectSprint(sprints, snapshot)) {
            return; // sprint switched: the refresh this triggers re-renders everything
        }
        if (snapshot.error() != null) {
            setContentDescription(snapshot.error());
        } else {
            String goal = snapshot.sprintGoal();
            StringBuilder description = new StringBuilder(model.sprint())
                    .append("  \u2022  ").append(snapshot.total()).append(" tickets");
            if (snapshot.blockedCount() > 0) {
                description.append("  \u2022  ").append(snapshot.blockedCount()).append(" blocked");
            }
            if (goal != null && !goal.isBlank()) {
                description.append("  \u2022  ").append(goal);
            }
            String spent = cost == null ? "" : cost.spentSuffix(model.sprint());
            if (!spent.isEmpty()) {
                description.append("  \u2022  ").append(spent);
            }
            if (store != null && store.exists() && !store.summary().isBlank()) {
                description.append("  \u2022  store ").append(store.summary());
            }
            setContentDescription(description.toString());
        }
        setTitleToolTip("tasks: " + model.root() + "\nrepo: " + repoRoot());
        updateActionEnablement();
    }

    /**
     * The numbered V-stage header text: {@code 3 · architecture}, with the
     * down-arrow turn marker on implementation (the snake continues directly
     * below in test-implementation); the untracked group stays unnumbered.
     */
    private static String numberedStageHeader(String stage) {
        int number = VStageLayout.stageNumber(stage);
        String turn = "implementation".equals(stage) ? " \u2193" : "";
        return number > 0 ? number + " \u00b7 " + stage + turn : stage;
    }

    private void applyFlatSnapshot(BoardSnapshot snapshot) {        for (Map.Entry<String, ColumnUi> entry : columns.entrySet()) {
            List<TicketRow> rows = snapshot.column(entry.getKey());
            entry.getValue().viewer.setInput(rows);
            entry.getValue().header.setText(entry.getKey() + " (" + rows.size() + ")");
        }
    }

    private void applyPipelineSnapshot(BoardSnapshot snapshot) {
        if (pipelineContent == null || pipelineContent.isDisposed()) {
            return;
        }
        PipelineSnapshot pipeline = snapshot.pipeline();
        for (PipelineColumnUi ui : pipelineColumns) {
            StageColumn column = pipeline == null ? null : pipeline.column(ui.stage);
            List<TicketRow> rows = column == null ? List.of() : column.rows();
            int blockedCount = column == null ? 0 : column.blockedCount();
            // keep the numbered snake header (set at creation); append count
            ui.headerLabel.setText(numberedStageHeader(ui.stage) + " (" + rows.size());
            ui.blockedLabel.setText(" \u00b7 " + blockedCount + " blocked)");
            ui.blockedLabel.setForeground(blockedCount > 0
                    ? ui.blockedLabel.getDisplay().getSystemColor(SWT.COLOR_RED) : null);
            // Every column keeps its fixed V cell (U-016): empty ones stay
            // rendered with their table — no collapse-to-header anymore.
            ui.viewer.setInput(rows);
        }
        pipelineContent.layout(true, true);
        pipelineScroll.setMinSize(pipelineContent.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    private void refreshSprintCombo(List<String> sprints) {
        if (sprintCombo == null || sprintCombo.isDisposed() || model == null) {
            return;
        }
        updatingSprintCombo = true;
        try {
            List<String> items = new ArrayList<>(sprints);
            String current = model.sprint();
            if (!items.contains(current)) {
                items.add(current);
            }
            sprintCombo.setItems(items.toArray(new String[0]));
            sprintCombo.select(Math.max(0, items.indexOf(current)));
        } finally {
            updatingSprintCombo = false;
        }
    }

    /**
     * B-002: a refresh that finds the board sitting empty on the unpicked
     * {@code (backlog)} default while real sprints exist switches to the
     * newest one — the live case where a peer plans a sprint, moves the
     * tickets into it, and the board silently shows nothing instead. The
     * decision lives in {@link SprintSelection} (SWT-free, tested); an
     * explicit user pick (or a non-empty board) always wins.
     *
     * @return whether the sprint changed (caller skips the rest; the
     *         triggered refresh re-renders title, columns and counts)
     */
    private boolean maybeAutoSelectSprint(List<String> sprints, BoardSnapshot snapshot) {
        if (model == null || snapshot.error() != null) {
            return false;
        }
        String candidate = SprintSelection.autoSelect(model.sprint(), sprintPicked, sprints,
                snapshot.total());
        if (candidate == null) {
            return false;
        }
        // a sprint switch invalidates the dispatch loop's context, exactly
        // like a manual selection (captureDispatch snapshots the sprint)
        cancelDispatchForSelection();
        model.setSprint(candidate);
        refreshSprintCombo(sprints);
        statusMessage("Auto-selected sprint " + candidate + " \u2014 (backlog) was empty");
        refresh();
        return true;
    }

    private void restartWatcher() {
        if (watcher != null) {
            watcher.stop();
        }
        Path projectDir = model.root().resolve(TaskStore.sanitizeProject(model.project()));
        watcher = new TaskStoreWatcher(projectDir, () -> {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(this::onStoreChange);
            }
        });
        watcher.start();
    }

    private void onStoreChange() {
        if (boardArea == null || boardArea.isDisposed()) {
            return;
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    /** First selected row across both layouts (deterministic column order). */
    private TicketRow selectedRow() {
        for (ColumnUi ui : columns.values()) {
            TicketRow row = selectionOf(ui.viewer);
            if (row != null) {
                return row;
            }
        }
        for (PipelineColumnUi ui : pipelineColumns) {
            TicketRow row = selectionOf(ui.viewer);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    private static TicketRow selectionOf(TableViewer viewer) {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed()) {
            return null;
        }
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection != null && !selection.isEmpty()
                && selection.getFirstElement() instanceof TicketRow row ? row : null;
    }

    private void selectTicket(String id) {
        for (ColumnUi ui : columns.values()) {
            if (selectIn(ui.viewer, id)) {
                return;
            }
        }
        for (PipelineColumnUi ui : pipelineColumns) {
            if (selectIn(ui.viewer, id)) {
                return;
            }
        }
    }

    private static boolean selectIn(TableViewer viewer, String id) {
        Object input = viewer.getInput();
        if (!(input instanceof List<?> rows)) {
            return false;
        }
        for (Object element : rows) {
            if (element instanceof TicketRow row && id.equals(row.id())) {
                viewer.setSelection(new StructuredSelection(row), true);
                viewer.getControl().setFocus();
                return true;
            }
        }
        return false;
    }

    private void updateActionEnablement() {
        if (launchAction == null) {
            return;
        }
        TicketRow row = selectedRow();
        launchAction.setEnabled(!dispatchPending && canLaunch(row));
        autoDispatchAction.setEnabled(!dispatchPending);
        autoLoopAction.setEnabled(!dispatchPending);
        takeOverAction.setEnabled(row != null);
    }

    /** Launch enablement shared by the toolbar action and the context menu. */
    private boolean canLaunch(TicketRow row) {
        return launcher != null
                && row != null
                && ("sprint-backlog".equals(row.status()) || "in-progress".equals(row.status()))
                && FleetJobsModel.getDefault().jobs().stream()
                        .noneMatch(j -> row.id().equals(j.taskId()) && j.state() == FleetJobHandle.State.RUNNING);
    }

    // ------------------------------------------------------------------
    // Ticket actions
    // ------------------------------------------------------------------

    private void openDetails() {
        TicketRow row = selectedRow();
        if (row == null) {
            return;
        }
        Task task = model.loadTask(row.id());
        if (task == null) {
            MessageDialog.openInformation(getSite().getShell(), "Board",
                    "Ticket " + row.id() + " is no longer in the store.");
            refresh();
            return;
        }
        new TicketDetailsDialog(getSite().getShell(), task, repoRoot()).open();
    }

    /** "Cost overview" toolbar action: aggregates the fleet actuals comments over the whole project. */
    private void openCostOverview() {
        if (model == null) {
            return;
        }
        new CostOverviewDialog(getSite().getShell(), model.costOverview(), model.project()).open();
    }

    private void launchSelected() {
        TicketRow row = selectedRow();
        if (!canLaunch(row) || dispatchPending) {
            return;
        }
        BoardDispatch dispatch = captureDispatch();
        runDispatchJob("Launching " + row.id(), () -> dispatch.launch(row.id()), handle -> {
            revealFleetView();
            statusMessage("Launched " + row.id());
        });
    }

    /**
     * U-018 column-level launch: the sprint-backlog header's ▶ dispatches
     * the top READY ticket of the column (rows are priority-sorted, so
     * "top" means highest priority first). Status-line feedback names the
     * launched id - or why nothing launched.
     */
    private void launchFirstReady(String status) {
        if (dispatchPending) {
            return;
        }
        BoardSnapshot snapshot = lastSnapshot;
        if (snapshot == null) {
            return;
        }
        TicketRow pick = null;
        for (TicketRow row : snapshot.column(status)) {
            StageReadiness.Readiness verdict = snapshot.readinessOf(row.id());
            if (verdict != null && verdict.kind() == StageReadiness.Kind.READY
                    && canLaunch(row)) {
                pick = row;
                break;
            }
        }
        if (pick == null) {
            statusMessage("No READY ticket in " + status + " (verdicts: "
                    + snapshot.readyCount() + " ready in the sprint)");
            return;
        }
        final TicketRow launched = pick;
        BoardDispatch dispatch = captureDispatch();
        runDispatchJob("Launching " + launched.id(), () -> dispatch.launch(launched.id()), handle -> {
            revealFleetView();
            statusMessage("Launched " + launched.id());
        });
    }

    /**
     * "Auto-dispatch" toolbar action (ROADMAP H6 piece 2, manual wave):
     * {@link AutoDispatch} plans over the current sprint's tickets —
     * readiness evaluated across the whole project, spend from the
     * project-wide cost overview, live fleet jobs against the concurrency
     * cap — and every admitted id launches through the same
     * {@link TaskFleetLauncher} path as "Launch task", one call each. The
     * policy loads from the {@link DispatchPolicyStore} at click time with
     * the cost-calibrated per-launch estimate. Opt-in per click; the
     * background loop is the "Auto" toggle below.
     */
    private void autoDispatch() {
        if (model == null || launcher == null || dispatchPending) {
            return;
        }
        BoardDispatch dispatch = captureDispatch();
        runDispatchJob("Auto-dispatch", () -> dispatch.scheduler(() -> storedDispatch().policy()).tick(), plan -> {
            if (!plan.launch().isEmpty()) {
                revealFleetView();
            }
            MessageDialog.openInformation(getSite().getShell(), "Auto-dispatch", BoardDispatch.summary(plan));
        });
    }

    /** The stored dispatch policy + bootstrap, or the defaults; never throws. */
    private DispatchPolicyStore.DispatchSettings storedDispatch() {
        return dispatchStore == null ? DispatchPolicyStore.defaults() : dispatchStore.load();
    }

    /** "Dispatch settings" toolbar action: edit and persist the policy both dispatch actions load. */
    private void openDispatchSettings() {
        if (dispatchStore == null) {
            MessageDialog.openInformation(getSite().getShell(), "Dispatch settings",
                    "Settings persistence is unavailable; dispatch runs on the defaults.");
            return;
        }
        new DispatchSettingsDialog(getSite().getShell(), dispatchStore).open();
    }

    /**
     * "Auto ▶/■" toggle (ROADMAP H6 piece 4, the self-draining loop): a
     * {@link DispatchScheduler} over the selected sprint. Tickets added to that
     * sprint drain automatically; changing root/project/sprint stops the loop.
     * The policy loads from the
     * {@link DispatchPolicyStore} at toggle time (with the cost-calibrated
     * estimate); the manual "Auto-dispatch" action above loads it per click.
     */
    private void toggleDispatchLoop() {
        if (autoLoopAction == null) {
            return;
        }
        boolean on = autoLoopAction.isChecked();
        autoLoopAction.setText(on ? "Auto \u25A0" : "Auto \u25B6");
        if (on) {
            startDispatchLoop();
        } else {
            stopDispatchLoop("Auto-dispatch loop stopped.");
        }
    }

    private void startDispatchLoop() {
        if (model == null || launcher == null || dispatchPending) {
            return;
        }
        stopDispatchLoop(null);
        BoardDispatch dispatch = captureDispatch();
        runDispatchJob("Starting auto-dispatch", () -> dispatch.scheduler(() -> storedDispatch().policy()), scheduler -> {
            dispatchScheduler = scheduler;
            scheduler.start(AUTO_DISPATCH_PERIOD);
            statusMessage("Auto-dispatch loop started — every "
                    + AUTO_DISPATCH_PERIOD.toSeconds() + "s over " + model.sprint() + ".");
        });
    }

    private BoardDispatch captureDispatch() {
        AtomicBoolean cancelled = dispatchCancelled;
        return new BoardDispatch(model.root(), model.project(), model.sprint(), launcher, cancelled::get);
    }

    /** All store reads, reservations and file locks execute on a Job, never SWT. */
    private <T> void runDispatchJob(String name, java.util.function.Supplier<T> work,
            java.util.function.Consumer<T> completed) {
        AtomicBoolean cancelled = dispatchCancelled;
        Display display = boardArea.getDisplay();
        dispatchPending = true;
        updateActionEnablement();
        Job job = Job.create(name, monitor -> {
            T result = null;
            RuntimeException failure = null;
            try {
                if (!cancelled.get()) {
                    result = work.get();
                }
            } catch (RuntimeException e) {
                failure = e;
            }
            T value = result;
            RuntimeException error = failure;
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (boardArea.isDisposed()) {
                        return;
                    }
                    dispatchPending = false;
                    if (!cancelled.get()) {
                        if (error == null) {
                            completed.accept(value);
                        } else {
                            stopDispatchLoop(null);
                            autoLoopAction.setChecked(false);
                            autoLoopAction.setText("Auto \u25B6");
                            MessageDialog.openError(getSite().getShell(), name, String.valueOf(error.getMessage()));
                        }
                    }
                    refresh();
                    updateActionEnablement();
                });
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void cancelDispatchForSelection() {
        stopDispatchLoop(null);
        autoLoopAction.setChecked(false);
        autoLoopAction.setText("Auto \u25B6");
    }

    private void stopDispatchLoop(String message) {
        dispatchCancelled.set(true);
        dispatchCancelled = new AtomicBoolean();
        if (dispatchScheduler != null) {
            DispatchScheduler previous = dispatchScheduler;
            dispatchScheduler = null;
            previous.requestStop();
            // stop may wait for an in-flight reservation under the scheduler's
            // lifecycle lock. Invalidate its launch token immediately, then wait
            // off SWT so a contended repository cannot freeze the workbench.
            Job stop = Job.create("Stopping auto-dispatch", monitor -> {
                previous.stop();
                return Status.OK_STATUS;
            });
            stop.setSystem(true);
            stop.schedule();
        }
        if (message != null) {
            statusMessage(message);
        }
    }

    private void statusMessage(String message) {
        if (getViewSite() == null || getViewSite().getActionBars() == null) {
            return;
        }
        IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
        status.setErrorMessage(null);
        status.setMessage(message);
    }

    /**
     * TUI-first takeover (ROADMAP H5 item 3): the ticket's fleet session is
     * handed to the attached opencode TUI over the {@code /tui} control
     * channel (routing off the UI thread — the client may spawn/wait for
     * the server); without a session or TUI the pre-TUI behavior stands —
     * open the fleet worktree and select the ticket.
     */
    private void takeOverSelected() {
        TicketRow row = selectedRow();
        if (row == null) {
            return;
        }
        String sessionId = fleetSessionOf(row.id());
        if (sessionId == null) {
            openWorktreeTakeOver(row);
            return;
        }
        String prompt = TakeoverRouter.takeoverPrompt(row.id(), row.title());
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
                if (boardArea == null || boardArea.isDisposed()) {
                    return;
                }
                if (result.outcome() == TakeoverRouter.Outcome.TUI) {
                    MessageDialog.openInformation(getSite().getShell(), "Take over",
                            "Session handed to the attached TUI.\n" + result.detail());
                } else {
                    openWorktreeTakeOver(row);
                }
            });
        });
    }

    /** Routes the takeover; a client-acquisition failure is a CHAT result, never an exception. */
    private static TakeoverRouter.Result routeTakeover(String sessionId, String prompt) {
        try {
            return TakeoverRouter.route(connectClient(), sessionId, prompt);
        } catch (RuntimeException e) {
            return TakeoverRouter.Result.chat(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Distributed-fleet store sync (pull \u2192 claim \u2192 push discipline):
     * commit local ticket changes, pull-rebase, push — off the UI thread (git
     * may wait on the network). A pull conflict offers the recover path
     * (abort the rebase; nothing was pushed).
     */
    private void syncStore() {
        if (model == null) {
            return;
        }
        Path root = model.root();
        ExecutorService executor = takeoverExecutor;
        if (executor == null) {
            return;
        }
        setContentDescription("Syncing task store...");
        executor.execute(() -> {
            StoreSync.Outcome outcome = StoreSync.sync(root, "sync task store");
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (boardArea == null || boardArea.isDisposed()) {
                    return;
                }
                reportSyncOutcome(outcome);
                refresh();
            });
        });
    }

    /** Reports the store-sync outcome; a pull conflict offers to abort the rebase. */
    private void reportSyncOutcome(StoreSync.Outcome outcome) {
        switch (outcome) {
            case PUSHED -> MessageDialog.openInformation(getSite().getShell(), "Sync store",
                    "Task store committed and pushed.");
            case UP_TO_DATE -> MessageDialog.openInformation(getSite().getShell(), "Sync store",
                    "Task store is up to date (nothing to commit or push).");
            case PULL_CONFLICT -> {
                boolean recover = MessageDialog.openQuestion(getSite().getShell(), "Sync store",
                        "Pulling the shared task store hit a rebase conflict.\n\n"
                                + "Abort the rebase and keep your local commits? "
                                + "(Resolve manually with git if you decline.)");
                if (recover) {
                    StoreSync.Outcome recovered = StoreSync.recover(model.root());
                    MessageDialog.openInformation(getSite().getShell(), "Sync store",
                            recovered == StoreSync.Outcome.FAILED
                                    ? "Rebase aborted — your local commits are intact; retry the sync later."
                                    : "Recovery left the store mid-rebase — resolve manually with git.");
                }
            }
            case PUSH_REJECTED -> MessageDialog.openWarning(getSite().getShell(), "Sync store",
                    "Push was rejected (a peer pushed newer store state).\n\nSync again to rebase onto it.");
            case NOT_A_REPO -> MessageDialog.openWarning(getSite().getShell(), "Sync store",
                    "The task store root is not a git working copy:\n" + model.root());
            case FAILED -> MessageDialog.openWarning(getSite().getShell(), "Sync store",
                    "Sync failed (see the Error log for git details).");
        }
    }

    /** The fleet job's session for a ticket, or {@code null} when no job carries one. */
    private static String fleetSessionOf(String ticketId) {
        return FleetJobsModel.getDefault().jobs().stream()
                .filter(job -> ticketId != null && ticketId.equals(job.taskId()))
                .map(FleetJobHandle::sessionId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** The pre-TUI takeover: open the ticket's fleet worktree and select it on the board. */
    private void openWorktreeTakeOver(TicketRow row) {
        Path repo = repoRoot();
        Path worktree = repo == null ? null
                : FleetGit.worktreePath(repo, row.id());
        if (worktree != null && Files.isDirectory(worktree)) {
            Program.launch(worktree.toString());
        } else {
            MessageDialog.openInformation(getSite().getShell(), "Take over",
                    "No fleet worktree for " + row.id() + ".\n"
                            + "Expected: " + worktree + "\n"
                            + "Use 'Launch task' first.");
        }
        selectTicket(row.id());
    }

    private void revealFleetView() {
        IWorkbenchPage page = getSite().getPage();
        try {
            IViewPart fleet = page.findView(FleetView.ID);
            if (fleet == null) {
                page.showView(FleetView.ID);
            }
        } catch (PartInitException e) {
            logError("Cannot open Fleet view", e);
        }
    }

    private static void logError(String message, Throwable error) {
        Platform.getLog(Platform.getBundle(BoardPlugin.PLUGIN_ID))
                .log(new Status(Status.ERROR, BoardPlugin.PLUGIN_ID, message, error));
    }

    private Path repoRoot() {
        if (model == null) {
            return null;
        }
        Path root = model.root();
        Path opencode = root == null ? null : root.getParent();
        return opencode == null ? null : opencode.getParent();
    }

    /**
     * The board's task-store root: the explicit override, else the adopted
     * repo store (workspace climb, then open-workspace projects), else the
     * preference fallback — the full order lives in
     * {@link TasksRootResolution} (SWT-free, unit-tested). B-002 AC-4: what
     * resolves here is what the watcher watches, and a stale preference
     * default must never shadow the adopted repo store.
     */
    private static Path resolveTasksRoot(String override) {
        return TasksRootResolution.resolve(override, workspaceRoot(), workspaceProjectLocations(),
                BoardView::preferenceTasksRoot);
    }

    /**
     * The {@code tasksRoot} workspace preference text (Preferences →
     * OpenCode); {@code null} when unset or unreadable (headless/test
     * contexts) — {@link TasksRootResolution} treats null as no preference.
     */
    private static String preferenceTasksRoot() {
        try {
            String configured = new com.opencode.ide.core.OpencodePreferences().getTasksRoot();
            return configured == null || configured.isBlank() ? null : configured.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * O-001/B-002 adoption candidates: the locations of the open workspace
     * projects. A project imported from inside a repo makes that repo's
     * {@code .opencode/tasks} adoptable even when the workspace directory
     * itself is outside every repo. Empty when the resources plugin is
     * unavailable (tests, non-workbench hosts) — the climb still runs.
     */
    private static List<Path> workspaceProjectLocations() {
        try {
            var projects = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
                    .getRoot().getProjects();
            List<Path> locations = new ArrayList<>();
            for (var project : projects) {
                var location = project.getLocation();
                if (location != null) {
                    locations.add(location.toFile().toPath().toAbsolutePath().normalize());
                }
            }
            return locations;
        } catch (LinkageError | RuntimeException e) {
            // no resources plugin / no workbench: adoption falls back to the climb
            return List.of();
        }
    }

    /**
     * The board's current task-store root — the persisted root override when
     * set, else the adopted repo store with the preference fallback (see
     * {@link TasksRootResolution}). Package-private seam for the Fleet view's
     * peer-row scan (F-004), so both views read the same store.
     */
    static Path tasksRoot() {
        return resolveTasksRoot(storedRootOverride());
    }

    /** The persisted root override text (empty when unset); best-effort. */
    private static String storedRootOverride() {
        BoardPlugin plugin = BoardPlugin.getDefault();
        if (plugin == null) {
            return "";
        }
        try {
            var settings = plugin.getDialogSettings().getSection(SETTINGS_SECTION);
            if (settings != null) {
                String root = settings.get(SETTING_ROOT);
                if (root != null) {
                    return root;
                }
            }
        } catch (RuntimeException ignored) {
            // defaults survive an unreadable dialog settings file
        }
        return "";
    }

    private static Path workspaceRoot() {
        var location = Platform.getLocation();
        return location == null ? Path.of(".").toAbsolutePath().normalize()
                : location.toFile().toPath().toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private void loadSettings() {
        BoardPlugin plugin = BoardPlugin.getDefault();
        if (plugin != null) {
            try {
                var settings = plugin.getDialogSettings().getSection(SETTINGS_SECTION);
                if (settings != null) {
                    String root = settings.get(SETTING_ROOT);
                    if (root != null) {
                        rootOverride = root;
                    }
                    String project = settings.get(SETTING_PROJECT);
                    if (project != null && !project.isBlank()) {
                        projectName = project;
                    }
                    if (SETTING_MODE_PIPELINE.equalsIgnoreCase(settings.get(SETTING_MODE))) {
                        boardMode = BoardMode.PIPELINE;
                    } else if ("epic".equalsIgnoreCase(settings.get(SETTING_MODE))) {
                        boardMode = BoardMode.EPIC;
                    }
                    String stages = settings.get(SETTING_STAGES);
                    if (stages != null && !stages.isBlank()) {
                        visibleStages = new java.util.LinkedHashSet<>(List.of(stages.split(",")));
                    }
                }
            } catch (RuntimeException ignored) {
                // defaults survive an unreadable dialog settings file
            }
        }
        // B-002: the preference is deliberately NOT seeded into rootOverride
        // anymore. The old seeding froze whatever the preference said into
        // the dialog settings, after which the board never re-read the
        // preference — and a stale value (old clone, pre-P1-4 dev default)
        // permanently shadowed the adopted repo store. The preference is now
        // a LIVE fallback inside TasksRootResolution, ranked below adoption.
        if (projectName == null || projectName.isBlank()) {
            projectName = BoardModel.DEFAULT_PROJECT;
        }
        if (projectText != null && !projectText.isDisposed()) {
            projectText.setText(projectName);
        }
        if (rootText != null && !rootText.isDisposed()) {
            rootText.setText(rootOverride);
        }
        updatingModeCombo = true;
        try {
            if (modeCombo != null && !modeCombo.isDisposed()) {
                modeCombo.select(boardMode == BoardMode.PIPELINE ? 1 : boardMode == BoardMode.EPIC ? 2 : 0);
            }
        } finally {
            updatingModeCombo = false;
        }
    }

    private void saveSettings() {
        BoardPlugin plugin = BoardPlugin.getDefault();
        if (plugin == null) {
            return;
        }
        try {
            var all = plugin.getDialogSettings();
            var settings = all.getSection(SETTINGS_SECTION);
            if (settings == null) {
                settings = all.addNewSection(SETTINGS_SECTION);
            }
            settings.put(SETTING_ROOT, rootOverride == null ? "" : rootOverride);
            settings.put(SETTING_PROJECT, model == null ? projectName : model.project());
            settings.put(SETTING_MODE, boardMode == BoardMode.PIPELINE ? SETTING_MODE_PIPELINE
                    : boardMode == BoardMode.EPIC ? "epic" : "flat");
            settings.put(SETTING_STAGES, visibleStages == null ? "" : String.join(",", visibleStages));
            plugin.persistDialogSettings();
        } catch (RuntimeException ignored) {
            // persistence is best-effort
        }
    }

    @Override
    public void setFocus() {
        if (boardArea != null && !boardArea.isDisposed()) {
            boardArea.setFocus();
        }
    }

    @Override
    public void dispose() {
        saveSettings();
        stopDispatchLoop(null);
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
        if (refreshExecutor != null) {
            refreshExecutor.shutdown();
            refreshExecutor = null;
        }
        if (takeoverExecutor != null) {
            takeoverExecutor.shutdown();
            takeoverExecutor = null;
        }
        super.dispose();
    }

    /**
     * The Stages toolbar dropdown: one check item per V stage (plus the
     * untracked group), an "All stages" reset, and a live count. Checking
     * stages switches from "all visible" to an explicit selection; unchecking
     * the last visible stage re-enables everything (never an empty board by
     * accident).
     */
    private final class StageFilterMenuCreator implements org.eclipse.jface.action.IMenuCreator {

        private org.eclipse.swt.widgets.Menu menu;

        @Override
        public void dispose() {
            if (menu != null) {
                menu.dispose();
                menu = null;
            }
        }

        @Override
        public org.eclipse.swt.widgets.Menu getMenu(org.eclipse.swt.widgets.Control parent) {
            dispose();
            menu = new org.eclipse.swt.widgets.Menu(parent);
            fillMenu();
            return menu;
        }

        @Override
        public org.eclipse.swt.widgets.Menu getMenu(org.eclipse.swt.widgets.Menu parent) {
            dispose();
            menu = new org.eclipse.swt.widgets.Menu(parent);
            fillMenu();
            return menu;
        }

        private void fillMenu() {
            java.util.List<String> stages = new ArrayList<>(VStages.STAGES);
            stages.add(PipelineSnapshot.UNTRACKED);
            for (String stage : stages) {
                org.eclipse.swt.widgets.MenuItem item =
                        new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.CHECK);
                item.setText(stageLabel(stage));
                item.setSelection(isStageVisible(stage));
                item.addListener(org.eclipse.swt.SWT.Selection, e -> toggleStage(stage));
            }
            new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.SEPARATOR);
            org.eclipse.swt.widgets.MenuItem all =
                    new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.PUSH);
            all.setText("Show all stages");
            all.addListener(org.eclipse.swt.SWT.Selection, e -> {
                visibleStages = null;
                applyStageFilter();
            });
        }

        private boolean isStageVisible(String stage) {
            return visibleStages == null || visibleStages.contains(stage);
        }

        private void toggleStage(String stage) {
            visibleStages = StageSelection.toggle(visibleStages, stage);
            applyStageFilter();
        }

        private void applyStageFilter() {
            if (model != null) {
                model.setStageFilter(visibleStages);
                refresh();
                saveSettings();
            }
            updateStageFilterLabel();
        }

        private void updateStageFilterLabel() {
            if (stageFilterAction != null) {
                stageFilterAction.setText("Stages: " + StageSelection.label(visibleStages));
            }
        }

        private String stageLabel(String stage) {
            return PipelineSnapshot.UNTRACKED.equals(stage) ? stage + " (epics, stage-less)" : stage;
        }
    }
}
