package com.opencode.ide.ui.views;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.SavedPermission;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.SavedPermissionRows;
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

/**
 * Manage the remembered allow/deny permission rules of one opencode server (U-046 slice 2): lists
 * {@code GET /api/permission/saved} and removes one rule per row via {@code DELETE
 * /api/permission/saved/{id}} after a confirm. Follows {@link McpServersDialog} (table + per-row
 * action + feedback line); loads and deletes run off the UI thread through {@link ViewLoadSupport}.
 * The rules are user-global, so no server selection scoping applies.
 */
public final class SavedPermissionsDialog extends Dialog {

  private static final int REMOVE = 1001;

  private final OpencodeClient client;
  private Table table;
  private Label feedback;

  /**
   * @param client the owning connection's client
   */
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
    createButton(parent, EDIT, "Edit\u2026", false);
    createButton(parent, REMOVE, "Remove", false);
    createButton(parent, Window.OK, "Close", true);
  }

  @Override
  protected void buttonPressed(int buttonId) {
    if (buttonId == Window.OK) {
      close();
      return;
    }
    if (buttonId != REMOVE && buttonId != EDIT) {
      return;
    }
    TableItem[] selection = table.getSelection();
    SavedPermission rule = selection.length == 0 ? null : selectedRule(selection[0]);
    if (rule == null) {
      feedback.setText("select a rule first");
      return;
    }
    if (buttonId == EDIT) {
      edit(rule); // CP-07 AC2: the saved-permission EDIT route
      return;
    }
    if (!MessageDialog.openConfirm(
        getShell(),
        "Remove saved permission",
        "Forget the remembered rule "
            + rule.id()
            + "?\nThe agent will ask again the next time this permission comes up.")) {
      return;
    }
    remove(rule.id());
  }

  /** The EDIT button id (CP-07 AC2 - the saved-permission EDIT route). */
  private static final int EDIT = 1002;

  /** The records behind the rows (the table shows id + action + resource; Edit prefills them). */
  private List<SavedPermission> current = List.of();

  /**
   * Loads the rule list off the UI thread; failures surface on the feedback line, never a broken
   * dialog.
   */
  private void reload() {
    ViewLoadSupport.load(
        "Loading saved permissions",
        client::listSavedPermissions,
        this::showRows,
        error -> {
          if (!table.isDisposed()) {
            table.removeAll();
          }
          if (!feedback.isDisposed()) {
            feedback.setText("load failed: " + ViewLoadSupport.message(error));
          }
        });
  }

  /** Renders one row per rule (id + action + resource), or the empty-state sentence. */
  private void showRows(List<SavedPermission> rules) {
    if (table.isDisposed()) {
      return;
    }
    table.removeAll();
    current = rules == null ? List.of() : rules;
    for (SavedPermission rule : current) {
      TableItem item = new TableItem(table, SWT.NONE);
      item.setText(0, describe(rule));
    }
    feedback.setText(current.isEmpty() ? SavedPermissionRows.EMPTY_TEXT : "");
  }

  /** The record behind a selected row (row order == {@link #current} order). */
  private SavedPermission selectedRule(TableItem item) {
    int index = table.indexOf(item);
    return index >= 0 && index < current.size() ? current.get(index) : null;
  }

  /** {@code perm_saved_1 · allow · /src/*} - enough to recognise the rule being edited. */
  private static String describe(SavedPermission rule) {
    return (rule.id() == null ? "" : rule.id())
        + "  \u00b7  "
        + (rule.action() == null ? "" : rule.action())
        + " \u00b7 "
        + (rule.resource() == null ? "" : rule.resource());
  }

  /**
   * CP-07 AC2: the EDIT route - {@code PATCH /permission/saved/{id}} with the entered action and
   * resource, run off the UI thread like every other write here; a conflict (409) surfaces on the
   * feedback line through the load error path.
   */
  private void edit(SavedPermission rule) {
    String action = prompt("Edit saved permission", "Action (e.g. allow):", rule.action());
    if (action == null) {
      return;
    }
    String resource = prompt("Edit saved permission", "Resource / pattern:", rule.resource());
    if (resource == null) {
      return;
    }
    ViewLoadSupport.load(
        "Updating saved permission",
        () -> {
          client.updateSavedPermission(rule.id(), action, resource);
          return client.listSavedPermissions();
        },
        this::showRows,
        error -> {
          if (!feedback.isDisposed()) {
            feedback.setText("edit failed: " + ViewLoadSupport.message(error));
          }
        });
  }

  /** One value prompt; {@code null} = cancelled (a cancel is never a write). */
  private String prompt(String title, String message, String initial) {
    org.eclipse.jface.dialogs.InputDialog dialog =
        new org.eclipse.jface.dialogs.InputDialog(getShell(), title, message, initial, null);
    int result = dialog.open();
    return result == Window.OK ? dialog.getValue() : null;
  }

  /** Deletes one rule off the UI thread, then refreshes the list from the server. */
  private void remove(String id) {
    ViewLoadSupport.load(
        "Removing saved permission",
        () -> {
          client.deleteSavedPermission(id);
          return client.listSavedPermissions();
        },
        this::showRows,
        error -> {
          if (!feedback.isDisposed()) {
            feedback.setText("remove failed: " + ViewLoadSupport.message(error));
          }
        });
  }
}
