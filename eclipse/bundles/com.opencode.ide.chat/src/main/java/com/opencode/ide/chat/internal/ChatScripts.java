package com.opencode.ide.chat.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;

/**
 * Builds the JavaScript calls into the chat page (single source of truth for the
 * Java -> JS bridge contract).
 *
 * <p><b>Contract:</b> every payload is passed as a JSON <em>string literal</em>,
 * i.e. {@code window.__appendUser("{\"text\":\"hi\"}")}, because the page does
 * {@code JSON.parse(arg)}. Passing a JS object literal
 * ({@code window.__appendUser({"text":"hi"})}) makes {@code JSON.parse} receive
 * {@code "[object Object]"} and throw - which used to fail silently, because
 * {@code Browser.execute} on the Edge backend returns {@code true} even when the
 * script throws. Keeping the quoting in one tested place prevents a repeat.</p>
 */
public final class ChatScripts {

    private static final Gson GSON = new Gson();

    private ChatScripts() {
    }

    /** {@code window.__appendUser("{...}")} - echoes the prompt into the transcript. */
    public static String appendUser(String text) {
        return call("__appendUser", Map.of("text", nullToEmpty(text)));
    }

    /** {@code window.__startAssistant("{...}")} - creates the reply bubble if absent. */
    public static String startAssistant(String messageId) {
        return call("__startAssistant", Map.of("mid", nullToEmpty(messageId)));
    }

    /** {@code window.__appendDelta("{...}")} - appends one streamed text chunk. */
    public static String appendDelta(String messageId, String text) {
        return call("__appendDelta", Map.of("mid", nullToEmpty(messageId), "text", nullToEmpty(text)));
    }

    /** {@code window.__appendReasoningDelta("{...}")} - appends one streamed reasoning chunk. */
    public static String appendReasoning(String messageId, String text) {
        return call("__appendReasoningDelta", Map.of("mid", nullToEmpty(messageId), "text", nullToEmpty(text)));
    }

    /**
     * {@code window.__setAssistantText("{...}")} - authoritative final render,
     * including the compact {@code tool} lines ({@code tools}: array of
     * {@code {name, state}}, rendered by the page above the body).
     */
    public static String setAssistantText(String messageId, String text, String reasoning, String meta,
            List<ChatSessionController.ToolLine> tools) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mid", nullToEmpty(messageId));
        payload.put("text", nullToEmpty(text));
        payload.put("reasoning", nullToEmpty(reasoning));
        payload.put("meta", nullToEmpty(meta));
        payload.put("tools", tools == null ? List.of() : tools);
        return call("__setAssistantText", payload);
    }

    /**
     * {@code window.__stopStream("{...}")} - removes the streaming cursor from a
     * bubble (send finished, failed or aborted; harmless when no cursor exists).
     */
    public static String stopStream(String messageId) {
        return call("__stopStream", Map.of("mid", nullToEmpty(messageId)));
    }

    /** {@code window.__setMessages("[...]")} - replaces the transcript (history load). */
    public static String setMessages(Object rows) {
        return call("__setMessages", rows);
    }

    /** {@code window.__setNotice("...")} - centered status line. */
    public static String setNotice(String text) {
        return "window.__setNotice(" + GSON.toJson(nullToEmpty(text)) + ")";
    }

    /** {@code window.__setTheme("light"|"dark")}. */
    public static String setTheme(String theme) {
        return "window.__setTheme(" + GSON.toJson(nullToEmpty(theme)) + ")";
    }

    /** {@code window.__clear()} - empties the transcript. */
    public static String clear() {
        return "window.__clear()";
    }

    /** Serializes {@code payload} to JSON and passes it as a JSON string literal. */
    private static String call(String function, Object payload) {
        String json = GSON.toJson(payload);
        return "window." + function + "(" + GSON.toJson(json) + ")";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
