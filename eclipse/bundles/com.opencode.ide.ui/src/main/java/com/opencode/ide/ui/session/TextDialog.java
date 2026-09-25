package com.opencode.ide.ui.session;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Read-only document dialog: shows a fetched service document (session log,
 * session stats, terminal screen) in a scrollable, copyable text area.
 * Wave A (2026-09-25); a thin SWT shell over {@link ServiceText}'s output.
 */
public final class TextDialog extends Dialog {

    private final String title;
    private final String text;

    /** @param text the document to show; null renders as empty */
    public TextDialog(Shell parent, String title, String text) {
        super(parent);
        this.title = title;
        this.text = text == null ? "" : text;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText(title);
        StyledText viewer = new StyledText(area,
                SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.setText(text);
        viewer.setAlwaysShowScrollBars(false);
        GridData layout = new GridData(SWT.FILL, SWT.FILL, true, true);
        layout.widthHint = 720;
        layout.heightHint = 420;
        viewer.setLayoutData(layout);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, Window.OK, "Close", true);
    }
}
