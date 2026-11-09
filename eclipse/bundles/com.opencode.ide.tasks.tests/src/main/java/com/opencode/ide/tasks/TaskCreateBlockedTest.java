package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/**
 * B-026: task_create honors blocked/blocker arguments instead of silently dropping them. A ticket
 * born blocked enters the U-067 state through the one door: status moves to blocked, resume_to
 * remembers product-backlog (where clearing returns it), and the reason is mandatory; a blocker
 * without blocked=true is a caller mistake and is rejected, not ignored.
 */
public class TaskCreateBlockedTest extends ToolRpcHarness {

  private JsonObject callCreate(String argsJson) {
    JsonObject result = call("task_create", argsJson);
    assertFalse(result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  private String callError(String argsJson) {
    JsonObject result = call("task_create", argsJson);
    assertTrue("expected a domain error result", result.get("isError").getAsBoolean());
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  @Test
  public void bornBlockedEntersTheStateAndClearingReturnsToProductBacklog() {
    JsonObject t =
        callCreate(
            "{\"project\":\"p\",\"title\":\"born blocked\",\"blocked\":true,"
                + "\"blocker\":\"waiting on the PO decision\"}");
    assertEquals("blocked", t.get("status").getAsString());
    assertEquals("product-backlog", t.get("resume_to").getAsString());
    assertEquals("waiting on the PO decision", t.get("blocker").getAsString());
    assertTrue(t.get("blocked").getAsBoolean());

    // the ONE door out (U-067): task_clear_blocked, not a plain task_update
    JsonObject cleared = call("task_clear_blocked", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertFalse(cleared.get("isError").getAsBoolean());
    JsonObject after =
        JsonParser.parseString(
                cleared
                    .getAsJsonArray("content")
                    .get(0)
                    .getAsJsonObject()
                    .get("text")
                    .getAsString())
            .getAsJsonObject();
    assertEquals(
        "clearing returns to the status remembered at birth",
        "product-backlog",
        after.get("status").getAsString());
    assertTrue(after.get("resume_to").isJsonNull());
    assertTrue(after.get("blocker").isJsonNull());
    assertFalse(after.get("blocked").getAsBoolean());
  }

  @Test
  public void blockedWithoutAReasonIsRejected() {
    String err = callError("{\"project\":\"p\",\"title\":\"no reason\",\"blocked\":true}");
    assertTrue(err, err.contains("requires a non-empty blocker reason"));
  }

  @Test
  public void blankBlockerIsRejected() {
    String err =
        callError("{\"project\":\"p\",\"title\":\"blank\",\"blocked\":true,\"blocker\":\"  \"}");
    assertTrue(err, err.contains("requires a non-empty blocker reason"));
  }

  @Test
  public void blockerWithoutBlockedIsRejected() {
    String err =
        callError(
            "{\"project\":\"p\",\"title\":\"dangling\",\"blocker\":\"nobody raised blocked\"}");
    assertTrue(err, err.contains("blocker requires blocked=true"));
  }

  @Test
  public void plainCreateStaysUnblocked() {
    JsonObject t = callCreate("{\"project\":\"p\",\"title\":\"ordinary\"}");
    assertEquals("product-backlog", t.get("status").getAsString());
    assertFalse(t.get("blocked").getAsBoolean());
    assertTrue(t.get("blocker").isJsonNull());
    assertTrue(t.get("resume_to").isJsonNull());
  }
}
