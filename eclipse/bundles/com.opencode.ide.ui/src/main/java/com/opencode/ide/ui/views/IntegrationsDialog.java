package com.opencode.ide.ui.views;

import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.IntegrationInfo;
import com.opencode.ide.client.model.ProviderAuth;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.ConnectFlows;
import com.opencode.ide.ui.model.ConnectFlows.Attempt;
import com.opencode.ide.ui.model.ConnectFlows.AttemptKind;
import com.opencode.ide.ui.model.ConnectFlows.AttemptState;
import com.opencode.ide.ui.model.ConnectFlows.Flow;
import com.opencode.ide.ui.model.ConnectFlows.MethodRow;
import com.opencode.ide.ui.model.IntegrationRows;

/**
 * Connect the auth-method integrations of one opencode server (U-048): one
 * row per (integration, method) from the typed catalog, with the real connect
 * flows behind the Connect button -
 * <ul>
 * <li><b>key</b> methods open a masked key entry and connect via
 * {@code POST /integration/:id/connect/key}; the outcome is a notice,</li>
 * <li><b>command</b> methods start a command attempt and poll it until it
 * settles (a visible "connecting…" row, Cancel aborts),</li>
 * <li><b>oauth</b> methods start an OAuth attempt, open its authorization URL
 * in the external browser (the Providers-view pattern), complete it when a
 * code shows up - pasted by the user or carried by the attempt - and abort
 * on Cancel,</li>
 * <li><b>env</b> methods have no connect route on the wire at all and read
 * as such.</li>
 * </ul>
 * Every failure is a notice on the feedback line - nothing fakes success -
 * and the read-only catalog rendering keeps working when nothing is
 * connectable. Follows {@link SavedPermissionsDialog} (table + action
 * buttons + feedback line, IO off the UI thread through
 * {@link ViewLoadSupport}); attempt polling rides a
 * {@code Display#timerExec} chain that stops when the dialog closes or the
 * attempt settles. One attempt runs at a time.
 */
public final class IntegrationsDialog extends Dialog {

    private static final int CONNECT = 1001;
    private static final int CANCEL_ATTEMPT = 1002;
    private static final int PASTE_CODE = 1003;

    /** The Status cell of a running attempt (the Cancel button aborts it). */
    private static final String CONNECTING = "connecting\u2026";

    private final OpencodeClient client;
    private final String serverLabel;
    private Table table;
    private Label feedback;
    private List<MethodRow> rows = List.of();
    /** The one active connect attempt (UI-thread confined); null while none runs. */
    private Attempt attempt;
    /** The table row the active attempt belongs to (-1 while none). */
    private int attemptRow = -1;
    /** One OAuth complete request at a time. */
    private boolean completing;

