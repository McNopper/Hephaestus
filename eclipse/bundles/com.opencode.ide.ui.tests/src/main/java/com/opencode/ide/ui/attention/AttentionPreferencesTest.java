package com.opencode.ide.ui.attention;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.osgi.service.prefs.Preferences;

/**
 * SWT-free, OSGi-free tests for the attention preference contract: the
 * TUI-parity defaults (enabled=false, sound=true-when-enabled), the
 * {@code attention.*} key names, the setters, and the
 * {@link AttentionNotifications} gating statics that consume them. The
 * preference node is a fake so no platform runtime is needed.
 */
public class AttentionPreferencesTest {

    private final FakePreferences node = new FakePreferences();

    private final AttentionPreferences prefs = new AttentionPreferences(node);

    // ---------- defaults ----------

    @Test
    public void defaultsMatchTheTuiParityWhenNodeIsEmpty() {
        assertFalse("attention must be opt-in (TUI parity)", prefs.isEnabled());
        assertTrue("sound defaults on once enabled", prefs.isSoundEnabled());
    }

    @Test
    public void keysUseTheAttentionNamespace() {
        assertEquals("attention.enabled", AttentionPreferences.ENABLED_KEY);
        assertEquals("attention.sound", AttentionPreferences.SOUND_KEY);
        assertFalse(AttentionPreferences.DEFAULT_ENABLED);
        assertTrue(AttentionPreferences.DEFAULT_SOUND);
    }

    // ---------- reads and writes ----------

    @Test
    public void readsStoredValues() {
        node.putBoolean(AttentionPreferences.ENABLED_KEY, true);
        node.putBoolean(AttentionPreferences.SOUND_KEY, false);

        assertTrue(prefs.isEnabled());
        assertFalse(prefs.isSoundEnabled());
    }

    @Test
    public void settersRoundTripAndSaveFlushes() {
        prefs.setEnabled(true);
        prefs.setSoundEnabled(false);
        assertEquals("nothing flushed before save()", 0, node.flushes);

        try {
            prefs.save();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            throw new AssertionError(e);
        }

        assertEquals(1, node.flushes);
        AttentionPreferences reread = new AttentionPreferences(node);
        assertTrue(reread.isEnabled());
        assertFalse(reread.isSoundEnabled());
    }

    // ---------- gating ----------

    @Test
    public void shouldRenderRequiresEventAndEnabled() {
        AttentionEvent event = new AttentionEvent(AttentionKind.SESSION_COMPLETED,
                "Session ses_1 finished", "ses_1");

        assertFalse("null event never renders", prefs.shouldRender(null));
        assertFalse("disabled by default", prefs.shouldRender(event));

        prefs.setEnabled(true);
        assertTrue(prefs.shouldRender(event));
    }

    @Test
    public void shouldPlaySoundRequiresEnabledAndSoundFlag() {
        prefs.setSoundEnabled(true);
        assertFalse("sound is meaningless while disabled", prefs.shouldPlaySound());

        prefs.setEnabled(true);
        assertTrue(prefs.shouldPlaySound());

        prefs.setSoundEnabled(false);
        assertFalse("opt-out of sound is honored", prefs.shouldPlaySound());
    }

    /**
     * Minimal OSGI {@link Preferences} fake: string map storage with OSGI
     * {@code getBoolean(key, def)} semantics (default when absent); the node
     * machinery this feature never uses throws.
     */
    private static final class FakePreferences implements Preferences {

        private final Map<String, String> values = new HashMap<>();

        private int flushes;

        @Override
        public String get(String key, String def) {
            return values.getOrDefault(key, def);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean def) {
            String value = values.get(key);
            return value == null ? def : Boolean.parseBoolean(value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, Boolean.toString(value));
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public String[] keys() {
            return values.keySet().toArray(new String[0]);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public String absolutePath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String[] childrenNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] getByteArray(String key, byte[] def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getDouble(String key, double def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(String key, float def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getInt(String key, int def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(String key, long def) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Preferences node(String pathName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean nodeExists(String pathName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Preferences parent() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putByteArray(String key, byte[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putDouble(String key, double value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putFloat(String key, float value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putInt(String key, int value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putLong(String key, long value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeNode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sync() {
            throw new UnsupportedOperationException();
        }
    }
}
