package com.opencode.ide.ui.preferences;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.OpencodePreferences;

/**
 * Preferences for the connection to the opencode server: the primary
 * connection (spawn or connect mode) plus a list of <b>remote</b> servers
 * consumed by the {@link ConnectionsManager}. Remote passwords are stored
 * encrypted via Eclipse secure storage (see {@link OpencodePreferences});
 * the list widget only shows masked labels.
 */
public class ConnectionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private OpencodePreferences prefs;

    private Combo modeCombo;
    private Text urlText;
    private Text userText;
    private Text passwordText;
    private Label binaryLabel;
    private Text binaryText;
    private Button browseButton;
    private Label hostnameLabel;
    private Text hostnameText;
    private Label spawnHint;
    private Button attachServiceButton;
    private Label workdirLabel;
    private Text workdirText;
    private Text defaultModelText;
    private Text defaultVariantText;
    private Text tasksRootText;
    private Text tasksProjectText;
    private org.eclipse.swt.widgets.List remoteList;
    /** Remote entries as {@link ConnectionConfig}s; the widget only shows masked labels. */
    private final List<ConnectionConfig> remoteConfigs = new ArrayList<>();

    @Override
    public void init(IWorkbench workbench) {
        // no-op
    }

    @Override
    protected Control createContents(Composite parent) {
        prefs = new OpencodePreferences();

        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        composite.setLayoutData(new GridData(GridData.FILL_BOTH));

        createPrimaryGroup(composite);
        createRemotesGroup(composite);

        load();
        return composite;
    }

    private void createPrimaryGroup(Composite composite) {
        Group connectGroup = new Group(composite, SWT.NONE);
        connectGroup.setText("opencode server");
        connectGroup.setLayout(new GridLayout(2, false));
        connectGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label modeLabel = new Label(connectGroup, SWT.NONE);
        modeLabel.setText("Mode:");
        modeCombo = new Combo(connectGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        modeCombo.add(OpencodePreferences.MODE_CONNECT);
        modeCombo.add(OpencodePreferences.MODE_SPAWN);
        modeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label urlLabel = new Label(connectGroup, SWT.NONE);
        urlLabel.setText("Server URL:");
        urlText = new Text(connectGroup, SWT.BORDER);
        urlText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label userLabel = new Label(connectGroup, SWT.NONE);
        userLabel.setText("Username:");
        userText = new Text(connectGroup, SWT.BORDER);
        userText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label passwordLabel = new Label(connectGroup, SWT.NONE);
        passwordLabel.setText("Password:");
        passwordText = new Text(connectGroup, SWT.BORDER | SWT.PASSWORD);
        passwordText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // spawn knobs (used only in SPAWN mode; enablement follows the mode combo)
        binaryLabel = new Label(connectGroup, SWT.NONE);
        binaryLabel.setText("opencode binary:");
        Composite binaryRow = new Composite(connectGroup, SWT.NONE);
        GridLayout binaryRowLayout = new GridLayout(2, false);
        binaryRowLayout.marginWidth = 0;
        binaryRowLayout.marginHeight = 0;
        binaryRow.setLayout(binaryRowLayout);
        binaryRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        binaryText = new Text(binaryRow, SWT.BORDER);
        binaryText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        browseButton = new Button(binaryRow, SWT.PUSH);
        browseButton.setText("Browse...");
        browseButton.addListener(SWT.Selection, e -> browseBinary());

        hostnameLabel = new Label(connectGroup, SWT.NONE);
        hostnameLabel.setText("Spawn hostname:");
        hostnameText = new Text(connectGroup, SWT.BORDER);
        hostnameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        spawnHint = new Label(connectGroup, SWT.WRAP);
        spawnHint.setText("Spawn settings apply in Spawn mode only. Leave the binary "
                + "empty to auto-detect 'opencode' on the PATH.");
        spawnHint.setLayoutData(span(2));

        attachServiceButton = new Button(connectGroup, SWT.CHECK);
        attachServiceButton.setText("Attach to the shared opencode service (v2)");
        attachServiceButton.setToolTipText("Join the per-user background service every v2 client "
                + "(TUI, CLI) shares, instead of always spawning a private 'opencode serve'. "
                + "Falls back to spawn when the service cannot be discovered or started.");
        attachServiceButton.setLayoutData(span(2));

        modeCombo.addListener(SWT.Selection, e -> updateSpawnEnablement());

        // spawn working directory: the repo whose .opencode/ carries agents/skills/MCP config
        workdirLabel = new Label(connectGroup, SWT.NONE);
        workdirLabel.setText("Working directory:");
        Composite workdirRow = new Composite(connectGroup, SWT.NONE);
        GridLayout workdirRowLayout = new GridLayout(2, false);
        workdirRowLayout.marginWidth = 0;
        workdirRowLayout.marginHeight = 0;
        workdirRow.setLayout(workdirRowLayout);
        workdirRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        workdirText = new Text(workdirRow, SWT.BORDER);
        workdirText.setToolTipText("Repository the spawned server runs in (loads its .opencode/ agents, skills and MCP config). An open CDT project still wins.");
        workdirText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button workdirBrowse = new Button(workdirRow, SWT.PUSH);
        workdirBrowse.setText("Browse...");
        workdirBrowse.addListener(SWT.Selection, e -> browseDirectory(workdirText));

        Label hint = new Label(connectGroup, SWT.WRAP);
        hint.setText("Spawn mode (default): the plugin starts and manages its own "
                + "'opencode serve' process automatically when you open the OpenCode "
                + "perspective. Switch to Connect mode to use a server you started "
                + "yourself (then set Server URL/Username/Password).");
        hint.setLayoutData(span(2));
    }

    private void createRemotesGroup(Composite composite) {
        // ---- chat defaults + task board defaults (compact, no dialogs) ----
        Group defaultsGroup = new Group(composite, SWT.NONE);
        defaultsGroup.setText("Defaults");
        defaultsGroup.setLayout(new GridLayout(2, false));
        defaultsGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label modelLabel = new Label(defaultsGroup, SWT.NONE);
        modelLabel.setText("Default chat model:");
        defaultModelText = new Text(defaultsGroup, SWT.BORDER);
        defaultModelText.setToolTipText("provider/model — preselected in the Chat view when it exists on the server");
        defaultModelText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label variantLabel = new Label(defaultsGroup, SWT.NONE);
        variantLabel.setText("Default variant:");
        defaultVariantText = new Text(defaultsGroup, SWT.BORDER);
        defaultVariantText.setToolTipText("Reasoning variant for the default model (e.g. max); empty = model default");
        defaultVariantText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label tasksRootLabel = new Label(defaultsGroup, SWT.NONE);
        tasksRootLabel.setText("Task store root:");
        Composite tasksRow = new Composite(defaultsGroup, SWT.NONE);
        GridLayout tasksRowLayout = new GridLayout(2, false);
        tasksRowLayout.marginWidth = 0;
        tasksRowLayout.marginHeight = 0;
        tasksRow.setLayout(tasksRowLayout);
        tasksRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        tasksRootText = new Text(tasksRow, SWT.BORDER);
        tasksRootText.setToolTipText("<repo>/.opencode/tasks — the Board view's store (fallback when the workspace is elsewhere)");
        tasksRootText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button tasksBrowse = new Button(tasksRow, SWT.PUSH);
        tasksBrowse.setText("Browse...");
        tasksBrowse.addListener(SWT.Selection, e -> browseDirectory(tasksRootText));

        Label projectLabel = new Label(defaultsGroup, SWT.NONE);
        projectLabel.setText("Board project:");
        tasksProjectText = new Text(defaultsGroup, SWT.BORDER);
        tasksProjectText.setToolTipText("Task-store project the Board view shows by default");
        tasksProjectText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Group remotesGroup = new Group(composite, SWT.NONE);
        remotesGroup.setText("Remote connections");
        remotesGroup.setLayout(new GridLayout(2, false));
        remotesGroup.setLayoutData(new GridData(GridData.FILL_BOTH));

        Label hint = new Label(remotesGroup, SWT.WRAP);
        hint.setText("Additional remote 'opencode serve' servers (connect mode), shown "
                + "as extra roots in the Server view. Entries are stored as url[|user]; "
                + "passwords are kept encrypted in Eclipse secure storage. Blank/invalid "
                + "lines are ignored.");
        hint.setLayoutData(span(2));

        remoteList = new org.eclipse.swt.widgets.List(remotesGroup,
                SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData listData = new GridData(GridData.FILL_BOTH);
        listData.widthHint = 320;
        listData.heightHint = 80;
        remoteList.setLayoutData(listData);

        Composite buttons = new Composite(remotesGroup, SWT.NONE);
        GridLayout buttonLayout = new GridLayout(1, true);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttons.setLayout(buttonLayout);
        buttons.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));

        Button addButton = new Button(buttons, SWT.PUSH);
        addButton.setText("Add...");
        addButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        addButton.addListener(SWT.Selection, e -> addRemote());

        Button removeButton = new Button(buttons, SWT.PUSH);
        removeButton.setText("Remove");
        removeButton.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        removeButton.addListener(SWT.Selection, e -> {
            int[] selection = remoteList.getSelectionIndices();
            for (int i = selection.length - 1; i >= 0; i--) {
                if (selection[i] < remoteConfigs.size()) {
                    remoteConfigs.remove(selection[i]);
                }
            }
            remoteList.remove(selection);
        });
    }

    /** Display label that never reveals the password: {@code url — user •••}. */
    private static String masked(ConnectionConfig config) {
        StringBuilder label = new StringBuilder(config.baseUrl().toString());
        if (config.username() != null && !config.username().isBlank()) {
            label.append(" — ").append(config.username());
        }
        if (config.password() != null) {
            label.append(" •••");
        }
        return label.toString();
    }

    private void addRemote() {
        RemoteConnectionDialog dialog = new RemoteConnectionDialog(getShell());
        if (dialog.open() == IDialogConstants.OK_ID && dialog.config() != null) {
            remoteConfigs.add(dialog.config());
            remoteList.add(masked(dialog.config()));
            remoteList.select(remoteList.getItemCount() - 1);
        }
    }

    private static GridData span(int horizontalSpan) {
        GridData data = new GridData(GridData.FILL_HORIZONTAL);
        data.horizontalSpan = horizontalSpan;
        data.widthHint = 200;
        return data;
    }

    /** Spawn widgets track the mode combo: enabled in SPAWN mode, dimmed in CONNECT mode. */
    private void updateSpawnEnablement() {
        boolean spawn = OpencodePreferences.MODE_SPAWN.equals(modeCombo.getText());
        binaryLabel.setEnabled(spawn);
        binaryText.setEnabled(spawn);
        browseButton.setEnabled(spawn);
        hostnameLabel.setEnabled(spawn);
        hostnameText.setEnabled(spawn);
        workdirLabel.setEnabled(spawn);
        workdirText.setEnabled(spawn);
        spawnHint.setEnabled(spawn);
        attachServiceButton.setEnabled(spawn);
    }

    /** File dialog for the opencode binary path (empty field = auto-detect). */
    private void browseBinary() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Locate the opencode binary");
        String path = dialog.open();
        if (path != null) {
            binaryText.setText(path);
        }
    }

    /** Directory dialog helper for the working-directory / task-store fields. */
    private void browseDirectory(Text target) {
        org.eclipse.swt.widgets.DirectoryDialog dialog =
                new org.eclipse.swt.widgets.DirectoryDialog(getShell(), SWT.OPEN);
        dialog.setText("Select directory");
        String path = dialog.open();
        if (path != null) {
            target.setText(path);
        }
    }

    private void load() {
        modeCombo.setText(prefs.getMode());
        urlText.setText(prefs.getServerUrl());
        userText.setText(prefs.getUsername());
        passwordText.setText(prefs.getPassword());
        binaryText.setText(prefs.getOpencodeBinary());
        hostnameText.setText(prefs.getSpawnHostname());
        attachServiceButton.setSelection(prefs.isAttachSharedService());
        workdirText.setText(prefs.getWorkingDirectory());
        defaultModelText.setText(prefs.getDefaultModel());
        defaultVariantText.setText(prefs.getDefaultVariant());
        tasksRootText.setText(prefs.getTasksRoot());
        tasksProjectText.setText(prefs.getTasksProject());
        remoteList.removeAll();
        remoteConfigs.clear();
        for (ConnectionConfig config : prefs.getRemoteConnectionConfigs()) {
            remoteConfigs.add(config);
            remoteList.add(masked(config));
        }
        updateSpawnEnablement();
    }

    @Override
    protected void performDefaults() {
        modeCombo.setText(OpencodePreferences.MODE_SPAWN);
        urlText.setText("http://127.0.0.1:4096");
        userText.setText("opencode");
        passwordText.setText("");
        binaryText.setText("");
        hostnameText.setText("127.0.0.1");
        attachServiceButton.setSelection(true);
        workdirText.setText("C:\\Development\\GitHub\\Hephaestus");
        defaultModelText.setText("kimi-code-plan-global/k3-256k");
        defaultVariantText.setText("max");
        tasksRootText.setText("C:\\Development\\GitHub\\Hephaestus\\.opencode\\tasks");
        tasksProjectText.setText("hephaestus");
        remoteList.removeAll();
        remoteConfigs.clear();
        updateSpawnEnablement();
    }

    @Override
    public boolean performOk() {
        prefs.setMode(modeCombo.getText());
        prefs.setServerUrl(urlText.getText().trim());
        prefs.setUsername(userText.getText().trim());
        prefs.setPassword(passwordText.getText());
        prefs.setOpencodeBinary(binaryText.getText().trim());
        String hostname = hostnameText.getText().trim();
        prefs.setSpawnHostname(hostname.isEmpty() ? "127.0.0.1" : hostname);
        prefs.setAttachSharedService(attachServiceButton.getSelection());
        prefs.setWorkingDirectory(workdirText.getText().trim());
        prefs.setDefaultModel(defaultModelText.getText().trim());
        prefs.setDefaultVariant(defaultVariantText.getText().trim());
        prefs.setTasksRoot(tasksRootText.getText().trim());
        prefs.setTasksProject(tasksProjectText.getText().trim());
        prefs.setRemoteConnectionConfigs(List.copyOf(remoteConfigs));
        try {
            prefs.save();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            com.opencode.ide.ui.internal.UiActivator.getDefault().getLog().log(
                    new org.eclipse.core.runtime.Status(
                            org.eclipse.core.runtime.Status.ERROR,
                            com.opencode.ide.ui.internal.UiActivator.PLUGIN_ID,
                            "Could not save opencode preferences", e));
            return false;
        }
        OpencodeConnection.getInstance().refresh();
        ConnectionsManager.getDefault().refresh();
        return true;
    }

    /** Minimal url/user/password entry dialog; validates the entry before OK. */
    private static final class RemoteConnectionDialog extends TitleAreaDialog {
        private Text urlField;
        private Text userField;
        private Text passwordField;
        private ConnectionConfig config;

        RemoteConnectionDialog(Shell parentShell) {
            super(parentShell);
        }

        @Override
        protected void configureShell(Shell newShell) {
            super.configureShell(newShell);
            newShell.setText("Add Remote Connection");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            setTitle("Add Remote Connection");
            setMessage("Connect-mode 'opencode serve' server shown as an extra Server view root.");

            Composite composite = new Composite(area, SWT.NONE);
            composite.setLayout(new GridLayout(2, false));
            composite.setLayoutData(new GridData(GridData.FILL_BOTH));

            Label urlLabel = new Label(composite, SWT.NONE);
            urlLabel.setText("Server URL:");
            urlField = new Text(composite, SWT.BORDER);
            urlField.setText("http://127.0.0.1:4097");
            urlField.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            Label userLabel = new Label(composite, SWT.NONE);
            userLabel.setText("Username:");
            userField = new Text(composite, SWT.BORDER);
            userField.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            Label passwordLabel = new Label(composite, SWT.NONE);
            passwordLabel.setText("Password:");
            passwordField = new Text(composite, SWT.BORDER | SWT.PASSWORD);
            passwordField.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            return area;
        }

        @Override
        protected void okPressed() {
            String url = urlField.getText().trim();
            String user = userField.getText().trim();
            String password = passwordField.getText();
            if (user.indexOf('|') >= 0 || user.indexOf('\n') >= 0 || user.indexOf('\r') >= 0) {
                setErrorMessage("Username must not contain '|' or line breaks.");
                return;
            }
            if (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
                setErrorMessage("Password must not contain line breaks.");
                return;
            }
            try {
                config = new ConnectionConfig(URI.create(url),
                        user.isEmpty() ? null : user,
                        password.isEmpty() ? null : password);
            } catch (RuntimeException e) {
                setErrorMessage("Invalid server URL: " + e.getMessage());
                return;
            }
            super.okPressed();
        }

        ConnectionConfig config() {
            return config;
        }
    }
}
