package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tools.McpDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * MCP-surface checks over the dispatcher: the 21 task_* tools are advertised with input schemas,
 * results are pretty JSON with the pm field names, a claim takes the born-ready queue, domain
 * errors are isError text results (not protocol errors) and missing parameters map to JSON-RPC
 * -32602.
 */
public class TaskToolsDispatchTest {

  private static final List<String> EXPECTED_TOOLS =
      List.of(
          "task_create",
          "task_get",
          "task_list",
          "task_update",
          "task_set_blocked",
          "task_clear_blocked",
          "task_route_review_doubt",
          "task_stage_agents",
          "task_set_stage_agent",
          "task_claim",
          "task_release",
          "task_add_comment",
          "task_add_artifact",
          "task_remove_artifact",
          "task_todo",
          "task_links",
          "task_board",
          "task_archive_done",
          "task_traceability",
          "task_readiness",
          "task_doctor");

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private McpDispatcher dispatcher;

  @Before
  public void setUp() {
    dispatcher = new McpDispatcher(new TaskToolProvider(tmp.getRoot().toPath().resolve("tasks")));
  }

  @Test
  public void taskArchiveDoneSweepsAndReportsThroughTheMcpSurface() {
    TaskStore store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    Task old = store.create("p", TaskStore.CreateSpec.of("old history"));
    store.update("p", old.id, Map.of("status", "done"));

    // past cutoff: nothing archived, the skip report explains why
    JsonObject skippedRun =
        callOk("task_archive_done", "{\"project\":\"p\",\"before\":\"2000-01-01T00:00:00Z\"}");
    assertEquals(0, skippedRun.getAsJsonArray("archived").size());
    assertTrue(skippedRun.getAsJsonArray("skipped").toString().contains(old.id + ":recent"));

    // future cutoff: the candidate moves and is reported
    JsonObject movedRun =
        callOk("task_archive_done", "{\"project\":\"p\",\"before\":\"2999-01-01T00:00:00Z\"}");
    assertEquals(1, movedRun.getAsJsonArray("archived").size());
    assertEquals(old.id, movedRun.getAsJsonArray("archived").get(0).getAsString());
    assertTrue(movedRun.has("skipped"));

    // a malformed cutoff is a domain error RESULT (not a JSON-RPC protocol error)
    JsonObject bad = call("task_archive_done", "{\"project\":\"p\",\"before\":\"nope\"}");
    assertTrue("domain error flagged", bad.get("isError").getAsBoolean());
    assertTrue("the message names the field: " + bad, bad.toString().contains("before"));
  }

  private JsonObject call(String tool, String argsJson) {
    return call(dispatcher, tool, argsJson);
  }

