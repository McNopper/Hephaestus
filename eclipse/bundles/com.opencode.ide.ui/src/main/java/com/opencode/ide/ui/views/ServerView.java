package com.opencode.ide.ui.views;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.activity.ActivityTracker;
import com.opencode.ide.client.activity.FileActivity;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.ManagedConnection;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.internal.UiActivator;
import com.opencode.ide.ui.internal.ViewLoadSupport;
import com.opencode.ide.ui.model.AgentSessions;
import com.opencode.ide.ui.model.CwdCheck;
import com.opencode.ide.ui.model.McpServerRows;
import com.opencode.ide.ui.model.ProjectVcs;
import com.opencode.ide.ui.model.ServerLabels;
import com.opencode.ide.ui.model.ServerSelection;
import com.opencode.ide.ui.model.WorkingSet;
import com.opencode.ide.ui.session.SessionActivity;
import com.opencode.ide.ui.session.SessionBusyPoller;

/**
 * Per-server explorer: one root per connection of the
 * {@link ConnectionsManager} (the primary opencode server first, then any
 * remote servers from the preferences), each with categories for the available
 * agents and the running sessions (nested by {@code parentID}, so subagents
 * appear under the agent that spawned them). Each agent definition row in
 * the Agents category additionally nests the server's live sessions that
 * currently run as that agent (matched by the session's {@code agent} field,
 * busy first) — double-clicking one opens its live transcript.
 *
 * <p>The primary root behaves exactly like the former single-root view
 * (including the live {@code /event} activity tracker); remote roots show
 * health + agents + sessions from their own client, with an {@code offline}
 * tag when unreachable. On top of the event stream, one
 * {@link SessionBusyPoller} per connection polls {@code /session/status}
 * so the busy icons stay live for remote roots and survive dropped events.
 * Working sessions are prominent, not just icon-decorated: their rows carry
 * a {@code "  • working"} name suffix, parents aggregate the count
 * (agent rows {@code "n working"}, the Agents/Sessions categories, the
 * view header), and the Details column shows <em>what</em> the session is
 * doing — the latest streamed {@code message.part.delta} text, kept and
 * throttled by {@link SessionActivity}. The tree is {@code SWT.VIRTUAL}:
 * tree items are only materialized when their parent is expanded, and
 * child counts come from the already-loaded in-memory lists (no fetching
 * for collapsed roots).</p>
 */
public class ServerView extends ViewPart implements Refreshable {

    public static final String ID = "com.opencode.ide.ui.views.ServerView";

    private TreeViewer viewer;
    private Action refreshAction;
    private Action reconnectAction;

    private volatile List<ServerNode> roots = List.of();
    private volatile ServerNode current;   // the primary root, while loaded
    private volatile ServerNode selectedServer;   // root under the cursor; drives the project/VCS header
    private volatile ProjectVcs projectVcs = ProjectVcs.UNKNOWN;
    private OpencodeEventListener eventListener;
    private Runnable trackerListener;
    private Runnable connectionsListener;
    private boolean refreshPending;
    private boolean reloadPending;

    /**
     * One busy-status poller per live connection client (see
     * {@link #updateBusyPollers}); created, read and disposed on the UI
     * thread only — poll results hop back via
     * {@link #onBusySessions(OpencodeClient, Set)}.
     */
    private final Map<OpencodeClient, SessionBusyPoller> busyPollers = new HashMap<>();

    /**
     * Live "what is it doing" snippets per session id, fed from the primary
     * server's {@code message.part.delta} events ({@link #handleEvent}) and
     * read by the session rows' Details column. UI-thread confined like
     * {@link #busyPollers}; its internal publish throttle bounds the label
     * churn on top of {@link #scheduleRefresh()}.
     */
    private final SessionActivity sessionActivity = new SessionActivity();

    /**
     * The busy set each connection's poller delivered last, so the next poll
     * can spot sessions that <em>stopped</em> working (present then, absent
     * now) and clear their snippets. UI-thread confined; updated in
     * {@link #onBusySessions}.
     */
    private final Map<OpencodeClient, Set<String>> lastBusyByClient = new HashMap<>();

    private final ActivityTracker tracker = new ActivityTracker();

    enum CategoryKind { AGENTS, SESSIONS, ACTIVE_FILES, WORKING_SET, MCP_SERVERS, SKILLS }

    static final class ServerNode {
        final boolean primary;
        final String label;
        final String mode;
        final String url;
        final boolean healthy;
        final String version;
        final Long pid;
        final OpencodeClient client;   // for per-connection header loads; null when offline
        final List<Agent> agents;
        final List<Session> sessions;          // mutable: updated live from /event
        final Map<String, SessionStatus> statuses;   // mutable
        final Map<String, String> activity;    // sessionId -> live label ("thinking"/"running tool"/...)
        final List<McpServerInfo> mcpServers;
        final List<SkillInfo> skills;
        final WorkingSet workingSet;
        final CategoryNode agentsCategory;
        final CategoryNode sessionsCategory;
        final CategoryNode filesCategory;
        final CategoryNode workingSetCategory;
        final CategoryNode mcpCategory;
        final CategoryNode skillsCategory;

        ServerNode(boolean primary, String label, String mode, String url, boolean healthy,
                String version, Long pid, List<Agent> agents, List<Session> sessions,
                Map<String, SessionStatus> statuses) {
            this(primary, label, mode, url, healthy, version, pid, null, agents, sessions, statuses,
                    List.of(), List.of(), WorkingSet.EMPTY);
        }

        ServerNode(boolean primary, String label, String mode, String url, boolean healthy,
                String version, Long pid, OpencodeClient client, List<Agent> agents,
                List<Session> sessions, Map<String, SessionStatus> statuses,
                List<McpServerInfo> mcpServers, List<SkillInfo> skills, WorkingSet workingSet) {
            this.primary = primary;
            this.label = label;
            this.mode = mode;
            this.url = url;
            this.healthy = healthy;
            this.version = version;
            this.pid = pid;
            this.client = client;
            this.agents = agents;
            this.sessions = new ArrayList<>(sessions);
            this.statuses = new HashMap<>(statuses);
            this.activity = new HashMap<>();
            this.mcpServers = mcpServers == null ? List.of() : mcpServers;
            this.skills = skills == null ? List.of() : skills;
            this.workingSet = workingSet == null ? WorkingSet.EMPTY : workingSet;
            this.agentsCategory = new CategoryNode("Agents", CategoryKind.AGENTS, this);
            this.sessionsCategory = new CategoryNode("Sessions", CategoryKind.SESSIONS, this);
            this.filesCategory = new CategoryNode("Active files", CategoryKind.ACTIVE_FILES, this);
            this.workingSetCategory = new CategoryNode("Working set", CategoryKind.WORKING_SET, this);
            this.mcpCategory = new CategoryNode("MCP servers", CategoryKind.MCP_SERVERS, this);
            this.skillsCategory = new CategoryNode("Skills", CategoryKind.SKILLS, this);
        }

        /** The pure (SWT-free) projection used for label derivation. */
        ServerLabels.Server info() {
            return new ServerLabels.Server(primary, label, mode, url, healthy, version, pid);
        }
    }

    static final class CategoryNode {
        final String label;
        final CategoryKind kind;
        final ServerNode server;

