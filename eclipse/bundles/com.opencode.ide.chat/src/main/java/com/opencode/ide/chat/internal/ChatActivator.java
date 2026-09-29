package com.opencode.ide.chat.internal;

import java.io.IOException;

import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Owns the single {@link ChatWebServer} serving the bundled chat web assets
 * and the plugin's dialog settings (chat-view continuity persistence, U-039).
 * Started lazily on first use; stopped with the bundle.
 */
public class ChatActivator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.opencode.ide.chat";

    private static ChatActivator instance;
    private static volatile ChatWebServer webServer;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            persistDialogSettings(); // U-039: chat continuity settings survive restarts
            ChatWebServer server = webServer;
            if (server != null) {
                server.stop();
                webServer = null;
            }
        } finally {
            instance = null;
            super.stop(context);
        }
    }

    public static ChatActivator getDefault() {
        return instance;
    }

    /** Persists the dialog settings now (best-effort; the protected variant is not view-callable). */
    public void persistDialogSettings() {
        saveDialogSettings();
    }

    /** URL for a web resource (starts the server on first use). */
    public static String webUrl(String path) {
        return server().url(path);
    }

    /**
     * Base URL of the local asset server ({@code http://127.0.0.1:<port>/}), or
     * {@code null} while it is not running. Used to tell our own page apart from
     * external links, which must open in the system browser.
     */
    public static String webUrlBase() {
        ChatWebServer server = webServer;
        return (server == null) ? null : server.url("");
    }

    private static synchronized ChatWebServer server() {
        ChatWebServer server = webServer;
        if (server == null) {
            try {
                server = ChatWebServer.start(path -> {
                    java.net.URL entry = getDefault().getBundle().getEntry("web/" + path);
                    if (entry == null) {
                        // must be an IOException: ChatWebServer maps it to 404
                        throw new IOException("no bundle entry for web/" + path);
                    }
                    return entry.openStream();
                });
            } catch (IOException e) {
                getDefault().getLog().log(
                        new Status(Status.ERROR, PLUGIN_ID, "failed to start chat web server", e));
                throw new IllegalStateException("chat web server unavailable", e);
            }
            webServer = server;
        }
        return server;
    }
}
