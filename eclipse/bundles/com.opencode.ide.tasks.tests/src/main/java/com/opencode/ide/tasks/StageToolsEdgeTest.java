package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import org.junit.Test;

/**
 * Edge cases of the stage-bearing tools over the JSON-RPC dispatcher (same
 * harness as TaskToolsDispatchTest): NotFound/Invalid domain errors arrive as
 * isError text results, bogus stages are rejected on create and update,
 * an explicit null clears the stage, and a happy-path task_advance produces
 * exactly the store effects of the direct TaskStore call.
 */
public class StageToolsEdgeTest extends ToolRpcHarness {

    private JsonObject callOk(String tool, String argsJson) {
        JsonObject result = call(tool, argsJson);
        assertFalse(result.get("isError").getAsBoolean());
        return JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
    }

    private String callErrorText(String tool, String argsJson) {
        JsonObject result = call(tool, argsJson);
        assertTrue("expected an isError result", result.get("isError").getAsBoolean());
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    public void advanceMissingTicketIsANotFoundErrorResult() {
        String text = callErrorText("task_advance", "{\"project\":\"p\",\"ticket_id\":\"T-404\"}");
        assertTrue("the NotFound channel names the missing ticket", text.contains("T-404 not found"));
    }

    @Test
    public void sendBackWithBlankReasonIsAnInvalidErrorResult() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"system\"}");
        for (String blank : new String[] {"\"\"", "\"   \""}) {
            String text = callErrorText("task_send_back",
                    "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"reason\":" + blank + "}");
            assertTrue(text.contains("reason"));
        }
        assertEquals("a rejected send back changes nothing", "system",
                new TaskStore(root).get("p", "T-001").stage);
    }

    @Test
    public void createWithBogusStageIsAnInvalidErrorResult() {
        String text = callErrorText("task_create",
                "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"waterfall\"}");
        assertTrue(text.contains("stage"));
        assertTrue("nothing was written", new TaskStore(root).list("p", null, null, null, null).isEmpty());
    }

    @Test
    public void updateWithBogusStageIsAnInvalidErrorResult() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"design\"}");
        String text = callErrorText("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":\"nope\"}");
        assertTrue(text.contains("stage"));
        assertEquals("a rejected update wrote nothing", "design",
                new TaskStore(root).get("p", "T-001").stage);
    }

    @Test
    public void updateWithNullStageClearsItThroughTools() {
        callOk("task_create", "{\"project\":\"p\",\"title\":\"a\",\"stage\":\"design\"}");
        JsonObject cleared = callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"T-001\",\"stage\":null}");
        assertTrue("explicit null clears the stage", cleared.get("stage").isJsonNull());
        assertNull(new TaskStore(root).get("p", "T-001").stage);
    }

    @Test
    public void advanceThroughToolsProducesTheSameStoreEffectsAsTheDirectCall() {
        JsonObject created = callOk("task_create",
                "{\"project\":\"p\",\"title\":\"tool walker\",\"stage\":\"requirements\"}");
        String toolId = created.get("id").getAsString();
        callOk("task_update",
                "{\"project\":\"p\",\"ticket_id\":\"" + toolId + "\",\"status\":\"in-review\",\"assignee\":\"w\"}");
        JsonObject advanced = callOk("task_advance",
                "{\"project\":\"p\",\"ticket_id\":\"" + toolId + "\",\"by\":\"pm\"}");
        assertEquals("system", advanced.get("stage").getAsString());
        assertEquals("architect", advanced.get("role").getAsString());
        assertEquals("product-backlog", advanced.get("status").getAsString());
        assertTrue(advanced.get("assignee").isJsonNull());

        TaskStore direct = new TaskStore(root);
        String twinId = direct.create("p", TaskStore.CreateSpec.of("direct walker"), "requirements").id;
        direct.update("p", twinId, Map.of("status", "in-review", "assignee", "w"));
        Task twin = direct.advance("p", twinId, "pm");
        Task toolTicket = direct.get("p", toolId);

        assertEquals(twin.stage, toolTicket.stage);
        assertEquals(twin.role, toolTicket.role);
        assertEquals(twin.status, toolTicket.status);
        assertNull(toolTicket.assignee);
        Task.HistoryEvent toolLast = toolTicket.history.get(toolTicket.history.size() - 1);
        Task.HistoryEvent twinLast = twin.history.get(twin.history.size() - 1);
        assertEquals(twinLast.action(), toolLast.action());
        assertEquals("advanced to system", toolLast.action());
        assertEquals("pm", toolLast.by());
        assertEquals("and the tool-written file carries the stage",
                "system", new TaskStore(root).get("p", toolId).stage);
    }
}
