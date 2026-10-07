package com.opencode.ide.board.views;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.LinkedHashMap;
import java.util.Map;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.tasks.VStages;
import com.opencode.ide.board.model.DispatchPolicyStore;
import com.opencode.ide.board.model.DispatchPolicyStore.DispatchSettings;

/**
 * The dispatch policy editor (the Board's "Dispatch settings…" toolbar
 * action): the three {@link AutoDispatch} values, the bootstrap agent and
 * command, and the per-stage model policy (U-072: one model per V-stage,
 * blank = server default). Validation errors surface in a red status line and keep the dialog
 * open; Save persists through the {@link DispatchPolicyStore} — both
 * auto-dispatch actions re-read the store at action time, so edits apply
 * without a restart. The dialog only renders and saves; every rule lives in
 * the store (SWT-free, unit-tested).
 */
final class DispatchSettingsDialog extends Dialog {

    private final DispatchPolicyStore store;

    private Text maxConcurrentText;
    private Text costBudgetText;
    private Button includeStaleButton;
    private Text bootstrapAgentText;
    private Text bootstrapCommandText;
    /** One model field per V-stage (insertion order = the V ladder). */
    private final Map<String, Text> stageModelTexts = new LinkedHashMap<>();
    private Label statusLine;

    DispatchSettingsDialog(Shell parentShell, DispatchPolicyStore store) {
        super(parentShell);
        this.store = store;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Dispatch settings");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = (Composite) super.createDialogArea(parent);
        body.setLayoutData(new GridData(GridData.FILL_BOTH));
        GridLayout layout = (GridLayout) body.getLayout();
        layout.numColumns = 2;

        DispatchSettings current = store.load();
        maxConcurrentText = field(body, "Max concurrent:",
                String.valueOf(current.policy().maxConcurrent()), 120,
                "Fleet sessions the dispatcher may run at once (>= 1)");
        costBudgetText = field(body, "Cost budget (USD):",
                String.valueOf(current.policy().costBudgetUsd()), 120,
                "Recorded spend the dispatch budget counts; 0 (or blank) = unlimited");
        Label staleLabel = new Label(body, SWT.NONE);
        staleLabel.setText("Include STALE re-runs:");
        staleLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        includeStaleButton = new Button(body, SWT.CHECK);
        includeStaleButton.setText("re-run tickets whose upstream changed (rework before new work)");
        includeStaleButton.setSelection(current.policy().includeStale());
        includeStaleButton.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        bootstrapAgentText = field(body, "Bootstrap agent:", current.bootstrapAgent(), 340,
                "Agent context for the bootstrap shell call (blank = server default)");
        bootstrapCommandText = field(body, "Bootstrap command:", current.bootstrapCommand(), 340,
                "Shell command run in every new fleet session before the prompt (blank = none)");

        // U-072: the per-stage model policy - one row per V-stage, blank = server default
        Label stageHeader = new Label(body, SWT.NONE);
        stageHeader.setText("Stage models (provider/model#variant; blank = server default):");
        stageHeader.setToolTipText("Which model each V-stage's workers run on - consulted as the "
                + "last launch fallback (per-run and per-ticket overrides still win)");
        stageHeader.setLayoutData(spanning(new GridData(SWT.BEGINNING, SWT.CENTER, true, false)));
        Map<String, String> stageModels = store.loadStageModels();
        for (String stage : VStages.STAGES) {
            Label stageLabel = new Label(body, SWT.NONE);
            stageLabel.setText(stage + ":");
            stageLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
            Text stageText = new Text(body, SWT.BORDER);
            stageText.setText(stageModels.getOrDefault(stage, ""));
            stageText.setToolTipText("model for V-stage " + stage + " - e.g. z.ai/glm-4.7#high "
                    + "(blank keeps the server default for this stage)");
            stageText.setLayoutData(fixedWidth(340));
            stageModelTexts.put(stage, stageText);
        }

        statusLine = new Label(body, SWT.WRAP);
        statusLine.setLayoutData(spanning(new GridData(SWT.FILL, SWT.CENTER, true, false)));
        return body;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, OK, "Save", true);
        createButton(parent, CANCEL, "Cancel", false);
    }

    @Override
    protected void okPressed() {
        Integer maxConcurrent = parseWhole(maxConcurrentText.getText());
        if (maxConcurrent == null || maxConcurrent < 1) {
            error("Max concurrent must be a whole number >= 1.");
            return;
        }
        Double budget = parseBudget(costBudgetText.getText());
        if (budget == null) {
            error("Cost budget must be a number >= 0 (0 = unlimited).");
            return;
        }
        DispatchSettings settings = new DispatchSettings(
                AutoDispatch.of(maxConcurrent, budget, includeStaleButton.getSelection()),
                bootstrapAgentText.getText(),
                bootstrapCommandText.getText());
        Map<String, String> stageModels = new LinkedHashMap<>();
        for (Map.Entry<String, Text> entry : stageModelTexts.entrySet()) {
            String value = entry.getValue().getText().strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!DispatchPolicyStore.plausibleModel(value)) {
                error("Stage " + entry.getKey() + ": '" + value
                        + "' must be provider/model or provider/model#variant.");
                return;
            }
            stageModels.put(entry.getKey(), value);
        }
        try {
            store.save(settings);
            store.saveStageModels(stageModels);
        } catch (RuntimeException e) {
            error("Cannot save: " + e.getMessage());
            return;
        }
        super.okPressed();
    }

    private Text field(Composite body, String label, String value, int width, String tooltip) {
        Label fieldLabel = new Label(body, SWT.NONE);
        fieldLabel.setText(label);
        fieldLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        Text text = new Text(body, SWT.BORDER);
        text.setText(value == null ? "" : value);
        text.setToolTipText(tooltip);
        text.setLayoutData(fixedWidth(width));
        return text;
    }

    private void error(String message) {
        statusLine.setText(message);
        statusLine.setForeground(statusLine.getDisplay().getSystemColor(SWT.COLOR_RED));
    }

    private static GridData fixedWidth(int widthHint) {
        GridData data = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        data.widthHint = widthHint;
        return data;
    }

    private static GridData spanning(GridData data) {
        data.horizontalSpan = 2;
        return data;
    }

    private static Integer parseWhole(String raw) {
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A finite {@code double >= 0}; blank (no budget entered) reads as 0 = unlimited. */
    private static Double parseBudget(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0d;
        }
        try {
            double value = Double.parseDouble(raw.strip());
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
