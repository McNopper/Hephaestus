package com.opencode.ide.chat.views;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.opencode.ide.client.activity.PermissionRequest;

/**
 * The U-014 in-chat permission dialog: a small modal surfaced from the chat
 * view while THIS session's run waits on a {@code permission.asked} - the
 * same once/always/reject semantics as the ask banner row (and the fleet's
 * permissions dialog), routed through the single
 * {@code com.opencode.ide.chat.ChatPermissionDecision} mapping. The dialog
 * only REPORTS the picked decision through its return code; the answer POST
 * (and the reject feedback prompt) stay in the view's single answer path.
 * Dismissing it without answering leaves the ask on the banner row - the
 * pending state is never lost.
 *
 * <p>Rendering only: opens on the UI thread (SWT's standard modal nested
 * event loop) and never touches the client itself.</p>
 */
final class ChatPermissionDialog extends Dialog {

    /** Return codes of the three decision buttons (jface client-id range). */
    static final int ALLOW_ONCE = IDialogConstants.CLIENT_ID + 1;
    static final int ALLOW_ALWAYS = IDialogConstants.CLIENT_ID + 2;
    static final int REJECT = IDialogConstants.CLIENT_ID + 3;

    private final PermissionRequest request;

    ChatPermissionDialog(Shell parentShell, PermissionRequest request) {
        super(parentShell);
        this.request = request;
    }

    /** @return the answered permission id (used to close a dialog whose ask was answered elsewhere). */
    String requestId() {
        return request.permissionId();
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        // the ask's display text IS the title (U-014): "bash: git push"
        newShell.setText(request.display() == null ? "Permission request" : request.display());
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(540, 220);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = (Composite) super.createDialogArea(parent);
        Label message = new Label(body, SWT.WRAP);
        String patterns = request.patterns().isEmpty()
                ? "" : "\n" + String.join("\n", request.patterns());
        message.setText("The agent is waiting for your permission to continue"
                + (request.display() == null ? "." : ":\n" + request.display() + patterns));
        message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return body;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // same three decisions (and labels) as the banner row - one mapping
        createButton(parent, ALLOW_ONCE, "Allow once", true);
        createButton(parent, ALLOW_ALWAYS, "Allow always", false);
        createButton(parent, REJECT, "Reject\u2026", false);
    }
}
