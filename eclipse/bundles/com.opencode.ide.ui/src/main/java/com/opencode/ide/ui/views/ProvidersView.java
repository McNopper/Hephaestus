package com.opencode.ide.ui.views;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.OauthStart;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.UsageLimits;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.ManagedConnection;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.context.SessionViewIds;
import com.opencode.ide.ui.internal.ProviderIcons;
import com.opencode.ide.ui.internal.ProviderLogos;
import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.internal.UiActivator;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.ModelComparator;
import com.opencode.ide.ui.model.ModelFilter;
import com.opencode.ide.ui.model.ModelRow;
import com.opencode.ide.ui.model.ModelRows;
import com.opencode.ide.ui.model.ProviderAuthState;
import com.opencode.ide.ui.model.UsageRows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.core.commands.Parameterization;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.ILazyContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;

/**
 * Models from {@code GET /config/providers}, shown as a flat, sortable table.
 *
 * <p>Provider and Model are kept as <b>separate columns</b> (one row per model) so that sorting by
 * either works. A text filter and a "server" indicator (which server these providers came from) are
 * provided.
 *
 * <p><b>Usage columns (T-152, gap G4):</b> Budget / Spent / Left show what the workspace's {@code
 * GET /api/experimental/usage/limits} budgets leave on each model, in the TUI {@code usage.show}
 * dialog's wording ({@code $100} / {@code $0} / {@code $100 left}), with its scope line ({@code
 * Only you · All models}) as the cell tooltip. Models no budget covers show nothing - and when the
 * route is absent or refuses, the view says {@code Usage limits unavailable} instead of a number it
 * was never given (see {@link UsageRows}).
 *
 * <p>The table is {@code SWT.VIRTUAL} with an {@link ILazyContentProvider}: rows are materialized
 * on demand from the element array. Filtering and sorting operate on the source array (no
 * per-element {@code ViewerFilter} callbacks), and the data comes from the primary connection's
 * cached providers via the {@link ConnectionsManager} (30s TTL / invalidated on disconnect).
 */
public class ProvidersView extends ViewPart implements Refreshable {

  public static final String ID = "com.opencode.ide.ui.views.ProvidersView";

  private TableViewer viewer;
  private Text filterText;
  private ModelFilter filter;
  private ModelComparator comparator;
  private Action refreshAction;

  /** All rows from the last load (the filter/sort source array). */
  private volatile ModelRow[] source = new ModelRow[0];

  /** Auth states by provider id from the last load (empty while unknown). */
  private volatile Map<String, ProviderAuthState> authStates = Map.of();

  /**
   * The Usage cells by model key from the last load (T-152); empty when no limits were loaded or no
   * budget covers any model - the columns then read as empty, never as a made-up number.
   */
  private volatile Map<String, UsageRows.Row> usageRows = Map.of();

  private record ProvidersLoad(
      List<ModelRow> rows,
      String server,
      String healthTag,
      Map<String, ProviderAuthState> auths,
      UsageLimits usage,
      String usageError,
      Map<String, String> integrations) {}

