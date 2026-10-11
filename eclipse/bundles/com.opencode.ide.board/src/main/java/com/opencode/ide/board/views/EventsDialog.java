package com.opencode.ide.board.views;

import com.opencode.ide.board.model.EventsFeed;
import com.opencode.ide.fleet.GlobalEventsAggregator;
import com.opencode.ide.fleet.GlobalEventsAggregator.ObservedEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * The Fleet view's global event feed (opened from the "Events" toolbar action): the aggregator's
 * newest {@link #RECENT} events as {@code HH:mm:ss · connection · type} rows, newest first, with
 * the full properties JSON as the row tooltip, plus a liveness header ({@code n connections (m
 * failed)}). Live while open — every delivered event rebuilds the table on the UI thread, coalesced
 * so bursts collapse into one rebuild. The dialog only renders; rows and badges come from the
 * SWT-free {@link EventsFeed}.
 */
final class EventsDialog extends Dialog {

  private static final int RECENT = 50;

  private final GlobalEventsAggregator events;
  private final EventsFeed feed;

  /**
   * Field (not ad-hoc method ref): capturing lambdas are not equal, so removal needs the same
   * instance.
   */
  private final Consumer<ObservedEvent> listener = this::onEvent;

  private final AtomicBoolean refreshPending = new AtomicBoolean();

  private Label liveness;
  private TableViewer viewer;
  private volatile boolean closed;

  EventsDialog(Shell parentShell, GlobalEventsAggregator events, EventsFeed feed) {
    super(parentShell);
    this.events = events;
    this.feed = feed;
  }

  @Override
  protected void configureShell(Shell newShell) {
    super.configureShell(newShell);
    newShell.setText("Fleet events");
  }

  @Override
  protected boolean isResizable() {
    return true;
  }

  @Override
  protected Point getInitialSize() {
    return new Point(760, 480);
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite body = (Composite) super.createDialogArea(parent);
    body.setLayoutData(new GridData(GridData.FILL_BOTH));

    liveness = new Label(body, SWT.WRAP);
    liveness.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Composite tableComposite = new Composite(body, SWT.NONE);
    TableColumnLayout tableLayout = new TableColumnLayout();
    tableComposite.setLayout(tableLayout);
    tableComposite.setLayoutData(new GridData(GridData.FILL_BOTH));

    viewer = new TableViewer(tableComposite, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
    viewer.getTable().setHeaderVisible(true);
    viewer.getTable().setLinesVisible(true);
    viewer.setContentProvider(ArrayContentProvider.getInstance());
    ColumnViewerToolTipSupport.enableFor(viewer);
    TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
    column.getColumn().setText("Time · Connection · Type");
    column.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            return element instanceof ObservedEvent event ? feed.format(event) : "";
          }

          @Override
          public String getToolTipText(Object element) {
            return element instanceof ObservedEvent event ? EventsFeed.tooltip(event) : "";
          }
        });
    tableLayout.setColumnData(column.getColumn(), new ColumnWeightData(100, 420, true));

    rebuild();
    events.addListener(listener);
    return body;
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    createButton(parent, OK, "Close", true);
  }

  @Override
  public boolean close() {
    closed = true;
    events.removeListener(listener);
    return super.close();
  }

  /**
   * Event delivered (any stream thread): one coalesced UI-thread rebuild while the dialog lives.
   */
  private void onEvent(ObservedEvent event) {
    if (closed || !refreshPending.compareAndSet(false, true)) {
      return;
    }
    Display display = Display.getDefault();
    if (display == null || display.isDisposed()) {
      refreshPending.set(false);
      return;
    }
    display.asyncExec(
        () -> {
          refreshPending.set(false);
          if (!closed && getShell() != null && !getShell().isDisposed()) {
            rebuild();
          }
        });
  }

  /** Refills the table (newest first) and the liveness header from the aggregator. */
  private void rebuild() {
    List<ObservedEvent> recent = new ArrayList<>(events.recent(RECENT));
    Collections.reverse(recent);
    viewer.setInput(recent);
    String badge =
        EventsFeed.liveness(events.connections().size(), events.failedConnections().size());
    liveness.setText(badge.isEmpty() ? "No connections subscribed." : badge);
  }
}
