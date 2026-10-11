package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;

import com.opencode.ide.ui.FakePreferences;
import org.junit.Test;

/**
 * SWT-free, OSGi-free tests for the U-066 scope preference contract: repo default (the historical
 * behavior), round-trip persistence with a flush on save, and a never-throws degrade path for a
 * corrupted stored value. The preference node is the shared ui.tests fake.
 */
public class SessionScopePreferencesTest {

  private final FakePreferences node = new FakePreferences();

  private final SessionScopePreferences prefs = new SessionScopePreferences(node);

  @Test
  public void defaultsToRepoWhenUnset() {
    assertEquals(SessionScope.REPO, prefs.get());
    assertEquals("server.sessions.scope", SessionScopePreferences.SCOPE_KEY);
    assertEquals(SessionScope.REPO, SessionScopePreferences.DEFAULT_SCOPE);
  }

  @Test
  public void machineScopeRoundTripsAndSaveFlushes() {
    prefs.set(SessionScope.MACHINE);
    assertEquals("nothing flushed before save()", 0, node.flushCount());

    try {
      prefs.save();
    } catch (org.osgi.service.prefs.BackingStoreException e) {
      throw new AssertionError(e);
    }

    assertEquals(1, node.flushCount());
    assertEquals(SessionScope.MACHINE, new SessionScopePreferences(node).get());
  }

  @Test
  public void unknownStoredValueDegradesToTheDefault() {
    node.put(SessionScopePreferences.SCOPE_KEY, "tablespace");
    assertEquals("corrupt value never throws", SessionScope.REPO, prefs.get());
  }

  @Test
  public void nullSetterWritesTheDefault() {
    prefs.set(null);
    assertEquals(SessionScope.REPO, prefs.get());
  }
}
