package com.opencode.ide.board.views;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** A scrollable, read-only, monospace text dialog (diff output and similar). */
final class TextDialog extends Dialog {

  private final String title;
  private final String text;

  TextDialog(Shell parentShell, String title, String text) {
    super(parentShell);
    this.title = title;
    this.text = text == null ? "" : text;
  }

  @Override
  protected void configureShell(Shell newShell) {
    super.configureShell(newShell);
    newShell.setText(title);
  }

  @Override
  protected boolean isResizable() {
    return true;
  }

  @Override
  protected Point getInitialSize() {
    return new Point(900, 640);
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite body = (Composite) super.createDialogArea(parent);
    body.setLayoutData(new GridData(GridData.FILL_BOTH));
    Text content =
        new Text(
            body, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
    content.setFont(JFaceResources.getFont(JFaceResources.TEXT_FONT));
    content.setText(text);
    content.setBackground(content.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
    content.setLayoutData(new GridData(GridData.FILL_BOTH));
    return body;
  }
}
