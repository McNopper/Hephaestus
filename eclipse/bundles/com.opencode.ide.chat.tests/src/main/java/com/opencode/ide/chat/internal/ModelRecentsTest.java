package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Unit tests for CP-09 / FR-CP-09 - the recent-models list the selector orders by: most-recent
 * first, no duplicates, blanks skipped, capped at the remembered N, and a stable storage round trip
 * (order preserved, never sorted - "most recent first" is the whole point).
 */
public class ModelRecentsTest {

  @Test
  public void theJustUsedModelMovesToTheFrontAndOlderOnesSlideBack() {
    List<String> recents =
        ModelRecents.recentWith(List.of("prov/b", "prov/c"), "prov/a", ModelRecents.LIMIT);

    assertEquals(List.of("prov/a", "prov/b", "prov/c"), recents);
  }

  @Test
  public void reUsingAModelMovesItToFrontInsteadOfDuplicatingIt() {
    List<String> recents =
        ModelRecents.recentWith(List.of("prov/a", "prov/b"), "prov/b", ModelRecents.LIMIT);

    assertEquals(List.of("prov/b", "prov/a"), recents);
    assertEquals(2, recents.size());
  }

  @Test
  public void blankInputIsSkippedAndTheListStaysClean() {
    assertTrue(ModelRecents.recentWith(null, null, ModelRecents.LIMIT).isEmpty());
    assertTrue(ModelRecents.recentWith(List.of(), "   ", ModelRecents.LIMIT).isEmpty());
    assertEquals(
        List.of("prov/a"),
        ModelRecents.recentWith(
            java.util.Arrays.asList(null, "", "  "), "prov/a", ModelRecents.LIMIT));
  }

  @Test
  public void theListIsCappedAtTheConfiguredLimit() {
    List<String> full = List.of("m1", "m2", "m3", "m4", "m5");
    List<String> recents = ModelRecents.recentWith(full, "m6", 5);

    assertEquals(5, recents.size());
    assertEquals("the newest wins the front slot", "m6", recents.get(0));
    assertEquals("the oldest falls off the back", "m4", recents.get(4));
  }

  @Test
  public void storageRoundTripPreservesTheOrder() {
    List<String> stored = List.of("prov/z", "prov/a", "prov/m");
    String formatted = ModelRecents.format(stored);

    assertEquals("prov/z,prov/a,prov/m", formatted);
    assertEquals(stored, ModelRecents.parse(formatted));
    assertEquals("", ModelRecents.format(null));
    assertEquals(List.of(), ModelRecents.parse(null));
    assertEquals(List.of(), ModelRecents.parse("  "));
    assertEquals(List.of("a", "b"), ModelRecents.parse("a,, ,b"));
  }
}
