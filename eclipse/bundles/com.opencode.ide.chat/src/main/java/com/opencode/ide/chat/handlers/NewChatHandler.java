package com.opencode.ide.chat.handlers;

import com.opencode.ide.chat.views.ChatView;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens a NEW chat window (fresh session), via the same mechanism the {@code
 * com.opencode.ide.chat.openChat} handler uses without parameters. Bound to Ctrl+Alt+Shift+N
 * (default scheme, rebindable).
 */
public class NewChatHandler extends AbstractHandler {

  @Override
  public Object execute(ExecutionEvent event) {
    IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
    if (window != null) {
      IWorkbenchPage page = window.getActivePage();
      if (page != null) {
        ChatView.openNew(page, null, null);
      }
    }
    return null;
  }
}
