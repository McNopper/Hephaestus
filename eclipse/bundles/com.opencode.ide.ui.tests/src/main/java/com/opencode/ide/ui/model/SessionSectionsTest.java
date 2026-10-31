package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.client.model.ShellTask;
import com.opencode.ide.ui.model.SessionSections.Section;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link SessionSections}: the Session Details tree's section composition (U-041) —
 * subagents and shell tasks as collapsible sections above the message rows, empty sections omitted.
 * SWT-free.
 */
public class SessionSectionsTest {

  private static SessionSubagents.Row subagent(String id) {
    return new SessionSubagents.Row(id, "Child " + id, "explore", "idle", 0.01, 125L, true);
  }

  private static SessionShells.Row shell(String id) {
    return new SessionShells.Row(id, "make verify", "running", null, null, true);
  }

  private static PendingQuestions.Row question(String id) {
    return new PendingQuestions.Row(
        "ses_1",
        id,
        "bash: git push",
        "child A",
        true,
        "ses_0",
        "Parent",
        1L,
        "5m ago",
        "waiting 5m");
  }

  @Test
  public void questionsSectionLeadsWithItsCount() {
    List<Object> roots =
        SessionSections.roots(
            List.of(subagent("ses_a")),
            List.of(shell("sh_1")),
            List.of(question("per_1"), question("per_2")));

    assertEquals(3, roots.size());
    Section questions = (Section) roots.get(0);
    assertEquals("the stalling section leads the tree", "Questions (2)", questions.label());
    assertEquals(2, questions.children().size());
    assertEquals("Subagents (1)", ((Section) roots.get(1)).label());
    assertEquals("Shell tasks (1)", ((Section) roots.get(2)).label());
  }

  @Test
  public void emptyQuestionsOmitTheirSection() {
    List<Object> roots = SessionSections.roots(List.of(subagent("ses_a")), List.of(), List.of());

    assertEquals(1, roots.size());
    assertEquals("Subagents (1)", ((Section) roots.get(0)).label());

    // the two-arg form (pre-U-096 callers) contributes no questions section
    assertEquals(1, SessionSections.roots(List.of(subagent("ses_a")), null).size());
  }

  @Test
  public void bothSectionsCarryCountsAndTheirRowsAsChildren() {
    List<Object> roots =
        SessionSections.roots(
            List.of(subagent("ses_a"), subagent("ses_b")), List.of(shell("sh_1")));

    assertEquals(2, roots.size());
    assertTrue(roots.get(0) instanceof Section);
    Section subagents = (Section) roots.get(0);
    assertEquals("Subagents (2)", subagents.label());
    assertEquals(2, subagents.children().size());
    assertTrue(roots.get(1) instanceof Section);
    Section shells = (Section) roots.get(1);
    assertEquals("Shell tasks (1)", shells.label());
    assertEquals(1, shells.children().size());
  }

  @Test
  public void emptySectionsAreOmitted() {
    assertTrue(SessionSections.roots(List.of(), List.of()).isEmpty());
    assertTrue(SessionSections.roots(null, null).isEmpty());

    List<Object> onlyShells = SessionSections.roots(List.of(), List.of(shell("sh_1")));
    assertEquals(1, onlyShells.size());
    assertEquals("Shell tasks (1)", ((Section) onlyShells.get(0)).label());

    List<Object> onlySubagents = SessionSections.roots(List.of(subagent("ses_a")), List.of());
    assertEquals(1, onlySubagents.size());
    assertEquals("Subagents (1)", ((Section) onlySubagents.get(0)).label());
  }

  @Test
  public void sectionChildrenAreCopiedDefensively() {
    List<SessionSubagents.Row> rows = new java.util.ArrayList<>(List.of(subagent("ses_a")));
    Section section = (Section) SessionSections.roots(rows, List.of()).get(0);

    rows.clear();

    assertEquals(
        "mutating the input list cannot change a built section", 1, section.children().size());
  }

  /** The shell-row fixture stays valid wire-wise (the record is the client's). */
  @Test
  public void shellRowFixtureMatchesTheClientRecord() {
    ShellTask task =
        new ShellTask(
            "sh_1", "running", "make verify", "C:\\repo", null, null, new ShellTask.Time(1L, null));
    assertEquals("sh_1", task.id());
    assertTrue(task.isRunning());
  }
}