  @Override
  public void createPartControl(Composite parent) {
    Composite outer = new Composite(parent, SWT.NONE);
    GridLayout outerLayout = new GridLayout(1, false);
    outerLayout.marginWidth = 0;
    outerLayout.marginHeight = 0;
    outerLayout.verticalSpacing = 2;
    outer.setLayout(outerLayout);
    outer.setLayoutData(new GridData(GridData.FILL_BOTH));

    filterText = new Text(outer, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
    filterText.setMessage("Filter by provider / model / id / status...");
    filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    Composite tableComposite = new Composite(outer, SWT.NONE);
    TableColumnLayout layout = new TableColumnLayout();
    tableComposite.setLayout(layout);
    tableComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    // SWT.VIRTUAL + ILazyContentProvider: rows are only materialized when
    // scrolled into view; the element array is swapped on filter/sort/load.
    viewer =
        new TableViewer(
            tableComposite,
            SWT.MULTI
                | SWT.H_SCROLL
                | SWT.V_SCROLL
                | SWT.FULL_SELECTION
                | SWT.BORDER
                | SWT.VIRTUAL);
    viewer.setUseHashlookup(true);
    viewer.getTable().setHeaderVisible(true);
    viewer.getTable().setLinesVisible(true);
    viewer.setContentProvider(new LazyModelContentProvider());

    filter = new ModelFilter();
    comparator = new ModelComparator();

    createColumn(layout, "Provider", 2, this::providerWithAuth, 0);
    createColumn(
        layout,
        "Model",
        3,
        r ->
            r.model() == null ? "" : (r.model().name() == null ? r.model().id() : r.model().name()),
        1);
    createColumn(layout, "ID", 2, r -> r.model() == null ? "" : r.model().id(), 2);
    createColumn(
        layout,
        "Status",
        1,
        r -> r.model() == null || r.model().status() == null ? "" : r.model().status(),
        3);
    createColumn(layout, "Capabilities", 1, r -> ModelRows.capabilities(r.model()), 4);
    createColumn(layout, "Context", 1, r -> ModelRows.context(r.model()), 5);
    createColumn(layout, "Default", 1, r -> r.defaultModel() ? "yes" : "", 6);
    // T-152 (gap G4): the usage.show dialog's three facts per model, in its wording; the
    // tooltip carries the TUI's scope line (Only you · All models)
    createColumn(
        layout,
        "Budget",
        1,
        r -> UsageRows.cell(usageRows, r, UsageRows.BUDGET),
        UsageRows.BUDGET,
        r -> UsageRows.scope(usageRows, r));
    createColumn(
        layout,
        "Spent",
        1,
        r -> UsageRows.cell(usageRows, r, UsageRows.SPENT),
        UsageRows.SPENT,
        r -> UsageRows.scope(usageRows, r));
    createColumn(
        layout,
        "Left",
        1,
        r -> UsageRows.cell(usageRows, r, UsageRows.LEFT),
        UsageRows.LEFT,
        r -> UsageRows.scope(usageRows, r));

    viewer.getTable().setSortColumn(viewer.getTable().getColumn(0));
    viewer.getTable().setSortDirection(comparator.isAscending() ? SWT.UP : SWT.DOWN);

    filterText.addModifyListener(
        e -> {
          filter.setFilter(filterText.getText());
          applyFilterAndSort();
        });

    // double-click a model row -> open the chat with that model preselected
    viewer.addDoubleClickListener(
        (DoubleClickEvent e) -> openChatWithSelection((IStructuredSelection) e.getSelection()));

    // context menu with the same action, plus the provider OAuth connect
    // (enabled only on rows of providers the loaded auth data does not
    // show as authenticated)
    Action chatAction =
        new Action("Chat with This Model") {
          @Override
          public void run() {
            openChatWithSelection(viewer.getStructuredSelection());
          }
        };
    Action connectAction =
        new Action("Connect…") {
          @Override
          public void run() {
            connectSelectedProvider();
          }
        };
    connectAction.setToolTipText(
        "Start the provider's OAuth flow (complete it in the browser the server opens)");
    MenuManager menuManager = new MenuManager();
    menuManager.add(chatAction);
    menuManager.add(connectAction);
    Menu menu = menuManager.createContextMenu(viewer.getControl());
    viewer.getControl().setMenu(menu);
    menuManager.addMenuListener(manager -> connectAction.setEnabled(connectableSelection()));

    contributeActions();
    refresh();
  }

  /** Launches the chat (via the openChat command from the ONE seam) with the selected model. */
  private void openChatWithSelection(IStructuredSelection selection) {
    if (selection == null || selection.isEmpty()) {
      return;
    }
    Object first = selection.getFirstElement();
    if (!(first instanceof ModelRow row) || row.model() == null || row.model().id() == null) {
      return;
    }
    try {
      ICommandService commands = getViewSite().getService(ICommandService.class);
      var command = commands.getCommand(SessionViewIds.CHAT_OPEN_COMMAND_ID);
      if (!command.isDefined()) {
        MessageDialog.openInformation(
            viewer.getControl().getShell(), "Chat", "The opencode Chat view is not installed.");
        return;
      }
      ParameterizedCommand parameterized =
          new ParameterizedCommand(
              command,
              new Parameterization[] {
                new Parameterization(
                    command.getParameter(SessionViewIds.chatCommandParameter("providerId")),
                    row.providerId()),
                new Parameterization(
                    command.getParameter(SessionViewIds.chatCommandParameter("modelId")),
                    row.model().id())
              });
      IHandlerService handlers = getViewSite().getService(IHandlerService.class);
      handlers.executeCommand(parameterized, null);
    } catch (Exception e) {
      UiActivator.getDefault()
          .getLog()
          .log(new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Failed to open chat", e));
    }
  }

  // ---------- provider OAuth connect ----------

  /** The selected row, or {@code null}. */
  private ModelRow selectedRow() {
    Object first = viewer.getStructuredSelection().getFirstElement();
    return first instanceof ModelRow row ? row : null;
  }

  /**
   * Whether the selection is a model row of a provider not authenticated per the loaded auth data.
   */
  private boolean connectableSelection() {
    ModelRow row = selectedRow();
    if (row == null || row.providerId() == null) {
      return false;
    }
    ProviderAuthState state = authStates.get(row.providerId());
    return state == null || !state.authenticated();
  }

  /**
   * Starts the OAuth flow of the selected provider's first auth method on a background job. When
   * the server returns an authorization URL it is opened in the external browser (plus the server's
   * instructions, if any); a null URL means the server could not start the flow; the auth state is
   * refreshed afterwards.
   */
  private void connectSelectedProvider() {
    ModelRow row = selectedRow();
    if (row == null || row.providerId() == null) {
      return;
    }
    String providerId = row.providerId();
    setContentDescription("Starting " + providerId + " authorization...");
    ViewLoadSupport.load(
        "Starting provider authorization",
        () -> {
          OpencodeClient client = primaryClient(ConnectionsManager.getDefault());
          return client.beginProviderOauth(providerId);
        },
        start -> showOauthResult(providerId, start),
        error -> showOauthError(providerId, error));
  }

  /**
   * Reports the flow's outcome: authorization page opened (finish in the browser) or refused (no
   * URL).
   */
  private void showOauthResult(String providerId, OauthStart start) {
    if (viewer.getControl().isDisposed()) {
      return;
    }
    String url = start == null ? null : start.url();
    if (url != null && !url.isBlank()) {
      boolean opened = Program.launch(url);
      String message =
          "Authorization page for \""
              + providerId
              + "\":\n"
              + url
              + "\n\n"
              + (opened
                  ? "Opened in the external browser — complete the sign-in there."
                  : "Open the URL above in a browser and complete the sign-in.")
              + "\nThe view then refreshes the auth state.";
      if (start.instructions() != null && !start.instructions().isBlank()) {
        message += "\n\n" + start.instructions();
      }
      MessageDialog.openInformation(viewer.getControl().getShell(), "Connect provider", message);
      refresh();
    } else {
      MessageDialog.openWarning(
          viewer.getControl().getShell(),
          "Connect provider",
          "The opencode server could not start an OAuth flow for \""
              + providerId
              + "\" (its first auth method may not be OAuth).");
    }
  }

  private void showOauthError(String providerId, Throwable error) {
    if (viewer.getControl().isDisposed()) {
      return;
    }
    MessageDialog.openError(
        viewer.getControl().getShell(),
        "Connect provider",
        "Starting the OAuth flow for \""
            + providerId
            + "\" failed: "
            + ViewLoadSupport.message(error));
    UiActivator.getDefault()
        .getLog()
        .log(
            new Status(
                Status.ERROR, UiActivator.PLUGIN_ID, "Failed to start provider OAuth", error));
  }

  /**
   * Toolbar icon from the shared set in {@code com.opencode.ide.core} (see
   * ServerView#contributeActions).
   */
  private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
    return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
        "com.opencode.ide.core", "icons/actions/" + name + ".png");
  }

