package com.opencode.ide.board.views;

import com.opencode.ide.board.internal.BoardPlugin;
import com.opencode.ide.board.model.BoardModel;
import com.opencode.ide.board.model.BoardModel.BoardMode;
import com.opencode.ide.board.model.BoardSnapshot;
import com.opencode.ide.board.model.FreshnessStamp;
import com.opencode.ide.board.model.PipelineSnapshot;
import com.opencode.ide.board.model.RefreshGate;
import com.opencode.ide.board.model.StageColumn;
import com.opencode.ide.board.model.StageJourney;
import com.opencode.ide.board.model.StageSelection;
import com.opencode.ide.board.model.TaskStoreWatcher;
import com.opencode.ide.board.model.TasksOverrideMigration;
import com.opencode.ide.board.model.TicketRow;
import com.opencode.ide.board.model.VStageLayout;
import com.opencode.ide.core.TasksRootResolution;
import com.opencode.ide.git.StoreGitStatus;
import com.opencode.ide.git.StoreSync;
import com.opencode.ide.tasks.StageReadiness;
import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskStore;
import com.opencode.ide.tasks.VStages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ControlContribution;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.part.ViewPart;

/**
 * The PM Board: a kanban over the Markdown task store, in two layouts. The header carries TWO
 * dedicated rows grouped by meaning (U-017): the store row (store-root and project inputs,
 * persisted via dialog settings, plus Refresh and Sync store and the grey "updated HH:mm:ss"
 * freshness stamp of U-035 - store-level concerns) and the scope row (the "Group by" layout choice
 * (Progress = the flat five-column status kanban ordered by workflow progress with cards
 * priority-sorted within columns, V-model stages = the ten V-model stage columns on a two-row
 * boustrophedon - every column always renders; persisted too), and the blocked-only / bugs-only
 * (U-005 triage) / stage filters). Tickets drag between columns in both layouts (U-016): a drop on
 * a flat column changes the status, a drop on a stage column changes the stage (backward drops
 * carry the send-back reason contract). The board refreshes live via {@link TaskStoreWatcher} on
 * {@code <root>/<project>} — including peer-agent writes and git-checkout file replacements (B-002)
 * — and survives a missing store (notice instead of exception, polling continues). The watched root
 * is the adopted repo store ({@link TasksRootResolution}); a refresh always shows the full board
 * (owner model 2026-11-20: no backlogs, no sprint scope).
 *
 * <p>Blocked tickets are unmissable in both layouts: red bold rows, a red blocked count in every
 * V-model stage column header. The stage columns always render — all ten, empty ones included, each
 * at the fixed width — and sit on the diagonal {@link VStageLayout} grid so the board reads as a V
 * (definition leg descending left, verification leg ascending right; U-016). Bug tickets carry a
 * red {@code [bug]} type tag on the row (normal weight — blocked stays the louder signal; U-005).
 * The context menu on a ticket row mirrors the toolbar (Open ticket… / Copy ticket id) and adds the
 * V-pipeline moves "Advance stage →" / "Send back…" (failures surface in the status line).
 *
 * <p>Threading: refreshes are single-flight - the snapshot is computed on a background thread and
 * only the apply runs on the UI thread via {@code Display.asyncExec}. While a compute is in flight
 * further refresh requests just mark it dirty; the drain loop then re-computes once more
 * (latest-wins), so bursts of watcher events never queue up or overlap. Part activation ({@code
 * setFocus}) requests a refresh too, rate-limited to once per 5s (U-035) - the same peer-pickup
 * cadence the tickers own, without a poller thread.
 */
public class BoardView extends ViewPart {

  public static final String ID = "com.opencode.ide.board.views.BoardView";

  private static final String SETTINGS_SECTION = "BoardView";
  private static final String SETTING_ROOT = "tasksRoot";
  private static final String SETTING_PROJECT = "project";
  private static final String SETTING_MODE = "mode";
  private static final String SETTING_MODE_PIPELINE = "pipeline";
  private static final String SETTING_STAGES = "visibleStages";

  /** Hidden statuses, comma-joined (persisted; empty = all visible). */
  private static final String SETTING_HIDDEN_STATUSES = "hiddenStatuses";

  /** Whether the Archive row shows its table (persisted). */
  private static final String SETTING_SHOW_ARCHIVE = "showArchive";

  /**
   * Fixed width of every V-model stage column — empty ones keep it too: all ten columns always
   * render (U-016), so the V stays complete and recognizable on an empty board. No
   * collapse-to-header anymore.
   */
  private static final int PIPELINE_COLUMN_WIDTH = 260;

  /** Fixed height of a V-model stage column; fuller tables scroll internally. */
  private static final int V_COLUMN_HEIGHT = 150;

  /** The compact status-prefix legend (tooltip text on pipeline rows). */
  private static final String STATUS_LEGEND =
      "🆕 ready · 🏃 in-progress · 👀 in-review · ✅ done · ❌ not-implemented · ⏸️ paused · 🚫 blocked";

  /** U-035: the minimum time between two activation-triggered refreshes. */
  private static final Duration ACTIVATION_REFRESH_MIN_INTERVAL = Duration.ofSeconds(5);

  private BoardModel model;
  private TaskStoreWatcher watcher;

  private final Map<String, ColumnUi> columns = new LinkedHashMap<>();
  private final List<PipelineColumnUi> pipelineColumns = new ArrayList<>();

  /** U-018: every live row label provider, fed the readiness verdicts per snapshot apply. */
  private final List<BoardRowLabel> rowLabels = new ArrayList<>();

  /** U-025: the Archive row (done tickets archived with their wave). */
  private Composite archiveRow;

  private Button archiveToggle;
  private TableViewer archiveViewer;
  private boolean showArchive = false;

  /**
   * Hidden STATUSES (user direction 2026-09-18: hide done & co individually; empty = all visible).
   * Persisted; the flat layout drops the column, the other layouts drop the cards.
   */
  private java.util.Set<String> hiddenStatuses = java.util.Set.of();

  /** Container that holds whichever layout the current mode builds. */
  private Composite boardArea;

  private Composite flatArea;
  private ScrolledComposite pipelineScroll;
  private Composite pipelineContent;
  private Text rootText;
  private Text projectText;
  private Combo modeCombo;

  /** U-018: free-text filter input on the scope row. */
  private Text filterText;

  /** U-035: grey "updated HH:mm:ss" stamp in the store row (next to Sync store). */
  private Label freshnessLabel;

  private Action refreshAction;

  private Action syncStoreAction;
  private Action newProjectAction;
  private Action resetProjectAction;
  private Action adoptRepoAction;

  private Action blockedOnlyAction;

  /** U-005's optional triage filter: show only bug tickets (model: {@code bugsOnly}). */
  private Action bugsOnlyAction;

  private Action stageFilterAction;
  private Action statusFilterAction;

  /** Selected stage ids for the visibility filter; {@code null} = all visible. */
  private Set<String> visibleStages;

  private boolean updatingModeCombo;
  private String rootOverride = "";
  private String projectName = BoardModel.DEFAULT_PROJECT;
  private BoardMode boardMode = BoardMode.FLAT;

  /** Single-thread daemon executor: serializes snapshot computes, one per view. */
  private ExecutorService refreshExecutor;

  /** Single daemon thread for blocking background client work (store sync git IO). */
  private ExecutorService takeoverExecutor;

  /** True while a drain loop owns the compute (single-flight guard). */
  private final AtomicBoolean draining = new AtomicBoolean();

