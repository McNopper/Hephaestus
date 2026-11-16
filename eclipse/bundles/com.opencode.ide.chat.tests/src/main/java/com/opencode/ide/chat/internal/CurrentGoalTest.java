package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** T-141: the goal line's store-backed selection (pure - no SWT, no server). */
public class CurrentGoalTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String META =
      """
      {"sprints":{
        "old":{"id":"old","goal":"closed goal","status":"closed",
               "created_at":"2026-11-01T00:00:00.000Z"},
        "first-active":{"id":"first-active","goal":"older active","status":"active",
               "created_at":"2026-11-10T00:00:00.000Z"},
        "newest-active":{"id":"newest-active","goal":"the current wave","status":"active",
               "created_at":"2026-11-15T07:53:53.455Z"}
      }}
      """;

  @Test
  public void pickSelectsTheNewestActiveGoal() {
    assertEquals("the current wave", CurrentGoal.pick(META));
  }

  @Test
  public void pickSkipsClosedAndIgnoresThemEvenWhenNewer() {
    String json =
        """
        {"sprints":{
          "a":{"goal":"live","status":"active","created_at":"2026-11-01T00:00:00.000Z"},
          "b":{"goal":"dead but newer","status":"closed","created_at":"2026-11-20T00:00:00.000Z"}
        }}
        """;
    assertEquals("live", CurrentGoal.pick(json));
  }

  @Test
  public void pickIsNullWithoutAnActiveSprintOrOnGarbage() {
    assertNull(CurrentGoal.pick("{\"sprints\":{\"a\":{\"goal\":\"g\",\"status\":\"closed\"}}}"));
    assertNull(CurrentGoal.pick("{}"));
    assertNull(CurrentGoal.pick("not json"));
    assertNull(CurrentGoal.pick(null));
    assertNull(CurrentGoal.pick(""));
  }

  @Test
  public void activeSprintGoalReadsTheStoreLayout() throws Exception {
    Path root = tmp.getRoot().toPath().resolve("tasks");
    Files.createDirectories(root.resolve("hephaestus"));
    Files.writeString(
        root.resolve("hephaestus").resolve("_meta.json"), META, StandardCharsets.UTF_8);
    assertEquals("the current wave", CurrentGoal.activeSprintGoal(root, "hephaestus"));
    assertNull(CurrentGoal.activeSprintGoal(root, "other-project"));
    assertNull(CurrentGoal.activeSprintGoal(null, "hephaestus"));
  }
}
