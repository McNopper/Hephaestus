package com.opencode.ide.ui.views;

import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
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
import com.opencode.ide.client.model.SavedPermission;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.SavedPermissionRows;

/**
 * Manage the remembered allow/deny permission rules of one opencode server
 * (U-046 slice 2): lists {@code GET /api/permission/saved} and removes one
 * rule per row via {@code DELETE /api/permission/saved/{id}} after a confirm.
 * Follows {@link McpServersDialog} (table + per-row action + feedback line);
 * loads and deletes run off the UI thread through {@link ViewLoadSupport}.
 * The rules are user-global, so no server selection scoping applies.
 */
public final class SavedPermissionsDialog extends Dialog {

    private static final int REMOVE = 1001;

    private final OpencodeClient client;
    private Table table;
    private Label feedback;

    /** @param client the owning connection's client */
    public SavedPermissionsDialog(Shell parent, OpencodeClient client) {
        super(parent);
        this.client = client;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText("Saved permissions");
        table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        feedback = new Label(area, SWT.WRAP);
        feedback.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reload();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, REMOVE, "Remove", false);
        createButton(parent, Window.OK, "Close", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == Window.OK) {
            close();
            return;
        }
        if (buttonId != REMOVE) {
            return;
        }
        TableItem[] selection = table.getSelection();
        if (selection.length == 0) {
            feedback.setText("select a rule first");
            return;
        }
        String id = selection[0].getText(0);
        if (!MessageDialog.openConfirm(getShell(), "Remove saved permission",
                "Forget the remembered rule " + id + "?\nThe agent will ask again the next time this permission comes up.")) {
            return;
        }
        remove(id);
    }

    /** Loads the rule list off the UI thread; failures surface on the feedback line, never a broken dialog. */
    private void reload() {
        ViewLoadSupport.load("Loading saved permissions", () -> {
            List<SavedPermission> saved = client.listSavedPermissions();
            return SavedPermissionRows.ids(saved);
        }, this::showRows, error -> {
            if (!table.isDisposed()) {
                table.removeAll();
            }
            if (!feedback.isDisposed()) {
                feedback.setText("load failed: " + ViewLoadSupport.message(error));
            }
        });
    }

    /** Renders one row per rule id, or the empty-state sentence when none is remembered. */
    private void showRows(List<String> ids) {
        if (table.isDisposed()) {
            return;
        }
        table.removeAll();
        for (String id : ids == null ? List.<String>of() : ids) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, id);
        }
        feedback.setText(ids == null || ids.isEmpty() ? SavedPermissionRows.EMPTY_TEXT : "");
    }

    /** Deletes one rule off the UI thread, then refreshes the list from the server. */
    private void remove(String id) {
        ViewLoadSupport.load("Removing saved permission", () -> {
            client.deleteSavedPermission(id);
            return SavedPermissionRows.ids(client.listSavedPermissions());
        }, this::showRows, error -> {
            if (!feedback.isDisposed()) {
                feedback.setText("remove failed: " + ViewLoadSupport.message(error));
            }
        });
    }
}
