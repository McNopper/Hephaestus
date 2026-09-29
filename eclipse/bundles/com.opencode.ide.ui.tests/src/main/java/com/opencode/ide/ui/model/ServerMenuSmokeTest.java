package com.opencode.ide.ui.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.part.ViewPart;
import org.junit.Assume;
import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.activity.ActivityTracker;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Opt-in native SWT/JFace smoke: -Dopencode.ui.smoke=true with a desktop.
 * Exercises the actual Server menu wiring without a server, workspace or
 * workbench startup. Reflection stays here to avoid exposing view internals
 * as a library API just for tests.
 */
public class ServerMenuSmokeTest {

    @Test
    public void realMenuTracksTreePathOwnerAndRejectsMultiSelection() throws Exception {
        Assume.assumeTrue("requires an isolated desktop run", Boolean.getBoolean("opencode.ui.smoke"));
        Display display = new Display();
        Shell shell = new Shell(display);
        try {
            shell.setText("Batch B — isolated Server menu smoke");
            shell.setLayout(new FillLayout());
            TreeViewer viewer = new TreeViewer(shell, SWT.MULTI | SWT.BORDER);
            viewer.setUseHashlookup(true);
            Class<?> viewType = Class.forName("com.opencode.ide.ui.views.ServerView", true,
                    ServerSelection.class.getClassLoader());
            ViewPart view = (ViewPart) viewType.getConstructor().newInstance();
            IViewSite site = (IViewSite) Proxy.newProxyInstance(IViewSite.class.getClassLoader(),
                    new Class<?>[] { IViewSite.class }, (proxy, method, args) -> null);
            view.init(site);
            var field = viewType.getDeclaredField("viewer");
            field.setAccessible(true);
            field.set(view, viewer);
            Session session = new Session("same", null, "Same session on two servers", null,
                    null, null, null, null, null, null, null);
            Agent agent = new Agent("build", "build", null, "primary", null, null, null,
                    null, null, null);
            OpencodeClient client = (OpencodeClient) Proxy.newProxyInstance(OpencodeClient.class.getClassLoader(),
                    new Class<?>[] { OpencodeClient.class }, (proxy, method, args) -> null);
            Object primary = server(viewType, client, true, agent, session);
            Object remote = server(viewType, client, false, agent, session);
            viewer.setContentProvider(new ITreeContentProvider() {
                @Override public Object[] getElements(Object input) { return new Object[] { primary, remote }; }
                @Override public Object[] getChildren(Object parent) {
                    return parent == primary || parent == remote ? new Object[] { session, agent } : new Object[0];
                }
                @Override public Object getParent(Object element) { return null; }
                @Override public boolean hasChildren(Object element) { return element == primary || element == remote; }
            });
            viewer.setInput(List.of(primary, remote));
            viewer.expandAll();
            var menuMethod = viewType.getDeclaredMethod("createContextMenu");
            menuMethod.setAccessible(true);
            menuMethod.invoke(view);
            shell.setSize(650, 260);
            shell.open();
            while (display.readAndDispatch()) { }
            Menu menu = viewer.getControl().getMenu();
            select(viewer, menu, new TreePath(new Object[] { primary, session }));
            enabled(menu, "Open in Chat", true);
            enabled(menu, "Abort session", true);
            enabled(menu, "Delete session...", true);
            enabled(menu, "MCP servers\u2026", true);
            select(viewer, menu, new TreePath(new Object[] { remote, session }));
            enabled(menu, "Open in Chat", false);
            enabled(menu, "Live output", false);
            enabled(menu, "Abort session", true);
            enabled(menu, "Delete session...", true);
            enabled(menu, "MCP servers\u2026", true);
            select(viewer, menu, new TreePath(new Object[] { primary, agent }));
            enabled(menu, "New session with this agent", true);
            enabled(menu, "Abort session", false);
            select(viewer, menu, new TreePath(new Object[] { remote, agent }));
            enabled(menu, "New session with this agent", false);
            select(viewer, menu, new TreePath(new Object[] { primary, session }),
                    new TreePath(new Object[] { remote, session }));
            enabled(menu, "Delete session...", false);
            enabled(menu, "Copy session id", false);
            enabled(menu, "MCP servers\u2026", false); // no single owning server
            select(viewer, menu, new TreePath(new Object[] { primary }));
            enabled(menu, "Abort session", false);
            enabled(menu, "New session with this agent", false);
            enabled(menu, "MCP servers\u2026", true); // server-level action works on the root
        } finally {
            shell.dispose();
            display.dispose();
        }
    }

    private static Object server(Class<?> viewType, OpencodeClient client, boolean primary,
            Agent agent, Session... sessions) throws Exception {
        Class<?> node = Class.forName(viewType.getName() + "$ServerNode", true, viewType.getClassLoader());
        var constructor = java.util.Arrays.stream(node.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 14).findFirst().orElseThrow();
        constructor.setAccessible(true);
        Map<String, SessionStatus> statuses = new java.util.HashMap<>();
        for (Session session : sessions) {
            statuses.put(session.id(), new SessionStatus("busy"));
        }
        return constructor.newInstance(primary, primary ? "primary" : "remote", "connect", "http://localhost",
                true, "test", null, client, List.of(agent), List.of(sessions),
                statuses, List.of(), List.of(), WorkingSet.EMPTY);
    }

