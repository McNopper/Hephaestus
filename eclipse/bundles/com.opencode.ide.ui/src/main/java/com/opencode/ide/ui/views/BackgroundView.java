package com.opencode.ide.ui.views;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.SessionObservation;
import com.opencode.ide.client.activity.SessionObserver;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.context.SessionViewIds;
import com.opencode.ide.ui.internal.PendingQuestionsFeed;
import com.opencode.ide.ui.model.BackgroundModel;
import com.opencode.ide.ui.model.PendingQuestions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

/**
 * The Background view (U-040/U-041/T-005/T-004): the "what is going on" cockpit the opencode TUI
 * provides, as an Eclipse view — every agent request and chat (sessions and subagent children with
 * live activity), every shell launched (with output and reap), and every permission ask pending
 * (answerable: once / always / reject). Directory-scoped like every v2 list; every pane degrades
 * leniently on servers that lack a surface (404/unsupported = empty pane + a status note, never an
 * error dialog).
 *
 * <p>Refresh is a cheap poll ({@link #REFRESH_MILLIS}): the session list and status maps every
 * cycle, the deep observation ({@code SessionObserver}) only for BUSY sessions and the selection,
 * so a big service stays cheap.
 */
public class BackgroundView extends ViewPart {

  public static final String ID = "com.opencode.ide.ui.views.BackgroundView";

  private static final int REFRESH_MILLIS = 3_000;

  private Tree agentsTree;
  private Table shellsTable;
  private Table asksTable;
  private CTabItem asksTab;
  private Label statusLine;
  private String statusNote = "";
  private boolean refreshing;
  private Runnable questionsListener;

