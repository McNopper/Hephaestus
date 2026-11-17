package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/**
 * U-104 - the store-backed stage -&gt; dispatch agent map over the JSON-RPC surface: an empty
 * default, set/get round trips persisted in {@code _meta.json} (readable after a store reopen), a
 * blank agent clearing the row back to the default, and a non-V stage rejected loudly at the verb
 * (U-107/U-109 pattern - never silently sanitized). No file edits anywhere: the map is the
 * chat/Board-settable selection the stage-pipeline skill's STAGE-AGENT MAP reads at spawn time.
 */
public class StageAgentsTest extends ToolRpcHarness {

  private JsonObject callObj(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertFalse(result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  @Test
  public void aFreshStoreHasNoOverridesAndUsesTheDefaultMap() {
    JsonObject map = callObj("task_stage_agents", "{\"project\":\"p\"}");
    assertEquals("no override rows by default", 0, map.size());
  }

  @Test
  public void setAndGetRoundTripAndPersistenceAcrossAReopen() {
    JsonObject set =
        callObj(
            "task_set_stage_agent",
            "{\"project\":\"p\",\"stage\":\"implementation\",\"agent\":\"hephaestus-implementation\"}");
    assertEquals("hephaestus-implementation", set.get("implementation").getAsString());

    callObj(
        "task_set_stage_agent",
        "{\"project\":\"p\",\"stage\":\"test-design\",\"agent\":\"hephaestus-test-design\"}");
    JsonObject got = callObj("task_stage_agents", "{\"project\":\"p\"}");
    assertEquals(2, got.size());
    assertEquals("hephaestus-implementation", got.get("implementation").getAsString());
    assertEquals("hephaestus-test-design", got.get("test-design").getAsString());

    // the map lives in _meta.json: a FRESH store instance reads it back (meta round trip)
    JsonObject reopened = new JsonObject();
    new TaskStore(root).stageAgents("p").forEach(reopened::addProperty);
    assertEquals("persisted", 2, reopened.size());
    assertEquals("hephaestus-implementation", reopened.get("implementation").getAsString());
  }

  @Test
  public void aNonVStageIsRejectedLoudly() {
    JsonObject result =
        call(
            "task_set_stage_agent",
            "{\"project\":\"p\",\"stage\":\"backlog\",\"agent\":\"whatever\"}");
    assertTrue("domain error, not a protocol error", result.get("isError").getAsBoolean());
    String text =
        result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertTrue(text, text.contains("stage must be one of"));

    // nothing was stored for the rejected stage
    assertEquals(0, callObj("task_stage_agents", "{\"project\":\"p\"}").size());
  }

  @Test
  public void aBlankAgentClearsTheRowBackToTheDefault() {
    callObj(
        "task_set_stage_agent",
        "{\"project\":\"p\",\"stage\":\"design\",\"agent\":\"hephaestus-design\"}");
    assertEquals(1, callObj("task_stage_agents", "{\"project\":\"p\"}").size());

    JsonObject cleared =
        callObj("task_set_stage_agent", "{\"project\":\"p\",\"stage\":\"design\",\"agent\":\"\"}");
    assertEquals("blank agent = back to the default agent file", 0, cleared.size());
    assertEquals("and the clear persists too", 0, new TaskStore(root).stageAgents("p").size());
  }
}
