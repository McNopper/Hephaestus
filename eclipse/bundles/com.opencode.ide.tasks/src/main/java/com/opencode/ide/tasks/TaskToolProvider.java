package com.opencode.ide.tasks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import com.opencode.ide.tools.McpTool;
import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.ParamError;
import com.opencode.ide.tools.ToolProvider;

/**
 * The task-board pack of the {@code eclipse-build} MCP server: the
 * {@code task_*} tools over the {@link TaskStore} (one Markdown file per task
 * under {@code <root>/<project>/}). Tool-per-tool these replace the retired
 * Python {@code pm} MCP server ({@code pm_*} tools) with identical semantics -
 * claim ordering and null-when-empty, lax update rules, sprint force-set on
 * plan, close-sprint returns, traceability pairing - so worker agents and the
 * {@code pm-*} skills keep working after a pure rename. On top of that pack,
 * {@code task_advance}/{@code task_send_back} drive the V-model pipeline
 * (stage hand-forward with the in-review/done quality gate, and the blocked
 * hand-back loop; see {@link VStages}), and {@code task_readiness} exposes
 * the {@link StageReadiness} dispatch verdicts so the PM agent can ask
 * what's runnable right now.
 *
 * <p>Parameter names keep the historical {@code ticket_id}/{@code project}
 * spellings for compatibility with existing agent prose ("task" and "ticket"
 * name the same thing; the store calls them task files).</p>
 *
 * <p>Error channel: missing/ill-typed parameters raise {@link ParamError}
 * (JSON-RPC -32602); domain errors (not found, invalid value, bad state)
 * return {@link McpToolResult#error(String)} text results. A claim that finds
 * nothing returns the JSON literal {@code null} - worker loops terminate on
 * it, exactly as with the pm server.</p>
 */
public final class TaskToolProvider implements ToolProvider {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final List<McpTool> TOOLS = buildTools();

    private final Path root;

    /**
     * @param root the store root; each {@code project} argument addresses a
     *             subdirectory. Use {@code .opencode/tasks} of the repository
     *             the agents work in (the stdio launcher defaults to that
     *             relative to its working directory).
     */
    public TaskToolProvider(Path root) {
        this.root = root;
    }

    @Override
    public String language() {
        return "tasks";
    }

    @Override
    public List<McpTool> tools() {
        return TOOLS;
    }

    @Override
    public McpToolResult call(String toolName, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        try {
            return dispatch(toolName, args);
        } catch (TaskStore.NotFound | TaskStore.Invalid e) {
            return McpToolResult.error(e.getMessage());
        } catch (TaskStore.UncheckedIo e) {
            return McpToolResult.error("task store IO error: " + e.getMessage());
        }
    }

