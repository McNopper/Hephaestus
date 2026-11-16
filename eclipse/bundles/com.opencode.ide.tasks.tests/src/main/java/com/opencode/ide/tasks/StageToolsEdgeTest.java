package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/** The edge channels of the stage-related task tools, over the shared JSON-RPC dispatcher. */
public class StageToolsEdgeTest extends ToolRpcHarness {

  private JsonObject callOk(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertFalse("expected a clean result", result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  private String callErrorText(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertTrue("expected an isError result", result.get("isError").getAsBoolean());
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  @Test
  public void createWithBogusStageIsAnInvalidErrorResult() {
    String text =
        callErrorText("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"waterfall\"}");
    assertTrue(text.contains("stage"));
    assertTrue(
        "nothing was written", new TaskStore(root).list("p", null, null, null, null).isEmpty());
  }

  @Test
  public void updateWithBogusStageIsAnInvalidErrorResult() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"design\"}");
    String text =
        callErrorText(
            "task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":\"nope\"}");
    assertTrue(text.contains("stage"));
    assertEquals(
        "a rejected update wrote nothing", "design", new TaskStore(root).get("p", "T-001").stage);
  }

  @Test
  public void updateWithNullStageIsRefusedThroughTools() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"design\"}");
    String text =
        callErrorText("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":null}");
    assertTrue(text.contains("stage"));
    assertEquals(
        "the stage survives unchanged", "design", new TaskStore(root).get("p", "T-001").stage);
  }
}
