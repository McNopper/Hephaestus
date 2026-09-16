package com.opencode.ide.board.views;

import java.nio.file.Path;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.opencode.ide.board.model.TicketMarkdown;
import com.opencode.ide.chat.MarkdownPage;
import com.opencode.ide.tasks.Task;

/**
 * Read-only ticket details. The ticket renders as ONE markdown document in the
 * chat web component ({@link MarkdownPage}, doc mode) — the same pipeline as
 * the chat, so tables, math and mermaid architecture diagrams stored in
 * tickets render as diagrams. A plain-SWT fallback path covers machines
 * without WebView2. The SWT artifact table + Open button stay: artifact
 * opening goes through {@code ArtifactResolver} (repo confinement), which a
 * rendered link would bypass.
 */
final class TicketDetailsDialog extends Dialog {

    private final Task task;
    private final Path repoRoot;
    private MarkdownPage page;
    private Table artifacts;

    TicketDetailsDialog(Shell parentShell, Task task, Path repoRoot) {
        super(parentShell);
        this.task = task;
        this.repoRoot = repoRoot;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText((task.id == null ? "Ticket" : task.id) + " — " + safe(task.title));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(900, 700);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite body = (Composite) super.createDialogArea(parent);
        body.setLayoutData(new GridData(GridData.FILL_BOTH));
        GridLayout layout = (GridLayout) body.getLayout();
        layout.numColumns = 1;

        Label meta = new Label(body, SWT.WRAP);
        meta.setText(metaLine());
        meta.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        meta.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        page = MarkdownPage.create(body);
        if (page != null) {
            page.load();
            page.setDocument(TicketMarkdown.document(task));
        } else {
            fallbackDocument(body); // no WebView2: plain text, still readable
        }

        Label artifactsTitle = new Label(body, SWT.NONE);
        artifactsTitle.setText("Artifacts");
        artifactsTitle.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        artifactsTitle.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

        artifacts = artifacts(body);
        Button open = new Button(body, SWT.PUSH);
        open.setText("Open artifact");
        open.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        open.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int index = artifacts.getSelectionIndex();
                if (index >= 0 && index < task.artifacts.size()) {
                    openArtifact(task.artifacts.get(index));
                } else {
                    info("Select an artifact first.");
                }
            }
        });
        return body;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // the embedded browser swallows ESC, so an explicit Close button is required
        createButton(parent, OK, "Close", true);
    }

    @Override
    public boolean close() {
        if (page != null) {
            try {
                page.dispose();
            } catch (Throwable ignored) {
                // best-effort during close
            }
            page = null;
        }
        return super.close();
    }

    private String metaLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(task.status));
        // U-005: the type rides the header line too (bug vs feature is the
        // first triage question); blank types (legacy) are simply omitted
        if (task.type != null && !task.type.isBlank()) {
            sb.append("  •  ").append(task.type.trim());
        }
        if (task.sprint != null) {
            sb.append("  •  sprint ").append(task.sprint);
        }
        if (task.role != null) {
            sb.append("  •  ").append(task.role);
        }
        sb.append("  •  ").append(task.storyPoints).append(" pts");
        if (task.assignee != null) {
            sb.append("  •  @").append(task.assignee);
        }
        if (task.blocked) {
            sb.append("  •  BLOCKED: ").append(safe(task.blocker));
        }
        return sb.toString();
    }

    /** Plain-SWT fallback when no embedded browser is available. */
    private void fallbackDocument(Composite parent) {
        Text text = new Text(parent, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
        text.setText(TicketMarkdown.document(task));
        text.setBackground(text.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        text.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
    }

    private void openArtifact(Task.Artifact artifact) {
        String kind = artifact.kind() == null ? "" : artifact.kind();
        String ref = artifact.ref() == null ? "" : artifact.ref();
        if (ref.isBlank()) {
            info("Artifact has no reference.");
            return;
        }
        switch (kind) {
            case "file", "path" -> {
                com.opencode.ide.board.model.ArtifactResolver.Result result =
                        com.opencode.ide.board.model.ArtifactResolver.resolve(repoRoot, ref);
                if (result.openable()) {
                    Program.launch(result.path().toString());
                } else {
                    info(result.refusal());
                }
            }
            case "url", "doc", "git" -> copyToClipboard(ref);
            default -> info("Unsupported artifact kind: " + kind);
        }
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            info("Copied to clipboard:\n" + text);
        } finally {
            clipboard.dispose();
        }
    }

    private Table artifacts(Composite parent) {
        Table table = new Table(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (task.artifacts.isEmpty()) {
            new TableItem(table, SWT.NONE).setText("(no artifacts)");
        }
        for (Task.Artifact artifact : task.artifacts) {
            String line = safe(artifact.kind()) + ":" + safe(artifact.ref());
            if (artifact.note() != null && !artifact.note().isBlank()) {
                line += " — " + artifact.note();
            }
            new TableItem(table, SWT.NONE).setText(line);
        }
        table.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
                int index = table.getSelectionIndex();
                if (index >= 0 && index < task.artifacts.size()) {
                    openArtifact(task.artifacts.get(index));
                }
            }
        });
        return table;
    }

    private void info(String message) {
        MessageDialog.openInformation(getShell(), "Ticket " + safe(task.id), message);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