  private JsonObject call(McpDispatcher d, String tool, String argsJson) {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool
            + "\",\"arguments\":"
            + argsJson
            + "}}";
    JsonObject response = JsonParser.parseString(d.handle(body)).getAsJsonObject();
    assertFalse(
        "domain errors must be isError results, not protocol errors", response.has("error"));
    JsonObject result = response.getAsJsonObject("result");
    assertEquals(1, result.getAsJsonArray("content").size());
    assertEquals(
        "text",
        result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
    return result;
  }

  private JsonObject callOk(String tool, String argsJson) {
    return callOk(dispatcher, tool, argsJson);
  }

  private JsonObject callOk(McpDispatcher d, String tool, String argsJson) {
    JsonObject result = call(d, tool, argsJson);
    assertFalse(result.get("isError").getAsBoolean());
    return JsonParser.parseString(
            result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString())
        .getAsJsonObject();
  }

  private String callText(String tool, String argsJson) {
    return call(tool, argsJson)
        .getAsJsonArray("content")
        .get(0)
        .getAsJsonObject()
        .get("text")
        .getAsString();
  }

  /** The append-only history actions of a returned ticket, in order. */
  private static List<String> historyActions(JsonObject ticket) {
    List<String> out = new ArrayList<>();
    JsonArray history = ticket.getAsJsonArray("history");
    for (int i = 0; i < history.size(); i++) {
      out.add(history.get(i).getAsJsonObject().get("action").getAsString());
    }
    return out;
  }

  /** The comment texts of a returned ticket, in order. */
  private static List<String> commentTexts(JsonObject ticket) {
    List<String> out = new ArrayList<>();
    JsonArray comments = ticket.getAsJsonArray("comments");
    for (int i = 0; i < comments.size(); i++) {
      out.add(comments.get(i).getAsJsonObject().get("text").getAsString());
    }
    return out;
  }

  @Test
  public void toolsListAdvertisesEveryRegisteredToolWithSchemas() {
    JsonObject response =
        JsonParser.parseString(
                dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .getAsJsonObject();
    JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(21, tools.size());
    for (int i = 0; i < tools.size(); i++) {
      JsonObject tool = tools.get(i).getAsJsonObject();
      assertEquals(EXPECTED_TOOLS.get(i), tool.get("name").getAsString());
      assertTrue(tool.get("description").getAsString().length() > 10);
      assertTrue("inputSchema must be an object", tool.get("inputSchema").isJsonObject());
    }
    // spot-check required arrays on two tools
    for (int i = 0; i < tools.size(); i++) {
      if (tools.get(i).getAsJsonObject().get("name").getAsString().equals("task_create")) {
        JsonArray req =
            tools
                .get(i)
                .getAsJsonObject()
                .getAsJsonObject("inputSchema")
                .getAsJsonArray("required");
        assertEquals("[\"project\",\"title\"]", req.toString());
      }
    }
  }

  @Test
  public void createThenGetRoundTripsThroughJsonRpc() {
    JsonObject created =
        callOk(
            "task_create",
            "{\"project\":\"p\",\"title\":\"Write the thing\",\"type\":\"task\",\"role\":\"developer\","
                + "\"priority\":\"high\",\"story_points\":3,"
                + "\"acceptance_criteria\":[\"GIVEN a\",\"WHEN b\"]}");
    assertEquals("T-001", created.get("id").getAsString());
    assertEquals("ready", created.get("status").getAsString());
    assertTrue("snake_case field names preserved", created.has("story_points"));
    assertTrue(created.has("created_at"));

    JsonObject got = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("Write the thing", got.get("title").getAsString());
    assertEquals(3, got.get("story_points").getAsInt());
  }

  @Test
  public void claimWithNothingClaimableReturnsJsonNull() {
    String text = callText("task_claim", "{\"project\":\"p\",\"role\":\"developer\"}");
    assertEquals("the JSON literal null - worker loops terminate on it", "null", text);
  }

  @Test
  public void claimLifecycleThroughTools() {
    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"a\",\"role\":\"developer\",\"priority\":\"high\"}");
    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"b\",\"role\":\"developer\",\"priority\":\"low\"}");
    JsonObject claimed =
        callOk("task_claim", "{\"project\":\"p\",\"role\":\"developer\",\"by\":\"agent-1\"}");
    assertEquals("T-001", claimed.get("id").getAsString()); // high before low
    assertEquals("in-progress", claimed.get("status").getAsString());
    assertEquals("agent-1", claimed.get("assignee").getAsString());
    JsonObject board = callOk("task_board", "{\"project\":\"p\"}");
    assertEquals(
        "the board has one column per valid status (incl. U-038's paused)",
        Task.VALID_STATUSES.size(),
        board.size());
    assertEquals(0, board.getAsJsonArray("paused").size());
    assertEquals(1, board.getAsJsonArray("in-progress").size());
    assertEquals(1, board.getAsJsonArray("ready").size());
  }

  @Test
  public void notFoundAndInvalidAreErrorTextResults() {
    JsonObject result = call("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-404\"}");
    assertTrue(result.get("isError").getAsBoolean());
    String text =
        result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertTrue(text.contains("T-404 not found"));

    JsonObject badKind =
        call(
            "task_add_artifact",
            "{\"project\":\"p\",\"ticket_id\":\"x\",\"kind\":\"carrier\",\"ref\":\"r\"}");
    assertTrue(badKind.get("isError").getAsBoolean());
  }

