package com.opencode.ide.core.internal;

import java.util.logging.Level;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.ServiceTracker;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.core.ConnectionsManager;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.core.OpencodePreferences;
import com.opencode.ide.core.context.ProjectContext;

/**
 * Bundle activator for {@code com.opencode.ide.core}. Owns the singleton
 * {@link OpencodeConnection} lifecycle, tracks the optional
 * {@link ProjectContext} service (implemented by the CDT bundle), and provides
 * simple logging helpers.
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
        ClientLog.install((level, message, cause) -> {
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
        connectionService = context.registerService(OpencodeConnection.class.getName(),
                new ServiceFactory<OpencodeConnection>() {
                    @Override
                    public OpencodeConnection getService(Bundle bundle,
                            ServiceRegistration<OpencodeConnection> registration) {
                        bridgeTasksRootPreference();
                        return OpencodeConnection.getInstance();
                    }

                    @Override
                    public void ungetService(Bundle bundle,
                            ServiceRegistration<OpencodeConnection> registration,
                            OpencodeConnection service) {
                        // singleton - nothing to release
                    }
                }, null);
        connectionsService = context.registerService(ConnectionsManager.class.getName(),
                new ServiceFactory<ConnectionsManager>() {
                    @Override
                    public ConnectionsManager getService(Bundle bundle,
                            ServiceRegistration<ConnectionsManager> registration) {
                        return ConnectionsManager.getDefault();
                    }

                    @Override
                    public void ungetService(Bundle bundle,
                            ServiceRegistration<ConnectionsManager> registration,
                            ConnectionsManager service) {
                        // singleton - nothing to release
                    }
                }, null);
        // The ProjectContext tracker is opened LAZILY (first getProjectContext()
        // call), never here: this activator is frequently activated BY the CDT
        // bundle's CdtProjectContext component creation (constructor class loads
        // reach into this bundle), and opening the tracker eagerly re-enters that
        // same component creation - Felix SCR then reports a circular reference
        // and permanently disables the CDT integration.
        // Safety net: if bundle stop() does not run (abnormal Eclipse/JVM exit),
        // the JVM shutdown hook still tears down any spawned opencode server so it
        // is never left orphaned when Eclipse closes.
        shutdownHook = new Thread(() -> {
            ConnectionsManager.disposeIfCreated();
            OpencodeConnection.disposeIfCreated();
        }, "opencode-eclipse-shutdown");
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

    /** @return the currently registered {@link ProjectContext} service, or {@code null}. */
    public static ProjectContext getProjectContext() {
        CoreActivator a = instance;
        if (a == null) {
            return null;
        }
        ServiceTracker<ProjectContext, ProjectContext> tracker = a.projectContextTracker();
        return tracker != null ? tracker.getService() : null;
    }

    /**
     * Opens the tracker on first use only (see start() for why eager opening is
     * forbidden). Never re-enters component creation: first callers are client
     * builds on background jobs, long after bundle start.
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
                        public void removedService(ServiceReference<ProjectContext> reference,
                                ProjectContext service) {
                            context.ungetService(reference);
                        }
                    };
            tracker.open();
            projectContextTracker = tracker;
        }
        return projectContextTracker;
    }

    /**
     * Bridges the tasksRoot preference into the system property the eclipse-build
     * MCP endpoint reads - one task store for the Board view, the fleet and the
     * in-session task_* tools. Safe at any activation phase: a no-op (with a
     * warning) while the instance area is not yet available.
     */
    private static void bridgeTasksRootPreference() {
        try {
            String configured = new OpencodePreferences().getTasksRoot();
            if (configured != null && !configured.isBlank()) {
                System.setProperty("opencode.tasks.root", configured.trim());
            }
        } catch (RuntimeException | LinkageError e) {
            logWarning("cannot bridge tasksRoot preference into opencode.tasks.root: " + e.getMessage());
        }
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
