package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.Session;
import org.junit.Test;

/**
 * SWT-free tests for the U-066 session-scope surface: the REPO/MACHINE enum semantics (REPO =
 * byte-identical historical scoping, MACHINE = unscoped + capped), the machine cap pinned against
 * the live probe, and the directory row label that keeps machine-scope sessions distinguishable.
 */
public class SessionScopeTest {

  @Test
  public void repoScopeKeepsTheRepositoryDirectory() {
    assertEquals("C:\\repo", SessionScope.dirFor(SessionScope.REPO, "C:\\repo"));
    assertFalse(SessionScope.isMachine(SessionScope.REPO));
  }

  @Test
  public void machineScopeIsUnscopedAndCapped() {
    assertEquals(
        "machine scope = null directory (unscoped call)",
        null,
        SessionScope.dirFor(SessionScope.MACHINE, "C:\\repo"));
    assertTrue(SessionScope.isMachine(SessionScope.MACHINE));
    assertEquals(
        "cap pinned against the 2026-10-11 live probe (211 sessions)",
        500,
        SessionScope.LIST_LIMIT);
  }

  @Test
  public void nullScopeDegradesToRepo() {
    assertEquals("C:\\repo", SessionScope.dirFor(null, "C:\\repo"));
    assertFalse(SessionScope.isMachine(null));
  }

  @Test
  public void directoryLabelCarriesTheDirectoryOrNothing() {
    Session withDir =
        new Session(
            "ses_1",
            null,
            "title",
            "build",
            null,
            null,
            null,
            null,
            null,
            null,
            new Session.Location("C:\\Other\\proj"));
    assertEquals(" — C:\\Other\\proj", ServerLabels.sessionDirectoryLabel(withDir));

    Session withoutDir =
        new Session("ses_2", null, "title", "build", null, null, null, null, null, null, null);
    assertEquals("", ServerLabels.sessionDirectoryLabel(withoutDir));
    assertEquals("", ServerLabels.sessionDirectoryLabel(null));
  }
}