  private void contributeActions() {
    refreshAction =
        new Action("Refresh") {
          @Override
          public void run() {
            refresh();
          }
        };
    refreshAction.setToolTipText("Refresh providers from the opencode server");
    refreshAction.setImageDescriptor(icon("refresh"));
    IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
    toolBar.add(refreshAction);
  }

  @Override
  public void refresh() {
    setContentDescription("Loading...");
    ViewLoadSupport.load(
        "Loading opencode providers",
        () -> {
          ConnectionsManager manager = ConnectionsManager.getDefault();
          OpencodeClient client = primaryClient(manager);
          String healthTag = healthTag(client.getHealth()); // also ensures spawned/connected
          ProviderList list = manager.providers(manager.primaryConnection());
          Map<String, ProviderAuthState> auths =
              ProviderAuthState.load(
                  client,
                  OpencodeConnection.getInstance().getWorkingDirectory()); // lenient: never throws
          // T-152: the workspace budgets, LENIENT on purpose - an older build without the
          // experimental route (or a refused token) degrades to the view's "unavailable"
          // line instead of failing the providers load or reporting a number it cannot back
          UsageLimits usage = null;
          String usageError = null;
          try {
            usage = client.getUsageLimits();
          } catch (Exception e) {
            usageError = ViewLoadSupport.message(e);
          }
          String server = OpencodeConnection.getInstance().getConnectConfig().baseUrl().toString();
          return new ProvidersLoad(
              ModelRows.toRows(list),
              server,
              healthTag,
              auths,
              usage,
              usageError,
              integrations(list));
        },
        result ->
            showRows(
                result.rows(),
                result.server(),
                result.healthTag(),
                result.auths(),
                result.usage(),
                result.usageError(),
                result.integrations()),
        this::showError);
  }