    private McpToolResult dispatch(String name, JsonObject a) {
        TaskStore store = new TaskStore(root);
        switch (name) {
            case "task_create":
            {
                TaskStore.CreateSpec spec = new TaskStore.CreateSpec(
                        reqStr(a, "title"),
                        optStr(a, "description", ""),
                        orDefault(optStr(a, "type", null), "task"),
                        orDefault(optStr(a, "role", null), "developer"),
                        orDefault(optStr(a, "priority", null), "medium"),
                        a.has("story_points") ? reqInt(a, "story_points") : 0,
                        strList(a, "acceptance_criteria"),
                        strList(a, "labels"),
                        optStr(a, "epic", null),
                        orDefault(optStr(a, "id_prefix", null), "T"));
                return json(store.create(reqStr(a, "project"), spec, optStr(a, "stage", null)).toJson());
            }
            case "task_get":
                return json(store.get(reqStr(a, "project"), reqStr(a, "ticket_id")).toJson());
            case "task_list":
            {
                JsonArray arr = new JsonArray();
                store.list(reqStr(a, "project"),
                        optStr(a, "role", null),
                        optStr(a, "status", null),
                        optStr(a, "sprint", null),
                        a.has("blocked") && !a.get("blocked").isJsonNull() ? reqBool(a, "blocked") : null)
                        .forEach(t -> arr.add(t.toJson()));
                return json(arr);
            }
            case "task_update":
            {
                Map<String, Object> changes = new LinkedHashMap<>();
                for (String field : List.of("title", "description", "type", "status", "story_points",
                        "priority", "role", "stage", "assignee", "acceptance_criteria", "labels",
                        "epic", "sprint")) {
                    JsonElement v = a.get(field);
                    if (v == null) {
                        continue;
                    }
                    if (v.isJsonNull() && "story_points".equals(field)) {
                        continue; // cannot null an int; leave unchanged (pm parity)
                    }
                    changes.put(field, v); // explicit nulls pass through and clear the field
                }
                return json(store.update(reqStr(a, "project"), reqStr(a, "ticket_id"), changes).toJson());
            }
            case "task_advance":
                return json(store.advance(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        optStr(a, "by", null)).toJson());
            case "task_send_back":
                return json(store.sendBack(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqStr(a, "reason"), optStr(a, "by", null)).toJson());
            case "task_set_blocked":
                return json(store.setBlocked(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqStr(a, "blocker"), optStr(a, "by", null)).toJson());
            case "task_clear_blocked":
                return json(store.clearBlocked(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        optStr(a, "by", null)).toJson());
            case "task_claim":
            {
                Task claimed = store.claim(reqStr(a, "project"), reqStr(a, "role"),
                        orDefault(optStr(a, "status", null), "sprint-backlog"), optStr(a, "by", null));
                return json(claimed == null ? JsonNull.INSTANCE : claimed.toJson());
            }
            case "task_release":
                return json(store.release(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        optStr(a, "by", null)).toJson());
            case "task_add_comment":
                return json(store.addComment(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqStr(a, "comment"), optStr(a, "by", null)).toJson());
            case "task_add_artifact":
                return json(store.addArtifact(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqStr(a, "kind"), reqStr(a, "ref"), optStr(a, "note", ""),
                        optStr(a, "by", null)).toJson());
            case "task_add_todo":
                return json(store.addTodo(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqStr(a, "text"), optBool(a, "done", false),
                        optStr(a, "by", null)).toJson());
            case "task_toggle_todo":
                return json(store.toggleTodo(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqInt(a, "index"), optStr(a, "by", null)).toJson());
            case "task_remove_todo":
                return json(store.removeTodo(reqStr(a, "project"), reqStr(a, "ticket_id"),
                        reqInt(a, "index"), optStr(a, "by", null)).toJson());
            case "task_backlog":
            {
                JsonArray arr = new JsonArray();
                store.backlog(reqStr(a, "project")).forEach(t -> arr.add(t.toJson()));
                return json(arr);
            }
            case "task_board":
            {
                JsonObject o = new JsonObject();
                store.board(reqStr(a, "project"), optStr(a, "sprint", null))
                        .forEach((status, tasks) -> {
                            JsonArray arr = new JsonArray();
                            tasks.forEach(t -> arr.add(t.toJson()));
                            o.add(status, arr);
                        });
                return json(o);
            }
            case "task_plan_sprint":
            {
                List<String> ids = strList(a, "ticket_ids");
                return json(store.planSprint(reqStr(a, "project"), optStr(a, "sprint_id", null),
                        ids, optStr(a, "goal", "")).toJson());
            }
            case "task_close_sprint":
            {
                Map<String, Object> out = store.closeSprint(reqStr(a, "project"), reqStr(a, "sprint_id"));
                JsonObject o = new JsonObject();
                o.add("sprint", ((Task.Sprint) out.get("sprint")).toJson());
                JsonArray arr = new JsonArray();
                ((List<String>) out.get("returned_to_backlog")).forEach(arr::add);
                o.add("returned_to_backlog", arr);
                return json(o);
            }
            case "task_traceability":
            {
                Map<String, Object> out = store.traceability(reqStr(a, "project"));
                return json(PRETTY.toJsonTree(out));
            }
            case "task_readiness":
            {
                JsonArray arr = new JsonArray();
                for (Map<String, Object> row : store.readiness(reqStr(a, "project"))) {
                    arr.add(PRETTY.toJsonTree(row));
                }
                return json(arr);
            }
            case "task_doctor":
            {
                JsonObject o = new JsonObject();
                JsonArray arr = new JsonArray();
                store.inconsistencies(reqStr(a, "project")).forEach(arr::add);
                o.add("inconsistencies", arr);
                o.addProperty("ok", arr.isEmpty());
                return json(o);
            }
            default:
                return McpToolResult.error("unknown tool '" + name + "'");
        }
    }

    private static McpToolResult json(JsonElement payload) {
        return new McpToolResult(PRETTY.toJson(payload), false);
    }

    // -- parameter extraction -------------------------------------------------

