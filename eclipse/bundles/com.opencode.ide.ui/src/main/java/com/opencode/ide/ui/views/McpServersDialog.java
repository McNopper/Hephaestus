package com.opencode.ide.ui.views;

import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.ui.model.McpServerRows;

/**
 * Manage the MCP servers registered with one opencode server - connect,
 * disconnect, remove over the v2 {@code experimental.mcp.*} verbs (Wave A,
 * 2026-09-25). Acts on the snapshot the view already loaded; the view's
 * refresh cycle picks up the resulting state.
 */
public final class McpServersDialog extends Dialog {

    private static final int CONNECT = 1001;
    private static final int DISCONNECT = 1002;
    private static final int REMOVE = 1003;

    private final OpencodeClient client;
    private final List<McpServerInfo> servers;
    private Table table;
    private Label feedback;

    /** @param servers the already-loaded MCP server snapshot of one connection */
    public McpServersDialog(Shell parent, OpencodeClient client, List<McpServerInfo> servers) {
        super(parent);
        this.client = client;
        this.servers = servers == null ? List.of() : servers;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText("Manage MCP servers");
        table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        for (McpServerRows.Row row : McpServerRows.rows(servers)) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] {row.name(), row.status() == null ? "unknown" : row.status()});
        }
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        feedback = new Label(area, SWT.NONE);
        feedback.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, CONNECT, "Connect", false);
        createButton(parent, DISCONNECT, "Disconnect", false);
        createButton(parent, REMOVE, "Remove", false);
        createButton(parent, Window.OK, "Close", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == Window.OK) {
            close();
            return;
        }
        TableItem[] selection = table.getSelection();
        if (selection.length == 0) {
            feedback.setText("select a server first");
            return;
        }
        String name = selection[0].getText(0);
        try {
            act(buttonId, name);
        } catch (Exception e) {
            feedback.setText("failed: " + e.getMessage());
        }
    }

    private void act(int buttonId, String name) throws Exception {
        if (buttonId == CONNECT) {
            client.connectMcp(name);
            feedback.setText("connected " + name);
        } else if (buttonId == DISCONNECT) {
            client.disconnectMcp(name);
            feedback.setText("disconnected " + name);
        } else if (buttonId == REMOVE) {
            client.removeMcp(name);
            feedback.setText("removed " + name);
        }
    }
}
