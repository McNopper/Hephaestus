package com.opencode.ide.ui.internal;

/**
 * Implemented by OpenCode views whose content can be reloaded. Used by the toolbar Refresh actions
 * and the {@code com.opencode.ide.ui.refreshViews} command handler to trigger the views' refresh
 * logic wherever the view is open.
 */
public interface Refreshable {

  void refresh();
}