  @Override
  public void createPartControl(Composite parent) {
    Composite body = new Composite(parent, SWT.NONE);
    body.setLayout(new GridLayout(1, false));
    CTabFolder tabs = new CTabFolder(body, SWT.BORDER | SWT.BOTTOM);
    tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    tabs.setSelectionBackground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

    CTabItem agentsTab = new CTabItem(tabs, SWT.NONE);
    agentsTab.setText("Agents (requests & chats)");
    agentsTab.setToolTipText(
        "Every session and subagent with its live activity - what each agent is DOING");
    agentsTree = new Tree(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    agentsTree.setHeaderVisible(true);
    agentsTree.setLinesVisible(true);
    column(agentsTree, "Session / agent", 260);
    column(agentsTree, "Status", 80);
    column(agentsTree, "Current activity", 220);
    column(agentsTree, "Last text", 320);
    column(agentsTree, "Cost", 80);
    column(agentsTree, "Tokens", 70);
    agentsTab.setControl(agentsTree);

    CTabItem shellsTab = new CTabItem(tabs, SWT.NONE);
    shellsTab.setText("Shells");
    shellsTab.setToolTipText("Every shell command launched by any session");
    shellsTable = new Table(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    shellsTable.setHeaderVisible(true);
    shellsTable.setLinesVisible(true);
    tableColumn(shellsTable, "Command", 320);
    tableColumn(shellsTable, "Status", 80);
    tableColumn(shellsTable, "Exit", 60);
    tableColumn(shellsTable, "Started", 140);
    tableColumn(shellsTable, "Id", 90);
    shellsTab.setControl(shellsTable);

    CTabItem asksTabItem = new CTabItem(tabs, SWT.NONE);
    asksTab = asksTabItem;
    asksTabItem.setText("Questions");
    asksTabItem.setToolTipText(
        "Agent questions (permission asks) waiting for an answer - every session, subagents"
            + " included");
    asksTable = new Table(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    asksTable.setHeaderVisible(true);
    asksTable.setLinesVisible(true);
    tableColumn(asksTable, "Session / agent", 170);
    tableColumn(asksTable, "Question", 240);
    tableColumn(asksTable, "Age", 70);
    tableColumn(asksTable, "Detail", 220);
    asksTabItem.setControl(asksTable);

    statusLine = new Label(body, SWT.NONE);
    statusLine.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    statusLine.setText(
        "Live overview of agents, shells and permission asks - refreshing every "
            + (REFRESH_MILLIS / 1000)
            + "s");

    contributeActions();
    registerQuestionsFeed();
    scheduleRefresh();
  }

  /**
   * U-096: this view's Questions tab IS the collected agent-questions list - the shared registry
   * (fed from the SSE stream, one install for every consuming surface) drives it, and a registry
   * change triggers an off-cycle refresh so an answered question clears here the moment it is
   * answered anywhere (board/chat/server agree).
   */
  private void registerQuestionsFeed() {
    PendingQuestionsFeed.install();
    if (questionsListener == null) {
      questionsListener = this::onQuestionsChanged;
      PendingQuestions.getDefault().addListener(questionsListener);
    }
  }

  /** Registry change (any thread): re-render on the UI thread while the view lives. */
  private void onQuestionsChanged() {
    Display display = Display.getDefault();
    if (display == null || display.isDisposed()) {
      return;
    }
    display.asyncExec(
        () -> {
          if (asksTable != null && !asksTable.isDisposed()) {
            scheduleRefresh();
          }
        });
  }

  @Override
  public void dispose() {
    if (questionsListener != null) {
      PendingQuestions.getDefault().removeListener(questionsListener);
      questionsListener = null;
    }
    super.dispose();
  }

  private void column(Tree tree, String text, int width) {
    TreeColumn column = new TreeColumn(tree, SWT.NONE);
    column.setText(text);
    column.setWidth(width);
  }

  private void tableColumn(Table table, String text, int width) {
    TableColumn column = new TableColumn(table, SWT.NONE);
    column.setText(text);
    column.setWidth(width);
  }

  private void contributeActions() {
    Action openChatAction =
        new Action("Open Chat") {
          @Override
          public void run() {
            TreeItem[] selection = agentsTree.getSelection();
            if (selection.length == 1 && selection[0].getData() instanceof BackgroundViewRow row) {
              openChat(row.sessionId);
            }
          }
        };
    openChatAction.setToolTipText("Open (or resume) the chat of the selected session");
    Action outputAction =
        new Action("Shell Output") {
          @Override
          public void run() {
            showShellOutput();
          }
        };
    outputAction.setToolTipText("Show the captured output of the selected shell");
    Action reapAction =
        new Action("Reap Shell") {
          @Override
          public void run() {
            reapShell();
          }
        };
    reapAction.setToolTipText("Remove a finished shell task (DELETE /api/shell/:id)");
    Action answerOnce =
        new Action("Allow Once") {
          @Override
          public void run() {
            answerSelected("once", false);
          }
        };
    Action answerAlways =
        new Action("Allow Always") {
          @Override
          public void run() {
            answerSelected("always", true);
          }
        };
    Action answerReject =
        new Action("Reject") {
          @Override
          public void run() {
            answerSelected("reject", false);
          }
        };
    Action refreshAction =
        new Action("Refresh") {
          @Override
          public void run() {
            scheduleRefresh();
          }
        };
    IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
    Action tuningAction =
        new Action("Tuning") {
          @Override
          public void run() {
            new TuningDialog(getSite().getShell()).open();
          }
        };
    tuningAction.setToolTipText(
        "Adjust the worker poll sleep and the idle/budget windows live (RuntimeTuning)");
    toolBar.add(tuningAction);
    toolBar.add(refreshAction);
    toolBar.add(openChatAction);
    toolBar.add(outputAction);
    toolBar.add(reapAction);
    toolBar.add(answerOnce);
    toolBar.add(answerAlways);
    toolBar.add(answerReject);
  }

  /** Row identity carried on the tree/table items. */
  private record BackgroundViewRow(
      String sessionId, String permissionId, String shellId, String output) {}

  // ---------- data ----------

  private void scheduleRefresh() {
    if (agentsTree == null || agentsTree.isDisposed() || refreshing) {
      return;
    }
    refreshing = true;
    com.opencode.ide.client.WorkerPools.submit("opencode-background-view", this::load);
  }

  private void load() {
    List<BackgroundModel.AgentRow> agents = List.of();
    List<BackgroundModel.ShellRow> shells = List.of();
    List<PendingQuestions.Row> asks = List.of();
    String note = "";
    try {
      OpencodeConnection connection = OpencodeConnection.getInstance();
      connection.getClient(); // ensure spawned/connected
      OpencodeClient client = connection.getClient();
      String directory = connection.getWorkingDirectory();
      List<Session> sessions = client.getSessions(directory);
      Map<String, SessionStatus> statuses = client.getSessionStatus();
      Map<String, SessionObservation> observations = new HashMap<>();
      for (Session session : sessions) {
        if (session == null || session.id() == null) {
          continue;
        }
        SessionStatus status = statuses.get(session.id());
        boolean busy = status != null && status.type() != null && !"idle".equals(status.type());
        if (busy) {
          // deep observation only where work is happening (poll cost)
          observations.put(session.id(), SessionObserver.observe(client, session.id(), directory));
        }
      }
      agents = BackgroundModel.agents(sessions, statuses, observations);
      try {
        shells = BackgroundModel.shells(client.listShellTasks());
      } catch (UnsupportedOperationException e) {
        note = "shell list: not available on this server";
      }
      try {
        // U-096: the poll keeps reconciling the shared registry (the list is a
        // floor for asks the stream missed); the tab renders the registry -
        // every pending question, attributed against the session list
        PendingQuestions questions = PendingQuestions.getDefault();
        questions.reconcile(client.listPermissionRequests(directory));
        asks = questions.rows(sessions, System.currentTimeMillis());
      } catch (UnsupportedOperationException e) {
        note = "permission asks: not available on this server";
      }
    } catch (OpencodeException | RuntimeException e) {
      note = "not connected: " + e.getMessage();
    }
    String finalNote = note;
    List<BackgroundModel.AgentRow> finalAgents = agents;
    List<BackgroundModel.ShellRow> finalShells = shells;
    List<PendingQuestions.Row> finalAsks = asks;
    Display display = Display.getDefault();
    if (display == null || display.isDisposed()) {
      return;
    }
    display.asyncExec(
        () -> {
          refreshing = false;
          if (agentsTree == null || agentsTree.isDisposed()) {
            return;
          }
          renderAgents(finalAgents);
          renderShells(finalShells);
          renderAsks(finalAsks);
          statusLine.setText(
              finalNote.isEmpty()
                  ? "agents "
                      + finalAgents.size()
                      + " | shells "
                      + finalShells.size()
                      + " | questions "
                      + finalAsks.size()
                      + " - refreshed "
                      + java.time.LocalTime.now().withNano(0)
                      + " | "
                      + com.opencode.ide.client.RuntimeTuning.summary()
                  : finalNote);
          // the refresh cadence follows the live tuning knob (adjustable
          // while workers run - the Tuning action)
          display.timerExec(
              (int) com.opencode.ide.client.RuntimeTuning.pollMillis(), this::scheduleRefresh);
        });
  }

  private void renderAgents(List<BackgroundModel.AgentRow> rows) {
    agentsTree.removeAll();
    Map<String, TreeItem> bySession = new HashMap<>();
    for (BackgroundModel.AgentRow row : rows) {
      TreeItem parent = row.parentSessionId() == null ? null : bySession.get(row.parentSessionId());
      TreeItem item =
          parent == null ? new TreeItem(agentsTree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
      item.setText(0, (row.depth() > 0 ? "└ " : "") + safe(row.name()));
      item.setText(1, safe(row.status()));
      item.setText(2, safe(row.activity()));
      item.setText(3, safe(row.lastText()));
      item.setText(4, safe(row.cost()));
      item.setText(5, safe(row.tokens()));
      item.setData(new BackgroundViewRow(row.sessionId(), null, null, null));
      bySession.put(row.sessionId(), item);
    }
  }

  private void renderShells(List<BackgroundModel.ShellRow> rows) {
    shellsTable.removeAll();
    for (BackgroundModel.ShellRow row : rows) {
      TableItem item = new TableItem(shellsTable, SWT.NONE);
      item.setText(0, safe(row.command()));
      item.setText(1, safe(row.status()));
      item.setText(2, row.exit() == null ? "" : row.exit().toString());
      item.setText(3, safe(row.started()));
      item.setText(4, safe(row.id()));
      item.setData(new BackgroundViewRow(null, null, row.id(), null));
    }
  }

  private void renderAsks(List<PendingQuestions.Row> rows) {
    asksTable.removeAll();
    for (PendingQuestions.Row row : rows) {
      TableItem item = new TableItem(asksTable, SWT.NONE);
      item.setText(0, (row.subagent() ? "\u2514 " : "") + safe(row.sessionLabel()));
      item.setText(1, safe(row.question()));
      item.setText(2, safe(row.ageLabel()));
      item.setText(3, safe(row.detailLabel()));
      item.setData(new BackgroundViewRow(row.sessionId(), row.permissionId(), null, null));
    }
    if (rows.isEmpty()) {
      new TableItem(asksTable, SWT.NONE).setText(0, "(no pending questions)");
    }
    // the tab carries the badge: an unanswered question is never silent (U-096)
    if (asksTab != null && !asksTab.isDisposed()) {
      asksTab.setText(rows.isEmpty() ? "Questions" : "Questions (" + rows.size() + ")");
    }
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  // ---------- actions ----------

  /**
   * Opens/resumes the chat cross-bundle via the ChatView secondary-id convention ({@code ses_…} =
   * resume that session; the id built by the ONE seam, {@link SessionViewIds#secondaryId(String)})
   * - no bundle dependency; a missing chat bundle degrades to a status note.
   */
  private void openChat(String sessionId) {
    try {
      getSite()
          .getPage()
          .showView(
              SessionViewIds.CHAT_VIEW_ID,
              SessionViewIds.secondaryId(sessionId),
              org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
    } catch (Exception e) {
      note("cannot open the chat: " + e.getMessage());
    }
  }

  private void showShellOutput() {
    TableItem[] selection = shellsTable.getSelection();
    if (selection.length != 1
        || !(selection[0].getData() instanceof BackgroundViewRow row)
        || row.shellId() == null) {
      return;
    }
    offUi(
        () -> {
          try {
            return OpencodeConnection.getInstance().getClient().shellTaskOutput(row.shellId());
          } catch (OpencodeException | RuntimeException e) {
            return "(output unavailable: " + e.getMessage() + ")";
          }
        },
        output -> {
          Shell shell = getSite().getShell();
          MessageBox box = new MessageBox(shell, SWT.OK | SWT.SHELL_TRIM);
          box.setText("Shell output - " + row.shellId());
          box.setMessage(output == null || output.isBlank() ? "(no output captured)" : output);
          box.open();
        });
  }

  private void reapShell() {
    TableItem[] selection = shellsTable.getSelection();
    if (selection.length != 1
        || !(selection[0].getData() instanceof BackgroundViewRow row)
        || row.shellId() == null) {
      return;
    }
    offUi(
        () -> {
          try {
            OpencodeConnection.getInstance().getClient().removeShellTask(row.shellId());
            return "reaped " + row.shellId();
          } catch (OpencodeException | RuntimeException e) {
            return "reap failed: " + e.getMessage();
          }
        },
        this::note);
  }

  /** Answers the selected ask off the UI thread (FleetPermissionsDialog's rule). */
  private void answerSelected(String decision, boolean remember) {
    TableItem[] selection = asksTable.getSelection();
    if (selection.length != 1
        || !(selection[0].getData() instanceof BackgroundViewRow row)
        || row.permissionId() == null) {
      return;
    }
    offUi(
        () -> {
          try {
            boolean ok =
                OpencodeConnection.getInstance()
                    .getClient()
                    .respondToPermission(row.sessionId(), row.permissionId(), decision, remember);
            if (ok) {
              // clear now, the replied event confirms (U-096: clears everywhere)
              PendingQuestions.getDefault().markAnswered(row.permissionId());
            }
            return ok ? "answered '" + decision + "'" : "answer not accepted";
          } catch (OpencodeException | RuntimeException e) {
            return "answer failed: " + e.getMessage();
          }
        },
        message -> {
          note(message);
          scheduleRefresh();
        });
  }

  /**
   * One shared worker per call (WorkerPools - no thread-per-task); the result is delivered on the
   * UI thread.
   */
  private void offUi(
      java.util.concurrent.Callable<String> work, java.util.function.Consumer<String> onResult) {
    com.opencode.ide.client.WorkerPools.submit(
        "background-view-action",
        () -> {
          String result;
          try {
            result = work.call();
          } catch (Exception e) {
            result = "failed: " + e.getMessage();
          }
          String message = result;
          Display display = Display.getDefault();
          if (display == null || display.isDisposed()) {
            return;
          }
          display.asyncExec(
              () -> {
                if (statusLine != null && !statusLine.isDisposed()) {
                  onResult.accept(message);
                }
              });
        });
  }

  private void note(String message) {
    if (statusLine != null && !statusLine.isDisposed()) {
      statusLine.setText(message);
    }
  }

  @Override
  public void setFocus() {
    if (agentsTree != null && !agentsTree.isDisposed()) {
      agentsTree.setFocus();
    }
  }
}
