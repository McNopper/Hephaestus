package com.opencode.ide.ui.views;

import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import com.opencode.ide.ui.model.SkillRows;

/**
 * Picker for the session's "Attach skill" action (U-046 slice 2): lists the
 * skills of the session's location (name + description) and returns the
 * chosen row's attach id - exactly what the experimental attach POST sends
 * in its {@code skill} body field. Follows {@link McpServersDialog}'s
 * hand-rolled table pattern; the rows come prebuilt from {@link SkillRows}.
 */
public final class SkillPickerDialog extends Dialog {

    private final List<SkillRows.Row> rows;
    private Table table;
    private String selected;

    /** @param rows the prebuilt, sorted picker rows (may be empty) */
    public SkillPickerDialog(Shell parent, List<SkillRows.Row> rows) {
        super(parent);
        this.rows = rows == null ? List.of() : rows;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText("Attach skill");
        table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        TableColumn name = new TableColumn(table, SWT.NONE);
        name.setText("Skill");
        name.setWidth(220);
        TableColumn description = new TableColumn(table, SWT.NONE);
        description.setText("Description");
        description.setWidth(420);
        for (SkillRows.Row row : rows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, row.name());
            String text = row.descriptionLabel();
            if (text != null) {
                item.setText(1, text);
            }
            item.setData(row.attachId()); // the payload id travels with the row
        }
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // double-click accepts the picked row
        table.addListener(SWT.DefaultSelection, event -> okPressed());
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, Window.OK, "Attach", true);
        createButton(parent, Window.CANCEL, "Cancel", false);
        getShell().setDefaultButton(getButton(Window.OK));
    }

    @Override
    protected void okPressed() {
        TableItem[] selection = table.getSelection();
        if (selection.length == 0) {
            return; // nothing picked - OK stays inert (the list names the targets)
        }
        Object attachId = selection[0].getData();
        selected = attachId instanceof String id ? id : null;
        super.okPressed();
    }

    /**
     * @return the attach id of the picked skill after {@link #open()} answered
     *         {@link Window#OK}; {@code null} when cancelled or nothing was
     *         picked
     */
    public String selectedSkillId() {
        return selected;
    }
}
