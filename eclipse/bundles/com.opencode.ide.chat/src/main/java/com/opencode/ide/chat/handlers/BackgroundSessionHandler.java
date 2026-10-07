package com.opencode.ide.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.opencode.ide.chat.views.ChatView;

/**
 * Delegates to the active {@link ChatView}'s background action (U-064): the
 * session keeps working while the user does other things; backgrounded
 * sessions are listed in the Background view. Bound to Ctrl+Alt+Shift+B
 * globally; the chat composer additionally takes the TUI's Ctrl+B
 * ({@code com.opencode.ide.chat.BackgroundKeys}). When the active part is
 * not a chat view, the first chat view with a reply in flight is
 * backgrounded, so the binding also works while the focus sits in another
 * view.
 */
public class BackgroundSessionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        if (part instanceof ChatView chat) {
            chat.backgroundRequested();
            return null;
        }
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window != null) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                for (IViewReference reference : page.getViewReferences()) {
                    if (ChatView.ID.equals(reference.getId())
                            && reference.getView(false) instanceof ChatView chat && chat.isGenerating()) {
                        chat.backgroundRequested();
                        break;
                    }
                }
            }
        }
        return null;
    }
}
