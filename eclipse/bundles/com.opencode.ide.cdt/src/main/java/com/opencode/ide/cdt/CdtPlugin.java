package com.opencode.ide.cdt;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator for {@code com.opencode.ide.cdt}. Plain lifecycle holder (mirrors the ui
 * bundle's activator): keeps the plugin singleton for later logging/preferences use. The {@code
 * ProjectContext} and {@code MarkerApplier} services are registered via OSGi Declarative Services
 * ({@code OSGI-INF/*.xml}), which also unregisters them when this bundle stops.
 */
public class CdtPlugin extends Plugin {

  public static final String PLUGIN_ID = "com.opencode.ide.cdt";

  private static CdtPlugin instance;

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    instance = this;
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    try {
      instance = null;
    } finally {
      super.stop(context);
    }
  }

  public static CdtPlugin getDefault() {
    return instance;
  }
}