    private static String reqStr(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e == null || e.isJsonNull()) {
            throw new ParamError("missing required string parameter '" + key + "'");
        }
        if (!e.isJsonPrimitive()) {
            throw new ParamError("parameter '" + key + "' must be a string");
        }
        return e.getAsString();
    }

    private static String optStr(JsonObject a, String key, String dflt) {
        JsonElement e = a.get(key);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (!e.isJsonPrimitive()) {
            throw new ParamError("parameter '" + key + "' must be a string");
        }
        return e.getAsString();
    }

    private static boolean optBool(JsonObject a, String key, boolean dflt) {
        JsonElement e = a.get(key);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (!e.isJsonPrimitive()) {
            throw new ParamError("parameter '" + key + "' must be a boolean");
        }
        return e.getAsBoolean();
    }

    private static int reqInt(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            throw new ParamError("parameter '" + key + "' must be an integer");
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException ex) {
            throw new ParamError("parameter '" + key + "' must be an integer");
        }
    }

    private static boolean reqBool(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new ParamError("parameter '" + key + "' must be a boolean");
        }
        return e.getAsBoolean();
    }

    private static List<String> strList(JsonObject a, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = a.get(key);
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (!e.isJsonArray()) {
            throw new ParamError("parameter '" + key + "' must be an array of strings");
        }
        for (JsonElement item : e.getAsJsonArray()) {
            if (!item.isJsonPrimitive()) {
                throw new ParamError("parameter '" + key + "' must be an array of strings"
                        + " (null and nested items are rejected)");
            }
            out.add(item.getAsString());
        }
        return out;
    }

    private static String orDefault(String v, String dflt) {
        return v == null ? dflt : v;
    }

    // -- schemas ---------------------------------------------------------------

    private static List<McpTool> buildTools() {
        List<McpTool> out = new ArrayList<>();
        String roleDesc = "Discipline that owns/claims the task. Known roles: architect, developer, "
                + "tester, pm, cpp-engineer, graphics-engineer (extensible - any non-empty string accepted).";

        out.add(new McpTool("task_create",
                "Create a task (ticket) in a project (status=product-backlog).",
                schema(new String[]{"project", "title"}, obj -> {
                    obj.add("project", strP());
                    obj.add("title", strP());
                    obj.add("description", strP());
                    obj.add("type", enumP(Task.VALID_TYPES));
                    obj.add("role", strP(roleDesc));
                    obj.add("stage", stageP());
                    obj.add("priority", enumP(List.of("low", "medium", "high", "critical")));
                    obj.add("story_points", intP());
                    obj.add("acceptance_criteria", arrP());
                    obj.add("labels", arrP());
                    obj.add("epic", strP());
                    obj.add("id_prefix", strP());
                })));
        out.add(new McpTool("task_get", "Get one task by id.", schema(new String[]{"project", "ticket_id"}, obj -> {
            obj.add("project", strP());
            obj.add("ticket_id", strP());
        })));
        out.add(new McpTool("task_list", "List tasks, optionally filtered by role/status/sprint/blocked.",
                schema(new String[]{"project"}, obj -> {
                    obj.add("project", strP());
                    obj.add("role", strP(roleDesc));
                    obj.add("status", enumP(Task.VALID_STATUSES));
                    obj.add("sprint", strP());
                    obj.add("blocked", boolP());
                })));
        out.add(new McpTool("task_update", "Update mutable fields of a task (id/created_at/history/comments are protected).",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("title", strP());
                    obj.add("description", strP());
                    obj.add("type", enumP(Task.VALID_TYPES));
                    obj.add("status", enumP(Task.VALID_STATUSES));
                    obj.add("story_points", intP());
                    obj.add("priority", enumP(List.of("low", "medium", "high", "critical")));
                    obj.add("role", strP(roleDesc));
                    obj.add("stage", stageP());
                    obj.add("assignee", strP());
                    obj.add("acceptance_criteria", arrP());
                    obj.add("labels", arrP());
                    obj.add("epic", strP());
                    obj.add("sprint", strP());
                })));
        out.add(new McpTool("task_advance",
                "Advance a ticket to the next V-model pipeline stage. Quality gate: the stage's work must be "
                        + "finished (status in-review or done). Effect: stage and role move to the next stage's, "
                        + "status resets to product-backlog (the next stage's backlog is fed by the previous "
                        + "stage), the assignee is cleared, the blocked flag stays as-is.",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_send_back",
                "Send a ticket back to the previous V-model pipeline stage with a reason (the feedback loop). "
                        + "Effect: stage and role move to the previous stage's, status resets to product-backlog, "
                        + "the assignee is cleared and the blocked flag is raised with blocker "
                        + "'sent back from <old stage>: <reason>' (clear via task_clear_blocked once resolved).",
                schema(new String[]{"project", "ticket_id", "reason"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("reason", strP("Why the ticket goes back; becomes part of the blocker text."));
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_set_blocked", "Mark a task blocked with a reason (orthogonal flag).",
                schema(new String[]{"project", "ticket_id", "blocker"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("blocker", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_clear_blocked", "Clear the blocked flag on a task.",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_claim",
                "Atomically claim the next matching task (-> in-progress). Different agents get different tasks. Returns null when nothing is claimable.",
                schema(new String[]{"project", "role"}, obj -> {
                    obj.add("project", strP());
                    obj.add("role", strP(roleDesc));
                    JsonObject status = enumP(List.of("sprint-backlog"));
                    status.addProperty("description", "Only sprint-backlog tickets are claimable.");
                    obj.add("status", status);
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_release", "Release a task back to sprint-backlog (so another agent can pick it up).",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_add_comment", "Append a comment to a task.",
                schema(new String[]{"project", "ticket_id", "comment"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("comment", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_add_artifact",
                "Record where a produced artifact lives (file/git/path/url/doc) so the next agent can find it.",
                schema(new String[]{"project", "ticket_id", "kind", "ref"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("kind", enumP(List.of("file", "git", "path", "url", "doc")));
                    obj.add("ref", strP());
                    obj.add("note", strP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_add_todo", "Append a todo (checklist item) to a task.",
                schema(new String[]{"project", "ticket_id", "text"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("text", strP());
                    obj.add("done", boolP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_toggle_todo", "Flip the done state of a task's todo by 0-based index.",
                schema(new String[]{"project", "ticket_id", "index"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("index", intP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_remove_todo", "Remove a task's todo by 0-based index.",
                schema(new String[]{"project", "ticket_id", "index"}, obj -> {
                    obj.add("project", strP());
                    obj.add("ticket_id", strP());
                    obj.add("index", intP());
                    obj.add("by", strP());
                })));
        out.add(new McpTool("task_backlog", "Prioritized product backlog for a project.",
                schema(new String[]{"project"}, obj -> obj.add("project", strP()))));
        out.add(new McpTool("task_board", "Sprint Kanban grouped by status (all five columns always present).",
                schema(new String[]{"project"}, obj -> {
                    obj.add("project", strP());
                    obj.add("sprint", strP());
                })));
        out.add(new McpTool("task_plan_sprint", "Create/commit a sprint and move given tasks into it (force-sets sprint-backlog).",
                schema(new String[]{"project"}, obj -> {
                    obj.add("project", strP());
                    obj.add("sprint_id", strP());
                    obj.add("ticket_ids", arrP());
                    obj.add("goal", strP());
                })));
        out.add(new McpTool("task_close_sprint", "Close a sprint; unfinished tasks return to product-backlog.",
                schema(new String[]{"project", "sprint_id"}, obj -> {
                    obj.add("project", strP());
                    obj.add("sprint_id", strP());
                })));
        out.add(new McpTool("task_traceability", "Build a definition<->verification traceability matrix for a project.",
                schema(new String[]{"project"}, obj -> obj.add("project", strP()))));
        out.add(new McpTool("task_readiness",
                "Per-ticket V-model dispatch readiness (H6): one {id, stage, kind, reason} row per ticket, ordered "
                        + "by severity (STALE, BLOCKED, WAIT_UPSTREAM, RUNNING, READY, NOT_APPLICABLE) then id - "
                        + "what's runnable right now.",
                schema(new String[]{"project"}, obj -> obj.add("project", strP()))));
        out.add(new McpTool("task_doctor",
                "Store self-check (lint): reports inconsistent flag combinations per ticket (done but blocked, "
                        + "sprint set while product-backlog); ok=true when the store is consistent.",
                schema(new String[]{"project"}, obj -> obj.add("project", strP()))));
        return List.copyOf(out);
    }

    private interface Props {
        void accept(JsonObject props);
    }

    private static JsonObject schema(String[] required, Props fill) {
        JsonObject props = new JsonObject();
        fill.accept(props);
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);
        return schema;
    }

    private static JsonObject strP() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject strP(String description) {
        JsonObject o = strP();
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject intP() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        return o;
    }

    private static JsonObject boolP() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        return o;
    }

    private static JsonObject arrP() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        o.add("items", items);
        return o;
    }

    private static JsonObject enumP(List<String> values) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        JsonArray arr = new JsonArray();
        values.forEach(arr::add);
        o.add("enum", arr);
        return o;
    }

    private static JsonObject stageP() {
        JsonObject o = enumP(VStages.STAGES);
        o.addProperty("description", "V-model pipeline stage: definition leg (requirements, system, "
                + "architecture, design, implementation) then verification leg (test-implementation, "
                + "test-design, test-architecture, test-system, test-requirements).");
        return o;
    }
}