  /**
   * T-240 (AC1/AC3 at the MCP surface): the reported bug - task_get on an ARCHIVED id used to
   * answer the plain "not found in project". The archived answer now names {@code _archive/} while
   * a genuinely absent id keeps the pinned historical message, and task_list still never returns
   * archived ids (the working set stays the working set).
   */
  @Test
  public void taskGetNamesTheArchiveForAnArchivedIdWhileAMissingIdKeepsThePlainNotFound() {
    JsonObject created = callOk("task_create", "{\"project\":\"p\",\"title\":\"swept history\"}");
    String id = created.get("id").getAsString();
    callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"" + id + "\",\"status\":\"done\"}");
    JsonObject sweep =
        callOk("task_archive_done", "{\"project\":\"p\",\"before\":\"2999-01-01T00:00:00Z\"}");
    assertEquals(
        "the fixture really swept into _archive/", 1, sweep.getAsJsonArray("archived").size());

    String archivedText = callText("task_get", "{\"project\":\"p\",\"ticket_id\":\"" + id + "\"}");
    assertTrue(
        "the archived verdict names the archive: " + archivedText,
        archivedText.contains("ticket " + id + " is archived - see "));
    assertTrue(
        "it points at the archive file: " + archivedText,
        archivedText.contains("_archive/" + id + ".md"));

    String missingText = callText("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-404\"}");
    assertEquals(
        "the absent id keeps the pinned message",
        "ticket T-404 not found in project p",
        missingText);

