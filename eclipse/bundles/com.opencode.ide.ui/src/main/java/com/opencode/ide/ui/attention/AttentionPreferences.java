package com.opencode.ide.ui.attention;

import java.util.Objects;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import com.opencode.ide.ui.internal.UiActivator;

/**
 * The two U-047 attention preferences, stored on the ui plugin's
 * {@link InstanceScope} node (mirroring the TUI's attention defaults):
 * <ul>
 *   <li>{@code attention.enabled} — default <b>false</b>: desktop
 *   notifications are strictly opt-in, matching the TUI where attention is
 *   a user-chosen setting.</li>
 *   <li>{@code attention.sound} — default <b>true when enabled</b>: a soft
 *   beep accompanies every notification; meaningful only while enabled.</li>
 * </ul>
 *
 * <p>The node is injected (constructor) so tests run SWT-free and OSGi-free
 * against a fake {@link Preferences}; {@link #forPlugin()} is the thin
 * production factory. Reads pass the {@code DEFAULT_*} constants as the
 * per-call fallback, so the semantics hold even before the
 * {@link AttentionPreferenceInitializer} seeded the default scope.</p>
 */
public final class AttentionPreferences {

    /** Preference key: desktop notifications on/off. */
    public static final String ENABLED_KEY = "attention.enabled";

    /** Preference key: sound on/off (meaningful only when enabled). */
    public static final String SOUND_KEY = "attention.sound";

    /** Default: disabled (opt-in, matching the TUI). */
    public static final boolean DEFAULT_ENABLED = false;

    /** Default: sound on whenever notifications are enabled. */
    public static final boolean DEFAULT_SOUND = true;

    private final Preferences node;

    /** @param node the plugin's preference node to read and write. */
    public AttentionPreferences(Preferences node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /** @return preferences on the ui plugin's instance-scope node. */
    public static AttentionPreferences forPlugin() {
        return new AttentionPreferences(InstanceScope.INSTANCE.getNode(UiActivator.PLUGIN_ID));
    }

    /** @return whether desktop notifications are enabled (default off). */
    public boolean isEnabled() {
        return node.getBoolean(ENABLED_KEY, DEFAULT_ENABLED);
    }

    /** @return whether notifications should beep (default on; only used when enabled). */
    public boolean isSoundEnabled() {
        return node.getBoolean(SOUND_KEY, DEFAULT_SOUND);
    }

    /** Stores the enabled flag (persisted by {@link #save()}). */
    public void setEnabled(boolean enabled) {
        node.putBoolean(ENABLED_KEY, enabled);
    }

    /** Stores the sound flag (persisted by {@link #save()}). */
    public void setSoundEnabled(boolean sound) {
        node.putBoolean(SOUND_KEY, sound);
    }

    /** Persists pending writes to the backing store. */
    public void save() throws BackingStoreException {
        node.flush();
    }

    // ---------- gating (SWT-free: the rendering decision, not the rendering) ----------

    /**
     * @param event the event that wants attention
     * @return whether a popup should be rendered for this event — a
     *         {@code null} event or a disabled preference never renders
     */
    public boolean shouldRender(AttentionEvent event) {
        return event != null && isEnabled();
    }

    /**
     * @return whether a beep should accompany a rendered popup — sound only
     *         ever plays when notifications themselves are enabled
     */
    public boolean shouldPlaySound() {
        return isEnabled() && isSoundEnabled();
    }
}
