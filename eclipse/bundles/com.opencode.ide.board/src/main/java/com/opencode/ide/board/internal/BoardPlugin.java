package com.opencode.ide.board.internal;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator for {@code com.opencode.ide.board}: owns the plugin instance and its dialog
 * settings (Board view persistence).
 */
public final class BoardPlugin extends AbstractUIPlugin {

  public static final String PLUGIN_ID = "com.opencode.ide.board";

  private static BoardPlugin instance;

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    instance = this;
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    try {
      persistDialogSettings();
    } finally {
      instance = null;
      super.stop(context);
    }
  }

  /** The running plugin instance, or {@code null} outside an OSGi runtime. */
  public static BoardPlugin getDefault() {
    return instance;
  }

  /** Persists the dialog settings now (best-effort; the protected variant is not view-callable). */
  public void persistDialogSettings() {
    saveDialogSettings();
  }
}