  /** providerId -&gt; {@code integrationID}, the field the TUI's console gating reads. */
  private static Map<String, String> integrations(ProviderList list) {
    if (list == null || list.providers() == null) {
      return Map.of();
    }
    Map<String, String> out = new HashMap<>();
    for (Provider provider : list.providers()) {
      if (provider != null && provider.id() != null && provider.integrationID() != null) {
        out.put(provider.id(), provider.integrationID());
      }
    }
    return out;
  }

  private static OpencodeClient primaryClient(ConnectionsManager manager) throws OpencodeException {
    ManagedConnection primary = manager.primaryConnection();
    if (primary != null) {
      return primary.client();
    }
    return OpencodeConnection.getInstance().getClient();
  }

  private static String healthTag(HealthStatus health) {
    return ((health != null && health.healthy()) ? "healthy" : "unhealthy")
        + (health != null && health.version() != null ? ", v" + health.version() : "");
  }

  private void showRows(
      List<ModelRow> rows,
      String server,
      String healthTag,
      Map<String, ProviderAuthState> auths,
      UsageLimits usage,
      String usageError,
      Map<String, String> integrations) {
    if (viewer.getControl().isDisposed()) {
      return;
    }
    source = rows.toArray(new ModelRow[0]);
    authStates = auths == null ? Map.of() : auths;
    usageRows = UsageRows.build(usage, rows, integrations);
    comparator.setUsage(usageRows);
    String usageLine = usageLine(usage);
    applyFilterAndSort();
    setContentDescription(
        "Server: "
            + server
            + "  •  "
            + healthTag
            + "  •  "
            + rows.size()
            + " models"
            + "  •  "
            + usageLine);
    // T-152 acceptance evidence: what the usage surface actually rendered (the intercept is
    // the desktop-safe verification channel - no clicks, no screenshots)
    com.opencode.ide.core.intercept.ViewIntercept.recordTruncated(
        "providers",
        "usage-limits="
            + usageLine
            + " cells="
            + usageRows.size()
            + "/"
            + rows.size()
            + (usageError == null ? "" : " error=" + usageError),
        400);
  }

  /**
   * The view's usage segment: the TUI dialog's description when limits loaded ({@code Usage:
   * Workspace: Default · $0 used of $100 · $100 left · Resets Nov 30}), an honest {@code Usage
   * limits unavailable} when the route failed or is absent - never a number the server did not
   * give.
   */
  private static String usageLine(UsageLimits usage) {
    String summary = UsageLimits.summary(usage);
    return summary == null ? "Usage limits unavailable" : "Usage: " + summary;
  }

