package com.opencode.ide.ui.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
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
                    null, null, null, null, null);
            Agent agent = new Agent("build", null, "primary", null, null, null, null,
                    null, null, null, null, null, null);
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
            Agent agent, Session session) throws Exception {
        Class<?> node = Class.forName(viewType.getName() + "$ServerNode", true, viewType.getClassLoader());
        var constructor = java.util.Arrays.stream(node.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() == 14).findFirst().orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(primary, primary ? "primary" : "remote", "connect", "http://localhost",
                true, "test", null, client, List.of(agent), List.of(session),
                Map.of(session.id(), new SessionStatus("busy")), List.of(), List.of(), WorkingSet.EMPTY);
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
}