        CategoryNode(String label, CategoryKind kind, ServerNode server) {
            this.label = label;
            this.kind = kind;
            this.server = server;
        }
    }

    /**
     * A live session row nested under the agent that runs it (Agents
     * category). A wrapper rather than the bare {@link Session} on purpose:
     * the same session also appears in the Sessions category, and a JFace
     * tree maps each element to exactly one item — the two tree positions
     * must be distinct objects. The record's structural equals/hashCode let
     * the viewer keep the row's expansion state across refreshes. Wrappers
     * are leaves: subagent children stay reachable under the session's row
     * in the Sessions category.
     */
    record AgentSessionNode(Agent agent, Session session) {
    }

    @Override
    public void createPartControl(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        TreeColumnLayout layout = new TreeColumnLayout();
        composite.setLayout(layout);

        // SWT.VIRTUAL: tree items are created lazily when a node is expanded
        // (children are served from the in-memory ServerNode lists, so counts
        // for collapsed roots are never fetched). Hash lookup is required for
        // virtual viewers because item.setData order is not guaranteed.
        viewer = new TreeViewer(composite,
                SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER | SWT.VIRTUAL);
        viewer.setUseHashlookup(true);
        viewer.getTree().setHeaderVisible(true);
        viewer.getTree().setLinesVisible(true);
        viewer.setContentProvider(new TreeContentProvider(tracker));

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

        TreeViewerColumn detailCol = new TreeViewerColumn(viewer, SWT.NONE);
        detailCol.getColumn().setText("Details");
        detailCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return detail(element);
            }
        });

        layout.setColumnData(nameCol.getColumn(), new ColumnWeightData(2, 140, true));
        layout.setColumnData(detailCol.getColumn(), new ColumnWeightData(3, 220, true));

        // double-click: a session nested under its agent -> live output
        // (SessionDetailsView, auto-refreshing); a session in the Sessions
        // category -> resume it in a chat window (unchanged)
        viewer.addDoubleClickListener(e -> {
            if (!selectedTarget().openSession()) {
                return;
            }
            Object selection = e.getSelection();
            Object first = (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured)
                    ? structured.getFirstElement()
                    : null;
            if (first instanceof AgentSessionNode nested && nested.session().id() != null) {
                openSessionDetails(nested.session().id());
            } else if (first instanceof Session s && s.id() != null) {
                openChatForSession(s.id());
            }
        });

        // the project/VCS header follows the selected connection (primary as the fallback)
        viewer.addSelectionChangedListener(event -> {
            ServerNode node = selectedOwner();
            if (node != selectedServer) {
                selectedServer = node;
                updateProjectHeader();
            }
        });

        createContextMenu();
        contributeActions();
        registerConnectionsListener();
        refresh();
    }

    private void createContextMenu() {
        // context menu on session rows (Sessions category and agent-nested):
        // live output (agent-nested double-click) + the session details view
        org.eclipse.jface.action.MenuManager menu = new org.eclipse.jface.action.MenuManager();
        org.eclipse.jface.action.Action liveOutput = new org.eclipse.jface.action.Action("Live output") {
            @Override
            public void run() {
                Session s = selectedSession();
                if (s != null && s.id() != null) {
                    openSessionDetails(s.id());
                }
            }
        };
        liveOutput.setToolTipText("Open the live transcript view (auto-refreshes while the agent runs)");
        menu.add(liveOutput);
        org.eclipse.jface.action.Action details = new org.eclipse.jface.action.Action("Session details") {
            @Override
            public void run() {
                Session s = selectedSession();
                if (s != null && s.id() != null) {
                    openSessionDetails(s.id());
                }
            }
        };
        details.setToolTipText("Open the transcript view (messages, parts, tools, tokens)");
        menu.add(details);
        org.eclipse.jface.action.Action openInChat = new org.eclipse.jface.action.Action("Open in Chat") {
            @Override
            public void run() {
                Session s = selectedSession();
                if (s != null && s.id() != null) {
                    openChatForSession(s.id());
                }
            }
        };
        openInChat.setToolTipText("Resume this session in a chat window");
        menu.add(openInChat);
        org.eclipse.jface.action.Action copySessionId = new org.eclipse.jface.action.Action("Copy session id") {
            @Override
            public void run() {
                Session s = selectedSession();
                if (s != null && s.id() != null) {
                    copyToClipboard(s.id());
                }
            }
        };
        menu.add(copySessionId);
        Action abort = new Action("Abort session") {
            @Override
            public void run() {
                changeSession(false);
            }
        };
        Action delete = new Action("Delete session...") {
            @Override
            public void run() {
                changeSession(true);
            }
        };
        menu.add(abort);
        menu.add(delete);
        menu.add(new org.eclipse.jface.action.Separator());
        org.eclipse.jface.action.Action agentDetails =
                new org.eclipse.jface.action.Action("Show agent details\u2026") {
                    @Override
                    public void run() {
                        Agent a = selectedAgent();
                        if (a != null) {
                            showAgentDetails(a);
                        }
                    }
                };
        agentDetails.setToolTipText("Description, tools and model of this agent definition");
        menu.add(agentDetails);
        Action newWithAgent = new Action("New session with this agent") {
            @Override
            public void run() {
                Agent agent = selectedAgent();
                if (selectedTarget().newAgentSession()) {
                    openChatParameter("agentId", agent.id()); // v2 wants the id, not the display name
                }
            }
        };
        menu.add(newWithAgent);
        // server-level, view-only (tier-0): lists the MCP servers already
        // loaded with the owning node — no IO, no confirmation
        org.eclipse.jface.action.Action mcpServers = new org.eclipse.jface.action.Action("MCP servers\u2026") {
            @Override
            public void run() {
                showMcpDetails();
            }
        };
        mcpServers.setToolTipText("The MCP servers registered with this opencode server");
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(mcpServers);
        viewer.getControl().setMenu(menu.createContextMenu(viewer.getControl()));
        menu.addMenuListener(manager -> {
            ServerSelection target = selectedTarget();
            liveOutput.setEnabled(target.openSession());
            details.setEnabled(target.openSession());
            openInChat.setEnabled(target.openSession());
            copySessionId.setEnabled(target.copySessionId());
            abort.setEnabled(target.abortSession());
            delete.setEnabled(target.deleteSession());
            agentDetails.setEnabled(target.agentDetails());
            newWithAgent.setEnabled(target.newAgentSession());
            mcpServers.setEnabled(target.mcpDetails());
        });
        getSite().registerContextMenu(menu, viewer);

    }

    /**
     * The currently selected session element, or {@code null}. Recognizes both
     * tree positions of a session: the raw {@link Session} rows of the
     * Sessions category and the {@link AgentSessionNode} rows nested under an
     * agent.
     */
    private Session selectedSession() {
        if (viewer.getStructuredSelection().size() != 1) {
            return null;
        }
        Object selection = viewer.getStructuredSelection();
        Object first = (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured)
                ? structured.getFirstElement()
                : null;
        if (first instanceof AgentSessionNode nested) {
            return nested.session();
        }
        return first instanceof Session s ? s : null;
    }

    /** Resolve the selected tree path, not a possibly equal row on another server. */
    private ServerNode selectedOwner() {
        if (!(viewer.getSelection() instanceof org.eclipse.jface.viewers.ITreeSelection selection)) {
            return null;
        }
        var paths = selection.getPaths();
        return paths.length == 1 && paths[0].getFirstSegment() instanceof ServerNode node ? node : null;
    }

    private ServerSelection selectedTarget() {
        ServerNode owner = selectedOwner();
        Session session = selectedSession();
        return owner == null ? ServerSelection.EMPTY
                : new ServerSelection(owner.client, owner.primary, session, selectedAgent(),
                        session != null && ServerLabels.isBusy(owner.statuses, session));
    }

    private void changeSession(boolean delete) {
        ServerSelection target = selectedTarget();
        Session session = target.session();
        ServerNode owner = selectedOwner();
        if (delete ? !target.deleteSession() : !target.abortSession()) {
            return;
        }
        String operation = delete ? "Delete" : "Abort";
        if (!org.eclipse.jface.dialogs.MessageDialog.openConfirm(getSite().getShell(), operation + " session",
                operation + " " + session.id() + " on " + owner.label + "?"
                        + (delete ? "\nThis removes the session and its stored messages." : ""))) {
            return;
        }
        ViewLoadSupport.load(operation + " opencode session", () -> {
            target.changeSession(delete);
            return session.id();
        }, ignored -> {
            if (viewer != null && !viewer.getControl().isDisposed()) {
                refresh();
            }
        }, error -> {
            if (viewer != null && !viewer.getControl().isDisposed()) {
                org.eclipse.jface.dialogs.MessageDialog.openError(getSite().getShell(),
                        operation + " failed", ViewLoadSupport.message(error));
            }
        });
    }

    /** The selected agent definition row (Agents category), or {@code null}. */
    private Agent selectedAgent() {
        if (viewer.getStructuredSelection().size() != 1) {
            return null;
        }
        Object selection = viewer.getStructuredSelection();
        Object first = (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured)
                ? structured.getFirstElement()
                : null;
        return first instanceof Agent agent ? agent : null;
    }

    /**
     * Read-only MCP details dialog (Batch C): the MCP servers registered
     * with the selected opencode server, taken from the owning node's
     * already-loaded list (refresh populated it) — tier-0 view-only, so no
     * confirmation and no background load.
     */
    private void showMcpDetails() {
        ServerNode owner = selectedOwner();
        if (owner == null || owner.client == null) {
            return;
        }
        org.eclipse.jface.dialogs.MessageDialog.openInformation(getSite().getShell(), "MCP servers",
                McpServerRows.dialogText(owner.label, owner.mcpServers));
    }

    /** Read-only agent definition dialog: mode, description, model. */
    private void showAgentDetails(Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.name() == null || agent.name().isBlank() ? "(unnamed)" : agent.name());
        if (agent.mode() != null) {
            sb.append("  \u2022  mode: ").append(agent.mode());
        }
        sb.append(agent.isNative() ? "  \u2022  built-in" : "  \u2022  user-defined");
        if (agent.description() != null && !agent.description().isBlank()) {
            sb.append("\n\n").append(agent.description().strip());
        }
        String model = agentModelLabel(agent.model());
        if (model != null) {
            sb.append("\n\nmodel: ").append(model);
        } else {
            sb.append("\n\nmodel: (server default)");
        }
        // TODO(v2): no tools line — v2's Agent.Info moved the per-tool toggles
        // into `request`, which the client deliberately does not model. What v2
        // does expose here is `permissions`; rendering those is a UI decision,
        // not a mechanical port, so the line is dropped rather than guessed.
        org.eclipse.jface.dialogs.MessageDialog.openInformation(getSite().getShell(),
                "Agent " + (agent.name() == null ? "" : agent.name()), sb.toString());
    }

    /** provider/model id (+ variant) or null when the agent uses the server default. */
    private static String agentModelLabel(Agent.ModelRef model) {
        if (model == null) {
            return null;
        }
        // v2 renamed Agent.ModelRef#modelID to #id
        String id = model.id() == null || model.id().isBlank() ? "?" : model.id();
        String label = model.providerID() == null || model.providerID().isBlank()
                ? id : model.providerID() + "/" + id;
        return model.variant() == null || model.variant().isBlank() ? label : label + " (" + model.variant() + ")";
    }

    /** Copies text to the clipboard (UI thread — the context menu). */
    private void copyToClipboard(String text) {
        if (text == null || text.isBlank() || viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(
                viewer.getControl().getDisplay());
        try {
            clipboard.setContents(new Object[] { text },
                    new org.eclipse.swt.dnd.Transfer[] { org.eclipse.swt.dnd.TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    /** Opens the session details view for one session (secondary id = session id). */
    private void openSessionDetails(String sessionId) {
        if (!selectedTarget().openSession()) {
            return;
        }
        try {
            getSite().getPage().showView(
                    "com.opencode.ide.ui.views.SessionDetailsView",
                    sessionId.replace('%', '_'),
                    org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE);
        } catch (org.eclipse.ui.PartInitException e) {
            UiActivator.getDefault().getLog().log(
                    new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.Status.ERROR,
                            UiActivator.PLUGIN_ID, "Failed to open session details for " + sessionId, e));
        }
    }

    /**
     * Toolbar icon descriptor from the shared action-icon set vendored in
     * {@code com.opencode.ide.core} ({@code generate-action-icons.py},
     * original artwork). Text-only toolbar actions render as flat labels
     * with no button affordance (user feedback 2026-09-16), so every toolbar
     * action gets an icon.
     */
    private static org.eclipse.jface.resource.ImageDescriptor icon(String name) {
        return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin(
                "com.opencode.ide.core", "icons/actions/" + name + ".png");
    }

    private void contributeActions() {
        refreshAction = new Action("Refresh") {            @Override
            public void run() {
                refresh();
            }
        };
        refreshAction.setToolTipText("Refresh servers, agents and sessions");
        refreshAction.setImageDescriptor(icon("refresh"));
        reconnectAction = new Action("Reconnect") {
            @Override
            public void run() {
                reconnect();
            }
        };
        reconnectAction.setToolTipText("Drop the client/server and reconnect");
        reconnectAction.setImageDescriptor(icon("reconnect"));

        Action expandAllAction = new Action("Expand All") {
            @Override
            public void run() {
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.expandAll();
                }
            }
        };
        expandAllAction.setToolTipText("Expand all sessions (incl. subagents)");
        expandAllAction.setImageDescriptor(icon("expand-all"));
        Action collapseAllAction = new Action("Collapse All") {
            @Override
            public void run() {
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    viewer.collapseAll();
                }
            }
        };
        collapseAllAction.setToolTipText("Collapse all categories and sessions");
        collapseAllAction.setImageDescriptor(icon("collapse-all"));

        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
        toolBar.add(refreshAction);
        toolBar.add(reconnectAction);
        toolBar.add(expandAllAction);
        toolBar.add(collapseAllAction);
    }

    /**
     * Connections came and went (or a remote's liveness changed): reload on the
     * UI thread. The listener is called from arbitrary threads.
     */
    private void registerConnectionsListener() {
        if (connectionsListener != null) {
            return;
        }
        connectionsListener = () -> {
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> {
                    if (viewer != null && !viewer.getControl().isDisposed()) {
                        refresh();
                    }
                });
            }
        };
        ConnectionsManager.getDefault().addListener(connectionsListener);
    }

    /** Resumes the given session in a chat window (via the openChat command - no chat-bundle dependency). */
    private void openChatForSession(String sessionId) {
        if (!selectedTarget().openSession()) {
            return;
        }
        openChatParameter("sessionId", sessionId);
    }

    private void openChatParameter(String parameter, String value) {
        try {
            var commands = getViewSite().getService(org.eclipse.ui.commands.ICommandService.class);
            var command = commands.getCommand("com.opencode.ide.chat.openChat");
            if (!command.isDefined()) {
                return; // chat bundle not installed
            }
            var parameterized = new org.eclipse.core.commands.ParameterizedCommand(command,
                    new org.eclipse.core.commands.Parameterization[] {
                            new org.eclipse.core.commands.Parameterization(
                                    command.getParameter("com.opencode.ide.chat.openChat." + parameter), value) });
            var handlers = getViewSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
            handlers.executeCommand(parameterized, null);
        } catch (Exception e) {
            UiActivator.getDefault().getLog().log(
                    new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.Status.ERROR,
                            UiActivator.PLUGIN_ID, "Failed to open chat for " + parameter + " " + value, e));
        }
    }

    @Override
    public void refresh() {
        setContentDescription("Loading...");
        ViewLoadSupport.load("Loading opencode servers", () -> {
            List<ServerNode> nodes = new ArrayList<>();
            nodes.add(loadPrimaryNode());
            ConnectionsManager manager = ConnectionsManager.getDefault();
            for (ManagedConnection connection : manager.connections()) {
                if (!connection.primary()) {
                    nodes.add(loadRemoteNode(manager, connection));
                }
            }
            return nodes;
        }, this::showNodes, this::showError);
    }

    /** The primary root: exactly the former single-root load (unchanged behavior). */
    private ServerNode loadPrimaryNode() throws Exception {
        OpencodeConnection connection = OpencodeConnection.getInstance();
        connection.getClient(); // ensure spawned/connected
        HealthStatus health = connection.getClient().getHealth();
        // v2 auxiliary lists resolve per location: scope them all to the
        // connection's working directory, or the shared service answers for
        // the user's home (wrong project's agents/skills/MCP servers)
        String scopeDir = connection.getWorkingDirectory();
        List<Agent> agents = connection.getClient().getAgents(scopeDir);
        // v2 session state is global per user: scope the primary view to the
        // connection's working directory, or every project on the machine
        // would show up here
        List<Session> sessions = connection.getClient().getSessions(scopeDir);
        Map<String, SessionStatus> statuses = connection.getClient().getSessionStatus();
        List<McpServerInfo> mcpServers = safeMcp(connection.getClient(), scopeDir);
        List<SkillInfo> skills = safeSkills(connection.getClient(), scopeDir);
        WorkingSet workingSet = WorkingSet.load(connection.getClient());   // lenient: never throws
        String mode = connection.getMode();
        String url = connection.getConnectConfig().baseUrl().toString();
        Long pid = connection.getSpawnedProcessId();
        return new ServerNode(true, "primary", mode, url, health.healthy(), health.version(), pid,
                connection.getClient(),
                agents == null ? Collections.emptyList() : agents,
                sessions == null ? Collections.emptyList() : sessions,
                statuses == null ? Collections.emptyMap() : statuses,
                mcpServers == null ? Collections.emptyList() : mcpServers,
                skills == null ? Collections.emptyList() : skills,
                workingSet);
    }

    /**
     * A remote root: health + agents + sessions from its own client. A failure
     * yields an offline node (tagged in the label) instead of an error - one
     * unreachable remote must not take the view down.
     */
    private ServerNode loadRemoteNode(ConnectionsManager manager, ManagedConnection connection) {
        String url = connection.config() != null
                ? connection.config().baseUrl().toString()
                : connection.id();
        String label = connection.label() == null ? url : connection.label();
        try {
            OpencodeClient client = connection.client();
            HealthStatus health = client.getHealth();
            List<Agent> agents = manager.agents(connection); // cached (30s TTL / disconnect)
            List<Session> sessions = client.getSessions();
            Map<String, SessionStatus> statuses = client.getSessionStatus();
            List<McpServerInfo> mcpServers = safeMcp(client, null);
            List<SkillInfo> skills = safeSkills(client, null);
            WorkingSet workingSet = WorkingSet.load(client);   // lenient: never throws
            return new ServerNode(false, label, null, url,
                    health != null && health.healthy(),
                    health == null ? null : health.version(),
                    null,
                    client,
                    agents == null ? Collections.emptyList() : agents,
                    sessions == null ? Collections.emptyList() : sessions,
                    statuses == null ? Collections.emptyMap() : statuses,
                    mcpServers == null ? Collections.emptyList() : mcpServers,
                    skills == null ? Collections.emptyList() : skills,
                    workingSet);
        } catch (Exception e) {
            return new ServerNode(false, label, null, url, false, null, null,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        }
    }

    /**
     * The auxiliary sections (MCP servers, skills) must never take the view
     * down: any endpoint failure or shape mismatch degrades to an empty
     * section (the client already tolerates 404/shape issues; this catches
     * transport errors too).
     */
    private static List<McpServerInfo> safeMcp(OpencodeClient client, String directory) {
        try {
            List<McpServerInfo> servers = client.getMcpServers(directory);
            return servers == null ? List.of() : servers;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<SkillInfo> safeSkills(OpencodeClient client, String directory) {
        try {
            List<SkillInfo> skills = client.getSkills(directory);
            return skills == null ? List.of() : skills;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void reconnect() {
        setContentDescription("Reconnecting...");
        ViewLoadSupport.load("Reconnecting opencode", () -> {
            OpencodeConnection.getInstance().refresh();
            return null;
        }, ignored -> refresh(), this::showError);
    }

    private void showNodes(List<ServerNode> nodes) {
        if (viewer.getControl().isDisposed()) {
            return;
        }
        roots = List.copyOf(nodes);
        current = nodes.stream().filter(n -> n.primary).findFirst().orElse(null);
        if (selectedServer != null) {
            String selectedUrl = selectedServer.url;
            selectedServer = nodes.stream().filter(n -> java.util.Objects.equals(n.url, selectedUrl))
                    .findFirst().orElse(null);
        }
        // Subscribe once to live server events so the Sessions tree self-updates.
        if (eventListener == null) {
            eventListener = this::onEvent;
            OpencodeConnection.getInstance().addEventListener(eventListener);
        }
        if (trackerListener == null) {
            trackerListener = this::onTrackerChanged;
            tracker.addListener(trackerListener);
        }
        // Backstop the SSE-driven busy icons with one /session/status
        // poller per connection: remote roots have no event stream here,
        // and any connection can drop or miss events. Reconciled per poll
        // into the owning node's statuses (busy set in, stale busy out).
        updateBusyPollers(nodes);
        // Drop snippets of sessions no loaded server knows (e.g. the server
        // restarted): they could never be displayed again.
        Set<String> knownIds = new HashSet<>();
        for (ServerNode node : nodes) {
            for (Session session : node.sessions) {
                if (session.id() != null) {
                    knownIds.add(session.id());
                }
            }
        }
        sessionActivity.retainAll(knownIds);
        // Pass a list as input (NEVER a tree element itself): if the input
        // equals a tree element, the TreeViewer's expansion logic can misbehave.
        viewer.setInput(roots);
        // Default: every server + categories expanded so agents and the
        // top-level sessions are visible, but the subagent children stay
        // collapsed (use Expand All to open them).
        List<Object> expanded = new ArrayList<>();
        for (ServerNode node : nodes) {
            expanded.add(node);
            expanded.add(node.agentsCategory);
            expanded.add(node.sessionsCategory);
            expanded.add(node.filesCategory);
            expanded.add(node.workingSetCategory);
            expanded.add(node.mcpCategory);
            expanded.add(node.skillsCategory);
        }
        viewer.setExpandedElements(expanded.toArray());
        updateContentDescription();
        updateProjectHeader();
    }

    // ---------- live updates (driven by /event SSE via core) ----------

    /** Called on the SSE thread; feeds the tracker, then hops to the UI thread to mutate the viewer's model. */
    private void onEvent(OpencodeEvent event) {
        tracker.apply(event);
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> handleEvent(event));
        }
    }

    /** Called on the SSE thread when derived activity changes; hop to the UI thread for a coalesced refresh. */
    private void onTrackerChanged() {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                if (viewer != null && !viewer.getControl().isDisposed()) {
                    scheduleRefresh();
                }
            });
        }
    }

    // ---------- live busy state (polled /session/status backstop) ----------

    /**
     * Ensures exactly one {@link SessionBusyPoller} runs per loaded
     * connection client, and disposes pollers whose client vanished (a
     * reconnect creates a new client, so the old poller retires with it).
     * Called on the UI thread from {@link #showNodes}; results arrive on
     * {@link #onBusySessions(OpencodeClient, Set)}. Offline roots (no
     * client) are skipped — they have nothing to poll until a refresh
     * reloads them.
     */
    private void updateBusyPollers(List<ServerNode> nodes) {
        Set<OpencodeClient> wanted = new HashSet<>();
        for (ServerNode node : nodes) {
            if (node.client != null) {
                wanted.add(node.client);
            }
        }
        busyPollers.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().dispose();
            lastBusyByClient.remove(entry.getKey());
            return true;
        });
        for (OpencodeClient client : wanted) {
            busyPollers.computeIfAbsent(client, c -> {
                SessionBusyPoller poller = new SessionBusyPoller(c,
                        SessionBusyPoller.DEFAULT_INTERVAL_MILLIS, this::logBusyPollError);
                poller.addListener(busy -> onBusySessions(c, busy));
                poller.start();
                return poller;
            });
        }
    }

    /**
     * One poller's fresh busy set (poller thread): reconcile it into every
     * node sharing that client — the same UI-thread hop the SSE listeners
     * use — and coalesce a viewer refresh when anything actually changed
     * (unchanged polls stay silent, so the tree never flickers at the poll
     * rhythm). Sessions that left the busy set also lose their streamed
     * snippet (see {@link #clearFinishedActivity}).
     */
    private void onBusySessions(OpencodeClient client, Set<String> busy) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (viewer == null || viewer.getControl().isDisposed()) {
                return;
            }
            boolean changed = false;
            for (ServerNode node : roots) {
                if (node.client == client && SessionBusyPoller.mergeInto(node.statuses, busy)) {
                    changed = true;
                }
            }
            if (clearFinishedActivity(client, busy)) {
                changed = true;
            }
            if (changed) {
                scheduleRefresh();
            }
        });
    }

    /**
     * A poll's busy set names sessions that stopped working since the
     * previous poll (present then, absent now — absent means idle since
     * opencode 1.18.23): drop their streamed snippets so the row stops
     * showing text the moment the work ended, even when the SSE idle and
     * completion events were both missed. Called on the UI thread from
     * {@link #onBusySessions}.
     *
     * @return whether any snippet was dropped (drives the refresh)
     */
    private boolean clearFinishedActivity(OpencodeClient client, Set<String> busy) {
        Set<String> current = busy == null ? Set.of() : busy;
        Set<String> previous = lastBusyByClient.put(client, Set.copyOf(current));
        if (previous == null) {
            return false;   // first poll for this client: nothing to compare against
        }
        boolean changed = false;
        for (String id : previous) {
            if (!current.contains(id) && sessionActivity.snippet(id) != null) {
                sessionActivity.clear(id);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Logs one poll failure. WARNING, not ERROR: transient by construction —
     * the poller reports only the transition into failure and keeps polling,
     * so an unreachable server cannot flood the error log while the view
     * stays open.
     */
    private void logBusyPollError(Exception error) {
        UiActivator activator = UiActivator.getDefault();
        if (activator != null) {
            activator.getLog().log(new Status(Status.WARNING, UiActivator.PLUGIN_ID,
                    "Polling /session/status failed; busy icons may go stale until it recovers", error));
        }
    }

    private void handleEvent(OpencodeEvent event) {
        ServerNode node = current;
        if (node == null || event == null || event.type() == null) {
            return;
        }
        switch (event.type()) {
            case "session.created", "session.renamed", "session.moved" -> {
                // v2 lifecycle events carry only ids/titles, not the full Session
                // object v1 nested under "info" - reload the node instead of
                // reconstructing a half-populated session.
                scheduleReload();
            }
            case "session.deleted" -> {
                String sid = sessionIdOf(event);
                if (sid != null) {
                    node.sessions.removeIf(x -> sid.equals(x.id()));
                    node.statuses.remove(sid);
                    node.activity.remove(sid);
                    sessionActivity.clear(sid);
                    scheduleRefresh();
                }
            }
            case "session.status" -> {
                String sid = event.string("sessionID");
                String statusType = extractStatusType(event);
                if (sid != null) {
                    if (statusType != null) {
                        node.statuses.put(sid, new SessionStatus(statusType));
                    }
                    if (!"busy".equalsIgnoreCase(statusType) && !"retry".equalsIgnoreCase(statusType)) {
                        node.activity.remove(sid); // idle -> stop showing thinking/running
                        sessionActivity.clear(sid); // ...and the streamed snippet
                    }
                    scheduleRefresh();
                }
            }
            case "session.idle" -> {
                String sid = event.string("sessionID");
                if (sid != null) {
                    node.statuses.put(sid, new SessionStatus("idle"));
                    node.activity.remove(sid);
                    sessionActivity.clear(sid);
                    scheduleRefresh();
                }
            }
            case "session.execution.succeeded", "session.execution.failed",
                    "session.execution.interrupted" -> {
                // defense in depth: the turn is over, even if session.idle was missed
                // (v1 read the same signal off a completed "message.updated")
                String sid = event.string("sessionID");
                if (sid != null) {
                    node.statuses.put(sid, new SessionStatus("idle"));
                    node.activity.remove(sid);
                    sessionActivity.clear(sid);
                    scheduleRefresh();
                }
            }
            case "session.text.started", "session.reasoning.started", "session.tool.called",
                    "session.tool.input.started", "session.tool.progress" -> {
                String sid = event.string("sessionID");
                String label = ServerLabels.activityLabel(event.type());
                if (sid != null && label != null) {
                    node.activity.put(sid, label);
                    scheduleRefresh();
                }
            }
            case "session.text.delta", "session.reasoning.delta" -> {
                // live "what is it doing": fold the streamed delta into the
                // session's snippet (throttled ~2/s per session inside
                // SessionActivity); a published change schedules the
                // already-coalesced refresh, everything else stays silent.
                // Subagents stream their own sessionID and are covered by
                // the same path.
                String field = "session.reasoning.delta".equals(event.type()) ? "reasoning" : "text";
                if (sessionActivity.onDelta(event.string("sessionID"), field, event.string("delta"))) {
                    scheduleRefresh();
                }
            }
            default -> {
                // other event types ignored for now
            }
        }
    }

    /** The session id of a v2 session event ({@code sessionID}, or {@code id} on older frames). */
    private static String sessionIdOf(OpencodeEvent event) {
        String sid = event.string("sessionID");
        return sid != null ? sid : event.string("id");
    }

    /** Reads the status type from {@code session.status}: v2 nests it as {@code status.type}. */
    private static String extractStatusType(OpencodeEvent event) {
        String flat = event.string("status");
        return flat != null ? flat : event.at("status.type");
    }

    // activity label mapping lives in ServerLabels (pure, tested)

    /**
     * Coalesce session-lifecycle events into one full reload (~2s). v2's
     * {@code session.created} carries ids only, so the node has to be refetched
     * rather than patched in place.
     */
    private void scheduleReload() {
        if (viewer == null || viewer.getControl().isDisposed() || reloadPending) {
            return;
        }
        reloadPending = true;
        Display.getDefault().timerExec(2000, () -> {
            reloadPending = false;
            if (viewer == null || viewer.getControl().isDisposed()) {
                return;
            }
            refresh();
        });
    }

    /** Coalesce rapid events into one viewer refresh (~3/sec). */
    private void scheduleRefresh() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        Display.getDefault().timerExec(300, () -> {
            refreshPending = false;
            if (viewer == null || viewer.getControl().isDisposed()) {
                return;
            }
            viewer.refresh();
            updateContentDescription();
        });
    }

    private void updateContentDescription() {
        List<ServerNode> nodes = roots;
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        ServerNode primary = current;
        if (nodes.size() == 1 && primary != null) {
            // single root: exactly the former description, but with the
            // working count derived from the busy set (poller + SSE) so it
            // is right for any connection, not just the event-streamed one
            long working = ServerLabels.busyCount(primary.sessions, primary.statuses);
            setContentDescription((primary.healthy ? "Connected" : "Unreachable") + ": " + primary.url
                    + "  •  live  •  " + primary.agents.size() + " agents, " + primary.sessions.size() + " sessions"
                    + (working > 0 ? "  •  " + working + " working" : "")
                    + projectVcsSuffix());
            return;
        }
        long up = nodes.stream().filter(n -> n.healthy).count();
        int agents = nodes.stream().mapToInt(n -> n.agents.size()).sum();
        int sessions = nodes.stream().mapToInt(n -> n.sessions.size()).sum();
        StringBuilder sb = new StringBuilder();
        if (primary != null) {
            sb.append(primary.healthy ? "Connected" : "Unreachable").append(": ").append(primary.url);
        } else {
            sb.append("opencode servers");
        }
        sb.append("  •  ").append(nodes.size()).append(" servers (").append(up).append(" up)")
                .append("  •  ").append(agents).append(" agents, ").append(sessions).append(" sessions");
        if (primary != null) {
            long working = ServerLabels.busyCount(primary.sessions, primary.statuses);
            if (working > 0) {
                sb.append("  •  ").append(working).append(" working");
            }
        }
        sb.append(projectVcsSuffix());
        setContentDescription(sb.toString());
    }

    // ---------- project/VCS header (SWT-free logic in ProjectVcs) ----------

    /** Loads the project/VCS line of the selected connection (the primary root as the fallback). */
    private void updateProjectHeader() {
        ServerNode node = selectedServer != null ? selectedServer : current;
        if (node == null || node.client == null) {
            projectVcs = ProjectVcs.UNKNOWN;
            setTitleToolTip("");
            updateContentDescription();
            return;
        }
        OpencodeClient client = node.client;
        ViewLoadSupport.load("Loading project VCS", () -> ProjectVcs.load(client, null),
                vcs -> showProjectVcs(client, vcs),
                error -> showProjectVcs(client, ProjectVcs.UNKNOWN));
    }

    /** Applies a loaded header; results superseded by a newer selection or refresh are dropped. */
    private void showProjectVcs(OpencodeClient client, ProjectVcs vcs) {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        ServerNode node = selectedServer != null ? selectedServer : current;
        if (node == null || node.client != client) {
            return;
        }
        projectVcs = vcs;
        setTitleToolTip(headerToolTip(vcs));
        updateContentDescription();
    }

    /**
     * The header tooltip: the project/VCS detail, prefixed with the cwd
     * mismatch warning when the server's project and the active workspace
     * project point at different directories (see {@link CwdCheck}).
     */
    private String headerToolTip(ProjectVcs vcs) {
        String tooltip = vcs.tooltip();
        String warning = CwdCheck.check(vcs.projectPath(), activeProjectLocation()).warningLine();
        if (warning.isEmpty()) {
            return tooltip;
        }
        return tooltip.isEmpty() ? warning : warning + "\n" + tooltip;
    }

    /**
     * The location of the active workspace project, derived from the workbench
     * page's selection (the selected resource's project, adaptable elements
     * included); {@code null} when the selection yields no resource.
     */
    private Path activeProjectLocation() {
        Object selection = getSite().getPage().getSelection();
        Object first = (selection instanceof org.eclipse.jface.viewers.IStructuredSelection structured
                && !structured.isEmpty()) ? structured.getFirstElement() : null;
        IResource resource = first instanceof IResource res ? res
                : first instanceof IAdaptable adaptable ? adaptable.getAdapter(IResource.class) : null;
        if (resource == null) {
            return null;
        }
        var location = resource.getProject().getLocation();
        return location == null ? null : location.toFile().toPath();
    }

    /** The project/VCS part of the description line; empty while unknown (degrades silently). */
    private String projectVcsSuffix() {
        String summary = projectVcs.summary();
        return summary.isEmpty() ? "" : "  •  " + summary;
    }

    private void showError(Throwable e) {
        if (viewer.getControl().isDisposed()) {
            return;
        }
        viewer.setInput(null);
        setContentDescription("Error: " + ViewLoadSupport.message(e));
        projectVcs = ProjectVcs.UNKNOWN;
        setTitleToolTip("");
        UiActivator.getDefault().getLog().log(
                new Status(Status.ERROR, UiActivator.PLUGIN_ID, "Failed to load opencode server", e));
    }

    // ---------- label helpers (delegate to the SWT-free ServerLabels) ----------

    /** @return the root node owning the given session (sessions nest within their own server). */
    private ServerNode ownerOf(Session session) {
        return ServerLabels.ownerOf(roots, session, node -> node.sessions, current);
    }

    String name(Object element) {
        if (element instanceof ServerNode node) {
            return ServerLabels.serverName(node.info());
        }
        if (element instanceof CategoryNode c) {
            int count = switch (c.kind) {
                case AGENTS -> c.server.agents.size();
                case SESSIONS -> c.server.sessions.size();
                case ACTIVE_FILES -> tracker.snapshot().files().size();
                case WORKING_SET -> c.server.workingSet.size();
                case MCP_SERVERS -> c.server.mcpServers.size();
                case SKILLS -> c.server.skills.size();
            };
            // the two session-bearing categories aggregate their working
            // state in the name, so activity is visible while collapsed
            int working = switch (c.kind) {
                case AGENTS -> workingAgents(c.server);
                case SESSIONS -> (int) ServerLabels.busyCount(c.server.sessions, c.server.statuses);
                default -> 0;
            };
            return ServerLabels.categoryName(c.label, count, working);
        }
        if (element instanceof FileActivity f) {
            return ServerLabels.fileActivityName(f);
        }
        if (element instanceof FileStatus file) {
            return WorkingSet.entryLabel(file);
        }
        if (element instanceof McpServerInfo mcp) {
            return mcp.id() == null ? "(unnamed)" : mcp.id();
        }
        if (element instanceof SkillInfo skill) {
            return skill.name() == null ? "(unnamed)" : skill.name();
        }
        if (element instanceof Agent a) {
            // definition row: bare name, plus " — n running" while live
            // sessions run as this agent and "n working" while any of them
            // works (prominently, in the name column)
            ServerNode owner = AgentSessions.serverOfAgent(roots, a, node -> node.agents);
            int running = owner == null ? 0 : AgentSessions.runningCount(owner.sessions, a);
            int working = owner == null ? 0 : AgentSessions.workingCount(owner.sessions, owner.statuses, a);
            return AgentSessions.agentName(a.name(), running, working);
        }
        if (element instanceof AgentSessionNode nested) {
            // nested under the agent row: bare title (the agent is the parent row)
            return ServerLabels.nestedSessionName(nested.session(),
                    isWorking(agentNodeOwner(nested.agent()), nested.session()));
        }
        if (element instanceof Session s) {
            return ServerLabels.sessionName(s, isWorking(ownerOf(s), s));
        }
        return String.valueOf(element);
    }

    /**
     * A session reads as working while it is busy itself or any subagent
     * under it works (a parent waiting on a busy fleet worker included) —
     * drives the row's {@code "  • working"} suffix in both tree positions.
     */
    private static boolean isWorking(ServerNode owner, Session session) {
        if (owner == null || session == null) {
            return false;
        }
        return ServerLabels.isBusy(owner.statuses, session)
                || ServerLabels.hasBusyDescendant(owner.sessions, owner.statuses, session.id());
    }

    /** @return the server whose Agents category holds the given agent definition. */
    private ServerNode agentNodeOwner(Agent agent) {
        return AgentSessions.serverOfAgent(roots, agent, node -> node.agents);
    }

    /** How many agent definitions of the server have a currently working live session. */
    private static int workingAgents(ServerNode server) {
        int working = 0;
        for (Agent agent : server.agents) {
            if (AgentSessions.workingCount(server.sessions, server.statuses, agent) > 0) {
                working++;
            }
        }
        return working;
    }

    String detail(Object element) {
        if (element instanceof AgentSessionNode nested) {
            element = nested.session(); // same rendering as the Sessions category rows
        }
        if (element instanceof ServerNode sn) {
            return ServerLabels.serverDetail(sn.info());
        }
        if (element instanceof CategoryNode c) {
            if (c.kind == CategoryKind.SESSIONS) {
                return ServerLabels.sessionsCategoryDetail(c.server.sessions, c.server.statuses);
            }
            if (c.kind == CategoryKind.WORKING_SET) {
                return c.server.workingSet.summary();
            }
            return "";
        }
        if (element instanceof McpServerInfo mcp) {
            return mcp.status() == null ? "" : mcp.status();
        }
        if (element instanceof SkillInfo skill) {
            String description = skill.description();
            if (description == null || description.isBlank()) {
                return "";
            }
            return description.length() <= 90 ? description : description.substring(0, 90) + "…";
        }
        if (element instanceof Agent a) {
            return ServerLabels.agentDetail(a);
        }
        if (element instanceof Session s) {
            // what it is doing, most specific first: the streamed text
            // snippet (message.part.delta — usually absent while a tool
            // runs, so it naturally alternates with the tracker's tool
            // label within a turn), then the derived tracker label, then
            // the legacy part label; the status type only when nothing
            // live is known
            ServerNode owner = ownerOf(s);
            String snippet = sessionActivity.snippet(s.id());
            String live = (owner != null && owner.primary)
                    ? ServerLabels.trackerLabel(tracker.snapshot(), s.id())
                    : null;
            if (live == null && owner != null) {
                live = owner.activity.get(s.id());
            }
            if (snippet != null) {
                live = snippet;
            }
            return ServerLabels.sessionDetail(s, live,
                    ServerLabels.statusType(owner == null ? null : owner.statuses, s));
        }
        return "";
    }

    Image icon(Object element) {
        if (element instanceof ServerNode) {
            return UiActivator.image(UiActivator.ICON_SERVER);
        }
        if (element instanceof CategoryNode) {
            return UiActivator.image(UiActivator.ICON_CATEGORY);
        }
        if (element instanceof AgentSessionNode nested) {
            return icon(nested.session()); // same (busy-aware) icon as the Sessions category
        }
        if (element instanceof Session s) {
            // busy/thinking sessions get a distinct (orange) bubble so changes are visible at a glance;
            // the busy flag comes from the node's statuses, fed by SSE events AND the /session/status poller
            ServerNode node = ownerOf(s);
            boolean active = ServerLabels.isBusy(node == null ? null : node.statuses, s)
                    || (node != null && node.activity.containsKey(s.id()))
                    || ServerLabels.sessionActive(tracker.snapshot(), s.id());
            return UiActivator.image(SessionBusyPoller.iconKey(active));
        }
        if (element instanceof Agent) {
            return UiActivator.image(UiActivator.ICON_AGENT);
        }
        if (element instanceof McpServerInfo) {
            return UiActivator.image(UiActivator.ICON_MCP);
        }
        if (element instanceof SkillInfo) {
            return UiActivator.image(UiActivator.ICON_SKILL);
        }
        if (element instanceof FileActivity) {
            return UiActivator.image(UiActivator.ICON_FILE);
        }
        if (element instanceof FileStatus) {
            return UiActivator.image(UiActivator.ICON_FILE);
        }
        return null;
    }

    /**
     * Tree content provider: servers -> categories -> items; sessions nest by
     * parentID within their own server, and agent definition rows nest the
     * live sessions running as that agent (wrapped, busy first). Compatible
     * with {@code SWT.VIRTUAL}:
     * {@link #getChildren(Object)} serves the in-memory lists only (no IO), so
     * the viewer can resolve items lazily on expand.
     */
    private static final class TreeContentProvider implements ITreeContentProvider {
        private final ActivityTracker tracker;
        private List<ServerNode> roots = List.of();

        TreeContentProvider(ActivityTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public Object[] getElements(Object input) {
            if (input instanceof ServerNode node) {
                return new Object[] { node };
            }
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
            if (parent instanceof ServerNode node) {
                List<Object> children = new ArrayList<>();
                children.add(node.agentsCategory);
                children.add(node.sessionsCategory);
                if (node.primary && !tracker.snapshot().files().isEmpty()) {
                    children.add(node.filesCategory); // hidden while nothing is being worked on
                }
                children.add(node.workingSetCategory);
                children.add(node.mcpCategory);
                children.add(node.skillsCategory);
                return children.toArray();
            }
            if (parent instanceof CategoryNode category) {
                if (category.kind == CategoryKind.AGENTS) {
                    return category.server.agents.toArray();
                }
                if (category.kind == CategoryKind.SESSIONS) {
                    return ServerLabels.topLevelSessions(category.server.sessions).toArray();
                }
                if (category.kind == CategoryKind.ACTIVE_FILES) {
                    return activeFiles();
                }
                if (category.kind == CategoryKind.WORKING_SET) {
                    return category.server.workingSet.entries().toArray();
                }
                if (category.kind == CategoryKind.MCP_SERVERS) {
                    return category.server.mcpServers.toArray();
                }
                if (category.kind == CategoryKind.SKILLS) {
                    return category.server.skills.toArray();
                }
                return new Object[0];
            }
            if (parent instanceof Agent agent) {
                // live sessions running as this agent (top-level only, busy
                // first) — wrapped so they stay distinct from their row in
                // the Sessions category (one element = one tree item)
                ServerNode owner = AgentSessions.serverOfAgent(roots, agent, node -> node.agents);
                if (owner == null) {
                    return new Object[0];
                }
                return AgentSessions.sessionsOf(owner.sessions, agent, owner.statuses).stream()
                        .map(session -> new AgentSessionNode(agent, session))
                        .toArray();
            }
            if (parent instanceof Session session) {
                ServerNode owner = ownerOf(session);
                return owner != null ? ServerLabels.childrenOf(owner.sessions, session.id()).toArray() : new Object[0];
            }
            return new Object[0];
        }

        /**
         * The tree parent of an element (used by the viewer to preserve
         * expansion state across refreshes): the category's server, the
         * agent's agents category, the agent of an agent-nested session row,
         * the session's parent session (or the owning server's Sessions
         * category for top-level sessions) and the primary's Active files
         * category for file activities.
         */
        @Override
        public Object getParent(Object element) {
            if (element instanceof CategoryNode category) {
                return category.server;
            }
            if (element instanceof AgentSessionNode nested) {
                return nested.agent();
            }
            if (element instanceof Agent agent) {
                // identity-first lookup: two servers can define structurally
                // equal agents, and the viewer passes the exact element
                ServerNode owner = AgentSessions.serverOfAgent(roots, agent, node -> node.agents);
                return owner != null ? owner.agentsCategory : null;
            }
            if (element instanceof McpServerInfo mcp) {
                for (ServerNode node : roots) {
                    if (node.mcpServers.contains(mcp)) {
                        return node.mcpCategory;
                    }
                }
                return null;
            }
            if (element instanceof SkillInfo skill) {
                for (ServerNode node : roots) {
                    if (node.skills.contains(skill)) {
                        return node.skillsCategory;
                    }
                }
                return null;
            }
            if (element instanceof FileStatus file) {
                for (ServerNode node : roots) {
                    if (node.workingSet.entries().contains(file)) {
                        return node.workingSetCategory;
                    }
                }
                return null;
            }
            if (element instanceof Session session) {
                ServerNode owner = ownerOf(session);
                if (owner == null) {
                    return null;
                }
                Session parent = ServerLabels.parentSession(owner.sessions, session);
                return parent != null ? parent : owner.sessionsCategory;
            }
            if (element instanceof FileActivity) {
                for (ServerNode node : roots) {
                    if (node.primary) {
                        return node.filesCategory;
                    }
                }
                return null;
            }
            return null;
        }

        @Override
        public boolean hasChildren(Object parent) {
            if (parent instanceof ServerNode) {
                return true;
            }
            if (parent instanceof CategoryNode category) {
                if (category.kind == CategoryKind.AGENTS) {
                    return !category.server.agents.isEmpty();
                }
                if (category.kind == CategoryKind.SESSIONS) {
                    return category.server.sessions.stream().anyMatch(s -> s.parentID() == null);
                }
                if (category.kind == CategoryKind.ACTIVE_FILES) {
                    return !tracker.snapshot().files().isEmpty();
                }
                if (category.kind == CategoryKind.WORKING_SET) {
                    return !category.server.workingSet.isEmpty();
                }
                if (category.kind == CategoryKind.MCP_SERVERS) {
                    return !category.server.mcpServers.isEmpty();
                }
                if (category.kind == CategoryKind.SKILLS) {
                    return !category.server.skills.isEmpty();
                }
            }
            if (parent instanceof Agent agent) {
                ServerNode owner = AgentSessions.serverOfAgent(roots, agent, node -> node.agents);
                return owner != null && AgentSessions.hasSessions(owner.sessions, agent);
            }
            if (parent instanceof Session session) {
                ServerNode owner = ownerOf(session);
                return owner != null && ServerLabels.hasSessionChildren(owner.sessions, session.id());
            }
            return false;
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
            // Input is a List of ServerNodes (see ServerView.showNodes). Keep
            // the roots so session->subagent nesting can resolve the owner.
            if (newInput instanceof java.util.Collection<?> collection) {
                List<ServerNode> resolved = new ArrayList<>();
                for (Object element : collection) {
                    if (element instanceof ServerNode node) {
                        resolved.add(node);
                    }
                }
                roots = List.copyOf(resolved);
            } else if (newInput instanceof ServerNode node) {
                roots = List.of(node);
            } else {
                roots = List.of();
            }
        }

        @Override
        public void dispose() {
            // stateless beyond roots
        }

        /** @return the root whose session list contains the given session's id (shared logic in ServerLabels). */
        private ServerNode ownerOf(Session session) {
            return ServerLabels.ownerOf(roots, session, node -> node.sessions,
                    roots.isEmpty() ? null : roots.get(0));
        }

        private Object[] activeFiles() {
            return tracker.snapshot().files().values().stream()
                    .sorted(Comparator.comparing(FileActivity::file))
                    .toArray();
        }
    }

    @Override
    public void dispose() {
        // stop the busy pollers first: they feed asyncExec callbacks that
        // all re-check the viewer's disposal, so a late callback is harmless
        for (SessionBusyPoller poller : busyPollers.values()) {
            poller.dispose();
        }
        busyPollers.clear();
        lastBusyByClient.clear();
        sessionActivity.clearAll();
        if (trackerListener != null) {
            tracker.removeListener(trackerListener);
            trackerListener = null;
        }
        if (eventListener != null) {
            try {
                OpencodeConnection.getInstance().removeEventListener(eventListener);
            } catch (Throwable ignored) {
                // best-effort during dispose
            }
            eventListener = null;
        }
        if (connectionsListener != null) {
            try {
                ConnectionsManager.getDefault().removeListener(connectionsListener);
            } catch (Throwable ignored) {
                // best-effort during dispose
            }
            connectionsListener = null;
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.getControl().setFocus();
        }
    }
}