    /**
     * @param client       the owning connection's client
     * @param serverLabel  the server node's label (labels the empty state)
     */
    public IntegrationsDialog(Shell parent, OpencodeClient client, String serverLabel) {
        super(parent);
        this.client = client;
        this.serverLabel = serverLabel;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText("Integrations");
        table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        for (String title : new String[] {"Integration", "Method", "Status"}) {
            TableColumn column = new TableColumn(table, SWT.NONE);
            column.setText(title);
        }
        GridData layoutData = new GridData(SWT.FILL, SWT.FILL, true, true);
        layoutData.heightHint = 320;
        layoutData.widthHint = 560;
        table.setLayoutData(layoutData);
        table.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateButtons();
            }
        });
        feedback = new Label(area, SWT.WRAP);
        feedback.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reload();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, CONNECT, "Connect", false);
        createButton(parent, CANCEL_ATTEMPT, "Cancel attempt", false);
        createButton(parent, PASTE_CODE, "Paste code\u2026", false);
        createButton(parent, Window.OK, "Close", true);
        updateButtons();
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == Window.OK) {
            close();
            return;
        }
        if (buttonId == CONNECT) {
            connectSelected();
        } else if (buttonId == CANCEL_ATTEMPT) {
            cancelAttempt();
        } else if (buttonId == PASTE_CODE) {
            pasteCode();
        }
    }

    @Override
    public boolean close() {
        // the poll chain stops itself once the table is disposed; dropping
        // the attempt also stops any pending timer from rescheduling
        attempt = null;
        return super.close();
    }

    // ---------- catalog ----------

    /**
     * Loads the catalog (plus the auth list for method labels) off the UI
     * thread; failures land on the feedback line, never a broken dialog.
     */
    private void reload() {
        ViewLoadSupport.load("Loading integrations", () -> {
            List<IntegrationInfo> integrations = client.listIntegrations();
            return ConnectFlows.attachLabels(ConnectFlows.methodRows(integrations), safeAuths());
        }, this::showRows, error -> {
            if (table.isDisposed()) {
                return;
            }
            rows = List.of();
            table.removeAll();
            feedback.setText("load failed: " + ViewLoadSupport.message(error));
            updateButtons();
        });
    }

    /** The method labels from the auth list; any failure degrades to label-less rows. */
    private List<ProviderAuth> safeAuths() {
        try {
            List<ProviderAuth> auths = client.getProviderAuths();
            return auths == null ? List.of() : auths;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Renders one row per (integration, method) - the read-only catalog view stays intact. */
    private void showRows(List<MethodRow> loaded) {
        if (table.isDisposed()) {
            return;
        }
        rows = loaded == null ? List.of() : loaded;
        table.removeAll();
        String lastIntegration = null;
        for (MethodRow row : rows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setData(row);
            String integration = row.integrationText();
            item.setText(new String[] {
                    integration.equals(lastIntegration) ? "" : integration,
                    row.methodText(),
                    row.connections() + (row.connections() == 1 ? " connection" : " connections")});
            lastIntegration = integration;
        }
        packColumns();
        feedback.setText(rows.isEmpty()
                ? IntegrationRows.dialogText(serverLabel, List.of())
                : rows.size() + " auth methods on "
                        + (serverLabel == null || serverLabel.isBlank() ? "this server" : serverLabel)
                        + " - select one and choose Connect");
        updateButtons();
    }

    private void packColumns() {
        for (TableColumn column : table.getColumns()) {
            column.pack();
            if (column.getWidth() > 420) {
                column.setWidth(420);   // long authorization URLs must not stretch the dialog
            }
        }
    }

    // ---------- connect flows ----------

    private void connectSelected() {
        int index = selectedRowIndex();
        MethodRow row = selectedRow();
        if (row == null) {
            feedback.setText("select a method first");
            return;
        }
        if (!row.connectable()) {
            feedback.setText(notConnectableReason(row));
            return;
        }
        if (attempt != null && attempt.pending()) {
            feedback.setText("an attempt is already connecting - cancel it first");
            return;
        }
        if (row.flow() == Flow.KEY) {
            connectKey(row);
        } else if (row.flow() == Flow.COMMAND) {
            connectAttempt(index, row, AttemptKind.COMMAND);
        } else if (row.flow() == Flow.OAUTH) {
            connectAttempt(index, row, AttemptKind.OAUTH);
        }
    }

    private static String notConnectableReason(MethodRow row) {
        if ("env".equalsIgnoreCase(row.type())) {
            String names = row.names() == null || row.names().isEmpty()
                    ? "(any name)" : String.join(", ", row.names());
            return "environment method - set " + names + " in the server's environment";
        }
        return "this method has no connect flow (" + (row.type() == null ? "no type" : row.type()) + ")";
    }

    /** The key flow: masked entry, one connect call, the outcome as a notice. */
    private void connectKey(MethodRow row) {
        String key = new KeyEntryDialog(getShell(), row.integrationText()).openValue();
        if (key == null) {
            feedback.setText("key connect cancelled");
            return;
        }
        ViewLoadSupport.load("Connecting " + row.integrationId() + " with a key", () -> {
            Map<String, Object> connection = client.startIntegrationKey(row.integrationId(), key, null);
            return ConnectFlows.keyNotice(connection);
        }, notice -> feedback.setText(notice),
                error -> feedback.setText("key connect failed: " + ViewLoadSupport.message(error)));
    }

    /** The command/oauth flows: start an attempt, then poll it until it settles. */
    private void connectAttempt(int index, MethodRow row, AttemptKind kind) {
        String methodId = resolveMethodId(row);
        if (methodId == null) {
            return;   // the prompt already reported why nothing started
        }
        ViewLoadSupport.load("Starting " + row.integrationId() + " " + kind.name().toLowerCase() + " connect",
                () -> kind == AttemptKind.COMMAND
                        ? client.startIntegrationCommand(row.integrationId(), methodId, null)
                        : client.startIntegrationOauth(row.integrationId(), methodId, null),
                answer -> onAttemptStarted(index, kind, answer),
                error -> feedback.setText("connect failed: " + ViewLoadSupport.message(error)));
    }

    /**
     * The method id a command/oauth start posts: the catalog's when it
     * carries one, else an honest entry prompt (the typed catalog drops the
     * wire {@code id} - see {@link ConnectFlows}). {@code null} when the
     * user declines; the feedback line then says why nothing started.
     */
    private String resolveMethodId(MethodRow row) {
        String methodId = row.methodId();
        if (methodId != null) {
            return methodId;
        }
        InputDialog prompt = new InputDialog(getShell(), "Method id",
                "The catalog does not expose the method id of this " + row.type() + " method.\n"
                        + "Enter the id the server reported for it (GET /api/integration):",
                "", null);
        if (prompt.open() != Window.OK) {
            feedback.setText("connect cancelled - no method id");
            return null;
        }
        String entered = prompt.getValue() == null ? "" : prompt.getValue().strip();
        if (entered.isEmpty()) {
            feedback.setText("connect cancelled - no method id");
            return null;
        }
        return entered;
    }

    private void onAttemptStarted(int index, AttemptKind kind, Map<String, Object> answer) {
        if (table.isDisposed() || index < 0 || index >= rows.size()) {
            return;
        }
        attempt = ConnectFlows.started(kind, rows.get(index).integrationId(), answer);
        attemptRow = index;
        if (attempt.pending()) {
            setRowStatus(index, CONNECTING + suffix(attempt.detail()));
            if (kind == AttemptKind.OAUTH) {
                openAuthorization(answer);
                completeCarriedCode(answer);   // lenient: an answer that already carries a code completes itself
            }
            if (attempt.pending()) {
                schedulePoll();
            }
        } else {
            setRowStatus(index, attempt.detail());
            feedback.setText(ConnectFlows.outcomeNotice(attempt));
        }
        updateButtons();
    }

    /**
     * Opens the OAuth authorization URL in the external browser (the
     * Providers-view pattern) and explains how the flow continues.
     */
    private void openAuthorization(Map<String, Object> answer) {
        String url = ConnectFlows.string(answer, "url");
        String instructions = ConnectFlows.string(answer, "instructions");
        String mode = ConnectFlows.string(answer, "mode");
        StringBuilder message = new StringBuilder();
        if (url == null || url.isBlank()) {
            message.append("the attempt started without an authorization URL");
        } else {
            boolean opened = Program.launch(url);
            message.append(opened
                    ? "authorization page opened in the external browser - finish the sign-in there: "
                    : "open this authorization URL in a browser: ").append(url);
        }
        if (instructions != null && !instructions.isBlank()) {
            message.append(" - ").append(instructions);
        }
        if ("code".equals(mode)) {
            message.append(" - this flow expects an authorization code: use Paste code\u2026 when you have it");
        }
        feedback.setText(message.toString());
    }

    // ---------- attempt polling ----------

    /** The poll driver: a UI-thread timer chain; each tick polls off-thread via ViewLoadSupport. */
    private void schedulePoll() {
        if (table.isDisposed() || attempt == null || !attempt.pending()) {
            return;
        }
        table.getDisplay().timerExec((int) ConnectFlows.POLL_INTERVAL_MILLIS, this::pollOnce);
    }

    private void pollOnce() {
        if (table.isDisposed() || attempt == null || !attempt.pending()) {
            return;   // settled or the dialog closed: the chain ends here
        }
        Attempt current = attempt;
        ViewLoadSupport.load("Polling connect attempt", () -> current.kind() == AttemptKind.COMMAND
                ? client.integrationCommandAttempt(current.integrationId(), current.attemptId())
                : client.integrationOauthAttempt(current.integrationId(), current.attemptId()),
                status -> onPollResult(current, status),
                error -> onPollError(current, error));
    }

    private void onPollResult(Attempt current, Map<String, Object> status) {
        if (table.isDisposed() || attempt != current) {
            return;   // superseded: cancelled or settled meanwhile
        }
        attempt = current.on(status);
        if (attempt.pending()) {
            setRowStatus(attemptRow, CONNECTING + suffix(attempt.detail()));
            if (attempt.kind() == AttemptKind.OAUTH) {
                completeCarriedCode(status);
            }
            if (attempt.pending()) {   // a carried code may have completed it
                schedulePoll();
            }
        } else {
            setRowStatus(attemptRow, attempt.detail());
            feedback.setText(ConnectFlows.outcomeNotice(attempt));
        }
        updateButtons();
    }

    private void onPollError(Attempt current, Throwable error) {
        if (table.isDisposed() || attempt != current) {
            return;
        }
        String reason = "polling failed: " + ViewLoadSupport.message(error)
                + " - the attempt may still be running on the server";
        setRowStatus(attemptRow, reason);
        feedback.setText(reason);
        updateButtons();
    }

    /** U-048: an OAuth attempt that carries a code completes itself with it. */
    private void completeCarriedCode(Map<String, Object> status) {
        String code = ConnectFlows.string(status, "code");
        if (code != null && !code.isBlank()) {
            completeOauth(code.strip());
        }
    }

    /** Finishes the pending OAuth attempt with a code (pasted or carried); a null code completes without one. */
    private void completeOauth(String code) {
        if (attempt == null || !attempt.pending() || completing) {
            return;
        }
        completing = true;
        Attempt current = attempt;
        ViewLoadSupport.load("Completing OAuth connect",
                () -> client.completeIntegrationOauth(current.integrationId(), current.attemptId(), code),
                connection -> {
                    completing = false;
                    if (table.isDisposed() || attempt != current) {
                        return;
                    }
                    attempt = current.settled(AttemptState.COMPLETE, ConnectFlows.describe(connection));
                    setRowStatus(attemptRow, attempt.detail());
                    feedback.setText(ConnectFlows.outcomeNotice(attempt));
                    updateButtons();
                },
                error -> {
                    completing = false;
                    if (!table.isDisposed() && attempt == current) {
                        feedback.setText("completing the OAuth connect failed: "
                                + ViewLoadSupport.message(error));
                    }
                });
    }

    private void pasteCode() {
        if (attempt == null || !attempt.pending() || attempt.kind() != AttemptKind.OAUTH) {
            feedback.setText("no OAuth attempt is connecting");
            return;
        }
        InputDialog prompt = new InputDialog(getShell(), "Authorization code",
                "Paste the authorization code the " + attempt.integrationId()
                        + " flow showed (after the browser sign-in):",
                "", null);
        if (prompt.open() != Window.OK) {
            return;
        }
        String code = prompt.getValue() == null ? "" : prompt.getValue().strip();
        if (code.isEmpty()) {
            feedback.setText("no code entered - the attempt keeps waiting");
            return;
        }
        completeOauth(code);
    }

    /** Aborts the running attempt (404/409 are tolerated by the client - already gone is the goal). */
    private void cancelAttempt() {
        if (attempt == null || !attempt.pending()) {
            feedback.setText("no attempt is connecting");
            return;
        }
        Attempt current = attempt;
        ViewLoadSupport.load("Cancelling connect attempt", () -> {
            if (current.kind() == AttemptKind.COMMAND) {
                client.abortIntegrationCommandAttempt(current.integrationId(), current.attemptId());
            } else {
                client.abortIntegrationOauthAttempt(current.integrationId(), current.attemptId());
            }
            return null;
        }, ignored -> {
            if (table.isDisposed() || attempt != current) {
                return;
            }
            attempt = current.settled(AttemptState.ABORTED, "cancelled");
            setRowStatus(attemptRow, attempt.detail());
            feedback.setText(ConnectFlows.outcomeNotice(attempt));
            updateButtons();
        }, error -> {
            if (!table.isDisposed() && attempt == current) {
                feedback.setText("cancelling failed: " + ViewLoadSupport.message(error)
                        + " - the attempt keeps running on the server");
            }
        });
    }

    // ---------- widget plumbing ----------

    private int selectedRowIndex() {
        return table.getSelectionCount() == 1 ? table.getSelectionIndex() : -1;
    }

    private MethodRow selectedRow() {
        int index = selectedRowIndex();
        return index >= 0 && index < rows.size() ? rows.get(index) : null;
    }

    private void setRowStatus(int index, String text) {
        if (table.isDisposed() || index < 0 || index >= table.getItemCount()) {
            return;
        }
        table.getItem(index).setText(2, text == null || text.isBlank() ? "-" : text);
    }

    /** Keeps the action buttons in sync with the selection and the running attempt. */
    private void updateButtons() {
        MethodRow row = selectedRow();
        boolean attemptPending = attempt != null && attempt.pending();
        boolean onAttemptRow = attemptPending && attemptRow == selectedRowIndex();
        enableButton(CONNECT, row != null && row.connectable() && !attemptPending);
        enableButton(CANCEL_ATTEMPT, onAttemptRow);
        enableButton(PASTE_CODE, onAttemptRow && attempt.kind() == AttemptKind.OAUTH);
    }

    private void enableButton(int id, boolean enabled) {
        if (getButton(id) != null) {
            getButton(id).setEnabled(enabled);
        }
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " - " + detail;
    }

    /** The masked key entry of the key flow - the key never echoes anywhere. */
    private static final class KeyEntryDialog extends Dialog {

        private final String integrationText;
        private Text keyText;
        private String value;

        KeyEntryDialog(Shell parent, String integrationText) {
            super(parent);
            this.integrationText = integrationText;
        }

        /** The entered key, or {@code null} when cancelled or left blank. */
        String openValue() {
            return open() == Window.OK && value != null && !value.isBlank() ? value : null;
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            getShell().setText("Connect with key");
            Label prompt = new Label(area, SWT.WRAP);
            prompt.setText("Enter the API key for " + integrationText
                    + " (the input is masked and never displayed back):");
            keyText = new Text(area, SWT.SINGLE | SWT.BORDER | SWT.PASSWORD);
            keyText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, Window.OK, "Connect", true);
            createButton(parent, Window.CANCEL, "Cancel", false);
        }

        @Override
        protected void okPressed() {
            value = keyText.getText().strip();
            super.okPressed();
        }
    }
}
