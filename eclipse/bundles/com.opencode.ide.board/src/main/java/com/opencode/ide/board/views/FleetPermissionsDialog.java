package com.opencode.ide.board.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.opencode.ide.board.model.FleetJobsModel;
import com.opencode.ide.board.model.FleetPermissions;
import com.opencode.ide.board.model.FleetPermissions.Row;
import com.opencode.ide.fleet.PermissionQueue;
import com.opencode.ide.fleet.PermissionQueue.Response;

/**
 * The pending permission requests of unattended fleet sessions (opened from
 * the Fleet view's "Permissions (n)" toolbar action): one row per request -
 * session, ticket, request detail - with Approve once / Always / Reject
 * buttons per row. Answering runs on a background thread (the answer POST
 * may block while the server spawns) and refreshes the table through the
 * queue's change notification. All data comes pre-joined from
 * {@link FleetPermissions} (SWT-free, unit-tested); the dialog only renders.
 */
final class FleetPermissionsDialog extends Dialog {

    private static final int COL_SESSION = 0;
    private static final int COL_TASK = 1;
    private static final int COL_DETAIL = 2;
    private static final int COL_ACTIONS = 3;

    private final PermissionQueue queue;
    private final Set<String> answering = ConcurrentHashMap.newKeySet();
    private final List<TableEditor> editors = new ArrayList<>();
    private final List<Composite> actionCells = new ArrayList<>();
    /** Field (not ad-hoc method ref): capturing lambdas are not equal, so removal needs the same instance. */
    private final Runnable queueListener = this::onQueueChange;

    private Table table;
    private Label statusLine;
    private volatile boolean closed;

    FleetPermissionsDialog(Shell parentShell, PermissionQueue queue) {
        super(parentShell);
        this.queue = queue;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Fleet permissions");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(860, 480);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = (Composite) super.createDialogArea(parent);
        body.setLayoutData(new GridData(GridData.FILL_BOTH));
        GridLayout layout = (GridLayout) body.getLayout();
        layout.numColumns = 1;

        Label intro = new Label(body, SWT.WRAP);
        intro.setText("Permission requests raised by unattended fleet sessions - "
                + "the running agent waits until you answer.");
        intro.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(body, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(GridData.FILL_BOTH));
        column("Session", 150);
        column("Ticket", 80);
        column("Request", 280);
        column("", 240);

        statusLine = new Label(body, SWT.WRAP);
        statusLine.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        rebuildRows();
        queue.addListener(queueListener);
        return body;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, OK, "Close", true);
    }

    @Override
    public boolean close() {
        closed = true;
        queue.removeListener(queueListener);
        return super.close();
    }

    /** Queue change (any thread): refresh on the UI thread while the dialog lives. */
    private void onQueueChange() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || closed) {
            return;
        }
        display.asyncExec(() -> {
            if (!closed && getShell() != null && !getShell().isDisposed()) {
                rebuildRows();
            }
        });
    }

    /** Rebuilds all rows from the queue's current pending list. */
    private void rebuildRows() {
        for (TableEditor editor : editors) {
            editor.dispose();
        }
        editors.clear();
        for (Composite cell : actionCells) {
            cell.dispose(); // no-op when the editor already disposed it
        }
        actionCells.clear();
        table.removeAll();

        List<Row> rows = FleetPermissions.rows(queue.pending(), FleetJobsModel.getDefault().jobs());
        for (Row row : rows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(COL_SESSION, safe(row.sessionId()));
            item.setText(COL_TASK, safe(row.taskId()));
            item.setText(COL_DETAIL, safe(row.detail()));
            attachActions(item, row);
        }
        if (rows.isEmpty()) {
            new TableItem(table, SWT.NONE).setText(COL_SESSION, "(no pending permission requests)");
        }
        table.getParent().layout();
    }

    /** Approve once / Always / Reject buttons for one row. */
    private void attachActions(TableItem item, Row row) {
        if (answering.contains(row.permissionId())) {
            item.setText(COL_ACTIONS, "answering…");
            return;
        }
        TableEditor editor = new TableEditor(table);
        editor.horizontalAlignment = SWT.LEFT;
        editor.grabHorizontal = true;
        Composite cell = new Composite(table, SWT.NONE);
        GridLayout cellLayout = new GridLayout(3, true);
        cellLayout.marginWidth = 0;
        cellLayout.marginHeight = 0;
        cell.setLayout(cellLayout);
        answerButton(cell, row, "Approve once", Response.ONCE, false);
        answerButton(cell, row, "Always", Response.ALWAYS, true);
        answerButton(cell, row, "Reject", Response.REJECT, false);
        editor.setEditor(cell, item, COL_ACTIONS);
        editors.add(editor);
        actionCells.add(cell);
    }

    private void answerButton(Composite parent, Row row, String label, Response response, boolean remember) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, (Event e) -> answer(row, response, remember));
    }

    /** Answers on a background thread (the POST may block on server spawn); refresh arrives via the queue listener. */
    private void answer(Row row, Response response, boolean remember) {
        if (!answering.add(row.permissionId())) {
            return; // an answer for this request is already in flight
        }
        rebuildRows();
        com.opencode.ide.client.WorkerPools.submit("fleet-permission-answer", () -> {
            PermissionQueue.AnswerResult result = queue.answer(row.permissionId(), response, remember);
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (closed || getShell() == null || getShell().isDisposed()) {
                    return;
                }
                answering.remove(row.permissionId());
                statusLine.setText((result.success() ? "" : "FAILED: ") + result.message());
                rebuildRows();
            });
        });
    }

    private TableColumn column(String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
        return column;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
