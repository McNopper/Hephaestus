package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.tools.McpDispatcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * MCP-surface checks over the dispatcher: the 22 task_* tools are advertised
 * with input schemas, results are pretty JSON with the pm field names, a
 * claim with nothing to do returns the JSON literal null, domain errors are
 * isError text results (not protocol errors) and missing parameters map to
 * JSON-RPC -32602.
 */
public class TaskToolsDispatchTest {

    private static final List<String> EXPECTED_TOOLS = List.of(
            "task_create", "task_get", "task_list", "task_update", "task_advance", "task_send_back",
            "task_set_blocked", "task_clear_blocked", "task_claim", "task_release", "task_add_comment",
            "task_add_artifact", "task_add_todo", "task_toggle_todo", "task_remove_todo",
            "task_backlog", "task_board", "task_plan_sprint", "task_close_sprint",
            "task_traceability", "task_readiness", "task_doctor");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private McpDispatcher dispatcher;

    @Before
    public void setUp() {
        dispatcher = new McpDispatcher(
                new TaskToolProvider(tmp.getRoot().toPath().resolve("tasks")));
    }

    private JsonObject call(String tool, String argsJson) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + argsJson + "}}";
        JsonObject response = JsonParser.parseString(dispatcher.handle(body)).getAsJsonObject();
        assertFalse("domain errors must be isError results, not protocol errors", response.has("error"));
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(1, result.getAsJsonArray("content").size());
        assertEquals("text", result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        return result;
    }

    private JsonObject callOk(String tool, String argsJson) {
        JsonObject result = call(tool, argsJson);
        assertFalse(result.get("isError").getAsBoolean());
        return JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    }

