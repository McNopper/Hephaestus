package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


import org.junit.Test;

/**
 * The task_readiness tool (H6 agent-facing surface) over the JSON-RPC
 * dispatcher, same harness as TaskToolsDispatchTest: one compact
 * {id, stage, kind, reason} row per ticket, ordered by severity (STALE,
 * BLOCKED, WAIT_UPSTREAM, RUNNING, READY, NOT_APPLICABLE) then id, no-stage
 * tickets included as NOT_APPLICABLE, kinds/reasons identical to
 * StageReadiness over the same store, and malformed input mapping to
 * JSON-RPC -32602 per pack convention.
 */
public class TaskReadinessToolTest extends ToolRpcHarness {

    private JsonArray callArray(String tool, String argsJson) {
        JsonObject result = call(tool, argsJson);
        assertFalse(result.get("isError").getAsBoolean());
        return JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonArray();
    }

    private static void tick() throws InterruptedException {
        Thread.sleep(20); // distinct millisecond timestamps: STALE compares strictly-after
    }

    /**
     * T-001..T-007 covering every kind: T-001 READY (requirements in a
     * sprint), T-002 WAIT_UPSTREAM (system, nothing upstream), T-003
     * NOT_APPLICABLE (no stage), T-004 STALE (design ran before, epic chain
     * to T-006 which changed after), T-005 BLOCKED, T-006 the done
     * architecture anchor (itself WAIT_UPSTREAM), T-007 RUNNING.
     */
    private void createEveryKind() throws InterruptedException {
        call("task_create", "{\"project\":\"p\",\"title\":\"ready\",\"stage\":\"requirements\"}");
        call("task_plan_sprint", "{\"project\":\"p\",\"ticket_ids\":[\"T-001\"],\"goal\":\"g\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"waiting\",\"stage\":\"system\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"legacy\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"stale child\",\"stage\":\"design\"}");
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-004\",\"epic\":\"T-006\",\"title\":\"stale child v2\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"blocked\",\"stage\":\"design\"}");
        call("task_set_blocked", "{\"project\":\"p\",\"ticket_id\":\"T-005\",\"blocker\":\"waiting on legal\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"anchor\",\"stage\":\"architecture\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"runner\",\"stage\":\"design\"}");
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-007\",\"status\":\"in-progress\"}");
        tick();
        call("task_update", "{\"project\":\"p\",\"ticket_id\":\"T-006\",\"status\":\"done\"}");
    }

    private static List<String> ids(JsonArray rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).getAsJsonObject().get("id").getAsString());
        }
        return out;
    }

    @Test
    public void everyKindSortedBySeverityThenId() throws Exception {
        createEveryKind();
        JsonArray rows = callArray("task_readiness", "{\"project\":\"p\"}");
        assertEquals(7, rows.size());
        assertEquals("STALE before BLOCKED before WAIT_UPSTREAM (by id) before RUNNING before READY before NOT_APPLICABLE",
                List.of("T-004", "T-005", "T-002", "T-006", "T-007", "T-001", "T-003"), ids(rows));
    }

    @Test
    public void rowsAreCompactIdStageKindReason() throws Exception {
        createEveryKind();
        JsonArray rows = callArray("task_readiness", "{\"project\":\"p\"}");
        JsonObject stale = rows.get(0).getAsJsonObject();
        assertEquals("exactly the four compact fields", 4, stale.keySet().size());
        assertEquals("T-004", stale.get("id").getAsString());
        assertEquals("design", stale.get("stage").getAsString());
        assertEquals("STALE", stale.get("kind").getAsString());
        assertTrue("the reason names the changed upstream", stale.get("reason").getAsString().contains("T-006"));
    }

    @Test
    public void kindsAndReasonsMatchStageReadinessOverTheSameStore() throws Exception {
        createEveryKind();
        Map<String, StageReadiness.Readiness> verdicts =
                StageReadiness.evaluate(new TaskStore(root).list("p", null, null, null, null));
        JsonArray rows = callArray("task_readiness", "{\"project\":\"p\"}");
        assertEquals(verdicts.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            StageReadiness.Readiness verdict = verdicts.get(row.get("id").getAsString());
            assertEquals(verdict.kind().name(), row.get("kind").getAsString());
            assertEquals(verdict.reason(), row.get("reason").getAsString());
        }
    }

    @Test
    public void noStageTicketIsIncludedAsNotApplicableWithNullStage() throws Exception {
        call("task_create", "{\"project\":\"p\",\"title\":\"legacy\"}");
        JsonArray rows = callArray("task_readiness", "{\"project\":\"p\"}");
        assertEquals(1, rows.size());
        JsonObject row = rows.get(0).getAsJsonObject();
        assertEquals("T-001", row.get("id").getAsString());
        assertEquals("NOT_APPLICABLE", row.get("kind").getAsString());
        assertTrue("stage serializes as an explicit null", row.get("stage").isJsonNull());
        assertEquals("ticket has no V stage", row.get("reason").getAsString());
    }

    @Test
    public void sameKindRowsAreOrderedById() throws Exception {
        call("task_create", "{\"project\":\"p\",\"title\":\"b first\",\"stage\":\"requirements\"}");
        call("task_create", "{\"project\":\"p\",\"title\":\"a second\",\"stage\":\"requirements\"}");
        JsonArray rows = callArray("task_readiness", "{\"project\":\"p\"}");
        assertEquals(List.of("T-001", "T-002"), ids(rows));
        assertEquals("READY", rows.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("READY", rows.get(1).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    public void emptyProjectReturnsAnEmptyArray() {
        assertEquals(0, callArray("task_readiness", "{\"project\":\"empty\"}").size());
    }

    @Test
    public void missingProjectParameterIsJsonRpc32602() {
        JsonObject response = JsonParser.parseString(dispatcher.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_readiness\",\"arguments\":{}}}"))
                .getAsJsonObject();
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("project"));
    }

    @Test
    public void nonPrimitiveProjectParameterIsJsonRpc32602() {
        JsonObject response = JsonParser.parseString(dispatcher.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_readiness\",\"arguments\":{\"project\":{\"deep\":true}}}}"))
                .getAsJsonObject();
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("project"));
    }

    @Test
    public void toolIsAdvertisedWithAProjectOnlySchema() {
        JsonArray tools = JsonParser.parseString(
                dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
        JsonObject readiness = null;
        for (int i = 0; i < tools.size(); i++) {
            if ("task_readiness".equals(tools.get(i).getAsJsonObject().get("name").getAsString())) {
                readiness = tools.get(i).getAsJsonObject();
            }
        }
        assertTrue("task_readiness must be advertised", readiness != null);
        assertTrue(readiness.get("description").getAsString().length() > 10);
        assertEquals("[\"project\"]",
                readiness.getAsJsonObject("inputSchema").getAsJsonArray("required").toString());
    }
}