    private static void select(TreeViewer viewer, Menu menu, TreePath... paths) {
        // Select physical rows like a mouse click. TreeViewer.setSelection can
        // collapse equal domain objects to the first match on another root.
        var items = new org.eclipse.swt.widgets.TreeItem[paths.length];
        for (int i = 0; i < paths.length; i++) {
            var children = viewer.getTree().getItems();
            for (int segment = 0; segment < paths[i].getSegmentCount(); segment++) {
                Object element = paths[i].getSegment(segment);
                items[i] = java.util.Arrays.stream(children).filter(item -> item.getData() == element)
                        .findFirst().orElseThrow();
                children = items[i].getItems();
            }
        }
        viewer.getTree().setSelection(items);
        viewer.getTree().notifyListeners(SWT.Selection, new Event());
        assertArrayEquals("selection must retain the requested owner paths", paths,
                ((org.eclipse.jface.viewers.ITreeSelection) viewer.getSelection()).getPaths());
        menu.notifyListeners(SWT.Show, new Event());
    }

    private static void enabled(Menu menu, String text, boolean expected) {
        var item = java.util.Arrays.stream(menu.getItems()).filter(i -> text.equals(i.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing action: " + text));
        assertEquals(text, expected, item.getEnabled());
    }

    /**
     * U-041 (Server-view half): subagent sessions nest under their parent
     * session's row inside the Agents category too - the same {@code
     * parentID} layer the Sessions category serves - exercised through the
     * view's real tree content provider, without a workbench. Each nested
     * child is an {@code AgentSessionNode} wrapper, so it opens its
     * transcript through the existing double-click seam and keeps the
     * busy-aware session icon for free.
     */
    @Test
    public void agentRowsNestSubagentsUnderTheirParentSession() throws Exception {
        Assume.assumeTrue("requires an isolated desktop run", Boolean.getBoolean("opencode.ui.smoke"));
        Class<?> viewType = Class.forName("com.opencode.ide.ui.views.ServerView", true,
                ServerSelection.class.getClassLoader());
        Session parent = new Session("ses_parent", null, "Build session", "build", null,
                null, null, null, null, null, null);
        Session subagent = new Session("ses_child", null, "Spawned subagent", "general", "ses_parent",
                null, null, null, null, null, null);
        Session lone = new Session("ses_lone", null, "Lonely runner", "build", null,
                null, null, null, null, null, null);
        Agent agent = new Agent("build", "build", null, "primary", null, null, null,
                null, null, null);
        OpencodeClient client = (OpencodeClient) Proxy.newProxyInstance(OpencodeClient.class.getClassLoader(),
                new Class<?>[] { OpencodeClient.class }, (proxy, method, args) -> null);
        Object primary = server(viewType, client, true, agent, parent, subagent, lone);
        Object provider = provider(viewType);
        invoke(provider, "inputChanged", null, null, List.of(primary));

        // the agent row nests its top-level sessions only (the subagent
        // arrives one level down, under its parent session)
        List<String> direct = new ArrayList<>();
        for (Object wrapper : (Object[]) invoke(provider, "getChildren", agent)) {
            direct.add(wrapperSessionId(wrapper));
        }
        assertEquals(2, direct.size());
        assertTrue(direct.contains("ses_parent"));
        assertTrue(direct.contains("ses_lone"));
        assertFalse("subagents do not nest directly under the agent row", direct.contains("ses_child"));

        Object parentWrapper = wrapperOf(provider, agent, "ses_parent");
        Object loneWrapper = wrapperOf(provider, agent, "ses_lone");
        assertArrayEquals(new String[] {"ses_child"}, childIds(provider, parentWrapper).toArray());
        assertTrue((Boolean) invoke(provider, "hasChildren", parentWrapper));
        assertTrue("a session without children stays a leaf row",
                childIds(provider, loneWrapper).isEmpty());
        assertFalse((Boolean) invoke(provider, "hasChildren", loneWrapper));

        // the viewer's parent mapping follows the wrapper hierarchy (records
        // compare structurally, exactly like the viewer's element mapping)
        Object childWrapper = childWrappers(provider, parentWrapper).get(0);
        assertEquals(parentWrapper, invoke(provider, "getParent", childWrapper));
        assertEquals(agent, invoke(provider, "getParent", parentWrapper));
        assertEquals(agent, invoke(provider, "getParent", loneWrapper));
    }

    /** The view's tree content provider, built around a fresh activity tracker. */
    private static Object provider(Class<?> viewType) throws Exception {
        Class<?> type = Class.forName(viewType.getName() + "$TreeContentProvider", true,
                viewType.getClassLoader());
        var constructor = type.getDeclaredConstructor(ActivityTracker.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new ActivityTracker());
    }

    /** Invokes a declared method on the provider with the given arguments. */
    private static Object invoke(Object provider, String name, Object... args) throws Exception {
        for (var method : provider.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(provider, args);
            }
        }
        throw new AssertionError("Missing method: " + name + "/" + args.length);
    }

    private static Object wrapperOf(Object provider, Object agent, String sessionId) throws Exception {
        for (Object wrapper : (Object[]) invoke(provider, "getChildren", agent)) {
            if (sessionId.equals(wrapperSessionId(wrapper))) {
                return wrapper;
            }
        }
        throw new AssertionError("No agent row nests session " + sessionId);
    }

    private static List<Object> childWrappers(Object provider, Object wrapper) throws Exception {
        return List.of((Object[]) invoke(provider, "getChildren", wrapper));
    }

    private static List<String> childIds(Object provider, Object wrapper) throws Exception {
        List<String> ids = new ArrayList<>();
        for (Object child : childWrappers(provider, wrapper)) {
            ids.add(wrapperSessionId(child));
        }
        return ids;
    }

    private static String wrapperSessionId(Object wrapper) throws Exception {
        var session = wrapper.getClass().getDeclaredMethod("session");
        session.setAccessible(true);
        return ((Session) session.invoke(wrapper)).id();
    }
}
