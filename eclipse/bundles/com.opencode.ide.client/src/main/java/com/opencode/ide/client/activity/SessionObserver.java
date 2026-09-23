package com.opencode.ide.client.activity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Builds {@link SessionObservation}s from pollable endpoints alone
 * ({@code GET /session}, {@code /session/active}, {@code /session/:id/message})
 * — the "what is it doing" answer for fleet workers, subagents and any other
 * session, without an event stream.
 *
 * <p>The message list is read RAW ({@link OpencodeClient#getMessagesJson}):
 * the chat-shaped {@code ChatEntry} parsing deliberately drops tool input and
 * shell detail, which is exactly what observability needs. Everything is read
 * leniently; unknown shapes degrade to absent fields, never exceptions. Only
 * an unreachable message endpoint yields {@code null} ("unobservable").</p>
 *
 * <p>v2 lists messages newest-first, so the first running tool and the first
 * assistant text found while walking the list are the current ones.</p>
 */
public final class SessionObserver {

    /** Fields probed (in order) for a tool call's display target. */
    private static final List<String> TARGET_KEYS =
            List.of("filePath", "path", "absolutePath", "command", "pattern", "query", "url");
    private static final int TAIL = 200;
    private static final int TARGET_MAX = 160;

    private SessionObserver() {
    }

    /**
     * Observes one session.
     *
     * @param client    the server the session lives on
     * @param sessionId the session to observe
     * @param directory scope for the session list (subagent children), or
     *                  {@code null} for the server-wide list
     * @return the observation, or {@code null} when the session's message list
     *         cannot be fetched (unknown session / server unreachable)
     */
    public static SessionObservation observe(OpencodeClient client, String sessionId, String directory) {
        if (client == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        JsonArray messages;
        try {
            messages = client.getMessagesJson(sessionId);
        } catch (Exception e) {
            return null; // unobservable: unknown session or dead server
        }
        Map<String, SessionStatus> active = Map.of();
        try {
            Map<String, SessionStatus> map = client.getSessionStatus();
            if (map != null) {
                active = map;
            }
        } catch (Exception e) {
            // coarse status is optional - keep observing
        }
        List<Session> sessions = List.of();
        try {
            List<Session> list = client.getSessions(directory);
            if (list != null) {
                sessions = list;
            }
        } catch (Exception e) {
            // the session list only feeds identity + subagent children
        }

        Session self = null;
        List<SessionObservation.Child> children = new ArrayList<>();
        for (Session session : sessions) {
            if (session == null) {
                continue;
            }
            if (sessionId.equals(session.id())) {
                self = session;
            } else if (sessionId.equals(session.parentID())) {
                children.add(childOf(session, active));
            }
        }
        children.sort(Comparator.comparing(child -> child.sessionId() == null ? "" : child.sessionId()));

        String status = statusOf(active, sessionId);

        List<SessionObservation.ToolUse> tools = new ArrayList<>();
        List<SessionObservation.ShellRun> shells = new ArrayList<>();
        String lastText = null;
        String activity = null;
        String agent = null;
        String model = null;
        double costSum = 0;
        long tokenSum = 0;
        boolean costKnown = false;
        boolean tokensKnown = false;
        for (JsonElement element : messages) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String type = str(message, "type");
            if ("assistant".equals(type)) {
                if (agent == null) {
                    agent = str(message, "agent");
                }
                if (model == null) {
                    model = modelOf(message);
                }
                if (message.has("cost") && message.get("cost").isJsonPrimitive()) {
                    costSum += message.get("cost").getAsDouble();
                    costKnown = true;
                }
                long messageTokens = tokensOf(message.get("tokens"));
                if (messageTokens >= 0) {
                    tokenSum += messageTokens;
                    tokensKnown = true;
                }
                JsonElement content = message.get("content");
                if (content != null && content.isJsonArray()) {
                    for (JsonElement partElement : content.getAsJsonArray()) {
                        if (!partElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject part = partElement.getAsJsonObject();
                        String partType = str(part, "type");
                        if ("tool".equals(partType)) {
                            SessionObservation.ToolUse tool = toolOf(part);
                            tools.add(tool);
                            if (activity == null
                                    && com.opencode.ide.client.model.Turns.toolInFlight(tool.status())) {
                                activity = tool.target() == null
                                        ? "tool: " + tool.name()
                                        : "tool: " + tool.name() + " " + tool.target();
                            }
                        } else if ("text".equals(partType) && lastText == null) {
                            lastText = blankToNull(tail(str(part, "text"), TAIL));
                        }
                    }
                }
                if (lastText == null) {
                    lastText = blankToNull(tail(str(message, "text"), TAIL));
                }
            } else if ("shell".equals(type)) {
                SessionObservation.ShellRun shell = shellOf(message);
                shells.add(shell);
                if (activity == null && com.opencode.ide.client.model.Turns.shellInFlight(shell.status())) {
                    activity = "shell: " + shell.command();
                }
            }
        }

        // the session row's aggregates are authoritative; messages fill gaps
        Double cost = self != null && self.cost() != null ? self.cost()
                : costKnown ? Double.valueOf(costSum) : null;
        Long tokens = self != null && self.tokens() != null ? Long.valueOf(tokensOf(self.tokens()))
                : tokensKnown ? Long.valueOf(tokenSum) : null;
        if (self != null) {
            agent = agent != null ? agent : self.agent();
            model = model != null ? model : modelOf(self);
        }
        String title = self != null ? self.title() : null;
        String outcome = self != null ? self.outcome() : null;

        return new SessionObservation(sessionId, title, agent, model, status, outcome,
                cost, tokens, activity, lastText, tools, shells, children);
    }

    /**
     * B-013: a call is CURRENT activity while its status is non-terminal —
     * v2 reports {@code running} and {@code streaming} (output still
     * arriving); comparing against {@code running} alone missed every
     * in-flight {@code streaming} call. The single semantic lives in
     * {@link com.opencode.ide.client.model.Turns#toolInFlight(String)} /
     * {@link com.opencode.ide.client.model.Turns#shellInFlight(String)}.
     */

    /** Coarse status: the active map when it knows the session, else idle (v2 lists running sessions only). */
    private static String statusOf(Map<String, SessionStatus> active, String sessionId) {
        SessionStatus entry = active.get(sessionId);
        if (entry != null && entry.type() != null) {
            return entry.type();
        }
        return "idle";
    }

    private static SessionObservation.Child childOf(Session session, Map<String, SessionStatus> active) {
        SessionStatus entry = active.get(session.id());
        String status = entry != null && entry.type() != null ? entry.type() : "idle";
        return new SessionObservation.Child(session.id(), session.title(), session.agent(), status,
                session.cost(), session.tokens() == null ? null : Long.valueOf(tokensOf(session.tokens())));
    }

    private static SessionObservation.ToolUse toolOf(JsonObject part) {
        String name = str(part, "tool");
        if (name == null) {
            name = str(part, "name");
        }
        JsonObject state = obj(part, "state");
        String status = state == null ? null : str(state, "status");
        String target = null;
        JsonObject input = state == null ? null : obj(state, "input");
        if (input != null) {
            for (String key : TARGET_KEYS) {
                target = blankToNull(str(input, key));
                if (target != null) {
                    break;
                }
            }
        }
        return new SessionObservation.ToolUse(name == null ? "tool" : name, status,
                target == null ? null : tail(target, TARGET_MAX));
    }

    private static SessionObservation.ShellRun shellOf(JsonObject message) {
        String command = str(message, "command");
        String status = str(message, "status");
        Integer exit = number(message, "exit");
        if (exit == null) {
            exit = number(obj(message, "metadata"), "exit");
        }
        String output = null;
        JsonObject out = obj(message, "output");
        if (out != null) {
            output = str(out, "output");
        }
        return new SessionObservation.ShellRun(command, status, exit, blankToNull(tail(output, TAIL)));
    }

    /** {@code provider/id[#variant]} from a message's model object, or {@code null}. */
    private static String modelOf(JsonObject message) {
        JsonObject model = obj(message, "model");
        if (model == null) {
            return null;
        }
        return modelOf(str(model, "providerID"), str(model, "id"), str(model, "variant"));
    }

    private static String modelOf(Session session) {
        return modelOf(session.providerId(), session.modelId(),
                session.model() == null ? null : session.model().variant());
    }

    private static String modelOf(String provider, String id, String variant) {
        if (id == null) {
            return null;
        }
        String ref = (provider == null ? "" : provider + "/") + id;
        return variant == null ? ref : ref + "#" + variant;
    }

    /** Sum of input/output/reasoning, or {@code -1} when absent/non-numeric. */
    private static long tokensOf(JsonElement tokens) {
        if (tokens == null || !tokens.isJsonObject()) {
            return -1;
        }
        JsonObject object = tokens.getAsJsonObject();
        long sum = 0;
        boolean known = false;
        for (String key : List.of("input", "output", "reasoning")) {
            Long value = longOrNull(object, key);
            if (value != null) {
                sum += value;
                known = true;
            }
        }
        return known ? sum : -1;
    }

    private static long tokensOf(Session.Tokens tokens) {
        return tokens.input() + tokens.output() + tokens.reasoning();
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(trimmed.length() - max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static Integer number(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsLong();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject obj(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }
}
