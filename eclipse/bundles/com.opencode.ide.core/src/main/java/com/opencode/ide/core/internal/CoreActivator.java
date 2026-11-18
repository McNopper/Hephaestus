package com.opencode.ide.core.internal;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.TasksRootResolution;
import com.opencode.ide.core.context.ProjectContext;
import java.util.logging.Level;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Bundle activator for {@code com.opencode.ide.core}. Owns the singleton {@link OpencodeConnection}
 * lifecycle, tracks the optional {@link ProjectContext} service (implemented by the CDT bundle),
 * and provides simple logging helpers.
 */
public class CoreActivator extends Plugin {

  public static final String PLUGIN_ID = "com.opencode.ide.core";

  private static CoreActivator instance;

  private ServiceTracker<ProjectContext, ProjectContext> projectContextTracker;
  private ServiceRegistration<?> connectionService;
  private ServiceRegistration<?> connectionsService;
  private BundleContext bundleContext;
  private Thread shutdownHook;

  @Override
  public void start(BundleContext context) throws Exception {
    super.start(context);
    instance = this;
    bundleContext = context;
    // Best effort here: this activator can run during early OSGi class loading,
    // before the platform's instance data location exists. The bridge is then
    // retried on the first service consumer (see the connection service factory).
    bridgeTasksRootPreference();
    ClientLog.install(
        (level, message, cause) -> {
          if (Level.WARNING.equals(level)) {
            logWarning(message);
          } else {
            logError(message, cause);
          }
        });
    // Service factories keep both singletons UNconstructed here: eager
    // construction touches InstanceScope (via OpencodePreferences) during
    // bundle start, which is illegal before the instance area is initialized
    // and used to fail activation - taking every view down with it.
    connectionService =
        context.registerService(
            OpencodeConnection.class.getName(),
            new ServiceFactory<OpencodeConnection>() {
              @Override
              public OpencodeConnection getService(
                  Bundle bundle, ServiceRegistration<OpencodeConnection> registration) {
                bridgeTasksRootPreference();
                return OpencodeConnection.getInstance();
              }

              @Override
              public void ungetService(
                  Bundle bundle,
                  ServiceRegistration<OpencodeConnection> registration,
                  OpencodeConnection service) {
                // singleton - nothing to release
              }
            },
            null);
    connectionsService =
        context.registerService(
            ConnectionsManager.class.getName(),
            new ServiceFactory<ConnectionsManager>() {
              @Override
              public ConnectionsManager getService(
                  Bundle bundle, ServiceRegistration<ConnectionsManager> registration) {
                return ConnectionsManager.getDefault();
              }

              @Override
              public void ungetService(
                  Bundle bundle,
                  ServiceRegistration<ConnectionsManager> registration,
                  ConnectionsManager service) {
                // singleton - nothing to release
              }
            },
            null);
    // The ProjectContext tracker is opened LAZILY (first getProjectContext()
    // call), never here: this activator is frequently activated BY the CDT
    // bundle's CdtProjectContext component creation (constructor class loads
    // reach into this bundle), and opening the tracker eagerly re-enters that
    // same component creation - Felix SCR then reports a circular reference
    // and permanently disables the CDT integration.
    // Safety net: if bundle stop() does not run (abnormal Eclipse/JVM exit),
    // the JVM shutdown hook still tears down any spawned opencode server so it
    // is never left orphaned when Eclipse closes.
    shutdownHook =
        new Thread(
            () -> {
              ConnectionsManager.disposeIfCreated();
              OpencodeConnection.disposeIfCreated();
            },
            "opencode-eclipse-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM already shutting down - nothing to register
    }
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    try {
      if (connectionService != null) {
        connectionService.unregister();
        connectionService = null;
      }
      if (connectionsService != null) {
        connectionsService.unregister();
        connectionsService = null;
      }
      if (projectContextTracker != null) {
        projectContextTracker.close();
        projectContextTracker = null;
      }
      bundleContext = null;
      ConnectionsManager.disposeIfCreated();
      OpencodeConnection.disposeIfCreated();
      if (shutdownHook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
          // JVM already shutting down - that's fine, the hook will run
        }
        shutdownHook = null;
      }
    } finally {
      ClientLog.install(null);
      instance = null;
      super.stop(context);
    }
  }

  public static CoreActivator getDefault() {
    return instance;
  }

  /**
   * @return the currently registered {@link ProjectContext} service, or {@code null}.
   */
  public static ProjectContext getProjectContext() {
    CoreActivator a = instance;
    if (a == null) {
      return null;
    }
    ServiceTracker<ProjectContext, ProjectContext> tracker = a.projectContextTracker();
    return tracker != null ? tracker.getService() : null;
  }

  /**
   * Opens the tracker on first use only (see start() for why eager opening is forbidden). Never
   * re-enters component creation: first callers are client builds on background jobs, long after
   * bundle start.
   */
  private synchronized ServiceTracker<ProjectContext, ProjectContext> projectContextTracker() {
    if (projectContextTracker == null && bundleContext != null) {
      BundleContext context = bundleContext;
      ServiceTracker<ProjectContext, ProjectContext> tracker =
          new ServiceTracker<ProjectContext, ProjectContext>(
              context, ProjectContext.class.getName(), null) {
            @Override
            public ProjectContext addingService(ServiceReference<ProjectContext> reference) {
              ProjectContext service = context.getService(reference);
              // a different project context may now be available - drop cached client
              OpencodeConnection.refreshIfCreated();
              return service;
            }

            @Override
            public void removedService(
                ServiceReference<ProjectContext> reference, ProjectContext service) {
              context.ungetService(reference);
            }
          };
      tracker.open();
      projectContextTracker = tracker;
    }
    return projectContextTracker;
  }

  /**
   * Bridges the task-store root into the system property the eclipse-build MCP endpoint reads — one
   * task store for the Board view, the fleet and the in-session task_* tools. B-016: the property
   * gets the FULL shared resolution ({@link TasksRootResolution#resolveForWorkspace(String)}, empty
   * override — workspace climb, open-project repo adoption, then the preference), not just the raw
   * preference: with a blank tasksRoot preference the endpoint used to fall back to {@code
   * ~/.harness/tasks} while the Board adopted the open repo's store — two surfaces, two stores.
   * Safe at any activation phase: a no-op while the workspace location is not yet available (the
   * bridge re-runs on the first service consumer, when the workspace is open). Public so the wiring
   * test can drive it.
   */
  public static void bridgeTasksRootPreference() {
    try {
      if (Platform.getLocation() == null) {
        // workspace not yet known — do not freeze a guess into the
        // property; the re-bridge on the first service consumer
        // resolves against the open workspace
        return;
      }
      System.setProperty(
          "opencode.tasks.root", TasksRootResolution.resolveForWorkspace("").toString());
      // T-176 second stage: only after a CORRECT root exists, hand off to the
      // endpoint bundle (its component reads the property at activation)
      logInfo("tasks root bridged: " + System.getProperty("opencode.tasks.root"));
      startEclipseBuildBundle();
    } catch (RuntimeException | LinkageError e) {
      logWarning(
          "cannot bridge the resolved tasksRoot into opencode.tasks.root: " + e.getMessage());
    }
  }

  private static final java.util.concurrent.atomic.AtomicBoolean MCP_BUNDLE_STARTED =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Starts the {@code com.opencode.ide.mcp} bundle, which owns the {@code eclipse-build} endpoint
   * (T-176, second stage, observed 2026-11-18): NOTHING ever classloads that bundle — its only
   * consumer (core's {@code McpInfo} DS reference) is resolved by service <i>name</i>, never by
   * loading the class — so even an {@code immediate="true"} {@code McpServiceComponent} never ran
   * (log evidence: core attached, zero {@code com.opencode.ide.mcp} entries). Core already
   * Require-Bundles the mcp bundle; starting it from here is the deterministic hand-off, and doing
   * it only inside a SUCCESSFUL tasks-root bridge guarantees the component activates with the
   * correct store root (the ordering its javadoc promises). Idempotent, best-effort: a failed start
   * is logged and the bridge itself never fails.
   */
  private static void startEclipseBuildBundle() {
    if (!MCP_BUNDLE_STARTED.compareAndSet(false, true)) {
      return;
    }
    try {
      org.osgi.framework.Bundle mcp = Platform.getBundle("com.opencode.ide.mcp");
      if (mcp == null) {
        MCP_BUNDLE_STARTED.set(false); // retry on a later bridge
        logInfo("eclipse-build hand-off: mcp bundle not found in the platform");
        return;
      }
      logInfo(
          "eclipse-build hand-off: starting com.opencode.ide.mcp (state " + mcp.getState() + ")");
      mcp.start(); // idempotent per OSGi: a STARTED bundle is a no-op
      logInfo("eclipse-build hand-off: com.opencode.ide.mcp now state " + mcp.getState());
      final org.osgi.framework.Bundle observed = mcp;
      Thread probe =
          new Thread(
              () -> {
                try {
                  Thread.sleep(2500);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
                }
                logInfo(
                    "eclipse-build hand-off: mcp state after 2.5s = "
                        + observed.getState()
                        + ", context="
                        + (observed.getBundleContext() != null));
              },
              "mcp-state-probe");
      probe.setDaemon(true);
      probe.start();
    } catch (Exception e) {
      MCP_BUNDLE_STARTED.set(false); // allow a later bridge retry
      logWarning(
          "cannot start the eclipse-build endpoint bundle (com.opencode.ide.mcp): "
              + e.getMessage());
    }
  }

  private static void logInfo(String message) {
    CoreActivator a = instance;
    if (a != null) {
      a.getLog().log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }
  }

  static void logInfoShared(String message) {
    logInfo(message);
  }

  public static void logError(String message, Throwable cause) {
    CoreActivator a = instance;
    if (a != null) {
      a.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, cause));
    }
  }

  public static void logWarning(String message) {
    CoreActivator a = instance;
    if (a != null) {
      a.getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }
  }
}