  /** Set on every refresh request; consumed by the drain loop (latest-wins). */
  private final AtomicBoolean dirty = new AtomicBoolean();

  /** U-035: activation refreshes are rate-limited (once per window) so tabbing never churns. */
  private final RefreshGate activationRefreshGate =
      new RefreshGate(ACTIVATION_REFRESH_MIN_INTERVAL.toMillis());

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
    final Label wipLabel;
    final Label blockedLabel;
    final TableViewer viewer;

    PipelineColumnUi(
        String stage, Label headerLabel, Label wipLabel, Label blockedLabel, TableViewer viewer) {
      this.stage = stage;
      this.headerLabel = headerLabel;
      this.wipLabel = wipLabel;
      this.blockedLabel = blockedLabel;
      this.viewer = viewer;
    }
  }

  @Override
  public void createPartControl(Composite parent) {
    refreshExecutor = com.opencode.ide.client.WorkerPools.serialExecutor("board-refresh");
    takeoverExecutor = com.opencode.ide.client.WorkerPools.serialExecutor("board-takeover");

    Composite outer = new Composite(parent, SWT.NONE);
    GridLayout outerLayout = new GridLayout(1, false);
    outerLayout.marginWidth = 0;
    outerLayout.marginHeight = 0;
    outer.setLayout(outerLayout);

    // U-017: the two header rows (store / scope) stack ABOVE
    // the board area, so they are created first. Settings load before
    // the rows so the embedded contributions seed from stored values.
    loadSettings();
    contributeToolbar(outer);

    boardArea = new Composite(outer, SWT.NONE);
    GridLayout boardLayout = new GridLayout(1, false);
    boardLayout.marginWidth = 0;
    boardLayout.marginHeight = 0;
    boardArea.setLayout(boardLayout);
    // U-033: the board area grabs the view's excess (a plain
    // GridData(FILL_BOTH) style only aligns - it never grabs - so the
    // columns below can share the full width)
    boardArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    buildBoardArea();
    initModel();
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

  private void buildFlatArea() {
    flatArea = new Composite(boardArea, SWT.NONE);
    java.util.List<String> visibleStatuses = new ArrayList<>();
    for (String status : Task.VALID_STATUSES) {
      if (!hiddenStatuses.contains(status)) {
        visibleStatuses.add(status);
      }
    }
    GridLayout columnLayout = new GridLayout(Math.max(1, visibleStatuses.size()), true);
    columnLayout.marginWidth = 0;
    columnLayout.marginHeight = 0;
    columnLayout.horizontalSpacing = 3;
    flatArea.setLayout(columnLayout);
    // U-033 equal growth: the flat area grabs the board area's excess
    // and every column below grabs its share of THAT - all status
    // columns grow EQUALLY on resize (makeColumnsEqualWidth splits the
    // width evenly among the equally-grabbing columns). The V pipeline
    // keeps its fixed-width cells by design (U-016).
    flatArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    for (String status : visibleStatuses) {
      columns.put(status, createColumn(status));
    }
  }

  private void buildPipelineArea() {
    pipelineScroll = new ScrolledComposite(boardArea, SWT.H_SCROLL | SWT.V_SCROLL);
    pipelineScroll.setExpandHorizontal(true);
    pipelineScroll.setExpandVertical(true);
    // grabs so the scroll area fills the (now-grabbing) board area; the
    // V cells INSIDE keep their fixed width (U-016) - only the canvas
    // grows, never the columns
    pipelineScroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    pipelineContent = new Composite(pipelineScroll, SWT.NONE);
    GridLayout gridLayout = new GridLayout(VStageLayout.GRID_COLUMNS, false);
    gridLayout.marginWidth = 2;
    gridLayout.marginHeight = 2;
    gridLayout.horizontalSpacing = 4;
    gridLayout.verticalSpacing = 4;
    pipelineContent.setLayout(gridLayout);

    // The V (U-016, orientation per user direction 2026-09-18): two
    // vertical arms side by side - the left column is the definition
    // leg (requirements at the top, implementation at the vertex), the
    // right column is the verification leg level-paired with it
    // (test-implementation at the vertex, test-requirements at the
    // top). All ten stage columns render even when empty, at the fixed
    // width; nothing collapses to its header.
    for (List<String> row : VStageLayout.grid()) {
      for (String stage : row) {
        if (stage == null) {
          Composite spacer = new Composite(pipelineContent, SWT.NONE);
          spacer.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));
        } else {
          createPipelineColumn(stage);
        }
      }
    }

    // U-025: the Archive row - done tickets land here (wave close
    // archives them, or the Archive context action); hidden by
    // default, listing loads only while visible
    Label archiveSeparator = new Label(pipelineContent, SWT.SEPARATOR | SWT.HORIZONTAL);
    GridData archiveSeparatorData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    archiveSeparatorData.horizontalSpan = VStageLayout.GRID_COLUMNS;
    archiveSeparator.setLayoutData(archiveSeparatorData);

    archiveRow = new Composite(pipelineContent, SWT.NONE);
    GridLayout archiveLayout = new GridLayout(1, false);
    archiveLayout.marginWidth = 0;
    archiveLayout.marginHeight = 0;
    archiveLayout.verticalSpacing = 2;
    archiveRow.setLayout(archiveLayout);
    GridData archiveRowData = new GridData(SWT.FILL, SWT.TOP, true, false);
    archiveRowData.horizontalSpan = VStageLayout.GRID_COLUMNS;
    archiveRow.setLayoutData(archiveRowData);

    archiveToggle = new Button(archiveRow, SWT.FLAT);
    archiveToggle.setText(showArchive ? "Archive (…) \u25BE" : "Archive \u25B8 (hidden)");
    archiveToggle.setToolTipText(
        "Done tickets archived with their wave - out of the active "
            + "board, record kept. Click to "
            + (showArchive ? "hide" : "show"));
    archiveToggle.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    archiveToggle.addListener(
        SWT.Selection,
        e -> {
          showArchive = !showArchive;
          saveSettings();
          refresh();
        });

    archiveViewer = createTicketViewer(archiveRow);
    archiveViewer.getTable().getParent().setLayoutData(rowTableData());
    applyArchiveVisibility();

    pipelineScroll.setContent(pipelineContent);
  }

  private ColumnUi createColumn(String status) {
    Composite column = new Composite(flatArea, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 2;
    layout.marginHeight = 0;
    column.setLayout(layout);
    // U-033: EVERY status column grabs (equally, alongside
    // makeColumnsEqualWidth above) - a resize grows all columns by the
    // same share instead of heaping the extra width on the right ones
    column.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    Composite headerLine = new Composite(column, SWT.NONE);
    GridLayout headerLayout = new GridLayout(1, false);
    headerLayout.marginWidth = 0;
    headerLayout.marginHeight = 0;
    headerLayout.horizontalSpacing = 2;
    headerLine.setLayout(headerLayout);
    headerLine.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Label header = new Label(headerLine, SWT.NONE);
    header.setText(TicketRow.statusHeaderText(status) + " (0)");
    header.setFont(boldFont());
    header.setToolTipText(TicketRow.statusHelp(status));
    header.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));

    TableViewer viewer = createTicketViewer(column);
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
    GridLayout headerLayout = new GridLayout(3, false);
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
    // (U-016 rubberduck review).
    headerLabel.setText(numberedStageHeader(stage));
    int number = VStageLayout.stageNumber(stage);
    headerLabel.setToolTipText(
        "Stage " + number + " of 10 along the V (definition leg 1-5, verification leg 6-10)");
    Label blockedLabel = new Label(header, SWT.NONE);
    blockedLabel.setFont(bold);
    blockedLabel.setToolTipText(
        "tickets blocked with a reason in this stage "
            + "(the needs-human surface: cleared blockers re-enter the pump)");
    // U-028 FR-001..FR-005: the WIP count beside total/blocked - a plain
    // count of status in-progress, recomputed per snapshot; deliberately
    // no WIP-limit concept
    Label wipLabel = new Label(header, SWT.NONE);
    wipLabel.setFont(bold);
    wipLabel.setToolTipText(
        "work in progress: tickets with status in-progress "
            + "(a plain count - U-028: no WIP limits)");

    Composite tableComposite = new Composite(column, SWT.NONE);
    TableColumnLayout tableLayout = new TableColumnLayout();
    tableComposite.setLayout(tableLayout);
    tableComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

    TableViewer viewer =
        new TableViewer(
            tableComposite,
            SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
    viewer.getTable().setLinesVisible(true);
    viewer.setContentProvider(ArrayContentProvider.getInstance());
    hookViewerBehavior(viewer);
    // U-065: stage cards share the ONE column set with every other mode
    addCardColumns(viewer, tableLayout);
    hookStageDrop(viewer.getTable(), stage);

    PipelineColumnUi created =
        new PipelineColumnUi(stage, headerLabel, wipLabel, blockedLabel, viewer);
    pipelineColumns.add(created);
    return created;
  }

  /** A full-width row table's layout data (the Archive row): spans wide, content-height. */
  private static GridData rowTableData() {
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.heightHint = 110;
    return data;
  }

  /** Archive-row visibility (the table only exists while shown). */
  private void applyArchiveVisibility() {
    if (archiveRow == null || archiveRow.isDisposed() || archiveViewer == null) {
      return;
    }
    Composite tableHolder = archiveViewer.getTable().getParent();
    if (tableHolder != null && !tableHolder.isDisposed()) {
      tableHolder.setVisible(showArchive);
      GridData data = (GridData) tableHolder.getLayoutData();
      data.exclude = !showArchive;
      tableHolder.setLayoutData(data);
    }
    archiveToggle.setText(showArchive ? "Archive \u25BE" : "Archive \u25B8 (hidden)");
  }

  /**
   * Shared ticket viewer for EVERY Group-by mode (U-065): one card column set - status emoji | type
   * emoji | styled title | points - built by {@link #addCardColumns}, so the card shape cannot
   * drift between the Progress kanban, the V-model stage columns and the archive row (the
   * 2026-09-18 Progress-only columns became the whole board's contract on 2026-10-07).
   */
  private TableViewer createTicketViewer(Composite column) {
    Composite tableComposite = new Composite(column, SWT.NONE);
    TableColumnLayout tableLayout = new TableColumnLayout();
    tableComposite.setLayout(tableLayout);
    // U-033: grab so the table stretches to its column's width (the
    // the archive row overrides this with its own data)
    tableComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    TableViewer viewer =
        new TableViewer(
            tableComposite,
            SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
    viewer.getTable().setLinesVisible(true);
    viewer.setContentProvider(ArrayContentProvider.getInstance());
    hookViewerBehavior(viewer);
    addCardColumns(viewer, tableLayout);
    return viewer;
  }

  /**
   * U-065: THE card column set every Group-by mode renders - status emoji | type emoji | styled
   * title (readiness chips, stage journey, blocker ride in the cell) | points. Progress (flat),
   * V-model stages and the archive row all go through this one builder, so the card shape cannot
   * drift between modes (the 2026-09-18 Progress-only columns became the whole board's contract on
   * 2026-10-07; owner direction the same day: emoji where possible - colored, visible, DPI-proof,
   * no image files for cards).
   */
  private void addCardColumns(TableViewer viewer, TableColumnLayout tableLayout) {
    TableViewerColumn status = new TableViewerColumn(viewer, SWT.CENTER);
    status.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            TicketRow row = asRow(element);
            return row == null ? "" : TicketRow.statusSymbol(row.status());
          }

          @Override
          public org.eclipse.swt.graphics.Color getForeground(Object element) {
            // U-067: the blocked icon lives HERE (the status cell) - and
            // it is RED (owner 2026-10-07); tints the monochrome glyph,
            // the color-emoji font is red anyway
            TicketRow row = asRow(element);
            if (row != null && "blocked".equals(row.status())) {
              return Display.getCurrent().getSystemColor(SWT.COLOR_RED);
            }
            return null;
          }
        });
    tableLayout.setColumnData(status.getColumn(), new ColumnWeightData(12, 32, false));

    TableViewerColumn type = new TableViewerColumn(viewer, SWT.CENTER);
    type.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            TicketRow row = asRow(element);
            return row == null ? "" : TicketRow.typeEmoji(row.type());
          }
        });
    tableLayout.setColumnData(type.getColumn(), new ColumnWeightData(12, 32, false));

    TableViewerColumn title = new TableViewerColumn(viewer, SWT.NONE);
    // U-065: the styled title cell keeps the readiness chips, stage
    // journey and blocker tail that the composed cards used to render
    BoardRowLabel titleCell = new BoardRowLabel();
    rowLabels.add(titleCell);
    title.setLabelProvider(titleCell);
    tableLayout.setColumnData(title.getColumn(), new ColumnWeightData(100, 110, true));

    TableViewerColumn points = new TableViewerColumn(viewer, SWT.RIGHT);
    points.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            TicketRow row = asRow(element);
            return row == null ? "" : row.pointsLabel();
          }
        });
    tableLayout.setColumnData(points.getColumn(), new ColumnWeightData(12, 26, false));

    // Owner direction 2026-10-07: the card columns SORT - a header click
    // cycles ascending, descending and back to the snapshot order; the
    // comparator itself is model-side (TicketRow.cardComparator) so the
    // ordering contract stays unit-tested SWT-free
    viewer.getTable().setHeaderVisible(true);
    hookColumnSort(viewer, status.getColumn(), "status", "status emoji");
    hookColumnSort(viewer, type.getColumn(), "type", "type emoji");
    hookColumnSort(viewer, title.getColumn(), "title", "ticket title");
    hookColumnSort(viewer, points.getColumn(), "points", "story points");
  }

  /**
   * Click-to-sort on one card column (owner direction 2026-10-07): the header cycles ascending,
   * descending and back to the snapshot order, and the marker in the header shows the state. The
   * marker IS the whole header text - the icon columns are too narrow for labels; the tooltip
   * carries the column's meaning plus the sort behavior. JFace's ViewerComparator constructor takes
   * a Comparator over STRING keys, so the row comparator rides in via the compare override instead.
   */
  private void hookColumnSort(TableViewer viewer, TableColumn column, String key, String meaning) {
    final int[] step = {0}; // 0 snapshot order, 1 ascending, 2 descending
    column.setText("\u21C5");
    column.setToolTipText(meaning + " - click to sort: ascending, descending, snapshot order");
    column.addListener(
        SWT.Selection,
        e -> {
          step[0] = (step[0] + 1) % 3;
          if (step[0] == 0) {
            viewer.setComparator(null);
            column.setText("\u21C5");
            return;
          }
          boolean ascending = step[0] == 1;
          Comparator<TicketRow> order = TicketRow.cardComparator(key, ascending);
          viewer.setComparator(
              new org.eclipse.jface.viewers.ViewerComparator() {
                @Override
                public int compare(
                    org.eclipse.jface.viewers.Viewer viewer, Object left, Object right) {
                  TicketRow a = asRow(left);
                  TicketRow b = asRow(right);
                  if (a == null || b == null) {
                    return a == null ? (b == null ? 0 : 1) : -1;
                  }
                  return order.compare(a, b);
                }
              });
          column.setText(ascending ? "\u25B2" : "\u25BC");
        });
  }

  private void hookViewerBehavior(TableViewer viewer) {
    viewer.addDoubleClickListener(
        new IDoubleClickListener() {
          @Override
          public void doubleClick(DoubleClickEvent event) {
            openDetails();
          }
        });
    ColumnViewerToolTipSupport.enableFor(viewer);
    hookContextMenu(viewer);
    hookDrag(viewer);
  }

  /**
   * Drag-and-drop (U-016 rubberduck review: the kanban's core missing affordance). Dragging carries
   * the ticket id; each column's table is a drop target — a flat column drops change the STATUS, a
   * V-model stage column drops change the STAGE (backward drops ask for the send-back reason first,
   * exactly like the context-menu send-back).
   */
  private void hookDrag(TableViewer viewer) {
    viewer.addDragSupport(
        DND.DROP_MOVE,
        new Transfer[] {TextTransfer.getInstance()},
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
    table.addListener(
        DND.Drop,
        event ->
            moveDroppedTicket(
                (String) event.data,
                () -> model.setStatus((String) event.data, status),
                "status " + status));
  }

  /** Drop target for a V-model stage column's table (moves the ticket to that stage). */
  private void hookStageDrop(Table table, String stage) {
    table.addListener(
        DND.Drop,
        event -> {
          String id = (String) event.data;
          TicketRow row = model == null ? null : model.row(id);
          // null-safe like TicketRow.stageDepth (U-026): a stage-less row has no depth
          int from =
              row == null || row.effectiveStage() == null
                  ? -1
                  : VStages.STAGES.indexOf(row.effectiveStage());
          int to = VStages.STAGES.indexOf(stage);
          if (from >= 0 && to >= 0 && to < from) {
            String previous = row.effectiveStage();
            InputDialog dialog =
                new InputDialog(
                    getSite().getShell(),
                    "Send back " + id,
                    "Reason for moving " + id + " back from '" + previous + "' to '" + stage + "':",
                    "",
                    text -> text == null || text.trim().isEmpty() ? "A reason is required" : null);
            if (dialog.open() != Window.OK
                || dialog.getValue() == null
                || dialog.getValue().trim().isEmpty()) {
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
  private void moveDroppedTicket(
      String id, java.util.function.Supplier<String> move, String target) {
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
    Action open =
        new Action("Open ticket\u2026") {
          @Override
          public void run() {
            openDetails();
          }
        };
    open.setEnabled(row != null);
    manager.add(open);
    Action copyId =
        new Action("Copy ticket id") {
          @Override
          public void run() {
            copyTicketId(row);
          }
        };
    copyId.setEnabled(row != null && row.id() != null);
    manager.add(copyId);
    // U-026 FR-006 shape: the movement trace as plain text, paste-ready
    // for chat or a ticket comment
    Action copyJourney =
        new Action("Copy stage journey") {
          @Override
          public void run() {
            copyStageJourney(row);
          }
        };
    copyJourney.setEnabled(row != null && row.id() != null);
    manager.add(copyJourney);
    manager.add(new Separator());
    // U-025: done tickets can leave the active board into the archive
    Action archive =
        new Action("Archive") {
          @Override
          public void run() {
            if (row == null || model == null) {
              return;
            }
            String error = model.archive(row.id());
            IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
            if (error != null) {
              status.setErrorMessage(error);
            } else {
              status.setErrorMessage(null);
              status.setMessage("Archived " + row.id() + " (see the Archive row)");
            }
            refresh();
          }
        };
    archive.setEnabled(row != null && "done".equals(row.status()));
    manager.add(archive);
  }

  /** Copies the ticket id to the clipboard and confirms in the status line. */
  private void copyTicketId(TicketRow row) {
    if (row == null || row.id() == null || boardArea == null || boardArea.isDisposed()) {
      return;
    }
    Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay());
    try {
      clipboard.setContents(new Object[] {row.id()}, new Transfer[] {TextTransfer.getInstance()});
      statusMessage("Copied: " + row.id());
    } finally {
      clipboard.dispose();
    }
  }

  /**
   * U-026: copies the ticket's stage journey (progress header + movement trace with timestamps,
   * authors and reasons) to the clipboard — the plain-text shape FR-006 prescribes for chat/comment
   * surfaces.
   */
  private void copyStageJourney(TicketRow row) {
    if (row == null
        || row.id() == null
        || model == null
        || boardArea == null
        || boardArea.isDisposed()) {
      return;
    }
    Task task = model.loadTask(row.id());
    if (task == null) {
      return;
    }
    String text = StageJourney.of(task).plainText();
    Clipboard clipboard = new Clipboard(getSite().getShell().getDisplay());
    try {
      clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
      statusMessage("Copied stage journey: " + row.id());
    } finally {
      clipboard.dispose();
    }
  }

  // U-110: the advance/send-back card actions retired with the wandering model - tickets are
  // stationary; fulfillment moves the LINKED downstream tickets instead.

  private static Font boldFont() {
    return JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);
  }

  private static TicketRow asRow(Object element) {
    return element instanceof TicketRow row ? row : null;
  }

  /**
   * Row rendering shared by both layouts: status glyph (colored), blocked bug accents, readiness
   * chips. Blocked rows render their [BLOCKED] tag red bold, bug rows carry a red [bug] tag;
   * readiness chips (· stale / · waiting) ride on the tail. Uses {@link StyledString} so the glyph
   * and tags carry their own colors inside one cell (user direction 2026-09-18: pictographs over
   * [IP]-style codes).
   */
  private final class BoardRowLabel extends org.eclipse.jface.viewers.StyledCellLabelProvider {
    private Map<String, StageReadiness.Readiness> readiness = Map.of();

    /** U-026: per-ticket stage journeys (progress + send-back marker). */
    private Map<String, StageJourney> journeys = Map.of();

    /** U-065: the styled TITLE cell of every card column (all modes). */
    BoardRowLabel() {}

    /** Latest per-ticket dispatch verdicts (UI thread, before setInput). */
    void setReadiness(Map<String, StageReadiness.Readiness> readiness) {
      this.readiness = readiness == null ? Map.of() : readiness;
    }

    /** Latest per-ticket stage journeys (UI thread, before setInput). */
    void setJourneys(Map<String, StageJourney> journeys) {
      this.journeys = journeys == null ? Map.of() : journeys;
    }

    @Override
    public void update(org.eclipse.jface.viewers.ViewerCell cell) {
      TicketRow row = asRow(cell.getElement());
      if (row == null) {
        cell.setText("");
        cell.setStyleRanges(null);
        return;
      }
      Display display = cell.getControl().getDisplay();
      org.eclipse.jface.viewers.StyledString text = new org.eclipse.jface.viewers.StyledString();
      // U-067: the blocked STATE owns its presentation (status column +
      // readiness chip) - the title cell stays CLEAN: no status-derived
      // prefix that doubles the information or goes stale on a status
      // change (owner 2026-10-07: "still the cross symbol in the text -
      // this is double information")
      text.append(
          row.title() == null || row.title().isBlank() ? safe(row.id()) : row.title().trim());
      // cross-mode awareness + readiness chips in quiet gray
      String tail = row.labelTail();
      if (!tail.isEmpty()) {
        text.append(" " + tail, colorStyler(display.getSystemColor(SWT.COLOR_DARK_GRAY)));
      }
      // U-026 FR-002/FR-004: per-card stage progress (<visited>/10,
      // quiet gray) and the send-back marker (red) when the journey
      // ever hit a setback - the unmissable feedback-loop signal
      StageJourney journey = row.id() == null ? null : journeys.get(row.id());
      if (journey != null) {
        text.append(
            " " + journey.progressLabel(),
            colorStyler(display.getSystemColor(SWT.COLOR_DARK_GRAY)));
        if (journey.hasSetback()) {
          text.append(" \u21A9", colorStyler(display.getSystemColor(SWT.COLOR_RED)));
        }
      }
      String chip = chipOf(readiness.get(row.id()));
      if (!chip.isEmpty()) {
        text.append(chip, colorStyler(display.getSystemColor(SWT.COLOR_DARK_YELLOW)));
      }
      cell.setText(text.getString());
      cell.setStyleRanges(text.getStyleRanges());
    }

    /** A foreground-color styler (this JFace names the seam StyledString.Styler). */
    private static org.eclipse.jface.viewers.StyledString.Styler colorStyler(Color color) {
      return new org.eclipse.jface.viewers.StyledString.Styler() {
        @Override
        public void applyStyles(org.eclipse.swt.graphics.TextStyle style) {
          style.foreground = color;
        }
      };
    }

    /** The status glyph's color: done green, running blue, review amber, ready gray. */
    /** The problem chip for a verdict; empty for READY/RUNNING/absent. */
    private static String chipOf(StageReadiness.Readiness verdict) {
      if (verdict == null) {
        return "";
      }
      return switch (verdict.kind()) {
        case STALE -> " \u00b7 stale";
        case WAIT_UPSTREAM -> " \u00b7 waiting";
        case BLOCKED ->
            " \u00b7 blocked-upstream"; // blocked flag may be clear while the verdict still blames
        // it
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
      sb.append(" · priority: ")
          .append(row.priority() == null || row.priority().isBlank() ? "medium" : row.priority());
      if (row.displayBlocked()) {
        sb.append("\n[BLOCKED] ").append(safe(row.blocker()));
      }
      StageReadiness.Readiness verdict = readiness.get(row.id());
      if (verdict != null) {
        sb.append("\nreadiness: ")
            .append(verdict.kind())
            .append(" - ")
            .append(safe(verdict.reason()));
      }
      // U-026: the journey digest on the card - progress plus the
      // latest setback with its reason (FR-004: source, destination
      // and reason text); the full trace lives in the ticket details
      StageJourney journey = row.id() == null ? null : journeys.get(row.id());
      if (journey != null) {
        sb.append("\njourney: ")
            .append(journey.progressLabel())
            .append(" of the V stages visited (distinct; rework counts once)");
        StageJourney.Movement setback = journey.latestSetback();
        if (setback != null) {
          sb.append("\n").append(setback.describe());
        }
      }
      return sb.toString();
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  // ------------------------------------------------------------------
  // Toolbar
  // ------------------------------------------------------------------

  /**
   * Toolbar icon descriptor from the shared action-icon set vendored in {@code
   * com.opencode.ide.core} ({@code generate-action-icons.py}, original artwork). Text-only toolbar
   * actions render as flat labels with no button affordance (user feedback 2026-09-16), so every
   * toolbar action gets an icon.
   */
  private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
    return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
        "com.opencode.ide.core", "icons/actions/" + name + ".png");
  }

  /**
   * One header row (U-017): an embedded flat tool bar inside the view's header stack — same {@link
   * Action}/{@link ControlContribution} objects the old single view toolbar held, just grouped by
   * meaning.
   */
  private static ToolBarManager headerRow(Composite parent) {
    ToolBarManager manager = new ToolBarManager(SWT.HORIZONTAL | SWT.FLAT);
    ToolBar bar = manager.createControl(parent);
    bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return manager;
  }

  private void contributeToolbar(Composite parent) {
    ControlContribution inputsCc =
        new ControlContribution("com.opencode.ide.board.inputs") {
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
            rootText.setToolTipText("Task store root (empty = auto-detect ../.harness/tasks)");
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

    ControlContribution modeCc =
        new ControlContribution("com.opencode.ide.board.groupby") {
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
            modeCombo.setItems("Progress", "V-model stages");
            modeCombo.setToolTipText(
                "Progress: five-column status kanban ordered by workflow progress. "
                    + "V-model stages: the ten V-model stage columns arranged as a V "
                    + "(requirements → test-requirements); every column shows, empty ones too.");
            modeCombo.setLayoutData(fixedSize(140));
            modeCombo.select(boardMode == BoardMode.PIPELINE ? 1 : 0);
            modeCombo.addSelectionListener(
                new SelectionAdapter() {
                  @Override
                  public void widgetSelected(SelectionEvent e) {
                    if (updatingModeCombo || modeCombo.isDisposed()) {
                      return;
                    }
                    BoardMode picked =
                        switch (modeCombo.getSelectionIndex()) {
                          case 1 -> BoardMode.PIPELINE;
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
            filterText.addModifyListener(
                e -> {
                  if (model != null && filterText != null && !filterText.isDisposed()) {
                    model.setTextFilter(filterText.getText());
                    refresh();
                  }
                });
            return box;
          }
        };

    blockedOnlyAction =
        new Action("Needs me (blocked)", Action.AS_CHECK_BOX) {
          @Override
          public void run() {
            if (model != null) {
              model.setBlockedOnly(isChecked());
              refresh();
            }
          }
        };
    blockedOnlyAction.setToolTipText(
        "Show only tickets waiting at the user - they are parked "
            + "(never dispatched); fix + Resume returns them into the wave cycle");
    blockedOnlyAction.setImageDescriptor(icon("blocked-only"));

    bugsOnlyAction =
        new Action("Bugs only", Action.AS_CHECK_BOX) {
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

    stageFilterAction =
        new Action("Stages", Action.AS_DROP_DOWN_MENU) {
          @Override
          public void run() {
            // opening the dropdown shows the menu; nothing to do on click itself
          }
        };
    stageFilterAction.setToolTipText("Show/hide individual V stages (applies to both layouts)");
    stageFilterAction.setImageDescriptor(icon("stages"));
    stageFilterAction.setMenuCreator(new StageFilterMenuCreator());

    // U-018: per-status visibility (hide done & co individually)
    statusFilterAction =
        new Action("Statuses", Action.AS_DROP_DOWN_MENU) {
          @Override
          public void run() {
            // opening the dropdown shows the menu
          }
        };
    statusFilterAction.setToolTipText(
        "Show/hide individual statuses (e.g. tuck away done) - "
            + "the flat layout drops the column, the others drop the cards");
    statusFilterAction.setMenuCreator(new StatusFilterMenuCreator());

    refreshAction =
        new Action("Refresh") {
          @Override
          public void run() {
            applyInputs();
          }
        };
    refreshAction.setToolTipText("Reload the board from the task store");
    refreshAction.setImageDescriptor(icon("refresh"));

    syncStoreAction =
        new Action("Sync store") {
          @Override
          public void run() {
            syncStore();
          }
        };
    syncStoreAction.setToolTipText(
        "Commit the task store and pull-rebase + push (store discipline: pull → claim → push)");
    syncStoreAction.setImageDescriptor(icon("sync-store"));

    // U-035: the grey freshness stamp rides the store row right next to
    // Sync store - the age of the last SUCCESSFUL snapshot apply stays
    // visible at a glance (a failed refresh keeps the last good stamp;
    // the error renders through the content description as today)
    ControlContribution freshnessCc =
        new ControlContribution("com.opencode.ide.board.freshness") {
          @Override
          protected Control createControl(Composite parent) {
            freshnessLabel = new Label(parent, SWT.NONE);
            freshnessLabel.setText("updated \u2014");
            freshnessLabel.setToolTipText(
                "time of the last successful board refresh "
                    + "(keeps the last good time on errors)");
            freshnessLabel.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            return freshnessLabel;
          }
        };
    // O-002: project actions so users never hand-delete task-store files -
    // discoverable from the empty board too (the store row always renders)
    newProjectAction =
        new Action("New project\u2026") {
          @Override
          public void run() {
            newProject();
          }
        };
    newProjectAction.setToolTipText(
        "Create a fresh task-store project (.harness/tasks/<name>) and switch the board to it");
    resetProjectAction =
        new Action("Reset project\u2026") {
          @Override
          public void run() {
            resetProject();
          }
        };
    resetProjectAction.setToolTipText(
        "Remove this project's tickets and wave history after an explicit confirmation "
            + "(other projects are never touched)");
    // O-001: one-click repo adoption - the repo nature anchors the
    // repo-driven derivation (spawn cwd, tasksRoot) without preferences
    adoptRepoAction =
        new Action("Adopt repo…") {
          @Override
          public void run() {
            adoptRepo();
          }
        };
    adoptRepoAction.setToolTipText(
        "Mark an opencode repository (containing .opencode/ or opencode.json) as an OpenCode "
            + "project - a root .project with the repo nature, so the Board, the Server "
            + "view and chat resolve to it from first open");

    // U-017: dedicated header rows replace the single cramped view
    // toolbar — grouped by meaning (user direction 2026-09-17, refined
    // by the rubberduck review: Refresh/Sync are store-level and sit
    // with the store inputs).
    ToolBarManager storeRow = headerRow(parent);
    storeRow.add(inputsCc);
    storeRow.add(refreshAction);
    storeRow.add(syncStoreAction);
    storeRow.add(freshnessCc);
    storeRow.add(newProjectAction);
    storeRow.add(resetProjectAction);
    storeRow.add(adoptRepoAction);
    storeRow.update(true);

    ToolBarManager scopeRow = headerRow(parent);
    scopeRow.add(modeCc);
    scopeRow.add(blockedOnlyAction);
    scopeRow.add(bugsOnlyAction);
    scopeRow.add(stageFilterAction);
    scopeRow.add(statusFilterAction);
    scopeRow.update(true);

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
    text.addFocusListener(
        new FocusAdapter() {
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
    model.setStatusFilter(hiddenStatuses);
    if (stageFilterAction != null) {
      stageFilterAction.setText("Stages: " + StageSelection.label(visibleStages));
    }
    restartWatcher();
    refresh();
  }

  private void applyInputs() {
    if (model == null
        || projectText == null
        || projectText.isDisposed()
        || rootText == null
        || rootText.isDisposed()) {
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
      model.setProject(newProject);
      model.setRoot(resolveTasksRoot(rootOverride));
      restartWatcher();
    }
    saveSettings();
    refresh();
  }

  /**
   * Requests a refresh from any thread (watcher, toolbar, sprint combo, initial load): marks dirty
   * and ensures exactly one drain loop is running. Never computes on the caller's thread.
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
   * Runs on the refresh executor: computes snapshots (plus the sprint list) while changes keep
   * arriving, applying each on the UI thread. The final re-check closes the mark-vs-release race so
   * no change is ever lost.
   */
  private void drainRefresh() {
    while (dirty.compareAndSet(true, false)) {
      BoardSnapshot snapshot = null;
      StoreGitStatus store = StoreGitStatus.NONE;
      try {
        snapshot = model.refresh();
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
      StoreGitStatus storeStatus = store;
      display.asyncExec(
          () -> {
            if (boardArea == null || boardArea.isDisposed()) {
              return;
            }
            try {
              applySnapshot(toApply, storeStatus);
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
  private void applySnapshot(BoardSnapshot snapshot, StoreGitStatus store) {
    // U-018: readiness chips on every card (U-026: stage journeys ride
    // the same path - progress + setbacks)
    for (BoardRowLabel label : rowLabels) {
      label.setReadiness(snapshot.readiness());
      label.setJourneys(snapshot.journeys());
    }
    if (boardMode == BoardMode.PIPELINE) {
      applyPipelineSnapshot(snapshot);
    } else {
      applyFlatSnapshot(snapshot);
    }
    if (snapshot.error() != null) {
      setContentDescription(snapshot.error());
    } else {
      updateFreshnessStamp();
      StringBuilder description =
          new StringBuilder("all tickets")
              .append("  \u2022  ")
              .append(snapshot.total())
              .append(" tickets");
      if (snapshot.blockedCount() > 0) {
        description.append("  \u2022  ").append(snapshot.blockedCount()).append(" blocked");
      }
      if (store != null && store.exists() && !store.summary().isBlank()) {
        description.append("  \u2022  store ").append(store.summary());
      }
      setContentDescription(description.toString());
    }
    // T-144 intercept: one line per applied snapshot - the Board's launch-and-capture
    // test channel (mode, totals, needs-human; errors included)
    com.opencode.ide.core.intercept.ViewIntercept.record(
        "board",
        boardMode
            + " | total="
            + snapshot.total()
            + " blocked="
            + snapshot.blockedCount()
            + " needsHuman="
            + snapshot.needsHumanCount()
            + " stale="
            + snapshot.staleCount()
            + (snapshot.error() != null ? " | ERROR: " + snapshot.error() : ""));
    setTitleToolTip("tasks: " + model.root() + "\nrepo: " + repoRoot());
  }

  /** U-035: rewrites the store-row stamp; grey and quiet, pure presentation. */
  private void updateFreshnessStamp() {
    if (freshnessLabel == null || freshnessLabel.isDisposed()) {
      return;
    }
    freshnessLabel.setText(
        FreshnessStamp.text(System.currentTimeMillis(), java.time.ZoneId.systemDefault()));
  }

  /**
   * The numbered V-stage header text: {@code 3 · architecture}, with the chevron connector to the
   * NEXT stage (down the definition leg, the vertex turn, up the verification leg - U-028).
   */
  private static String numberedStageHeader(String stage) {
    int number = VStageLayout.stageNumber(stage);
    // U-028 FR-006..FR-010: the chevron connector to the NEXT stage (down
    // the definition leg, the vertex turn, up the verification leg) -
    // pure decoration, derived from the layout's single geometry source
    String connector = VStageLayout.connectorGlyph(stage);
    String turn = connector == null ? "" : " " + connector;
    return number > 0 ? number + " \u00b7 " + stage + turn : stage;
  }

  private void applyFlatSnapshot(BoardSnapshot snapshot) {
    for (Map.Entry<String, ColumnUi> entry : columns.entrySet()) {
      List<TicketRow> rows = snapshot.column(entry.getKey());
      entry.getValue().viewer.setInput(rows);
      entry
          .getValue()
          .header
          .setText(TicketRow.statusHeaderText(entry.getKey()) + " (" + rows.size() + ")");
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
      ui.headerLabel.setText(numberedStageHeader(ui.stage) + " (" + rows.size() + ")");
      // U-028: the WIP count beside total/blocked (plain count, no limits)
      int wip = PipelineSnapshot.wipCount(rows);
      ui.wipLabel.setText(wip > 0 ? " \u00b7 " + wip + " WIP" : "");
      ui.blockedLabel.setText(" \u00b7 " + blockedCount + " blocked)");
      ui.blockedLabel.setForeground(
          blockedCount > 0 ? ui.blockedLabel.getDisplay().getSystemColor(SWT.COLOR_RED) : null);
      // Every column keeps its fixed V cell (U-016): empty ones stay
      // rendered with their table — no collapse-to-header anymore.
      ui.viewer.setInput(rows);
    }
    // the Archive row: loads only while visible (U-025)
    if (archiveViewer != null && !archiveViewer.getTable().isDisposed() && model != null) {
      List<TicketRow> archived = showArchive ? model.archivedRows() : List.of();
      archiveViewer.setInput(archived);
      applyArchiveVisibility();
      archiveToggle.setText(
          (showArchive
              ? "Archive (" + archived.size() + ") \u25BE"
              : "Archive (" + model.archiveCount() + ") \u25B8 hidden"));
    }
    pipelineContent.layout(true, true);
    pipelineScroll.setMinSize(pipelineContent.computeSize(SWT.DEFAULT, SWT.DEFAULT));
  }

  private void restartWatcher() {
    if (watcher != null) {
      watcher.stop();
    }
    Path projectDir = model.root().resolve(TaskStore.sanitizeProject(model.project()));
    watcher =
        new TaskStoreWatcher(
            projectDir,
            () -> {
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
    return selection != null
            && !selection.isEmpty()
            && selection.getFirstElement() instanceof TicketRow row
        ? row
        : null;
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
      MessageDialog.openInformation(
          getSite().getShell(), "Board", "Ticket " + row.id() + " is no longer in the store.");
      refresh();
      return;
    }
    new TicketDetailsDialog(getSite().getShell(), task, repoRoot()).open();
  }

  /** Writes a confirmation line into the view's status bar. */
  private void statusMessage(String message) {
    if (getViewSite() == null || getViewSite().getActionBars() == null) {
      return;
    }
    IStatusLineManager status = getViewSite().getActionBars().getStatusLineManager();
    status.setErrorMessage(null);
    status.setMessage(message);
  }

  /** O-002: prompt for a name, scaffold the project, switch the board to it. */
  private void newProject() {
    if (model == null) {
      return;
    }
    InputDialog dialog =
        new InputDialog(
            getSite().getShell(),
            "New project",
            "Task-store project name (created under .harness/tasks/):",
            "",
            text -> text == null || text.trim().isEmpty() ? "A name is required" : null);
    if (dialog.open() != Window.OK || dialog.getValue() == null) {
      return;
    }
    String name = dialog.getValue().trim();
    try {
      model.newProject(name);
      projectName = name;
      projectText.setText(name);
      model.setProject(name);
      saveSettings();
      refresh();
    } catch (RuntimeException e) {
      MessageDialog.openError(getSite().getShell(), "New project", e.getMessage());
    }
  }

  /** O-002: explicit confirmation naming exactly what is lost, then clear THIS project only. */
  private void resetProject() {
    if (model == null) {
      return;
    }
    int tickets = model.projectTasks().size();
    boolean confirmed =
        MessageDialog.openConfirm(
            getSite().getShell(),
            "Reset project",
            "Remove ALL "
                + tickets
                + " ticket(s) of project '"
                + model.project()
                + "'?\n\n"
                + "Lost: every ticket, its actuals and the wave history of THIS project.\n"
                + "Other projects are never touched.");
    if (!confirmed) {
      return;
    }
    try {
      model.resetProject();
      refresh();
    } catch (RuntimeException e) {
      MessageDialog.openError(getSite().getShell(), "Reset project", e.getMessage());
    }
  }

  /**
   * O-001: adopt an opencode repository as an OpenCode project — write the minimal {@code .project}
   * (the repo nature, no builders) into the repo root and open it in the workspace, so the
   * repo-driven derivation (spawn working directory in OpencodeConnection, tasksRoot in
   * TasksRootResolution) anchors to it without any manual preference. Repos with nested Eclipse
   * projects are refused with the auto-discovery explanation (Eclipse forbids overlapping
   * projects); the dev layout of THIS repo keeps working through markers exactly as today.
   */
  private void adoptRepo() {
    org.eclipse.swt.widgets.DirectoryDialog dialog =
        new org.eclipse.swt.widgets.DirectoryDialog(getSite().getShell(), SWT.OPEN);
    dialog.setText("Adopt repo as OpenCode project");
    dialog.setMessage(
        "Choose the root of an opencode repository (containing .opencode/ or opencode.json):");
    Path storeRoot = model == null ? null : model.root();
    if (storeRoot != null
        && storeRoot.getParent() != null
        && storeRoot.getParent().getParent() != null) {
      dialog.setFilterPath(storeRoot.getParent().getParent().toString());
    }
    String chosen = dialog.open();
    if (chosen == null) {
      return;
    }
    Path directory = Path.of(chosen);
    String problem = com.opencode.ide.core.RepoProjectSetup.adoptionProblem(directory);
    if (problem != null) {
      MessageDialog.openInformation(
          getSite().getShell(), "Adopt repo", directory + " cannot be adopted:\n" + problem);
      return;
    }
    try {
      String name = directory.getFileName().toString();
      Files.writeString(
          directory.resolve(".project"),
          com.opencode.ide.core.RepoProjectSetup.projectFileXml(name));
      org.eclipse.core.resources.IWorkspace workspace =
          org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
      org.eclipse.core.resources.IProject project = workspace.getRoot().getProject(name);
      org.eclipse.core.resources.IProjectDescription description =
          workspace.newProjectDescription(name);
      description.setLocation(new org.eclipse.core.runtime.Path(directory.toString()));
      project.create(description, null);
      project.open(null);
      project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
      setContentDescription(
          "adopted repo: "
              + name
              + " - Board/Server/chat resolve to it (the repo nature anchors the derivation)");
      refresh();
    } catch (org.eclipse.core.runtime.CoreException | java.io.IOException | RuntimeException e) {
      MessageDialog.openError(
          getSite().getShell(),
          "Adopt repo",
          "Adopting " + directory + " failed: " + e.getMessage());
    }
  }

  /**
   * Store sync (pull \u2192 claim \u2192 push discipline): commit local ticket changes,
   * pull-rebase, push — off the UI thread (git may wait on the network). A pull conflict offers the
   * recover path (abort the rebase; nothing was pushed).
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
    executor.execute(
        () -> {
          StoreSync.Outcome outcome = StoreSync.sync(root, "sync task store");
          Display display = Display.getDefault();
          if (display == null || display.isDisposed()) {
            return;
          }
          display.asyncExec(
              () -> {
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
      case PUSHED ->
          MessageDialog.openInformation(
              getSite().getShell(), "Sync store", "Task store committed and pushed.");
      case UP_TO_DATE ->
          MessageDialog.openInformation(
              getSite().getShell(),
              "Sync store",
              "Task store is up to date (nothing to commit or push).");
      case PULL_CONFLICT -> {
        boolean recover =
            MessageDialog.openQuestion(
                getSite().getShell(),
                "Sync store",
                "Pulling the shared task store hit a rebase conflict.\n\n"
                    + "Abort the rebase and keep your local commits? "
                    + "(Resolve manually with git if you decline.)");
        if (recover) {
          StoreSync.Outcome recovered = StoreSync.recover(model.root());
          MessageDialog.openInformation(
              getSite().getShell(),
              "Sync store",
              recovered == StoreSync.Outcome.FAILED
                  ? "Rebase aborted — your local commits are intact; retry the sync later."
                  : "Recovery left the store mid-rebase — resolve manually with git.");
        }
      }
      case PUSH_REJECTED ->
          MessageDialog.openWarning(
              getSite().getShell(),
              "Sync store",
              "Push was rejected (a peer pushed newer store state).\n\nSync again to rebase onto it.");
      case NOT_A_REPO ->
          MessageDialog.openWarning(
              getSite().getShell(),
              "Sync store",
              "The task store root is not a git working copy:\n" + model.root());
      case FAILED ->
          MessageDialog.openWarning(
              getSite().getShell(),
              "Sync store",
              "Sync failed (see the Error log for git details).");
      default ->
          MessageDialog.openWarning(
              getSite().getShell(), "Sync store", "Unexpected sync outcome: " + outcome);
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
   * The board's task-store root: the explicit override, else the adopted repo store (workspace
   * climb, then open-workspace projects), else the preference fallback — the full order lives in
   * the shared core seam {@link TasksRootResolution} (SWT-free, unit-tested; B-016: the
   * eclipse-build endpoint bridges the very same resolution into {@code opencode.tasks.root}).
   * B-002 AC-4: what resolves here is what the watcher watches, and a stale preference default must
   * never shadow the adopted repo store.
   */
  private static Path resolveTasksRoot(String override) {
    return TasksRootResolution.resolveForWorkspace(override);
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
            // T-052 settings migration: a persisted override from before the
            // store relocation is re-pointed to the relocated sibling - the
            // stale legacy skeleton exists but is empty and the Board would
            // silently show zero tickets (live finding 2026-11-18)
            rootOverride = TasksOverrideMigration.migrate(root);
          }
          String project = settings.get(SETTING_PROJECT);
          if (project != null && !project.isBlank()) {
            projectName = project;
          }
          if (SETTING_MODE_PIPELINE.equalsIgnoreCase(settings.get(SETTING_MODE))) {
            boardMode = BoardMode.PIPELINE;
          }
          if (settings.get(SETTING_SHOW_ARCHIVE) != null) {
            showArchive = Boolean.parseBoolean(settings.get(SETTING_SHOW_ARCHIVE));
          }
          String hidden = settings.get(SETTING_HIDDEN_STATUSES);
          if (hidden != null && !hidden.isBlank()) {
            hiddenStatuses =
                java.util.Set.copyOf(
                    java.util.Arrays.stream(hidden.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
          }
          String stages = settings.get(SETTING_STAGES);
          if (stages != null && !stages.isBlank()) {
            visibleStages = new java.util.LinkedHashSet<>(List.of(stages.split(",")));
            // owner directive 2026-11-19: the stage-less group is retired - drop a stale
            // "(untracked)" id an old settings file may still carry, and treat a selection
            // that (after the drop) covers everything as "all"
            visibleStages.retainAll(VStages.STAGES);
            if (visibleStages.isEmpty() || visibleStages.size() == VStages.STAGES.size()) {
              visibleStages = null;
            }
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
        modeCombo.select(boardMode == BoardMode.PIPELINE ? 1 : 0);
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
      settings.put(SETTING_MODE, boardMode == BoardMode.PIPELINE ? SETTING_MODE_PIPELINE : "flat");
      settings.put(SETTING_STAGES, visibleStages == null ? "" : String.join(",", visibleStages));
      settings.put(SETTING_HIDDEN_STATUSES, String.join(",", hiddenStatuses));
      settings.put(SETTING_SHOW_ARCHIVE, String.valueOf(showArchive));
      plugin.persistDialogSettings();
    } catch (RuntimeException ignored) {
      // persistence is best-effort
    }
  }

  @Override
  public void setFocus() {
    if (boardArea != null && !boardArea.isDisposed()) {
      boardArea.setFocus();
      // U-035: activation is a refresh cadence - it picks up changes
      // the watcher cannot see (a git checkout replacing files, a
      // peer store switch) without introducing a poller thread. Rate
      // limited so tabbing through views never churns the
      // single-flight refresh pipeline.
      if (activationRefreshGate.allow(System.currentTimeMillis())) {
        refresh();
      }
    }
  }

  @Override
  public void dispose() {
    saveSettings();
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
   * The shared drop-down lifecycle of the filter menus (status/stage); subclasses fill the items.
   */
  private abstract class FilterMenuCreator implements org.eclipse.jface.action.IMenuCreator {

    protected org.eclipse.swt.widgets.Menu menu;

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

    protected abstract void fillMenu();
  }

  /** The per-status visibility drop-down (hide done & co individually; persisted). */
  private final class StatusFilterMenuCreator extends FilterMenuCreator {

    @Override
    protected void fillMenu() {
      for (String status : Task.VALID_STATUSES) {
        org.eclipse.swt.widgets.MenuItem item =
            new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.CHECK);
        item.setText(
            TicketRow.statusSymbol(status)
                + " "
                + status
                + (hiddenStatuses.contains(status) ? " (hidden)" : ""));
        item.setSelection(!hiddenStatuses.contains(status));
        item.addListener(
            org.eclipse.swt.SWT.Selection, e -> toggleStatus(status, item.getSelection()));
      }
      new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.SEPARATOR);
      org.eclipse.swt.widgets.MenuItem all =
          new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.PUSH);
      all.setText("Show all statuses");
      all.addListener(
          org.eclipse.swt.SWT.Selection,
          e -> {
            hiddenStatuses = java.util.Set.of();
            applyStatusFilter();
          });
    }

    private void toggleStatus(String status, boolean visible) {
      java.util.Set<String> next = new java.util.LinkedHashSet<>(hiddenStatuses);
      if (visible) {
        next.remove(status);
      } else {
        next.add(status);
      }
      hiddenStatuses = java.util.Set.copyOf(next);
      applyStatusFilter();
    }

    private void applyStatusFilter() {
      saveSettings();
      if (model != null) {
        model.setStatusFilter(hiddenStatuses);
      }
      rebuildBoardArea();
      refresh();
    }
  }

  /**
   * The Stages toolbar dropdown: one check item per V stage, an "All stages" reset, and a live
   * count. Checking stages switches from "all visible" to an explicit selection; unchecking the
   * last visible stage re-enables everything (never an empty board by accident).
   */
  private final class StageFilterMenuCreator extends FilterMenuCreator {

    @Override
    protected void fillMenu() {
      for (String stage : VStages.STAGES) {
        org.eclipse.swt.widgets.MenuItem item =
            new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.CHECK);
        item.setText(stage);
        item.setSelection(isStageVisible(stage));
        item.addListener(org.eclipse.swt.SWT.Selection, e -> toggleStage(stage));
      }
      new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.SEPARATOR);
      org.eclipse.swt.widgets.MenuItem all =
          new org.eclipse.swt.widgets.MenuItem(menu, org.eclipse.swt.SWT.PUSH);
      all.setText("Show all stages");
      all.addListener(
          org.eclipse.swt.SWT.Selection,
          e -> {
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
  }
}
