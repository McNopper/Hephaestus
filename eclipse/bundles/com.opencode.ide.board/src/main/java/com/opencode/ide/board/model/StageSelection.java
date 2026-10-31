package com.opencode.ide.board.model;

import com.opencode.ide.tasks.VStages;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The SWT-free truth table behind the Stages toolbar dropdown: which stage ids a toggle produces,
 * and the compact {@code n/11} label. {@code null} means "all visible / no filter" — the same
 * convention as {@link BoardModel#stageFilter()} and the view's {@code visibleStages}. Extracted
 * 1:1 from the board view's former {@code toggleStage} so the selection semantics are testable
 * without SWT.
 *
 * <p>Semantics (quirks included, exactly as the menu behaved before):
 *
 * <ul>
 *   <li>{@code toggle(null, X)} — the first restriction starts from ALL stages (the ten canonical V
 *       stages plus the untracked group) and removes {@code X}: all-minus-X.
 *   <li>{@code toggle(S, X)} with {@code X} in {@code S} removes it; otherwise adds it.
 *       Non-canonical ids ride along untouched.
 *   <li>An empty result resets to {@code null}: unchecking the last visible stage never blanks the
 *       board by accident.
 *   <li>A full-sized result (all ten stages + untracked) resets to {@code null}: everything
 *       selected is the same as no filter. The check is by <b>size</b> ({@code 11}), not by
 *       content.
 * </ul>
 */
public final class StageSelection {

  private StageSelection() {}

  /** All selectable ids: the ten canonical V stages plus the untracked group. */
  public static Set<String> allStages() {
    Set<String> all = new LinkedHashSet<>(VStages.STAGES);
    all.add(PipelineSnapshot.UNTRACKED);
    return all;
  }

  /**
   * The selection after toggling {@code stage}. Never mutates {@code current}; returns the new
   * selection, or {@code null} for "all".
   *
   * <p>Defensive difference from the original SWT-embedded code: a {@code null} stage is ignored
   * and {@code current} comes back unchanged. The original would have inserted {@code null} into
   * the set (a latent poison for the {@code Set.copyOf} inside {@link BoardModel#setStageFilter}),
   * but no menu item can produce a {@code null} stage, so this only tightens unreachable behavior.
   */
  public static Set<String> toggle(Set<String> current, String stage) {
    if (stage == null) {
      return current;
    }
    Set<String> selection = new LinkedHashSet<>();
    if (current == null) {
      // first restriction: start from everything, then toggle
      selection.addAll(allStages());
      selection.remove(stage);
    } else {
      selection.addAll(current);
      if (!selection.remove(stage)) {
        selection.add(stage);
      }
    }
    // unchecking the last stage(s) would blank the board: treat as reset
    if (selection.isEmpty()) {
      return null;
    }
    if (selection.size() == VStages.STAGES.size() + 1) {
      return null; // everything checked = no filter
    }
    return selection;
  }

  /** The dropdown label: {@code "all"} or {@code "n/11"} for a selection. */
  public static String label(Set<String> selection) {
    return selection == null ? "all" : selection.size() + "/" + (VStages.STAGES.size() + 1);
  }
}
