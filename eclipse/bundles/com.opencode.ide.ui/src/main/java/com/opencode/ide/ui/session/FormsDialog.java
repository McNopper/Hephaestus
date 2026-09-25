package com.opencode.ide.ui.session;

import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Schema-driven dialog for one v2 form (capability alignment 2026-09-25).
 * The controls come from the DECLARED {@link FormSchema.Field}s - we never
 * guess the shape; hidden fields are skipped, required fields are validated
 * on OK, and {@link #answer()} returns values typed by the declared field
 * types.
 */
public final class FormsDialog extends TitleAreaDialog {

    private final String title;
    private final List<FormSchema.Field> fields;
    private final Map<String, Control> controls = new java.util.LinkedHashMap<>();
    private Map<String, Object> answer = Map.of();

    public FormsDialog(Shell parent, String title, List<FormSchema.Field> fields) {
        super(parent);
        this.title = title == null || title.isBlank() ? "Form" : title;
        this.fields = fields;
    }

    /** The typed answer after OK; empty when the dialog was cancelled. */
    public Map<String, Object> answer() {
        return answer;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        getShell().setText(title);
        setTitle(title);
        setMessage("Answer the form fields (shaped by the service's own schema)");
        Composite body = new Composite(parent, SWT.NONE);
        body.setLayout(new GridLayout(2, false));
        body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (FormSchema.Field field : fields) {
            if (field.hidden()) {
                continue;
            }
            Label label = new Label(body, SWT.NONE);
            label.setText(field.title() == null || field.title().isBlank() ? field.key() : field.title());
            if (field.options() != null && !field.options().isEmpty()) {
                Combo combo = new Combo(body, SWT.DROP_DOWN);
                combo.setItems(field.options().toArray(new String[0]));
                controls.put(field.key(), combo);
            } else if ("boolean".equals(field.type())) {
                org.eclipse.swt.widgets.Button check = new org.eclipse.swt.widgets.Button(body, SWT.CHECK);
                controls.put(field.key(), check);
            } else {
                Text text = new Text(body, SWT.BORDER);
                text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                if (field.url() != null && !field.url().isBlank()) {
                    // external fields are service-filled: show, do not edit
                    text.setText(field.url());
                    text.setEditable(false);
                }
                controls.put(field.key(), text);
            }
        }
        return body;
    }

    @Override
    protected void okPressed() {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Control> entry : controls.entrySet()) {
            Control control = entry.getValue();
            if (control instanceof Text text) {
                raw.put(entry.getKey(), text.getText());
            } else if (control instanceof Combo combo) {
                raw.put(entry.getKey(), combo.getText());
            } else if (control instanceof org.eclipse.swt.widgets.Button check) {
                raw.put(entry.getKey(), String.valueOf(check.getSelection()));
            }
        }
        List<String> missing = FormSchema.missingRequired(fields, raw);
        if (!missing.isEmpty()) {
            setErrorMessage("Required: " + String.join(", ", missing));
            return;
        }
        answer = FormSchema.answer(fields, raw);
        super.okPressed();
    }
}