    private String callText(String tool, String argsJson) {
        return call(tool, argsJson).getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    public void toolsListAdvertisesAllTwentyTwoToolsWithSchemas() {
        JsonObject response = JsonParser.parseString(
                dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).getAsJsonObject();
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(22, tools.size());
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            assertEquals(EXPECTED_TOOLS.get(i), tool.get("name").getAsString());
            assertTrue(tool.get("description").getAsString().length() > 10);
            assertTrue("inputSchema must be an object", tool.get("inputSchema").isJsonObject());
        }
        // spot-check required arrays on two tools
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).getAsJsonObject().get("name").getAsString().equals("task_create")) {
                JsonArray req = tools.get(i).getAsJsonObject().getAsJsonObject("inputSchema")
                        .getAsJsonArray("required");
                assertEquals("[\"project\",\"title\"]", req.toString());
            }
        }
    }

    @Test
    public void createThenGetRoundTripsThroughJsonRpc() {
        JsonObject created = callOk("task_create",
                "{\"project\":\"p\",\"title\":\"Write the thing\",\"type\":\"task\",\"role\":\"developer\","
                        + "\"priority\":\"high\",\"story_points\":3,"
                        + "\"acceptance_criteria\":[\"GIVEN a\",\"WHEN b\"]}");
        assertEquals("T-001", created.get("id").getAsString());
        assertEquals("product-backlog", created.get("status").getAsString());
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
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"role\":\"developer\",\"priority\":\"high\"}");
        callOk("task_create", "{\"project\":\"p\",\"title\":\"b\",\"role\":\"developer\",\"priority\":\"low\"}");
        callOk("task_plan_sprint", "{\"project\":\"p\",\"ticket_ids\":[\"T-001\",\"T-002\"],\"goal\":\"g\"}");
        JsonObject claimed = callOk("task_claim", "{\"project\":\"p\",\"role\":\"developer\",\"by\":\"agent-1\"}");
        assertEquals("T-001", claimed.get("id").getAsString()); // high before low
        assertEquals("in-progress", claimed.get("status").getAsString());
        assertEquals("agent-1", claimed.get("assignee").getAsString());
        JsonObject board = callOk("task_board", "{\"project\":\"p\"}");
        assertEquals(5, board.size());
        assertEquals(1, board.getAsJsonArray("in-progress").size());
        assertEquals(1, board.getAsJsonArray("sprint-backlog").size());
    }

    @Test
    public void notFoundAndInvalidAreErrorTextResults() {
        JsonObject result = call("task_get", "{\"project\":\"p\",\"ticket_id\":\"T-404\"}");
        assertTrue(result.get("isError").getAsBoolean());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("T-404 not found"));

        JsonObject badKind = call("task_add_artifact",
                "{\"project\":\"p\",\"ticket_id\":\"x\",\"kind\":\"carrier\",\"ref\":\"r\"}");
        assertTrue(badKind.get("isError").getAsBoolean());
    }

    @Test
    public void missingParameterIsJsonRpc32602() {
        JsonObject response = JsonParser.parseString(dispatcher.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_create\",\"arguments\":{\"project\":\"p\"}}}"))
                .getAsJsonObject();
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("title"));
    }

    @Test
    public void todoOpsThroughTools() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
        JsonObject t = callOk("task_add_todo", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"text\":\"step 1\"}");
        assertEquals(1, t.getAsJsonArray("todos").size());
        t = callOk("task_toggle_todo", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"index\":0}");
        assertTrue(t.getAsJsonArray("todos").get(0).getAsJsonObject().get("done").getAsBoolean());
        t = callOk("task_remove_todo", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"index\":0}");
        assertEquals(0, t.getAsJsonArray("todos").size());
    }

    @Test
    public void closeSprintReturnsListThroughTools() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"unfinished\"}");
        callOk("task_plan_sprint", "{\"project\":\"p\",\"ticket_ids\":[\"T-001\"]}");
        JsonObject out = callOk("task_close_sprint", "{\"project\":\"p\",\"sprint_id\":\"S-01\"}");
        assertEquals("closed", out.getAsJsonObject("sprint").get("status").getAsString());
        assertEquals(1, out.getAsJsonArray("returned_to_backlog").size());
    }

    @Test
    public void doctorReportsDriftAndGoesQuietWhenHealed() {
        // clean store: ok with an empty report
        JsonObject clean = callOk("task_doctor", "{\"project\":\"p\"}");
        assertTrue(clean.get("ok").getAsBoolean());
        assertEquals(0, clean.getAsJsonArray("inconsistencies").size());

        // sprint set while sitting in product-backlog (hand-drifted state)
        callOk("task_create", "{\"project\":\"p\",\"title\":\"drifter\"}");
        callOk("task_plan_sprint", "{\"project\":\"p\",\"ticket_ids\":[\"T-001\"],\"goal\":\"g\"}");
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"product-backlog\"}");
        JsonObject drift = callOk("task_doctor", "{\"project\":\"p\"}");
        assertFalse(drift.get("ok").getAsBoolean());
        assertEquals(1, drift.getAsJsonArray("inconsistencies").size());
        assertTrue(drift.getAsJsonArray("inconsistencies").get(0).getAsString()
                .contains("status=product-backlog but sprint="));

        // healed (sprint cleared) -> quiet again
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"sprint\":null}");
        JsonObject healed = callOk("task_doctor", "{\"project\":\"p\"}");
        assertTrue(healed.get("ok").getAsBoolean());
    }

    @Test
    public void updateThroughToolsKeepsFieldOrderInHistory() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
        JsonObject updated = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"title\":\"b\",\"story_points\":5}");
        assertEquals("b", updated.get("title").getAsString());
        assertEquals(5, updated.get("story_points").getAsInt());
        String last = updated.getAsJsonArray("history").get(1).getAsJsonObject().get("action").getAsString();
        assertEquals("updated:title,story_points", last);
    }

    @Test
    public void updateSupportsSprintAndExplicitNullsParity() {
        // pm parity: update could set sprint/epic/assignee to a value AND clear them with null
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
        JsonObject set = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"sprint\":\"S-09\",\"epic\":\"T-002\",\"assignee\":\"x\"}");
        assertEquals("S-09", set.get("sprint").getAsString());
        assertEquals("T-002", set.get("epic").getAsString());
        assertEquals("x", set.get("assignee").getAsString());
        JsonObject cleared = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"sprint\":null,\"epic\":null,\"assignee\":null}");
        assertTrue("explicit null clears the sprint", cleared.get("sprint").isJsonNull());
        assertTrue(cleared.get("epic").isJsonNull());
        assertTrue(cleared.get("assignee").isJsonNull());
        // story_points null is a no-op (cannot null an int)
        JsonObject points = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":7}");
        points = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":null}");
        assertEquals(7, points.get("story_points").getAsInt());
    }

    @Test
    public void floatStringStoryPointsIsCleanError() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
        JsonObject result = call("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"story_points\":\"2.5\"}");
        assertTrue("non-integer story_points is an isError result, not a protocol error",
                result.get("isError").getAsBoolean());
    }

    @Test
    public void projectIsolation() {
        callOk("task_create", "{\"project\":\"one\",\"title\":\"a\"}");
        callOk("task_create", "{\"project\":\"two\",\"title\":\"b\"}");
        JsonArray one = JsonParser.parseString(callText("task_list", "{\"project\":\"one\"}")).getAsJsonArray();
        JsonArray two = JsonParser.parseString(callText("task_list", "{\"project\":\"two\"}")).getAsJsonArray();
        assertEquals(1, one.size());
        assertEquals(1, two.size());
        assertEquals("a", one.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("b", two.get(0).getAsJsonObject().get("title").getAsString());
        // both projects mint their own T-001
        assertEquals(one.get(0).getAsJsonObject().get("id").getAsString(),
                two.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void vPipelineAdvanceAndSendBackThroughTools() {
        JsonObject created = callOk("task_create",
                "{\"project\":\"p\",\"title\":\"spec it\",\"stage\":\"requirements\"}");
        assertEquals("requirements", created.get("stage").getAsString());
        callOk("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"status\":\"in-review\"}");
        JsonObject advanced = callOk("task_advance",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"by\":\"pm\"}");
        assertEquals("system", advanced.get("stage").getAsString());
        assertEquals("architect", advanced.get("role").getAsString());
        assertEquals("product-backlog", advanced.get("status").getAsString());
        assertTrue("advance clears the assignee", advanced.get("assignee").isJsonNull());

        JsonObject sentBack = callOk("task_send_back",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":\"goals contradict\",\"by\":\"architect\"}");
        assertEquals("requirements", sentBack.get("stage").getAsString());
        assertEquals("pm", sentBack.get("role").getAsString());
        assertEquals("product-backlog", sentBack.get("status").getAsString());
        assertTrue("send back raises the blocked flag", sentBack.get("blocked").getAsBoolean());
        assertEquals("sent back from system: goals contradict", sentBack.get("blocker").getAsString());
        JsonObject cleared = callOk("task_clear_blocked", "{\"project\":\"p\",\"ticket_id\":\"T-001\"}");
        assertFalse(cleared.get("blocked").getAsBoolean());

        // error channels: unstaged legacy ticket, invalid stage on create, blank reason
        callOk("task_create", "{\"project\":\"p\",\"title\":\"legacy\"}");
        assertTrue("legacy ticket has no stage -> isError",
                call("task_advance", "{\"project\":\"p\",\"ticket_id\":\"T-002\"}").get("isError").getAsBoolean());
        assertTrue("invalid stage on create -> isError",
                call("task_create", "{\"project\":\"p\",\"title\":\"x\",\"stage\":\"waterfall\"}")
                        .get("isError").getAsBoolean());
        assertTrue("blank reason -> isError",
                call("task_send_back", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":\"\"}")
                        .get("isError").getAsBoolean());
    }

    @Test
    public void updateStageThroughToolsSetsAndClears() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\"}");
        JsonObject set = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":\"design\"}");
        assertEquals("design", set.get("stage").getAsString());
        JsonObject cleared = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":null}");
        assertTrue("explicit null clears the stage", cleared.get("stage").isJsonNull());
        assertTrue("invalid stage string -> isError",
                call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":\"nope\"}")
                        .get("isError").getAsBoolean());
    }
}
