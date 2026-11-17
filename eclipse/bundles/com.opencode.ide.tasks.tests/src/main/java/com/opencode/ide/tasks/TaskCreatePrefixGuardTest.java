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

  /**
   * U-109 AC1: the POST-filter value is validated at the single mint point. {@code W-!} passes the
   * raw U-107 guard (it does not end in '-' and is not blank), filters to {@code W-}, and used to
   * mint the malformed {@code W--001} - the very class U-107 closed at the raw value.
   */
  @Test
  public void aPrefixThatFiltersToATrailingDashIsRejectedAtTheMintPoint() {
    String err = callCreateError("{\"project\":\"p\",\"title\":\"bad\",\"id_prefix\":\"W-!\"}");

    assertTrue(err, err.contains("must not be blank or end in '-'"));
    assertTrue(
        "the message shows what the raw value filtered to: " + err,
        err.contains("filters to 'W-'"));
  }

  /**
   * U-109 AC2: {@code !!} filters to empty - the former SILENT {@code !!} -&gt; {@code T} fallback
   * is now a loud error (decision recorded on U-109), and the failed create leaves the {@code T}
   * counter untouched: the next ordinary create still mints {@code T-001}.
   */
  @Test
  public void aPrefixThatFiltersToBlankIsRejectedInsteadOfSilentlyBecomingT() {
    String err = callCreateError("{\"project\":\"p\",\"title\":\"bad\",\"id_prefix\":\"!!\"}");
    assertTrue(err, err.contains("must not be blank or end in '-'"));

    JsonObject ok = call("task_create", "{\"project\":\"p\",\"title\":\"ok\"}");
    assertFalse(ok.get("isError").getAsBoolean());
    JsonObject t =
        JsonParser.parseString(
                ok.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
            .getAsJsonObject();
    assertEquals(
        "nothing was minted under the T prefix by the rejected create",
        "T-001",
        t.get("id").getAsString());
  }
}
