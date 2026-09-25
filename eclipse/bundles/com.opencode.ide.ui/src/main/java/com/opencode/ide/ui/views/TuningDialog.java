package com.opencode.ide.ui.views;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

import com.opencode.ide.client.RuntimeTuning;

/**
 * Worker tuning (user requirement 2026-09-23: "adjust the sleep and idle
 * time somewhere" while shells and agents run). Three knobs, applied LIVE on
 * OK — every poller and the fleet watchdog read {@link RuntimeTuning} per
 * tick, so running workers pick changes up immediately. Session-local
 * (defaults restore with one button).
 */
public class TuningDialog extends Dialog {

    private Spinner pollSeconds;
    private Spinner stallMinutes;
    private Spinner budgetMinutes;
    private Spinner workerThreads;

    public TuningDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Worker Tuning (applies live) - " + com.opencode.ide.client.WorkerPools.summary());
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 8;
        layout.marginWidth = 8;
        body.setLayout(layout);

        Label intro = new Label(body, SWT.WRAP);
        intro.setText("Adjustable while workers run - pollers and the fleet watchdog read these live. "
                + com.opencode.ide.client.WorkerPools.summary()
                + " (queue depth + load; live per-job detail: the Progress view).");
        GridData introData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        intro.setLayoutData(introData);

        pollSeconds = spinner(body, "Worker poll sleep (s):",
                (int) Math.max(1, RuntimeTuning.pollMillis() / 1000), 1, 600,
                "How often workers and panels re-check for changes (fleet probe tick + view refresh)");
        stallMinutes = spinner(body, "Idle/stall abort (min):",
                (int) RuntimeTuning.stallTimeout().toMinutes(), 1, 120,
                "A session silent this long is aborted as hung (the PT5M knob)");
        budgetMinutes = spinner(body, "No-progress budget (min):",
                (int) RuntimeTuning.ticketBudget().toMinutes(), 1, 1440,
                "A run with no observable progress is stopped after this (the PT30M knob); progress resets the window");
        workerThreads = spinner(body, "Worker threads:",
                RuntimeTuning.workerThreads(), 1, 64,
                "The shared bounded pool size - fleet runs and chores share it; no thread-per-task");
        return body;
    }

    private Spinner spinner(Composite body, String label, int initial, int min, int max, String tooltip) {
        Label name = new Label(body, SWT.NONE);
        name.setText(label);
        name.setToolTipText(tooltip);
        Spinner spinner = new Spinner(body, SWT.BORDER);
        spinner.setMinimum(min);
        spinner.setMaximum(max);
        spinner.setSelection(Math.min(Math.max(initial, min), max));
        spinner.setToolTipText(tooltip);
        return spinner;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button defaults = createButton(parent, 2, "Restore Defaults", false);
        defaults.addListener(SWT.Selection, e -> {
            pollSeconds.setSelection(1);
            stallMinutes.setSelection(5);
            budgetMinutes.setSelection(30);
            workerThreads.setSelection(8);
        });
        createButton(parent, IDialogConstants.OK_ID, "Apply", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
    }

    @Override
    protected void okPressed() {
        RuntimeTuning.setPollMillis(pollSeconds.getSelection() * 1000L);
        RuntimeTuning.setStallTimeout(java.time.Duration.ofMinutes(stallMinutes.getSelection()));
        RuntimeTuning.setTicketBudget(java.time.Duration.ofMinutes(budgetMinutes.getSelection()));
        RuntimeTuning.setWorkerThreads(workerThreads.getSelection());
        com.opencode.ide.client.WorkerPools.resize(RuntimeTuning.workerThreads());
        super.okPressed();
    }
}
