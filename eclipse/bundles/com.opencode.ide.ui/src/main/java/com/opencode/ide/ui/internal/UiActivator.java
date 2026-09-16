package com.opencode.ide.ui.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.opencode.ide.ui.console.AgentToolsConsole;
import com.opencode.ide.ui.session.SessionBusyPoller;

/**
 * Bundle activator for {@code com.opencode.ide.ui}. Manages the plugin's
 * single instance and a shared, cached image registry for view/row icons.
 */
public class UiActivator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.opencode.ide.ui";

    /**
     * Icon paths (relative to the bundle root) used across the views. The
     * two session-row keys alias {@link SessionBusyPoller} — the pure
     * busy→icon mapping is their single source of truth, so the poller's
     * choices and the registry can never drift apart.
     */
    public static final String ICON_SERVER = "icons/server.png";
    public static final String ICON_CATEGORY = "icons/category.png";
    public static final String ICON_AGENT = "icons/agent.png";
    public static final String ICON_AGENT_BUSY = "icons/agent-busy.png";
    public static final String ICON_SESSION = SessionBusyPoller.ICON_SESSION;
    public static final String ICON_SESSION_BUSY = SessionBusyPoller.ICON_SESSION_BUSY;
    public static final String ICON_PROVIDERS = "icons/providers.png";
    public static final String ICON_MODEL = "icons/model.png";
    public static final String ICON_MCP = "icons/mcp.png";
    public static final String ICON_SKILL = "icons/skill.png";
    public static final String ICON_FILE = "icons/file.png";

    private static UiActivator instance;

    private final ConcurrentMap<String, Image> images = new ConcurrentHashMap<>();

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            AgentToolsConsole.uninstall();
            ProviderIcons.disposeAll();
            ProviderLogos.disposeAll();
            images.values().forEach(Image::dispose);
            images.clear();
        } finally {
            instance = null;
            super.stop(context);
        }
    }

    public static UiActivator getDefault() {
        return instance;
    }

    /**
     * @return a cached {@link Image} for the given bundle-relative path (e.g.
     *         {@link #ICON_SERVER}), or {@code null} if it cannot be found.
     */
    public static Image image(String path) {
        UiActivator a = instance;
        if (a == null) {
            return null;
        }
        return a.images.computeIfAbsent(path, p -> {
            ImageDescriptor descriptor = imageDescriptorFromPlugin(PLUGIN_ID, p);
            return descriptor == null ? null : descriptor.createImage();
        });
    }
}
