package com.opencode.ide.chat.handlers;

import com.opencode.ide.chat.views.ChatView;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Delegates to the active {@link ChatView}'s abort action (bound to Ctrl+Alt+Shift+A). When the
 * active part is not a chat view, the first chat view with a reply in flight is aborted, so the
 * binding also works while the focus sits in another view.
 */
public class AbortChatHandler extends AbstractHandler {

  @Override
  public Object execute(ExecutionEvent event) {
    IWorkbenchPart part = HandlerUtil.getActivePart(event);
    if (part instanceof ChatView chat) {
      chat.abortRequested();
      return null;
    }
    IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
    if (window != null) {
      IWorkbenchPage page = window.getActivePage();
      if (page != null) {
        for (IViewReference reference : page.getViewReferences()) {
          if (ChatView.ID.equals(reference.getId())
              && reference.getView(false) instanceof ChatView chat
              && chat.isGenerating()) {
            chat.abortRequested();
            break;
          }
        }
      }
    }
    return null;
  }
}
