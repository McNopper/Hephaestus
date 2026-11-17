package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/**
 * T-155 - the {@code review: human | ai} field over the JSON-RPC surface: accepted values with loud
 * rejection of anything else (U-109 pattern), absent = ai = today's behavior (every existing ticket
 * stays valid), the OWNER's first/last-stage-only constraint enforced at BOTH verbs, the codec
 * round trip, and the update-whitelist path (settable without file edits).
 */
public class ReviewFieldTest extends ToolRpcHarness {

  private JsonObject callOk(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertFalse("expected success: " + result, result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  private String callErr(String tool, String argsJson) {
    JsonObject result = call(tool, argsJson);
    assertTrue("expected a domain error", result.get("isError").getAsBoolean());
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  @Test
  public void reviewHumanShowsInCreateAndGetAndSurvivesTheFileRoundTrip() {
    JsonObject created =
        callOk(
            "task_create",
            "{\"project\":\"p\",\"title\":\"owner check\",\"stage\":\"requirements\","
                + "\"review\":\"human\"}");
    assertEquals("human", created.get("review").getAsString());

    JsonObject fetched =
        callOk(
            "task_get",
            "{\"project\":\"p\",\"ticket_id\":\"" + created.get("id").getAsString() + "\"}");
    assertEquals("task_get carries it", "human", fetched.get("review").getAsString());

    Task reopened = new TaskStore(root).get("p", created.get("id").getAsString());
    assertEquals("the codec round trip keeps it", "human", reopened.review);
    assertTrue(
        "the frontmatter carries the key",
        com.opencode.ide.tasks.TaskFileCodec.write(reopened).contains("review: human"));
  }

  @Test
  public void absentMeansAiAndLegacyTicketsStayValid() {
    JsonObject created = callOk("task_create", "{\"project\":\"p\",\"title\":\"plain\"}");
    assertTrue("the field is always present in the output", created.has("review"));
    assertTrue("absent = ai default (json null)", created.get("review").isJsonNull());
    assertNull(new TaskStore(root).get("p", created.get("id").getAsString()).review);

    // legacy shape: a file without the key must parse (the sync mojo's fixtures are exactly that)
    assertNull(
        TaskFileCodec.read("---\nid: T-009\ntitle: legacy\nstatus: product-backlog\n---\n\nx\n")
            .review);
  }

  @Test
  public void anInvalidReviewValueIsRejectedLoudlyAtTheVerb() {
    String err = callErr("task_create", "{\"project\":\"p\",\"title\":\"x\",\"review\":\"maybe\"}");
    assertTrue(err, err.contains("review must be 'human' or 'ai'"));
  }

  @Test
  public void humanIsOnlyAllowedOnTheFirstAndLastStageOrStageless() {
    String midErr =
        callErr(
            "task_create",
            "{\"project\":\"p\",\"title\":\"mid\",\"stage\":\"design\",\"review\":\"human\"}");
    assertTrue(midErr, midErr.contains("review: human is only allowed on the first stage"));

    JsonObject lastStage =
        callOk(
            "task_create",
            "{\"project\":\"p\",\"title\":\"acceptance\",\"stage\":\"test-requirements\","
                + "\"review\":\"human\"}");
    assertEquals("human", lastStage.get("review").getAsString());

    JsonObject stageless =
        callOk("task_create", "{\"project\":\"p\",\"title\":\"no stage\",\"review\":\"human\"}");
    assertEquals("human", stageless.get("review").getAsString());

    // a mid-V ticket cannot BECOME human either (stage is immutable through task_update,
    // so the constraint is checked against the ticket's fixed stage)
    JsonObject mid =
        callOk("task_create", "{\"project\":\"p\",\"title\":\"arch\",\"stage\":\"architecture\"}");
    String updateErr =
        callErr(
            "task_update",
            "{\"project\":\"p\",\"ticket_id\":\""
                + mid.get("id").getAsString()
                + "\",\"review\":\"human\"}");
    assertTrue(updateErr, updateErr.contains("review: human is only allowed"));
    assertTrue(
        "nothing changed",
        callOk(
                "task_get",
                "{\"project\":\"p\",\"ticket_id\":\"" + mid.get("id").getAsString() + "\"}")
            .get("review")
            .isJsonNull());
  }

  @Test
  public void aBornBlockedFirstStageTicketCanCarryHumanReview() {
    JsonObject created =
        callOk(
            "task_create",
            "{\"project\":\"p\",\"title\":\"owner decision\",\"stage\":\"requirements\","
                + "\"blocked\":true,\"blocker\":\"needs the owner's call\",\"review\":\"human\"}");
    assertEquals("human", created.get("review").getAsString());
    assertTrue("born blocked", created.get("blocked").getAsBoolean());
    assertEquals("first stage", "requirements", created.get("stage").getAsString());
  }

  @Test
  public void reviewIsSettableAndClearableThroughUpdate() {
    JsonObject first =
        callOk(
            "task_create", "{\"project\":\"p\",\"title\":\"flip me\",\"stage\":\"requirements\"}");
    String id = first.get("id").getAsString();

    JsonObject human =
        callOk(
            "task_update", "{\"project\":\"p\",\"ticket_id\":\"" + id + "\",\"review\":\"human\"}");
    assertEquals("human", human.get("review").getAsString());

    JsonObject cleared =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"" + id + "\",\"review\":null}");
    assertTrue("null clears back to the ai default", cleared.get("review").isJsonNull());
    assertNull(new TaskStore(root).get("p", id).review);
  }
}
