package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/**
 * U-107 (T-029 decision): task_create rejects id_prefix values that would mint a malformed id. Ids
 * are minted as {@code <prefix>-NNN}, so a blank prefix or one ending in '-' produced C--001 - the
 * guard rejects loudly instead of silently sanitizing. Ordinary prefixes are unchanged, and the
 * historic malformed id itself stays readable (no migration).
 */
public class TaskCreatePrefixGuardTest extends ToolRpcHarness {

  private String callCreateError(String argsJson) {
    JsonObject result = call("task_create", argsJson);
    assertTrue("expected a domain error result", result.get("isError").getAsBoolean());
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  @Test
  public void trailingDashPrefixIsRejected() {
    String err = callCreateError("{\"project\":\"p\",\"title\":\"broken\",\"id_prefix\":\"C-\"}");
    assertTrue(err, err.contains("must not be blank or end in '-'"));
  }

  @Test
  public void whitespaceAndBlankPrefixesAreRejected() {
    String err = callCreateError("{\"project\":\"p\",\"title\":\"a\",\"id_prefix\":\"   \"}");
    assertTrue(err, err.contains("must not be blank or end in '-'"));
  }

  @Test
  public void ordinaryPrefixesKeepWorking() {
    JsonObject result =
        call("task_create", "{\"project\":\"p\",\"title\":\"fine\",\"id_prefix\":\"W\"}");
    assertFalse(result.get("isError").getAsBoolean());
    JsonObject t =
        JsonParser.parseString(
                result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
            .getAsJsonObject();
    assertEquals("W-001", t.get("id").getAsString());
  }
}
