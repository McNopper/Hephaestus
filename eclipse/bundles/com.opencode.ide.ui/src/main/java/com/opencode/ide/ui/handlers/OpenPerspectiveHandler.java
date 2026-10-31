package com.opencode.ide.ui.handlers;

import com.opencode.ide.ui.OpencodePerspective;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Switches the active page to the OpenCode perspective ({@code
 * com.opencode.ide.ui.openPerspective}).
 */
public class OpenPerspectiveHandler extends AbstractHandler {

  @Override
  public Object execute(ExecutionEvent event) {
    IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
    if (window == null) {
      window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    }
    if (window == null) {
      return null;
    }
    IWorkbench workbench = PlatformUI.getWorkbench();
    IPerspectiveDescriptor descriptor =
        workbench.getPerspectiveRegistry().findPerspectiveWithId(OpencodePerspective.ID);
    if (descriptor == null) {
      return null;
    }
    IWorkbenchPage page = window.getActivePage();
    if (page != null) {
      page.setPerspective(descriptor);
    }
    return null;
  }
}
