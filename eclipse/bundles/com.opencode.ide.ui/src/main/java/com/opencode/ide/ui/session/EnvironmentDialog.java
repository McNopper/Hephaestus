package com.opencode.ide.ui.session;

import java.util.Map;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Editor dialog for the session's environment variables (U-048): a plain
 * {@code KEY=VALUE}-per-line text area over {@link EnvironmentText}. The
 * wire has NO read route and the PUT fully replaces the map, so the dialog
 * STATES that truth up front (title-area message) instead of implying a
 * loaded state: the editor starts empty, and saving writes exactly the
 * listed variables — anything not listed is removed on the server.
 */
public final class EnvironmentDialog extends TitleAreaDialog {

    /** The full-replace warning shown above the editor. */
    public static final String FULL_REPLACE_NOTICE =
            "Saving REPLACES the session's whole environment map (v2 PUT /api/session/{id}/environment).\n"
                    + "There is no read route: the editor starts empty, and any variable not listed here is removed.\n"
                    + "One KEY=VALUE pair per line; lines starting with # are comments.";

    private final String sessionId;
    private StyledText editor;
    private Map<String, String> answer;

    /** @param sessionId the session whose environment is replaced on OK */
    public EnvironmentDialog(Shell parent, String sessionId) {
        super(parent);
        this.sessionId = sessionId == null ? "?" : sessionId;
    }

    /** The edited variables after OK; {@code null} when cancelled. */
    public Map<String, String> answer() {
        return answer;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        getShell().setText("Session environment");
        setTitle("Session environment — " + sessionId);
        setMessage(FULL_REPLACE_NOTICE);
        editor = new StyledText(area, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        editor.setText("");
        GridData layout = new GridData(SWT.FILL, SWT.FILL, true, true);
        layout.widthHint = 640;
        layout.heightHint = 320;
        editor.setLayoutData(layout);
        return area;
    }

    @Override
    protected void okPressed() {
        try {
            answer = Map.copyOf(EnvironmentText.parse(editor.getText()));
        } catch (IllegalArgumentException e) {
            setErrorMessage(e.getMessage());
            return;
        }
        super.okPressed();
    }
}
