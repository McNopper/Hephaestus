package com.opencode.ide.ui.views;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.ITreeViewerListener;
import org.eclipse.jface.viewers.TreeExpansionEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.FileNode;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.ManagedConnection;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.internal.UiActivator;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.RepoTree;
import com.opencode.ide.ui.model.SearchResults;
import com.opencode.ide.ui.model.SearchResults.Row;

/**
 * Workspace explorer over the opencode server's file endpoints: a lazy file
 * tree ({@code GET /file?path=…}, one page per expanded directory, loaded in
 * background jobs) plus search ({@code /find/file}, {@code /find/symbol},
 * {@code /find}).
 *
 * <p>Search modes are selected by query prefix: plain text = fuzzy file
 * search, {@code @name} = symbol search, {@code /text} = text search; Enter
 * runs the search and its rows replace the tree until the "Show Tree" action
 * (or an empty query) restores it. Double-clicking a file or result row
 * copies its path to the clipboard and echoes it in the status line.</p>
 *
 * <p>The tree is {@code SWT.VIRTUAL} like the Server view: items are only
 * materialized on expansion, children are served from the cached
 * {@link RepoTree} pages (never on the UI thread), and a transient
 * {@code Loading…} placeholder stands in until the page arrives. All
 * formatting/merging logic lives in the SWT-free {@link RepoTree} /
 * {@link SearchResults} (tested in {@code com.opencode.ide.ui.tests}).</p>
 */
public class RepoView extends ViewPart implements Refreshable {

    public static final String ID = "com.opencode.ide.ui.repo";

    private Text searchBox;
    private TreeViewer viewer;
    private Action refreshAction;
    private Action showTreeAction;

    /** Loaded file-tree pages (UI-thread confined; loads deliver via asyncExec). */
    private RepoTree repo = new RepoTree();

    /** Directory paths with an in-flight page load (guards duplicate jobs). */
    private final Set<String> pendingLoads = new HashSet<>();

    /** Directory paths whose page load failed (message shown as placeholder child). */
    private final Map<String, String> loadErrors = new HashMap<>();

    /** Whether search results (not the tree) are currently shown. */
    private boolean searchActive;

    /** Monotonic ticket so a stale search load cannot overwrite a newer one. */
    private int searchTicket;

    /** Transient placeholder while a directory page is loading. */
    record LoadingNode(String path) {
    }

    /** Placeholder child after a failed directory load (retry via Refresh). */
    record ErrorNode(String path, String message) {
    }

