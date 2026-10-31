package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The root-node composition of the Session Details tree (U-041, U-096): pending questions of the
 * session's subtree, subagent children and shell tasks render as their own collapsible sections
 * ABOVE the message rows, so the live structure of the session is visible before the transcript
 * (questions first - the one thing that stalls the agent until the human answers). A section is a
 * real tree PARENT - its rows are nested children, matching the "nested under the parent session"
 * shape of the v2 subagent tree. Pure list surgery - SWT-free so the composition (section labels,
 * counts, ordering, empty sections omitted) is unit-testable and the view stays a thin shell.
 */
public final class SessionSections {

  private SessionSections() {}

  /**
   * @param subagents the session's child sessions (may be {@code null})
   * @param shells the session's shell tasks (may be {@code null})
   * @return the section roots (a Subagents section, then a Shell tasks section); an empty section
   *     contributes nothing. The caller appends the message rows after.
   */
  public static List<Object> roots(
      List<SessionSubagents.Row> subagents, List<SessionShells.Row> shells) {
    return roots(subagents, shells, List.of());
  }

  /**
   * U-096: the three-arg form adds the session subtree's pending QUESTIONS as the FIRST section -
   * the one thing that stalls the agent until the human answers leads the tree (above Subagents and
   * Shell tasks), so a subagent's question is visible on the parent's details without opening the
   * subagent.
   *
   * @param questions the pending questions of the session's subtree (may be {@code null})
   */
  public static List<Object> roots(
      List<SessionSubagents.Row> subagents,
      List<SessionShells.Row> shells,
      List<PendingQuestions.Row> questions) {
    List<Object> roots = new ArrayList<>();
    if (questions != null && !questions.isEmpty()) {
      roots.add(new Section("Questions (" + questions.size() + ")", questions));
    }
    if (subagents != null && !subagents.isEmpty()) {
      roots.add(new Section("Subagents (" + subagents.size() + ")", subagents));
    }
    if (shells != null && !shells.isEmpty()) {
      roots.add(new Section("Shell tasks (" + shells.size() + ")", shells));
    }
    return roots;
  }

  /** One section root: the header label and its nested rows. */
  public record Section(String label, List<?> children) {

    public Section {
      children = (children == null) ? List.of() : List.copyOf(children);
    }
  }
}
