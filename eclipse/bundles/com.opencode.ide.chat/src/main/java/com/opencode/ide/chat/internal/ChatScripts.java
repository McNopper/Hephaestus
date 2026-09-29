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

    /**
     * {@code window.__setInboxItems("[…]")} - replaces the composer queue row
     * (the session inbox's parked prompts, T-005 management surface); an
     * empty array hides the row.
     */
    public static String setInboxItems(List<ChatSessionController.InboxEntry> items) {
        return call("__setInboxItems", items == null ? List.of() : items);
    }

    /** {@code window.__setNotice("...")} - centered status line. */
    public static String setNotice(String text) {
        return "window.__setNotice(" + GSON.toJson(nullToEmpty(text)) + ")";
    }

    /** {@code window.__setTheme("light"|"dark")}. */
    public static String setTheme(String theme) {
        return "window.__setTheme(" + GSON.toJson(nullToEmpty(theme)) + ")";
    }

    /**
     * {@code window.__setReasoningVisible("{...}")} - toggles the visibility
     * of thinking/reasoning progress (live and history blocks; the page hides
     * via a CSS class, so toggling back needs no re-render).
     */
    public static String setReasoningVisible(boolean visible) {
        return call("__setReasoningVisible", Map.of("visible", visible));
    }

    // ---------- @-file autocomplete (U-012) ----------

    /**
     * {@code window.__setFileQuery("{...}")} - opens the {@code @}-file
     * dropdown for a query (the text after the {@code @}); the page shows a
     * searching row and asks Java for matches via {@code __javaFileQuery}.
     */
    public static String setFileQuery(String query) {
        return call("__setFileQuery", Map.of("query", nullToEmpty(query)));
    }

    /**
     * {@code window.__setFileCompletions("{...}")} - renders the
     * {@code @}-dropdown's two groups (U-047): alias reference roots
     * ({@code aliases}, rendered above) plus the file matches ({@code
     * paths}, already fuzzy-filtered and capped by the controller), with row
     * {@code selected} highlighted across BOTH groups (aliases first - the
     * host's keyboard selection spans the merged list); both lists empty
     * closes the dropdown.
     */
    public static String setFileCompletions(List<ChatSessionController.ReferenceProposal> aliases,
            List<String> paths, int selected) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aliases", aliases == null ? List.of() : aliases);
        payload.put("paths", paths == null ? List.of() : paths);
        payload.put("selected", Math.max(selected, 0));
        return call("__setFileCompletions", payload);
    }

    /** {@code window.__hideFileCompletions()} - closes the {@code @}-file dropdown. */
    public static String hideFileCompletions() {
        return "window.__hideFileCompletions()";
    }

    // ---------- question forms (U-014) ----------

    /**
     * {@code window.__setForms("[…]")} - replaces the open question forms:
     * one answerable card per form ({@code id} - the reply/cancel path
     * parameter, {@code title}, and the fields VERBATIM - the page renders
     * the service's field union leniently); an empty array hides the area.
     */
    public static String setForms(List<ChatSessionController.FormCard> forms) {
        return call("__setForms", forms == null ? List.of() : forms);
    }

    /**
     * Parses a form-answer payload handed UP by the page
     * ({@code __javaFormReply(formId, answersJson)}): the JSON string the
     * card's Submit button built, {@code {<fieldKey>: value}} with value
     * string | number | boolean | string[]. Lenient on purpose - an
     * unparseable or empty answer degrades to an empty map (the server
     * validates the real schema), never throws.
     */
    public static Map<String, Object> parseFormAnswers(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> answers = GSON.fromJson(json,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType());
            return answers == null ? Map.of() : answers;
        } catch (Exception e) {
            return Map.of(); // the card's own build went wrong - answer nothing
        }
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
