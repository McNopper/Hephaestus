package com.opencode.ide.ui.model;

import com.opencode.ide.ui.internal.UiActivator;
import java.util.Objects;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * The U-066 Server-view session scope, stored on the ui plugin's {@link InstanceScope} node
 * (mirroring {@code AttentionPreferences}): {@code server.sessions.scope} = {@code "repo"}
 * (default, the historical behavior) or {@code "machine"} (the all-sessions overview). The choice
 * persists across sessions (AC: "the choice persists").
 *
 * <p>The node is injected (constructor) so tests run SWT-free and OSGi-free against a fake {@link
 * Preferences}; {@link #forPlugin()} is the thin production factory. Reads pass the {@code
 * DEFAULT_SCOPE} constant as the per-call fallback.
 */
public final class SessionScopePreferences {

  /** Preference key: the sessions scope ({@code "repo"} | {@code "machine"}). */
  public static final String SCOPE_KEY = "server.sessions.scope";

  /** Default: repo scope (byte-identical historical behavior). */
  public static final SessionScope DEFAULT_SCOPE = SessionScope.REPO;

  private final Preferences node;

  /**
   * @param node the plugin's preference node to read and write.
   */
  public SessionScopePreferences(Preferences node) {
    this.node = Objects.requireNonNull(node, "node");
  }

  /**
   * @return preferences on the ui plugin's instance-scope node.
   */
  public static SessionScopePreferences forPlugin() {
    return new SessionScopePreferences(InstanceScope.INSTANCE.getNode(UiActivator.PLUGIN_ID));
  }

  /**
   * @return the persisted scope; an unknown stored value degrades to the default (never throws).
   */
  public SessionScope get() {
    String raw = node.get(SCOPE_KEY, DEFAULT_SCOPE.name());
    try {
      return SessionScope.valueOf(raw);
    } catch (IllegalArgumentException unknown) {
      return DEFAULT_SCOPE;
    }
  }

  /** Stores the scope (persisted by {@link #save()}). */
  public void set(SessionScope scope) {
    node.put(SCOPE_KEY, (scope == null ? DEFAULT_SCOPE : scope).name());
  }

  /** Persists pending writes to the backing store. */
  public void save() throws BackingStoreException {
    node.flush();
  }
}
