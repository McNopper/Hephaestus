package com.opencode.ide.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * The "OpenCode" perspective: a chat-first two-column layout.
 *
 * <pre>
 * | tools (tabs)      |          chat            |
 * | Server            |                          |
 * | Repo              |       (large, right)     |
 * | Providers          |                          |
 * | Board / Fleet     |                          |
 * </pre>
 *
 * The chat is the primary surface and takes most of the width; every other
 * view (server/sessions, workspace files, providers, PM board, fleet jobs)
 * is one tab in the single left stack, so nothing crowds the screen.
 *
 * The editor area anchors the columns but starts hidden; opening an editor
 * (e.g. a file from the Repo view) makes Eclipse show it again inside the
 * chat column.
 */
public class OpencodePerspective implements IPerspectiveFactory {

    public static final String ID = "com.opencode.ide.ui.perspective";

    // Views contributed by other bundles - referenced by ID so this bundle has
    // no compile dependency on them; they are silently skipped if not installed.
    private static final String CHAT_VIEW_ID = "com.opencode.ide.chat.views.ChatView";
    private static final String BOARD_VIEW_ID = "com.opencode.ide.board.views.BoardView";
    private static final String FLEET_VIEW_ID = "com.opencode.ide.board.views.FleetView";

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();

        // The chat owns the window; the editor area only reappears when an
        // editor is actually opened.
        layout.setEditorAreaVisible(false);

        // Views live in folders (tab stacks), so every view keeps its title
        // bar - closeable, detachable, movable, and reopenable via
        // Window -> Show View.

        // Left column - all tooling as tabs: context first (servers/sessions,
        // workspace files, providers), then execution (PM board, fleet jobs).
        IFolderLayout tools = layout.createFolder(
                "com.opencode.ide.ui.folder.tools", IPageLayout.LEFT, 0.28f, editorArea);
        tools.addView(com.opencode.ide.ui.views.ServerView.ID);
        tools.addView(com.opencode.ide.ui.views.RepoView.ID);
        tools.addView(com.opencode.ide.ui.views.ProvidersView.ID);
        tools.addView(BOARD_VIEW_ID);
        tools.addView(FLEET_VIEW_ID);
        // the newer panels join the SAME left stack (user 2026-09-23:
        // "all new ones, all in the left one, where fleet and so on is")
        tools.addView(com.opencode.ide.ui.views.BackgroundView.ID);
        tools.addView(com.opencode.ide.ui.views.SessionDetailsView.ID);

        // The chat fills everything to the right of the tab column.
        IFolderLayout chat = layout.createFolder(
                "com.opencode.ide.ui.folder.chat", IPageLayout.RIGHT, 0.72f, editorArea);
        chat.addView(CHAT_VIEW_ID);

        layout.addShowViewShortcut(com.opencode.ide.ui.views.ServerView.ID);
        layout.addShowViewShortcut(com.opencode.ide.ui.views.RepoView.ID);
        layout.addShowViewShortcut(CHAT_VIEW_ID);
        layout.addShowViewShortcut(com.opencode.ide.ui.views.ProvidersView.ID);
        layout.addShowViewShortcut(BOARD_VIEW_ID);
        layout.addShowViewShortcut(FLEET_VIEW_ID);
        layout.addShowViewShortcut(com.opencode.ide.ui.views.BackgroundView.ID);
        layout.addShowViewShortcut(com.opencode.ide.ui.views.SessionDetailsView.ID);
        layout.addPerspectiveShortcut(ID);
    }
}