    JsonArray live =
        JsonParser.parseString(callText("task_list", "{\"project\":\"p\"}")).getAsJsonArray();
    assertEquals("task_list stays live-only after the sweep", 0, live.size());
  }

  @Test
  public void missingParameterIsJsonRpc32602() {
    JsonObject response =
        JsonParser.parseString(
                dispatcher.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_create\",\"arguments\":{\"project\":\"p\"}}}"))
            .getAsJsonObject();
    assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
    assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("title"));
  }

  @Test
  public void todoOpsThroughTools() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    JsonObject t =
        callOk(
            "task_todo",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"action\":\"add\",\"text\":\"step 1\"}");
    assertEquals(1, t.getAsJsonArray("todos").size());
    t =
        callOk(
            "task_todo",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"action\":\"toggle\",\"index\":0}");
    assertTrue(t.getAsJsonArray("todos").get(0).getAsJsonObject().get("done").getAsBoolean());
    t =
        callOk(
            "task_todo",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"action\":\"remove\",\"index\":0}");
    assertEquals(0, t.getAsJsonArray("todos").size());
  }

  @Test
  public void readyQueueOrderThroughListTool() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"low first\",\"priority\":\"low\"}");
    callOk(
        "task_create", "{\"project\":\"p\",\"title\":\"high second\",\"priority\":\"critical\"}");
    JsonArray arr =
        JsonParser.parseString(callText("task_list", "{\"project\":\"p\",\"status\":\"ready\"}"))
            .getAsJsonArray();
    assertEquals(2, arr.size());
    assertEquals(
        "critical sorts ahead of low",
        "high second",
        arr.get(0).getAsJsonObject().get("title").getAsString());
  }

  @Test
  public void linksUpDownBackThroughTools() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"upstream\",\"stage\":\"requirements\"}");
    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"child one\",\"stage\":\"system\",\"basis\":\"T-001\"}");
    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"child two\",\"stage\":\"test-system\",\"basis\":\"T-001\"}");
    JsonObject child = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-002\"}");
    assertEquals("T-001", child.get("basis").getAsString());
    JsonObject links = callOk("task_links", "{\"project\":\"p\",\"ticket_id\":\"T-002\"}");
    assertEquals(
        "up walks to the entry ticket",
        "T-001",
        links.getAsJsonArray("up").get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(0, links.getAsJsonArray("down").size());
    assertEquals(
        "back names the sibling under the same basis",
        "T-003",
        links.getAsJsonArray("back").get(0).getAsJsonObject().get("id").getAsString());
    JsonObject top = callOk("task_links", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals(2, top.getAsJsonArray("down").size());
    assertEquals(0, top.getAsJsonArray("up").size());
  }

  @Test
  public void artifactRemoveThroughTools() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    JsonObject t =
        callOk(
            "task_add_artifact",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"kind\":\"file\",\"ref\":\"docs/wrong.md\"}");
    assertEquals(1, t.getAsJsonArray("artifacts").size());
    t =
        callOk(
            "task_remove_artifact",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"index\":0,\"by\":\"dev\"}");
    assertEquals(0, t.getAsJsonArray("artifacts").size());
    JsonArray history = t.getAsJsonArray("history");
    assertEquals(
        "artifact removed:file:docs/wrong.md",
        history.get(history.size() - 1).getAsJsonObject().get("action").getAsString());
    assertTrue(
        "out-of-range index is an isError result, not a protocol error",
        call("task_remove_artifact", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"index\":3}")
            .get("isError")
            .getAsBoolean());
    assertEquals(
        "a missing index parameter is a -32602 protocol error",
        -32602,
        JsonParser.parseString(
                dispatcher.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_remove_artifact\",\"arguments\":"
                        + "{\"project\":\"p\",\"ticket_id\":\"T-001\"}}}"))
            .getAsJsonObject()
            .getAsJsonObject("error")
            .get("code")
            .getAsInt());
  }

  @Test
  public void doctorReportsUnresolvableArtifactRefsThroughTools() throws IOException {
    // a store shaped like production (<repo>/.harness/tasks) - only then
    // can the doctor derive the repo root and check refs against it
    Path repo = tmp.newFolder("repo").toPath();
    McpDispatcher inRepo =
        new McpDispatcher(new TaskToolProvider(repo.resolve(".harness").resolve("tasks")));
    callOk(inRepo, "task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    callOk(
        inRepo,
        "task_add_artifact",
        "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"kind\":\"file\",\"ref\":\"docs/missing.md\"}");
    JsonObject doctor = callOk(inRepo, "task_doctor", "{\"project\":\"p\"}");
    assertFalse(doctor.get("ok").getAsBoolean());
    assertTrue(
        doctor.getAsJsonArray("inconsistencies").get(0).getAsString().contains("docs/missing.md"));
  }

  @Test
  public void doctorReportsDriftAndGoesQuietWhenHealed() throws IOException {
    // clean store: ok with an empty report (the drift semantics - an unknown
    // status outside the seven-state model - are pinned at the store layer
    // in TaskStoreSemanticsTest)
    JsonObject clean = callOk("task_doctor", "{\"project\":\"p\"}");
    assertTrue(clean.get("ok").getAsBoolean());
    assertEquals(0, clean.getAsJsonArray("inconsistencies").size());

    // unknown status written by hand (drift) -> reported
    callOk("task_create", "{\"project\":\"p\",\"title\":\"drifter\"}");
    Path file = tmp.getRoot().toPath().resolve("tasks").resolve("p").resolve("T-001.md");
    Files.writeString(file, Files.readString(file).replace("status: ready", "status: limbo"));
    JsonObject drift = callOk("task_doctor", "{\"project\":\"p\"}");
    assertFalse(drift.get("ok").getAsBoolean());
    assertEquals(1, drift.getAsJsonArray("inconsistencies").size());
    assertTrue(
        drift
            .getAsJsonArray("inconsistencies")
            .get(0)
            .getAsString()
            .contains("unknown status 'limbo'"));

    // healed (rewritten to a valid state) -> quiet again
    Files.writeString(file, Files.readString(file).replace("status: limbo", "status: ready"));
    JsonObject healed = callOk("task_doctor", "{\"project\":\"p\"}");
    assertTrue(healed.get("ok").getAsBoolean());
  }

  @Test
  public void updateThroughToolsKeepsFieldOrderInHistory() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    JsonObject updated =
        callOk(
            "task_update",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"title\":\"b\",\"story_points\":5}");
    assertEquals("b", updated.get("title").getAsString());
    assertEquals(5, updated.get("story_points").getAsInt());
    String last =
        updated.getAsJsonArray("history").get(1).getAsJsonObject().get("action").getAsString();
    assertEquals("updated:title,story_points", last);
  }

  @Test
  public void updateSupportsExplicitNullsParity() {
    // pm parity: update could set assignee to a value AND clear it with null
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    JsonObject set =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"assignee\":\"x\"}");
    assertEquals("x", set.get("assignee").getAsString());
    JsonObject cleared =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"assignee\":null}");
    assertTrue(cleared.get("assignee").isJsonNull());
    // story_points null is a no-op (cannot null an int)
    JsonObject points =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":7}");
    points =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":null}");
    assertEquals(7, points.get("story_points").getAsInt());
  }

  @Test
  public void floatStringStoryPointsIsCleanError() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
    JsonObject result =
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":\"2.5\"}");
    assertTrue(
        "non-integer story_points is an isError result, not a protocol error",
        result.get("isError").getAsBoolean());
  }

  /**
   * T-249 (AC2/AC3 at the MCP surface): the reported bug - task_update with an out-of-enum priority
   * used to sail through McpDispatcher (inputSchema enums are declarative there, never enforced)
   * and persist with an 'updated:priority' history marker. The store refuses it now with create's
   * byte-identical message shape, the stored ticket is untouched (status AND priority asserted),
   * and the refused attempt writes no history at all.
   */
  @Test
  public void updateRefusesOutOfEnumPriorityLeavingTheStoredTicketUntouched() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"priority\":\"high\"}");

    JsonObject refused =
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"priority\":\"urgent\"}");
    assertTrue(
        "out-of-enum priority is an isError result, not a protocol error",
        refused.get("isError").getAsBoolean());
    String text =
        refused.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertEquals(
        "the refusal names the allowed values in create's exact message shape",
        "priority must be one of [low, medium, high, critical], got 'urgent'",
        text);

    JsonObject stored = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("status untouched after the refusal", "ready", stored.get("status").getAsString());
    assertEquals(
        "priority untouched after the refusal", "high", stored.get("priority").getAsString());
    assertTrue(
        "the refused attempt wrote no history marker: " + historyActions(stored),
        historyActions(stored).stream().noneMatch(a -> a.contains("priority")));

    // create's own path still answers the byte-identical message (one wording per store)
    JsonObject createRefused =
        call("task_create", "{\"project\":\"p\",\"title\":\"b\",\"priority\":\"urgent\"}");
    assertTrue(createRefused.get("isError").getAsBoolean());
    assertEquals(
        "priority must be one of [low, medium, high, critical], got 'urgent'",
        createRefused.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
  }

  /**
   * T-249 (AC2 accepted path): an in-enum priority still updates through the dispatch surface and
   * sticks; a null priority is not a hole either - it follows create's default (medium), so no
   * {@code priority: null} row can be written while the refusal above guards the enum itself.
   */
  @Test
  public void updateAcceptsInEnumPriorityAndNullRestoresTheDefault() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");

    JsonObject updated =
        callOk(
            "task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"priority\":\"critical\"}");
    assertEquals(
        "in-enum priority still succeeds", "critical", updated.get("priority").getAsString());
    assertTrue(
        "the accepted write is recorded in history",
        historyActions(updated).contains("updated:priority"));
    JsonObject stored = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("the new priority persists", "critical", stored.get("priority").getAsString());

    JsonObject cleared =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"priority\":null}");
    assertEquals(
        "null restores create's medium default - never a null priority",
        "medium",
        cleared.get("priority").getAsString());
  }

  /**
   * T-253 (AC1 at the MCP surface): the sibling T-249 left open - task_update's type case was a
   * bare assignment, so {@code type:'banana'} persisted through McpDispatcher (inputSchema enums
   * are declarative there, never enforced). The store refuses it now through the SAME shared gate
   * create uses, in create's byte-identical message (one wording per store), the stored ticket is
   * untouched (status AND type asserted), and the refused attempt writes no history at all.
   */
  @Test
  public void updateRefusesOutOfEnumTypeLeavingTheStoredTicketUntouched() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"type\":\"bug\"}");

    JsonObject refused =
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"type\":\"banana\"}");
    assertTrue(
        "out-of-enum type is an isError result, not a protocol error",
        refused.get("isError").getAsBoolean());
    String text =
        refused.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertEquals(
        "the refusal names the allowed values in create's exact message shape",
        "type must be one of [story, task, bug, spike], got 'banana'",
        text);

    JsonObject stored = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("status untouched after the refusal", "ready", stored.get("status").getAsString());
    assertEquals("type untouched after the refusal", "bug", stored.get("type").getAsString());
    assertTrue(
        "the refused attempt wrote no history marker: " + historyActions(stored),
        historyActions(stored).stream().noneMatch(a -> a.contains("type")));

    // create's own path still answers the byte-identical message (one shared gate, one wording)
    JsonObject createRefused =
        call("task_create", "{\"project\":\"p\",\"title\":\"b\",\"type\":\"banana\"}");
    assertTrue(createRefused.get("isError").getAsBoolean());
    assertEquals(
        "type must be one of [story, task, bug, spike], got 'banana'",
        createRefused.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
  }

  /**
   * T-253 (AC1 accepted path): an in-enum type still updates through the dispatch surface and
   * sticks; a null type is not a hole either - it follows create's default (task), so no {@code
   * type: null} row can be written while the refusal above guards the enum itself.
   */
  @Test
  public void updateAcceptsInEnumTypeAndNullRestoresTheDefault() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");

    JsonObject updated =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"type\":\"spike\"}");
    assertEquals("in-enum type still succeeds", "spike", updated.get("type").getAsString());
    assertTrue(
        "the accepted write is recorded in history",
        historyActions(updated).contains("updated:type"));
    JsonObject stored = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("the new type persists", "spike", stored.get("type").getAsString());

    JsonObject cleared =
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"type\":null}");
    assertEquals(
        "null restores create's task default - never a null type",
        "task",
        cleared.get("type").getAsString());
  }

  @Test
  public void projectIsolation() {
    callOk("task_create", "{\"project\":\"one\",\"title\":\"a\"}");
    callOk("task_create", "{\"project\":\"two\",\"title\":\"b\"}");
    JsonArray one =
        JsonParser.parseString(callText("task_list", "{\"project\":\"one\"}")).getAsJsonArray();
    JsonArray two =
        JsonParser.parseString(callText("task_list", "{\"project\":\"two\"}")).getAsJsonArray();
    assertEquals(1, one.size());
    assertEquals(1, two.size());
    assertEquals("a", one.get(0).getAsJsonObject().get("title").getAsString());
    assertEquals("b", two.get(0).getAsJsonObject().get("title").getAsString());
    // both projects mint their own T-001
    assertEquals(
        one.get(0).getAsJsonObject().get("id").getAsString(),
        two.get(0).getAsJsonObject().get("id").getAsString());
  }

  // U-110: the wandering-verb tests (vPipeline advance/send-back, passStage) retired with the
  // model - the pipeline is the linked stage graph; division creates the downstream tickets.

  // C-M1-1 (acceptance-run design, T-095/T-127): stage: is immutable V-pipeline ownership -
  // task_create sets it at creation (division), task_update refuses any rewrite.
  @Test
  public void updateStageThroughToolsIsRefused() {
    callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"design\"}");
    assertTrue(
        "stage write through task_update -> isError (immutable ownership)",
        call(
                "task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":\"implementation\"}")
            .get("isError")
            .getAsBoolean());
    assertTrue(
        "stage clear through task_update -> isError too",
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":null}")
            .get("isError")
            .getAsBoolean());
    JsonObject t = callOk("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
    assertEquals("the stage survives unchanged", "design", t.get("stage").getAsString());
  }

  /**
   * T-220 (AC-2): the documented UNCLEAR round-trip, end to end through the dispatch path. An
   * in-review staged ticket takes a routed doubt per retry budget (N = {@link
   * TaskStore#REVIEW_DOUBT_RETRY_LIMIT}, read from the source), each round returning it to ready
   * with the claim released and a {@code review doubt retry (n/N)} history marker; once {@code
   * doubtRetriesInVisit} has counted N markers inside the same stage visit, the next doubt
   * escalates to blocked (the AGENTS escalation contract: round-trips exhausted -&gt; NEEDS-HUMAN)
   * and no (N+1)/N marker is ever written.
   */
  @Test
  public void unclearVerdictRoundTripsOncePerStageVisitThenEscalatesThroughTheTool() {
    int limit = TaskStore.REVIEW_DOUBT_RETRY_LIMIT;
    assertEquals("the review contract's budget (marker 'review doubt retry (1/1)')", 1, limit);

    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"staged work\",\"type\":\"task\",\"role\":\"developer\","
            + "\"stage\":\"implementation\"}");
    JsonObject claimed =
        callOk("task_claim", "{\"project\":\"p\",\"role\":\"developer\"," + "\"by\":\"dev-1\"}");
    assertEquals("in-progress", claimed.get("status").getAsString());
    assertEquals("dev-1", claimed.get("assignee").getAsString());
    callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"in-review\"}");

    // rounds 1..N: each doubt round-trips to the originator with its (n/N) marker
    for (int n = 1; n <= limit; n++) {
      final int round = n;
      JsonObject routed =
          callOk(
              "task_route_review_doubt",
              "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":\"cannot determine X round "
                  + round
                  + "\",\"by\":\"reviewer\"}");
      assertEquals("ready", routed.get("status").getAsString());
      assertTrue("the originator's claim is released", routed.get("assignee").isJsonNull());
      assertTrue(
          "history carries 'review doubt retry (" + round + "/" + limit + ")'",
          historyActions(routed)
              .contains(
                  "review doubt retry ("
                      + round
                      + "/"
                      + limit
                      + ") for stage implementation: cannot determine X round "
                      + round));
      assertTrue(
          "the doubt + retry are recorded as a comment",
          commentTexts(routed).stream()
              .anyMatch(s -> s.contains("review doubt retry (" + round + "/" + limit + ")")));
      // the originator resubmits for verdict - the next doubt arrives from in-review
      callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"in-review\"}");
    }

    // past the budget: doubtRetriesInVisit reports N consumed in this stage visit,
    // so routeReviewDoubt escalates instead of retrying (blocked = needs-a-human)
    JsonObject escalated =
        callOk(
            "task_route_review_doubt",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":\"still unclear\","
                + "\"by\":\"reviewer\"}");
    assertEquals("blocked", escalated.get("status").getAsString());
    assertTrue(escalated.get("blocked").getAsBoolean());
    assertTrue(
        "blocker quotes the exhausted budget: " + escalated.get("blocker").getAsString(),
        escalated
            .get("blocker")
            .getAsString()
            .startsWith(
                "review doubt unresolved after " + limit + " originator retries: still unclear"));
    assertTrue(
        "history carries 'review doubt escalated to NEEDS-HUMAN'",
        historyActions(escalated).contains("review doubt escalated to NEEDS-HUMAN: still unclear"));
    assertTrue(
        "the escalation comment names the stage",
        commentTexts(escalated).stream()
            .anyMatch(
                s -> s.contains("review doubt escalated to NEEDS-HUMAN (stage implementation)")));
    assertFalse(
        "exhaustion never writes a marker beyond the budget",
        historyActions(escalated).stream()
            .anyMatch(s -> s.startsWith("review doubt retry (" + (limit + 1))));
  }

  /**
   * T-220 (AC-1): task_route_review_doubt validates like its sibling verbs - an unknown ticket is
   * the standard not-found isError text, a missing reason is JSON-RPC -32602 (required parameter),
   * a blank reason and a stageless ticket are domain isError results from the store.
   */
  @Test
  public void routeReviewDoubtValidationMatchesTheSiblingVerbShapes() {
    callOk(
        "task_create",
        "{\"project\":\"p\",\"title\":\"staged\",\"role\":\"developer\",\"stage\":\"implementation\"}");
    callOk("task_create", "{\"project\":\"p\",\"title\":\"stageless\",\"role\":\"developer\"}");

    // unknown ticket -> the standard not-found shape (store NotFound -> isError text)
    JsonObject notFound =
        call(
            "task_route_review_doubt",
            "{\"project\":\"p\",\"ticket_id\":\"T-404\",\"reason\":\"doubt\"}");
    assertTrue("domain error flagged", notFound.get("isError").getAsBoolean());
    assertTrue(
        "the standard not-found text: " + notFound,
        notFound.toString().contains("T-404 not found"));

    // missing reason -> JSON-RPC -32602, exactly like a missing sibling required parameter
    JsonObject response =
        JsonParser.parseString(
                dispatcher.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_route_review_doubt\",\"arguments\":"
                        + "{\"project\":\"p\",\"ticket_id\":\"T-001\"}}}"))
            .getAsJsonObject();
    assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
    assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("reason"));

    // blank reason -> domain error RESULT (not a protocol error), named by the store
    JsonObject blank =
        call(
            "task_route_review_doubt",
            "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":\"  \"}");
    assertTrue(blank.get("isError").getAsBoolean());
    assertTrue(
        "the message names the problem: " + blank,
        blank.toString().contains("reason must be a non-empty string"));

    // stageless ticket -> domain error RESULT naming the missing stage
    JsonObject stageless =
        call(
            "task_route_review_doubt",
            "{\"project\":\"p\",\"ticket_id\":\"T-002\",\"reason\":\"doubt\"}");
    assertTrue(stageless.get("isError").getAsBoolean());
    assertTrue(
        "the message names the missing stage: " + stageless,
        stageless.toString().contains("no stage"));
  }
}
