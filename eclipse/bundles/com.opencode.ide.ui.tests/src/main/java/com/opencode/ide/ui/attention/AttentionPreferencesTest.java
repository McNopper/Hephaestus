package com.opencode.ide.ui.attention;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.ui.FakePreferences;
import org.junit.Test;

/**
 * SWT-free, OSGi-free tests for the attention preference contract: the TUI-parity defaults
 * (enabled=false, sound=true-when-enabled), the {@code attention.*} key names, the setters, and the
 * {@link AttentionNotifications} gating statics that consume them. The preference node is a fake so
 * no platform runtime is needed.
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
    assertEquals("nothing flushed before save()", 0, node.flushCount());

    try {
      prefs.save();
    } catch (org.osgi.service.prefs.BackingStoreException e) {
      throw new AssertionError(e);
    }

    assertEquals(1, node.flushCount());
    AttentionPreferences reread = new AttentionPreferences(node);
    assertTrue(reread.isEnabled());
    assertFalse(reread.isSoundEnabled());
  }

  // ---------- gating ----------

  @Test
  public void shouldRenderRequiresEventAndEnabled() {
    AttentionEvent event =
        new AttentionEvent(AttentionKind.SESSION_COMPLETED, "Session ses_1 finished", "ses_1");

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
}
