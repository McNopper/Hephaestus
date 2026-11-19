package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/**
 * U-110 done-time enforcement: the DEFINITION stage's product is the point of the V, so a
 * definition-stage ticket cannot reach {@code done} without a recorded artifact - the gate the
 * retired {@code task_advance} used to hold at stage exit now lives at the done transition
 * (stationary tickets never leave their stage). Verification stages and legacy unstaged tickets are
 * exempt; blocked tickets still cannot be done at all (U-067).
 */
public class DoneGateTest extends ToolRpcHarness {

  /** A clean two-arg round trip that fails the test on a domain error. */
  private JsonObject ok(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertFalse(result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  /** A two-arg round trip expecting a domain error; returns the error text. */
  private String err(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertTrue("expected a domain error result", result.get("isError").getAsBoolean());
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  @Test
  public void definitionStageCannotBeDoneBare() {
    ok("task_create", "{\"project\":\"p\",\"title\":\"t\",\"stage\":\"design\"}");
    ok("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"in-review\"}");
    String error = err("task_update", TO_DONE);
    assertTrue(error, error.contains("without a recorded artifact"));
  }

  @Test
  public void definitionStageDonesWithItsProduct() {
    ok("task_create", "{\"project\":\"p\",\"title\":\"t\",\"stage\":\"design\"}");
    ok(
        "task_add_artifact",
        "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"kind\":\"doc\","
            + "\"ref\":\"docs/design-product.md\",\"note\":\"stage product\",\"by\":\"w\"}");
    JsonObject done = ok("task_update", TO_DONE);
    assertEquals("done", done.get("status").getAsString());
  }

  @Test
  public void verificationStageIsExempt() {
    ok("task_create", "{\"project\":\"p\",\"title\":\"t\",\"stage\":\"test-design\"}");
    ok("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"in-review\"}");
    JsonObject done = ok("task_update", TO_DONE);
    assertEquals("done", done.get("status").getAsString());
  }

  @Test
  public void legacyUnstagedTicketIsExempt() {
    ok("task_create", "{\"project\":\"p\",\"title\":\"legacy bugfix\"}");
    JsonObject done = ok("task_update", TO_DONE);
    assertEquals("done", done.get("status").getAsString());
  }

  @Test
  public void blockedTicketStillCannotBeDone() {
    ok(
        "task_create",
        "{\"project\":\"p\",\"title\":\"t\",\"stage\":\"design\",\"blocked\":true,"
            + "\"blocker\":\"needs a human\"}");
    String error = err("task_update", TO_DONE);
    assertTrue(
        error, error.contains("never blocked") || error.contains("without a recorded artifact"));
  }

  @Test
  public void createWithAMissingBasisIsRejected() {
    String error =
        err(
            "task_create",
            "{\"project\":\"p\",\"title\":\"orphan\",\"stage\":\"implementation\","
                + "\"basis\":\"GONE-9\"}");
    assertTrue(error, error.contains("basis ticket GONE-9 not found"));
  }

  @Test
  public void basisIsRecordedAndRoundTripsThroughTheFile() throws Exception {
    ok("task_create", "{\"project\":\"p\",\"title\":\"sys\",\"stage\":\"system\"}");
    ok(
        "task_create",
        "{\"project\":\"p\",\"title\":\"design child\",\"stage\":\"design\",\"basis\":\"T-001\"}");
    TaskStore fresh = new TaskStore(root);
    Task child = fresh.get("p", "T-002");
    assertEquals("T-001", child.basis);
  }

  @Test
  public void taskUpdateCanRepointBasisThroughTheVerb() {
    ok("task_create", "{\"project\":\"p\",\"title\":\"root one\",\"stage\":\"requirements\"}");
    ok("task_create", "{\"project\":\"p\",\"title\":\"root two\",\"stage\":\"requirements\"}");
    ok(
        "task_create",
        "{\"project\":\"p\",\"title\":\"child\",\"stage\":\"design\",\"basis\":\"T-001\"}");
    // Owner rule 2026-11-19: re-joining is a VERB, not a hand edit - basis repoints through
    // task_update (validated live+archive), so chains stay correctable inside the API.
    ok("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-003\",\"basis\":\"T-002\"}");
    TaskStore fresh = new TaskStore(root);
    assertEquals("T-002", fresh.get("p", "T-003").basis);
    String error =
        err("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-003\",\"basis\":\"GONE-9\"}");
    assertTrue(error, error.contains("basis ticket GONE-9 not found"));
    assertEquals(
        "a rejected repoint leaves the link alone",
        "T-002",
        new TaskStore(root).get("p", "T-003").basis);
    ok("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-003\",\"basis\":null}");
    assertTrue("null clears the link", new TaskStore(root).get("p", "T-003").basis == null);
  }

  private static final String TO_DONE =
      "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"done\"}";
}