  /** Provider cell: the provider name plus {@code " · auth"} while its auth data shows a method. */
  private String providerWithAuth(ModelRow row) {
    String name = row.providerName();
    ProviderAuthState state = authStates.get(row.providerId());
    if (state == null || !state.authenticated()) {
      return name; // exactly the bare name while no auth data applies
    }
    return (name == null || name.isEmpty() ? row.providerId() : name) + " · auth";
  }

  /** Recomputes the visible array from the source (filter + sort) and pushes it into the viewer. */
  private void applyFilterAndSort() {
    if (viewer.getControl().isDisposed()) {
      return;
    }
    ModelRow[] visible = filterAndSort(source);
    viewer.setInput(visible);
    viewer.setItemCount(visible.length);
  }

  private ModelRow[] filterAndSort(ModelRow[] input) {
    List<ModelRow> rows = new ArrayList<>(input.length);
    for (ModelRow row : input) {
      if (filter.accepts(row)) {
        rows.add(row);
      }
    }
    rows.sort(comparator);
    return rows.toArray(new ModelRow[0]);
  }

  private void showError(Throwable e) {
    if (viewer.getControl().isDisposed()) {
      return;
    }
    viewer.setInput(java.util.Collections.emptyList());
    viewer.setItemCount(0);
    setContentDescription("Error: " + ViewLoadSupport.message(e));
    UiActivator.getDefault()
        .getLog()
        .log(new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Failed to load providers", e));
  }

  private void createColumn(
      TableColumnLayout layout,
      String title,
      int weight,
      java.util.function.Function<ModelRow, String> value,
      int index) {
    createColumn(layout, title, weight, value, index, null);
  }

  /**
   * A column with an optional per-cell tooltip (the Usage columns carry the TUI's scope line
   * there); {@code tooltip} returning null/empty hides it.
   */
  private void createColumn(
      TableColumnLayout layout,
      String title,
      int weight,
      java.util.function.Function<ModelRow, String> value,
      int index,
      java.util.function.Function<ModelRow, String> tooltip) {
    TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
    column.getColumn().setText(title);
    column.setLabelProvider(
        new ColumnLabelProvider() {
          @Override
          public String getText(Object element) {
            return value.apply((ModelRow) element);
          }

          @Override
          public String getToolTipText(Object element) {
            if (tooltip == null) {
              return null;
            }
            String text = tooltip.apply((ModelRow) element);
            return text == null || text.isEmpty() ? null : text;
          }

          @Override
          public Image getImage(Object element) {
            // a distinct icon per provider on the leading (Provider) column:
            // the vendored logo when available, the colored badge otherwise
            if (index != 0) {
              return null;
            }
            ModelRow row = (ModelRow) element;
            Image logo = ProviderLogos.imageFor(row.providerId());
            return logo != null
                ? logo
                : ProviderIcons.imageFor(row.providerId(), row.providerName());
          }
        });
    layout.setColumnData(column.getColumn(), new ColumnWeightData(weight, 60, true));
    column
        .getColumn()
        .addSelectionListener(
            new SelectionAdapter() {
              @Override
              public void widgetSelected(SelectionEvent e) {
                comparator.applySort(index);
                viewer.getTable().setSortColumn(column.getColumn());
                viewer.getTable().setSortDirection(comparator.isAscending() ? SWT.UP : SWT.DOWN);
                applyFilterAndSort();
              }
            });
  }

  /** Serves rows by index for the virtual table; the array is swapped on every filter/sort/load. */
  private final class LazyModelContentProvider implements ILazyContentProvider {
    private ModelRow[] rows = new ModelRow[0];

    @Override
    public void updateElement(int index) {
      ModelRow[] snapshot = rows;
      if (index >= 0 && index < snapshot.length) {
        viewer.replace(snapshot[index], index);
      }
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
      rows = newInput instanceof ModelRow[] array ? array : new ModelRow[0];
    }

    @Override
    public void dispose() {
      rows = new ModelRow[0];
    }
  }

  @Override
  public void setFocus() {
    if (filterText != null && !filterText.isDisposed()) {
      filterText.setFocus();
    } else if (viewer != null && !viewer.getControl().isDisposed()) {
      viewer.getControl().setFocus();
    }
  }
}
