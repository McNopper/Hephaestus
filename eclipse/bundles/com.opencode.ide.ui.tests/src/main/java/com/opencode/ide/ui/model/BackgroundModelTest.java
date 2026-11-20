package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;

import com.opencode.ide.client.model.Session;
import org.junit.Test;

/**
 * Unit tests for {@link BackgroundModel}: the shared session-label rule the collected questions
 * registry and the subagent rows both use. No SWT, no Display - plain model fixtures ({@code
 * ui.tests} convention).
 */
public class BackgroundModelTest {

  private static Session session(String id, String title, String agent) {
    return new Session(id, null, title, agent, null, null, null, null, null, null, null);
  }

  @Test
  public void titleWinsWhenPresent() {
    assertEquals(
        "Fix the widget", BackgroundModel.name(session("ses_1", "Fix the widget", "build")));
  }

  @Test
  public void blankTitleFallsBackToTheAgentName() {
    assertEquals("<build>", BackgroundModel.name(session("ses_1", "  ", "build")));
    assertEquals("<build>", BackgroundModel.name(session("ses_1", null, "build")));
  }

  @Test
  public void noTitleNoAgentFallsBackToTheId() {
    assertEquals("ses_1", BackgroundModel.name(session("ses_1", null, null)));
    assertEquals("ses_1", BackgroundModel.name(session("ses_1", "", "")));
  }
}
