package com.opencode.ide.client.activity;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One point-in-time answer to "what is this session doing": identity, coarse
 * status, current activity (a running tool/shell, when one is visible), the
 * tools and shell commands used so far, subagent children, and cost. Built by
 * {@link SessionObserver} from pollable endpoints only — no event stream
 * needed — so fleet workers (and later any Eclipse panel) can be watched by
 * polling.
 *
 * <p>Every field is nullable-tolerant: a partially readable session still
 * produces a useful observation. Wire order is newest-first, matching v2's
 * message list.</p>
 */
public record SessionObservation(
        String sessionId,
        String title,
        String agent,
        String model,
        String status,
        String outcome,
        Double cost,
        Long tokens,
        String activity,
        String lastText,
        List<ToolUse> tools,
        List<ShellRun> shells,
        List<Child> subagents) {

    public SessionObservation {
        tools = tools == null ? List.of() : List.copyOf(tools);
        shells = shells == null ? List.of() : List.copyOf(shells);
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
    }

    /** One tool invocation: name, coarse lifecycle (non-terminal {@code running}/{@code streaming}/{@code pending}/… vs terminal {@code completed}/{@code error}) and the main target (file/command/pattern) when known. */
    public record ToolUse(String name, String status, String target) {
    }

    /** One session shell command: the command line, lifecycle, exit code and a short output tail. */
    public record ShellRun(String command, String status, Integer exit, String outputTail) {
    }

    /** A subagent child session (nested via {@code parentID}). */
    public record Child(String sessionId, String title, String agent, String status,
            Double cost, Long tokens) {
    }

    /** The observation as one JSON object (the fleet tool's payload; nulls omitted). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        put(o, "session_id", sessionId);
        put(o, "title", title);
        put(o, "agent", agent);
        put(o, "model", model);
        put(o, "status", status);
        put(o, "outcome", outcome);
        if (cost != null) {
            o.addProperty("cost_usd", cost);
        }
        if (tokens != null) {
            o.addProperty("tokens", tokens);
        }
        put(o, "activity", activity);
        put(o, "last_text", lastText);
        JsonArray toolArray = new JsonArray();
        for (ToolUse tool : tools) {
            JsonObject t = new JsonObject();
            put(t, "name", tool.name());
            put(t, "status", tool.status());
            put(t, "target", tool.target());
            toolArray.add(t);
        }
        o.add("tools", toolArray);
        JsonArray shellArray = new JsonArray();
        for (ShellRun shell : shells) {
            JsonObject s = new JsonObject();
            put(s, "command", shell.command());
            put(s, "status", shell.status());
            if (shell.exit() != null) {
                s.addProperty("exit", shell.exit());
            }
            put(s, "output_tail", shell.outputTail());
            shellArray.add(s);
        }
        o.add("shells", shellArray);
        JsonArray childArray = new JsonArray();
        for (Child child : subagents) {
            JsonObject c = new JsonObject();
            put(c, "session_id", child.sessionId());
            put(c, "title", child.title());
            put(c, "agent", child.agent());
            put(c, "status", child.status());
            if (child.cost() != null) {
                c.addProperty("cost_usd", child.cost());
            }
            if (child.tokens() != null) {
                c.addProperty("tokens", child.tokens());
            }
            childArray.add(c);
        }
        o.add("subagents", childArray);
        return o;
    }

    private static void put(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }
}
