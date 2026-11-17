package com.opencode.ide.chat.session;

import com.opencode.ide.client.model.Session;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * The in-chat session switcher (CP-05 / FR-CP-05): the location's recent sessions with the three
 * actions the FR names - <b>switch</b> (rebinds the OPEN chat in place through the caller, never a
 * new view per switch), <b>pin/unpin</b> and <b>delete</b>.
 *
 * <p>Presentation only: the session list, the bound session and the pinned set arrive from the
 * caller, and every action is handed back through {@link Handler} - the controller owns the verbs
 * ({@code resume}/{@code deleteSession}) and the settings own the pin. The bound session defaults
 * to the selection, so the two AC2 actions are one click away for it.
 */
public class SessionsDialog extends Dialog {

  /** What the dialog asks the caller to do (the caller owns the verbs, this class owns none). */
  public interface Handler {
    /** Rebinds the open chat to {@code sessionId} (FR-CP-05: in place, no new view). */
    void switchTo(String sessionId);

    /** Flips the host-side pin marker for {@code sessionId} (FR-CP-05 AC2). */
    void togglePin(String sessionId);

    /** Deletes {@code sessionId} (FR-CP-05 AC2); the caller unbinds it if it was the bound one. */
    void delete(String sessionId);
  }

  private final List<Session> sessions;
  private final String boundSessionId;
  private final Set<String> pinned;
  private final Handler handler;
  private TableViewer viewer;
  private String chosen;

  private static final DateTimeFormatter UPDATED =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

  public SessionsDialog(
      Shell parentShell,
      List<Session> sessions,
      String boundSessionId,
      Set<String> pinned,
      Handler handler) {
    super(parentShell);
    this.sessions = sessions == null ? List.of() : sessions;
    this.boundSessionId = boundSessionId;
    this.pinned = pinned == null ? Set.of() : pinned;
    this.handler = handler;
    setShellStyle(getShellStyle() | SWT.RESIZE);
  }

  /**
   * The session the caller should treat as switched to; {@code null} when the dialog was Cancel.
   */
  public String getChosenSessionId() {
    return chosen;
  }

  @Override
  protected void configureShell(Shell newShell) {
    super.configureShell(newShell);
    newShell.setText("Switch session");
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite area = (Composite) super.createDialogArea(parent);
    Composite container = new Composite(area, SWT.NONE);
    container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    container.setLayout(new GridLayout(1, false));

    viewer = new TableViewer(container, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
    Table table = viewer.getTable();
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    createColumn(viewer, "", 0, row -> pinned.contains(idOf(row)) ? "\u25CF" : "");
    createColumn(viewer, "Title", 3, row -> titleOf(row));
    createColumn(viewer, "Updated", 1, row -> updatedOf(row));
    createColumn(viewer, "Id", 2, row -> idOf(row));

    viewer.setContentProvider(org.eclipse.jface.viewers.ArrayContentProvider.getInstance());
    List<Session> rows = new ArrayList<>(sessions);
    rows.sort((a, b) -> idOf(a).compareTo(idOf(b))); // deterministic base order
    rows.sort(
        (a, b) ->
            Boolean.compare(pinned.contains(idOf(b)), pinned.contains(idOf(a)))); // pinned first
    viewer.setInput(rows);
    viewer.addDoubleClickListener(
        (DoubleClickEvent e) -> {
          accept();
        });
    if (boundSessionId != null) {
      for (int i = 0; i < rows.size(); i++) {
        if (boundSessionId.equals(idOf(rows.get(i)))) {
          viewer.getTable().select(i);
          break;
        }
      }
    }
    return area;
  }

  private void createColumn(
      TableViewer tableViewer,
      String title,
      int weight,
      java.util.function.Function<Session, String> value) {
    TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.NONE);
    column.getColumn().setText(title);
    column.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            return value.apply((Session) element);
          }
        });
    column.getColumn().setWidth(weight <= 0 ? 24 : weight * 90);
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    createButton(parent, IDialogConstants.OK_ID, "Switch", true);
    createButton(parent, 100, "Pin / Unpin", false);
    createButton(parent, 101, "Delete", false);
    createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, false);
  }

  @Override
  protected void buttonPressed(int buttonId) {
    Session selected = selected();
    if (buttonId == IDialogConstants.OK_ID) {
      accept();
      return;
    }
    if (buttonId == 100 && selected != null && handler != null) {
      handler.togglePin(idOf(selected));
      refreshRows();
      return;
    }
    if (buttonId == 101 && selected != null && handler != null) {
      handler.delete(idOf(selected));
      refreshRows();
      return;
    }
    super.buttonPressed(buttonId);
  }

  private void accept() {
    Session selected = selected();
    if (selected == null) {
      return;
    }
    chosen = idOf(selected);
    if (handler != null) {
      handler.switchTo(chosen);
    }
    okPressed();
  }

  private Session selected() {
    IStructuredSelection selection = viewer.getStructuredSelection();
    Object first = selection == null ? null : selection.getFirstElement();
    return first instanceof Session session ? session : null;
  }

  private void refreshRows() {
    viewer.refresh();
  }

  private static String idOf(Session session) {
    return session == null || session.id() == null ? "" : session.id();
  }

  private static String titleOf(Session session) {
    if (session == null) {
      return "";
    }
    if (session.title() != null && !session.title().isBlank()) {
      return session.title();
    }
    return "(untitled)";
  }

  private static String updatedOf(Session session) {
    return session == null || session.time() == null
        ? ""
        : UPDATED.format(java.time.Instant.ofEpochMilli(session.time().updated()));
  }
}
