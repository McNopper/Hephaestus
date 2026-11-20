package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.opencode.ide.tasks.VStages;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for {@link StageSelection}: the full toggle truth table behind the Stages dropdown
 * (extracted from the SWT menu creator), including the two reset quirks - empty selection resets to
 * all, and a full-sized selection resets to all - plus the defensive null handling and the {@code
 * label} shapes. The vocabulary is exactly the ten canonical V stages (owner directive 2026-11-19:
 * the untracked/stage-less group is retired - a ticket is born in a stage).
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
  public void allStagesIsExactlyTheTenVStagesInOrder() {
    assertEquals(new ArrayList<>(VStages.STAGES), new ArrayList<>(StageSelection.allStages()));
    assertEquals(VStages.STAGES.size(), StageSelection.allStages().size());
  }

  @Test
  public void firstToggleFromAllRemovesOnlyTheToggledStage() {
    Set<String> next = StageSelection.toggle(null, "design");

    assertNotNull(next);
    assertEquals(VStages.STAGES.size() - 1, next.size()); // 9 of 10
    assertFalse(next.contains("design"));
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
    assertEquals(VStages.STAGES.size() - 2, next.size());
  }

  @Test
  public void removingTheLastRemainingStageResetsToAll() {
    assertNull(
        "empty selection must reset to all, never a blank board",
        StageSelection.toggle(new LinkedHashSet<>(Set.of("design")), "design"));
  }

  @Test
  public void togglingAnUnknownStageFromAllIsAResetNoOp() {
    // quirk, preserved from the original: "all" minus an id it does not
    // contain is still full-sized (10), and full-sized resets to null
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
    // 9 canonical-ish ids + one unknown = 10 elements -> counts as "full"
    Set<String> nine = allMinus("requirements", "system");
    nine.add("bogus1");

    assertNull(StageSelection.toggle(nine, "bogus2"));
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
    assertEquals("10/10", StageSelection.label(StageSelection.allStages()));
    assertEquals("9/10", StageSelection.label(allMinus("design")));
    assertEquals("2/10", StageSelection.label(Set.of("design", "system")));
    assertEquals("0/10", StageSelection.label(Set.of()));
  }
}
