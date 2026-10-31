package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opencode.ide.tasks.VStages;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for {@link StageSelection}: the full toggle truth table behind the Stages dropdown
 * (extracted from the SWT menu creator), including the two reset quirks — empty selection resets to
 * all, and a full-sized selection resets to all — plus the defensive null handling and the {@code
 * label} shapes.
 */
public class StageSelectionTest {

  private static Set<String> allMinus(String... removed) {
    Set<String> all = StageSelection.allStages();
    for (String stage : removed) {
      all.remove(stage);
    }
    return all;
  }

  @Test
  public void allStagesIsTheTenVStagesPlusUntrackedInOrder() {
    List<String> expected = new ArrayList<>(VStages.STAGES);
    expected.add(PipelineSnapshot.UNTRACKED);
    assertEquals(expected, new ArrayList<>(StageSelection.allStages()));
    assertEquals(VStages.STAGES.size() + 1, StageSelection.allStages().size());
  }

  @Test
  public void firstToggleFromAllRemovesOnlyTheToggledStage() {
    Set<String> next = StageSelection.toggle(null, "design");

    assertNotNull(next);
    assertEquals(VStages.STAGES.size(), next.size()); // 10 of 11
    assertFalse(next.contains("design"));
    assertTrue(next.contains(PipelineSnapshot.UNTRACKED));
    assertEquals(allMinus("design"), next);
  }

  @Test
  public void firstToggleFromAllHoldsForEverySingleStage() {
    for (String stage : StageSelection.allStages()) {
      Set<String> next = StageSelection.toggle(null, stage);
      assertEquals("stage " + stage, allMinus(stage), next);
    }
  }

  @Test
  public void togglingTheMissingStageBackInResetsToAll() {
    Set<String> withoutDesign = StageSelection.toggle(null, "design");

    // re-checking the only removed stage makes the selection full again
    assertNull(StageSelection.toggle(withoutDesign, "design"));
  }

  @Test
  public void removingASecondStageShrinksFurther() {
    Set<String> next = StageSelection.toggle(StageSelection.toggle(null, "design"), "system");

    assertEquals(allMinus("design", "system"), next);
    assertEquals(VStages.STAGES.size() - 1, next.size());
  }

  @Test
  public void removingTheLastRemainingStageResetsToAll() {
    assertNull(
        "empty selection must reset to all, never a blank board",
        StageSelection.toggle(new LinkedHashSet<>(Set.of("design")), "design"));
  }

  @Test
  public void removingTheUntrackedGroupAloneAlsoResetsToAll() {
    Set<String> onlyUntracked = new LinkedHashSet<>(Set.of(PipelineSnapshot.UNTRACKED));

    assertNull(StageSelection.toggle(onlyUntracked, PipelineSnapshot.UNTRACKED));
  }

  @Test
  public void removingAStagePreservesUntrackedWhenNotToggled() {
    Set<String> two = new LinkedHashSet<>(Set.of(PipelineSnapshot.UNTRACKED, "requirements"));

    Set<String> next = StageSelection.toggle(two, "requirements");

    assertEquals(Set.of(PipelineSnapshot.UNTRACKED), next);
  }

  @Test
  public void addingAStageToASetWithUntrackedKeepsUntracked() {
    Set<String> onlyUntracked = new LinkedHashSet<>(Set.of(PipelineSnapshot.UNTRACKED));

    Set<String> next = StageSelection.toggle(onlyUntracked, "design");

    assertEquals(Set.of(PipelineSnapshot.UNTRACKED, "design"), next);
  }

  @Test
  public void togglingAnUnknownStageFromAllIsAResetNoOp() {
    // quirk, preserved from the original: "all" minus an id it does not
    // contain is still full-sized (11), and full-sized resets to null
    assertNull(StageSelection.toggle(null, "bogus"));
  }

  @Test
  public void togglingAnUnknownStageOnARestrictedSetRidesAlong() {
    Set<String> next = StageSelection.toggle(new LinkedHashSet<>(Set.of("design")), "bogus");

    // quirk, preserved: non-canonical ids are kept; only size can reset
    assertEquals(Set.of("design", "bogus"), next);
  }

  @Test
  public void fullSizeCheckIsByCountNotContent() {
    // 10 arbitrary ids + one unknown = 11 elements -> counts as "full"
    Set<String> ten = allMinus("requirements");
    ten.remove("system");
    ten.add("bogus1");

    assertNull(StageSelection.toggle(ten, "bogus2"));
  }

  @Test
  public void toggleNeverMutatesTheCurrentSelection() {
    Set<String> current = new LinkedHashSet<>(List.of("design", "system"));

    Set<String> removed = StageSelection.toggle(current, "design");
    Set<String> added = StageSelection.toggle(current, "architecture");

    assertEquals(List.of("design", "system"), new ArrayList<>(current));
    assertEquals(Set.of("system"), removed);
    assertEquals(Set.of("design", "system", "architecture"), added);
  }

  @Test
  public void nullStageIsIgnoredDefensively() {
    // documented difference from the SWT-embedded original, which would
    // have added null into the set (poison for Set.copyOf downstream)
    assertNull(StageSelection.toggle(null, null));
    Set<String> restricted = Set.of("design");
    assertSame(restricted, StageSelection.toggle(restricted, null));
  }

  @Test
  public void labelCoversAllPartialAndEmpty() {
    assertEquals("all", StageSelection.label(null));
    assertEquals("11/11", StageSelection.label(StageSelection.allStages()));
    assertEquals("10/11", StageSelection.label(allMinus("design")));
    assertEquals("2/11", StageSelection.label(Set.of("design", "system")));
    assertEquals("0/11", StageSelection.label(Set.of()));
  }
}
