package com.opencode.ide.ui.attention;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;

import com.opencode.ide.ui.internal.UiActivator;

/**
 * Seeds the ui plugin's default-scope node with the attention defaults, so
 * the values show up in the Eclipse preference machinery (export, plug-in
 * registry, any future field editor). Runtime reads do not depend on this —
 * {@link AttentionPreferences} passes the same constants as per-call
 * fallbacks — this initializer makes the defaults visible, not load-bearing.
 */
public class AttentionPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        var node = DefaultScope.INSTANCE.getNode(UiActivator.PLUGIN_ID);
        node.putBoolean(AttentionPreferences.ENABLED_KEY, AttentionPreferences.DEFAULT_ENABLED);
        node.putBoolean(AttentionPreferences.SOUND_KEY, AttentionPreferences.DEFAULT_SOUND);
    }
}