    @Override
    public void createPartControl(Composite parent) {
        setTitleToolTip("Workspace file tree and search. Search: plain = fuzzy file search, "
                + "@prefix = symbols, /prefix = text; Enter runs it, \"Show Tree\" restores the tree. "
                + "Double-click copies a path.");

        Composite outer = new Composite(parent, SWT.NONE);
        GridLayout outerLayout = new GridLayout(1, false);
        outerLayout.marginWidth = 0;
        outerLayout.marginHeight = 0;
        outerLayout.verticalSpacing = 2;
        outer.setLayout(outerLayout);
        outer.setLayoutData(new GridData(GridData.FILL_BOTH));

        searchBox = new Text(outer, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
        searchBox.setMessage("Search — files (plain), @symbols, /text; Enter to run");
        searchBox.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchBox.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
                runSearch(searchBox.getText());
            }
        });

        Composite treeComposite = new Composite(outer, SWT.NONE);
        TreeColumnLayout layout = new TreeColumnLayout();
        treeComposite.setLayout(layout);
        treeComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // SWT.VIRTUAL + in-memory children (ServerView pattern): items are
        // materialized lazily on expansion; getChildren never performs IO.
        viewer = new TreeViewer(treeComposite,
                SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER | SWT.VIRTUAL);
        viewer.setUseHashlookup(true);
        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        viewer.setContentProvider(new TreeContentProvider());

        TreeViewerColumn nameCol = new TreeViewerColumn(viewer, SWT.NONE);
        nameCol.getColumn().setText("Name");
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return name(element);
            }

            @Override
            public Image getImage(Object element) {
                return icon(element);
            }
        });

        TreeViewerColumn kindCol = new TreeViewerColumn(viewer, SWT.NONE);
        kindCol.getColumn().setText("Kind");
        kindCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return kind(element);
            }
        });

        TreeViewerColumn locationCol = new TreeViewerColumn(viewer, SWT.NONE);
        locationCol.getColumn().setText("Location");
        locationCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return location(element);
            }
        });

        layout.setColumnData(nameCol.getColumn(), new ColumnWeightData(2, 160, true));
        layout.setColumnData(kindCol.getColumn(), new ColumnWeightData(1, 70, true));
        layout.setColumnData(locationCol.getColumn(), new ColumnWeightData(3, 220, true));

        // expanding a directory loads its page in the background (once; the
        // Loading… placeholder is replaced when the page arrives)
        viewer.addTreeListener(new ITreeViewerListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (event.getElement() instanceof FileNode node && node.isDirectory()) {
                    requestChildren(node);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                // nothing to do
            }
        });

        // double-click: copy the row's path to the clipboard + status line
        viewer.addDoubleClickListener(e -> {
            Object selection = e.getSelection();
            Object first = (selection instanceof IStructuredSelection structured)
                    ? structured.getFirstElement()
                    : null;
            String path = null;
            if (first instanceof FileNode node) {
                path = node.path();
            } else if (first instanceof Row row) {
                path = row.path();
            }
            if (path != null && !path.isBlank()) {
                copyPath(path);
            }
        });

        // context menu: copy the selected row's path (same behavior as double-click)
        MenuManager menu = new MenuManager();
        menu.setRemoveAllWhenShown(true);
        menu.addMenuListener(manager -> {
            Action copyPathAction = new Action("Copy path") {
                @Override
                public void run() {
                    String path = selectedPath();
                    if (path != null) {
                        copyPath(path);
                    }
                }
            };
            copyPathAction.setEnabled(selectedPath() != null);
            manager.add(copyPathAction);
        });
        viewer.getControl().setMenu(menu.createContextMenu(viewer.getControl()));

        contributeActions();
        refresh();
    }

    /** The selected row's path (file tree node or search result row), or {@code null}. */
    private String selectedPath() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return null;
        }
        Object selection = viewer.getStructuredSelection();
        Object first = (selection instanceof IStructuredSelection structured)
                ? structured.getFirstElement()
                : null;
        if (first instanceof FileNode node) {
            return node.path() == null || node.path().isBlank() ? null : node.path();
        }
        if (first instanceof Row row) {
            return row.path() == null || row.path().isBlank() ? null : row.path();
        }
        return null;
    }

    private void contributeActions() {
        refreshAction = new Action("Refresh") {
            @Override
            public void run() {
                refresh();
            }
        };
        refreshAction.setToolTipText("Reload the workspace tree from the opencode server");
        showTreeAction = new Action("Show Tree") {
            @Override
            public void run() {
                showTree();
            }
        };
        showTreeAction.setToolTipText("Leave the search results and restore the file tree");
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(refreshAction);
        toolBar.add(showTreeAction);
    }

    // ---------- client access (ProvidersView pattern) ----------

    private static OpencodeClient primaryClient(ConnectionsManager manager) throws OpencodeException {
        ManagedConnection primary = manager.primaryConnection();
        if (primary != null) {
            return primary.client();
        }
        return OpencodeConnection.getInstance().getClient();
    }

    // ---------- tree loading ----------

    @Override
    public void refresh() {
        setContentDescription("Loading...");
        RepoTree target = new RepoTree(); // a refresh drops all stale pages
        ViewLoadSupport.load("Loading workspace tree", () -> {
            OpencodeClient client = primaryClient(ConnectionsManager.getDefault());
            client.getHealth(); // ensures the primary server is spawned/connected
            return client.listFiles(RepoTree.ROOT);
        }, nodes -> {
            repo = target;
            pendingLoads.clear();
            loadErrors.clear();
            target.put(RepoTree.ROOT, nodes);
            if (viewer.getControl().isDisposed()) {
                return;
            }
            searchActive = false;
            searchBox.setText("");
            viewer.setInput(target.children(RepoTree.ROOT));
            setContentDescription("workspace root  •  " + target.children(RepoTree.ROOT).size() + " entries");
        }, this::showError);
    }

    /**
     * Loads one directory's children off the UI thread (once per path); the
     * cached page replaces the {@code Loading…} placeholder on arrival.
     */
    private void requestChildren(FileNode node) {
        String path = RepoTree.normalize(node.path());
        if (repo.isLoaded(path) || pendingLoads.contains(path) || loadErrors.containsKey(path)) {
            return;
        }
        pendingLoads.add(path);
        RepoTree target = repo;
        ViewLoadSupport.load("Loading " + (path.isEmpty() ? "workspace root" : path), () -> {
            return primaryClient(ConnectionsManager.getDefault()).listFiles(path);
        }, nodes -> {
            pendingLoads.remove(path);
            target.put(path, nodes);
            if (viewer.getControl().isDisposed() || target != repo) {
                return; // view closed or refreshed in the meantime
            }
            loadErrors.remove(path);
            viewer.refresh(node);
        }, error -> {
            pendingLoads.remove(path);
            if (viewer.getControl().isDisposed() || target != repo) {
                return;
            }
            loadErrors.put(path, ViewLoadSupport.message(error));
            viewer.refresh(node);
            setContentDescription("Error: " + ViewLoadSupport.message(error));
            UiActivator.getDefault().getLog().log(new Status(Status.ERROR, UiActivator.PLUGIN_ID,
                    "Failed to load " + (path.isEmpty() ? "workspace root" : path), error));
        });
    }

    // ---------- search ----------

    /** Runs the search for the current box content; an empty query restores the tree. */
    private void runSearch(String raw) {
        SearchResults.Query query = SearchResults.parse(raw);
        if (query.isEmpty()) {
            if (searchActive) {
                showTree();
            }
            return;
        }
        int ticket = ++searchTicket;
        setContentDescription("Searching " + query.mode().name().toLowerCase() + " '" + query.text() + "'...");
        ViewLoadSupport.load("Searching workspace " + query.mode().name().toLowerCase(), () -> {
            OpencodeClient client = primaryClient(ConnectionsManager.getDefault());
            return switch (query.mode()) {
                case FILE -> SearchResults.fromFiles(client.findFiles(query.text()));
                case SYMBOL -> SearchResults.fromSymbols(client.findSymbols(query.text()));
                case TEXT -> SearchResults.fromText(client.findText(query.text()));
            };
        }, rows -> {
            if (ticket != searchTicket || viewer.getControl().isDisposed()) {
                return; // a newer search superseded this one
            }
            searchActive = true;
            viewer.setInput(rows);
            setContentDescription(rows.size() + (rows.size() >= SearchResults.DEFAULT_CAP ? "+" : "")
                    + " " + query.mode().name().toLowerCase() + " results for '" + query.text() + "'");
        }, this::showError);
    }

    /** Restores the (already loaded) file tree and clears the search box. */
    private void showTree() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        if (searchBox != null && !searchBox.isDisposed()) {
            searchBox.setText("");
        }
        searchActive = false;
        viewer.setInput(repo.children(RepoTree.ROOT));
        setContentDescription("workspace root  •  " + repo.children(RepoTree.ROOT).size() + " entries");
    }

    private void showError(Throwable e) {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        setContentDescription("Error: " + ViewLoadSupport.message(e));
        UiActivator.getDefault().getLog().log(
                new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Repo view load failed", e));
    }

    // ---------- clipboard ----------

    private void copyPath(String path) {
        Clipboard clipboard = new Clipboard(viewer.getControl().getDisplay());
        try {
            clipboard.setContents(new Object[] { path }, new Transfer[] { TextTransfer.getInstance() });
            getViewSite().getActionBars().getStatusLineManager().setMessage("Copied: " + path);
        } finally {
            clipboard.dispose();
        }
    }

    // ---------- label helpers (tree + search rows) ----------

    String name(Object element) {
        if (element instanceof FileNode node) {
            return RepoTree.nameOf(node);
        }
        if (element instanceof LoadingNode) {
            return "Loading…";
        }
        if (element instanceof ErrorNode error) {
            return "Error: " + error.message();
        }
        if (element instanceof Row row) {
            return row.label();
        }
        return String.valueOf(element);
    }

    String kind(Object element) {
        if (element instanceof FileNode node) {
            return node.isDirectory() ? "directory" : "";
        }
        if (element instanceof LoadingNode || element instanceof ErrorNode) {
            return "";
        }
        if (element instanceof Row row) {
            return row.kind();
        }
        return "";
    }

    String location(Object element) {
        if (element instanceof FileNode node) {
            return node.path() == null ? "" : node.path();
        }
        if (element instanceof LoadingNode loading) {
            return loading.path().isEmpty() ? "" : loading.path();
        }
        if (element instanceof ErrorNode error) {
            return error.path().isEmpty() ? "" : error.path();
        }
        if (element instanceof Row row) {
            return row.location();
        }
        return "";
    }

    Image icon(Object element) {
        if (element instanceof FileNode node) {
            return UiActivator.image(node.isDirectory() ? UiActivator.ICON_CATEGORY : UiActivator.ICON_FILE);
        }
        if (element instanceof Row row) {
            return switch (row.kind()) {
                case "text" -> UiActivator.image(UiActivator.ICON_CATEGORY);
                case "file" -> UiActivator.image(UiActivator.ICON_FILE);
                default -> UiActivator.image(UiActivator.ICON_SKILL); // symbol kinds
            };
        }
        return null;
    }

    /**
     * Serves tree children from the cached {@link RepoTree} pages (or a
     * placeholder while loading) and the flat search rows — never performs
     * IO, so it is safe for the virtual tree's lazy item materialization.
     */
    private final class TreeContentProvider implements ITreeContentProvider {

        @Override
        public Object[] getElements(Object input) {
            if (input instanceof Object[] array) {
                return array;
            }
            if (input instanceof java.util.Collection<?> collection) {
                return collection.toArray();
            }
            return new Object[0];
        }

        @Override
        public Object[] getChildren(Object parent) {
            if (parent instanceof FileNode node && node.isDirectory()) {
                String path = RepoTree.normalize(node.path());
                if (repo.isLoaded(path)) {
                    return repo.children(path).toArray();
                }
                String error = loadErrors.get(path);
                if (error != null) {
                    return new Object[] { new ErrorNode(path, error) };
                }
                return new Object[] { new LoadingNode(path) };
            }
            return new Object[0];
        }

        @Override
        public Object getParent(Object element) {
            if (element instanceof FileNode node) {
                return repo.nodeAt(RepoTree.parentPath(node.path()));
            }
            return null;
        }

        @Override
        public boolean hasChildren(Object parent) {
            if (parent instanceof FileNode node && node.isDirectory()) {
                String path = RepoTree.normalize(node.path());
                // optimistic while unloaded (shows the expander); exact once loaded
                return !repo.isLoaded(path) || !repo.children(path).isEmpty();
            }
            return false;
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            // stateless: everything is served from the view's RepoTree field
        }

        @Override
        public void dispose() {
            // stateless
        }
    }

    @Override
    public void setFocus() {
        if (searchBox != null && !searchBox.isDisposed()) {
            searchBox.setFocus();
        } else if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }
}
