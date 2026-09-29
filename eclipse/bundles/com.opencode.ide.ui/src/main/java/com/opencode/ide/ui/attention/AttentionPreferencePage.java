package com.opencode.ide.ui.attention;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.opencode.ide.ui.internal.UiActivator;

/**
 * Minimal U-047 page: the two attention checkboxes (enabled, sound) on the
 * plugin's instance-scope node. Deliberately tiny — the defaults and the
 * gating logic live in {@link AttentionPreferences} /
 * {@link AttentionNotifications}; this page only makes them discoverable in
 * the Eclipse Preferences dialog (nested under the existing "OpenCode" page).
 */
public class AttentionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enabledButton;
    private Button soundButton;

    private AttentionPreferences prefs;

    @Override
    public void init(IWorkbench workbench) {
        // no-op
    }

    @Override
    protected Control createContents(Composite parent) {
        prefs = AttentionPreferences.forPlugin();

        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(GridData.FILL_BOTH));

        enabledButton = new Button(composite, SWT.CHECK);
        enabledButton.setText("Desktop notifications for attention events");
        enabledButton.setToolTipText("Permission asks, session errors and finished sessions "
                + "pop up as desktop notifications (U-047, TUI attention parity). Off by default.");
        enabledButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        soundButton = new Button(composite, SWT.CHECK);
        soundButton.setText("Play a sound with each notification");
        soundButton.setToolTipText("A soft system beep accompanies every attention popup "
                + "(only meaningful when notifications are enabled).");
        soundButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label hint = new Label(composite, SWT.WRAP);
        hint.setText("Applies to the primary opencode connection's event stream. "
                + "Events arrive live; no restart is needed after enabling.");
        hint.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        load();
        return composite;
    }

    private void load() {
        enabledButton.setSelection(prefs.isEnabled());
        soundButton.setSelection(prefs.isSoundEnabled());
        soundButton.setEnabled(enabledButton.getSelection());
        enabledButton.addListener(SWT.Selection,
                e -> soundButton.setEnabled(enabledButton.getSelection()));
    }

    @Override
    protected void performDefaults() {
        enabledButton.setSelection(AttentionPreferences.DEFAULT_ENABLED);
        soundButton.setSelection(AttentionPreferences.DEFAULT_SOUND);
        soundButton.setEnabled(AttentionPreferences.DEFAULT_ENABLED);
    }

    @Override
    public boolean performOk() {
        prefs.setEnabled(enabledButton.getSelection());
        prefs.setSoundEnabled(soundButton.getSelection());
        try {
            prefs.save();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            UiActivator activator = UiActivator.getDefault();
            if (activator != null) {
                activator.getLog().log(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.Status.ERROR, UiActivator.PLUGIN_ID,
                        "Could not save attention preferences", e));
            }
            return false;
        }
        return true;
    }
}
