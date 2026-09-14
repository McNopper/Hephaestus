package com.opencode.ide.board.views;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.opencode.ide.fleet.dispatch.CostOverview;
import com.opencode.ide.fleet.dispatch.CostOverview.TicketCost;
import com.opencode.ide.fleet.dispatch.CostOverview.Totals;

/**
 * Read-only fleet cost overview (opened from the Board's "Cost overview"
 * toolbar action): the summary lines on top (project-wide plus one per
 * sprint), then the per-ticket table — runs, cost, tokens in+out — sorted by
 * cost descending, with the grand total as a footer line under the table.
 * All data comes pre-aggregated from {@link CostOverview} (SWT-free,
 * unit-tested); the dialog only renders.
 */
final class CostOverviewDialog extends Dialog {

    private static final int TITLE_TRUNCATION = 60;

    private final CostOverview overview;
    private final String project;

    CostOverviewDialog(Shell parentShell, CostOverview overview, String project) {
        super(parentShell);
        this.overview = overview == null ? CostOverview.empty() : overview;
        this.project = project == null ? "" : project;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Cost overview \u2014 " + project);
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(820, 560);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = (Composite) super.createDialogArea(parent);
        body.setLayoutData(new GridData(GridData.FILL_BOTH));
        GridLayout layout = (GridLayout) body.getLayout();
        layout.numColumns = 1;

        Label summary = new Label(body, SWT.WRAP);
        summary.setText(summaryText());
        summary.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        tickets(body);

        Label footer = new Label(body, SWT.NONE);
        footer.setText(footerText());
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return body;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, OK, "Close", true);
    }

    /** The project-wide line plus one line per sprint that has runs. */
    private String summaryText() {
        if (overview.project().runs() == 0) {
            return "No fleet cost actuals recorded yet.";
        }
        List<String> lines = new ArrayList<>();
        lines.add(overview.formatSummary());
        for (Map.Entry<String, Totals> entry : overview.sprints().entrySet()) {
            String line = overview.formatSprintSummary(entry.getKey());
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String footerText() {
        String body = overview.project().body();
        return body.isEmpty() ? "" : "Total: " + body;
    }

    /** The per-ticket table, already sorted by cost desc (unknown cost last). */
    private Table tickets(Composite parent) {
        Table table = new Table(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(GridData.FILL_BOTH));
        column(table, "Ticket", 90, SWT.LEFT);
        column(table, "Title", 320, SWT.LEFT);
        column(table, "Runs", 50, SWT.RIGHT);
        column(table, "Cost", 80, SWT.RIGHT);
        column(table, "Tokens (in+out)", 120, SWT.RIGHT);
        for (TicketCost ticket : overview.tickets()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, safe(ticket.id()));
            item.setText(1, truncate(ticket.title()));
            item.setText(2, String.valueOf(ticket.runs()));
            item.setText(3, ticket.costUsd() == null ? "\u2014" : CostOverview.usd(ticket.costUsd()));
            item.setText(4, tokensLabel(ticket));
        }
        if (overview.tickets().isEmpty()) {
            new TableItem(table, SWT.NONE).setText(0, "(no fleet runs)");
        }
        return table;
    }

    private static TableColumn column(Table table, String title, int width, int align) {
        TableColumn column = new TableColumn(table, align);
        column.setText(title);
        column.setWidth(width);
        return column;
    }

    private static String tokensLabel(TicketCost ticket) {
        if (ticket.tokensIn() == null && ticket.tokensOut() == null) {
            return "\u2014";
        }
        long in = ticket.tokensIn() == null ? 0L : ticket.tokensIn();
        long out = ticket.tokensOut() == null ? 0L : ticket.tokensOut();
        return CostOverview.compact(in + out);
    }

    private static String truncate(String title) {
        String text = safe(title);
        return text.length() <= TITLE_TRUNCATION ? text
                : text.substring(0, TITLE_TRUNCATION - 1) + "\u2026";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
