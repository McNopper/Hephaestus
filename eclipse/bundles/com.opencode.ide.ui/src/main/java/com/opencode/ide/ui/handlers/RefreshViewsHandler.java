package com.opencode.ide.ui.handlers;

import com.opencode.ide.ui.internal.Refreshable;
import com.opencode.ide.ui.views.ProvidersView;
import com.opencode.ide.ui.views.ServerView;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Triggers the refresh logic of every open OpenCode view (Server, Providers), across all workbench
 * windows and pages ({@code com.opencode.ide.ui.refreshViews}).
 */
public class RefreshViewsHandler extends AbstractHandler {

  private static final String[] REFRESHABLE_VIEW_IDS = {ServerView.ID, ProvidersView.ID};

  @Override
  public Object execute(ExecutionEvent event) {
    for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
      for (IWorkbenchPage page : window.getPages()) {
        for (String viewId : REFRESHABLE_VIEW_IDS) {
          IViewPart view = page.findView(viewId);
          if (view instanceof Refreshable refreshable) {
            refreshable.refresh();
          }
        }
      }
    }
    return null;
  }
}
