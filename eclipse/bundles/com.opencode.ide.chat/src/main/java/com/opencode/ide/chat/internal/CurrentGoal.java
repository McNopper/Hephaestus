package com.opencode.ide.chat.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * T-141: the goal line's data - the NEWEST {@code active} sprint's goal from the task store's
 * {@code _meta.json} (the same sprints map the Board's sprint selector renders, BoardModel's {@code
 * sprintGoals}). opencode has no goal API (probed 2026-11-16: no session/goal field, every
 * candidate endpoint 404s), so the store is the honest source; the Board stays authoritative and
 * the chat's placeholder is a convenience mirror.
 *
 * <p>Every failure path resolves to {@code null}: a goal must never break the view.
 */
public final class CurrentGoal {

  private CurrentGoal() {}

  /**
   * The active sprint goal for {@code <tasksRoot>/<project>/_meta.json}; {@code null} on any miss.
   */
  public static String activeSprintGoal(Path tasksRoot, String project) {
    if (tasksRoot == null || project == null || project.isBlank()) {
      return null;
    }
    Path meta = tasksRoot.resolve(project).resolve("_meta.json");
    if (!Files.isRegularFile(meta)) {
      return null;
    }
    try {
      return pick(Files.readString(meta, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The goal of the sprint with the greatest {@code created_at} among those whose {@code status} is
   * {@code active}; {@code null} when none qualifies or the JSON is malformed.
   */
  public static String pick(String metaJson) {
    if (metaJson == null || metaJson.isBlank()) {
      return null;
    }
    try {
      JsonObject root = JsonParser.parseString(metaJson).getAsJsonObject();
      JsonObject sprints = root.getAsJsonObject("sprints");
      if (sprints == null) {
        return null;
      }
      String bestCreated = null;
      String bestGoal = null;
      for (Map.Entry<String, JsonElement> entry : sprints.entrySet()) {
        if (!entry.getValue().isJsonObject()) {
          continue;
        }
        JsonObject sprint = entry.getValue().getAsJsonObject();
        if (!"active".equals(optString(sprint, "status"))) {
          continue;
        }
        String created = optString(sprint, "created_at");
        if (created == null) {
          created = "";
        }
        // ISO-8601 UTC compares lexicographically (same shape + Z suffix)
        if (bestCreated == null || created.compareTo(bestCreated) > 0) {
          bestCreated = created;
          bestGoal = optString(sprint, "goal");
        }
      }
      return bestGoal == null || bestGoal.isBlank() ? null : bestGoal;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String optString(JsonObject object, String key) {
    return object.has(key) && object.get(key).isJsonPrimitive()
        ? object.get(key).getAsString()
        : null;
  }
}
