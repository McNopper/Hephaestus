package com.opencode.ide.ui.model;

/**
 * The Server view's Sessions scope (U-066): REPO keeps the historical behavior - only the
 * connection's working directory's sessions, loaded exactly as before; MACHINE widens the list to
 * every session on this machine so the overview shows what ALL projects are doing. The owner
 * direction: "default is of course our harness work, but it helps to see an overview what is going
 * on this machine."
 *
 * <p>SWT-free and OSGi-free on purpose - static/enum logic over client model types, unit-testable
 * without a {@code Display} (see {@code SessionScopeTest} in {@code com.opencode.ide.ui.tests}).
 */
public enum SessionScope {

  /** The default: sessions scoped to the connection's working directory (unchanged behavior). */
  REPO,

  /** Every session the machine's service knows ({@code location.directory} labels each row). */
  MACHINE;

  /**
   * Explicit result cap for BOTH scopes (owner 2026-10-11: "we show all sessions"). The service
   * pages at 50 by default, which silently truncates any project past 50 sessions (this repo had 60
   * at probe time) - so every session list call passes an explicit cap. Live probe (2026-10-11,
   * service 2.0.21): limit=500 returned all 211 machine sessions.
   */
  public static final int LIST_LIMIT = 500;

  /**
   * @param scope the chosen scope
   * @param repoDir the repo-scoped directory (REPO's filter), may be null
   * @return the {@code directory} argument the client call needs: the repo dir for {@link #REPO},
   *     {@code null} (unscoped) for {@link #MACHINE}
   */
  public static String dirFor(SessionScope scope, String repoDir) {
    return scope == MACHINE ? null : repoDir;
  }

  /**
   * @return true for a non-null scope that is machine-wide (default: false = repo).
   */
  public static boolean isMachine(SessionScope scope) {
    return scope == MACHINE;
  }
}
