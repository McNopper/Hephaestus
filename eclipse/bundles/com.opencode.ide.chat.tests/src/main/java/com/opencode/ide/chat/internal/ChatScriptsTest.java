package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Contract test for the Java -> JS bridge.
 *
 * <p>The chat page does {@code JSON.parse(arg)}, so every payload must be passed
 * as a JSON <em>string literal</em>. Hand-built calls used to emit a JS object
 * literal instead ({@code window.__appendUser({"text":"hi"})}), which made
 * {@code JSON.parse} throw inside the page while {@code Browser.execute} still
 * returned {@code true} - nothing rendered and nothing was logged. These tests
 * pin the emitted shape; {@code bridge-check.mjs} executes the very same strings
 * against the real page.</p>
 */
public class ChatScriptsTest {

    private static final Gson GSON = new Gson();

    /** Extracts the single argument of {@code window.__fn(<arg>)}. */
    private static String argumentOf(String script) {
        int open = script.indexOf('(');
        int close = script.lastIndexOf(')');
        assertTrue("not a call: " + script, open > 0 && close > open);
        return script.substring(open + 1, close);
    }

    private static Map<String, Object> payloadOf(String script) {
        String argument = argumentOf(script);
        assertTrue("payload must be a JSON string literal, was: " + argument, argument.startsWith("\""));
        String json = GSON.fromJson(argument, String.class);
        return GSON.fromJson(json, new TypeToken<Map<String, Object>>() { }.getType());
    }

    @Test
    public void appendUserEmitsAJsonStringLiteral() {
        assertEquals("window.__appendUser(\"{\\\"text\\\":\\\"hello **world**\\\"}\")",
                ChatScripts.appendUser("hello **world**"));
    }

    @Test
    public void payloadIsNeverAJsObjectLiteral() {
        // the original bug: window.__appendUser({"text":"hi"}) -> JSON.parse("[object Object]")
        List<String> scripts = List.of(
                ChatScripts.appendUser("hi"),
                ChatScripts.startAssistant("msg_1"),
                ChatScripts.appendDelta("msg_1", "chunk"),
                ChatScripts.appendReasoning("msg_1", "ponder"),
                ChatScripts.setAssistantText("msg_1", "text", "", "openai/gpt",
                        List.of(new ChatSessionController.ToolLine("read", "completed"))),
                ChatScripts.setMessages(List.of(Map.of("role", "user", "text", "hi"))),
                ChatScripts.setInboxItems(List.of(new ChatSessionController.InboxEntry("m", "t"))));
        for (String script : scripts) {
            String argument = argumentOf(script);
            assertFalse("must not pass a JS object/array literal: " + script, argument.startsWith("{"));
            assertFalse("must not pass a JS object/array literal: " + script, argument.startsWith("["));
            assertTrue("must pass a JSON string literal: " + script, argument.startsWith("\""));
        }
    }

    @Test
    public void appendUserRoundTripsTheText() {
        assertEquals("hi", payloadOf(ChatScripts.appendUser("hi")).get("text"));
    }

    @Test
    public void quotesNewlinesAndBackslashesSurviveTheDoubleEncoding() {
        String tricky = "say \"hi\"\n\tpath C:\\temp\\x  </script> \u00e9\u4e2d";
        assertEquals(tricky, payloadOf(ChatScripts.appendUser(tricky)).get("text"));
        assertEquals(tricky, payloadOf(ChatScripts.appendDelta("m", tricky)).get("text"));
        assertEquals(tricky, payloadOf(ChatScripts.setAssistantText("m", tricky, "", "", null)).get("text"));
    }

    @Test
    public void streamingScriptsCarryMessageIdAndText() {
        assertEquals("msg_1", payloadOf(ChatScripts.startAssistant("msg_1")).get("mid"));
        Map<String, Object> delta = payloadOf(ChatScripts.appendDelta("msg_1", "ack"));
        assertEquals("msg_1", delta.get("mid"));
        assertEquals("ack", delta.get("text"));
        Map<String, Object> reasoning = payloadOf(ChatScripts.appendReasoning("msg_1", "ponder"));
        assertEquals("msg_1", reasoning.get("mid"));
        assertEquals("ponder", reasoning.get("text"));
    }

    @Test
    public void finalRenderCarriesTextReasoningMetaAndTools() {
        Map<String, Object> payload = payloadOf(ChatScripts.setAssistantText("msg_1", "done",
                "thinking", "openai/gpt",
                List.of(new ChatSessionController.ToolLine("read", "completed"))));
        assertEquals("msg_1", payload.get("mid"));
        assertEquals("done", payload.get("text"));
        assertEquals("thinking", payload.get("reasoning"));
        assertEquals("openai/gpt", payload.get("meta"));
        assertEquals(List.of(Map.of("name", "read", "state", "completed")), payload.get("tools"));
    }

    @Test
    public void finalRenderWithoutToolsEmitsAnEmptyArray() {
        assertEquals(List.of(), payloadOf(ChatScripts.setAssistantText("m", "t", "", "", null)).get("tools"));
        assertEquals(List.of(), payloadOf(ChatScripts.setAssistantText("m", "t", "", "", List.of())).get("tools"));
    }

    @Test
    public void setMessagesPassesTheHistoryAsAJsonStringLiteral() {
        String script = ChatScripts.setMessages(List.of(
                Map.of("role", "user", "text", "q"),
                Map.of("role", "assistant", "text", "a")));
        String json = GSON.fromJson(argumentOf(script), String.class);
        List<Map<String, String>> rows =
                GSON.fromJson(json, new TypeToken<List<Map<String, String>>>() { }.getType());
        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).get("role"));
        assertEquals("a", rows.get(1).get("text"));
    }

    @Test
    public void inboxRowPassesEntriesAsAJsonStringLiteral() {
        String script = ChatScripts.setInboxItems(List.of(
                new ChatSessionController.InboxEntry("msg_q", "run the tests")));
        String json = GSON.fromJson(argumentOf(script), String.class);
        List<Map<String, String>> items =
                GSON.fromJson(json, new TypeToken<List<Map<String, String>>>() { }.getType());
        assertEquals(1, items.size());
        assertEquals("msg_q", items.get(0).get("id"));
        assertEquals("run the tests", items.get(0).get("text"));
        // null (no inbox read yet) must not become the literal null
        assertEquals(List.of(), GSON.fromJson(
                GSON.fromJson(argumentOf(ChatScripts.setInboxItems(null)), String.class),
                new TypeToken<List<Map<String, String>>>() { }.getType()));
    }

    @Test
    public void noticeAndThemeTakePlainStringArguments() {
        assertEquals("window.__setNotice(\"Connected.\")", ChatScripts.setNotice("Connected."));
        assertEquals("window.__setTheme(\"dark\")", ChatScripts.setTheme("dark"));
        assertEquals("window.__setReasoningVisible(\"{\\\"visible\\\":false}\")",
                ChatScripts.setReasoningVisible(false));
        assertEquals("window.__hideFileCompletions()", ChatScripts.hideFileCompletions());
        assertEquals("window.__clear()", ChatScripts.clear());
    }

    // ---------- @-file autocomplete (U-012) ----------

    @Test
    public void fileQueryCarriesTheQueryAsAJsonStringLiteral() {
        Map<String, Object> payload = payloadOf(ChatScripts.setFileQuery("char"));
        assertEquals("char", payload.get("query"));
        // an absent query must not become the literal null
        assertEquals("", payloadOf(ChatScripts.setFileQuery(null)).get("query"));
    }

    @Test
    public void fileCompletionsCarryPathsAndTheSelectedRow() {
        Map<String, Object> payload = payloadOf(ChatScripts.setFileCompletions(
                List.of(),
                List.of("src/Main.cpp", "web/char<b>x</b>.js"), 1));
        assertEquals(List.of(), payload.get("aliases"));
        assertEquals(List.of("src/Main.cpp", "web/char<b>x</b>.js"), payload.get("paths"));
        assertEquals(1.0, payload.get("selected"));
        // a null list must not become the literal null; a negative row clamps to 0
        assertEquals(List.of(), payloadOf(ChatScripts.setFileCompletions(null, null, -3)).get("paths"));
        assertEquals(List.of(), payloadOf(ChatScripts.setFileCompletions(null, null, -3)).get("aliases"));
        assertEquals(0.0, payloadOf(ChatScripts.setFileCompletions(null, null, -3)).get("selected"));
    }

    @Test
    public void fileCompletionsCarryTheAliasGroupAboveTheFiles() {
        Map<String, Object> payload = payloadOf(ChatScripts.setFileCompletions(
                List.of(new ChatSessionController.ReferenceProposal("ref_1", "repo-map")),
                List.of("src/RepoMap.cpp"), 0));
        assertEquals(List.of(Map.of("id", "ref_1", "name", "repo-map")), payload.get("aliases"));
        assertEquals(List.of("src/RepoMap.cpp"), payload.get("paths"));
        assertEquals(0.0, payload.get("selected"));
    }

    @Test
    public void aHostileFilePathSurvivesTheDoubleEncoding() {
        String hostile = "web/char\"; alert(1); \\\\ <b>.js\n\t";
        Map<String, Object> payload = payloadOf(ChatScripts.setFileCompletions(
                null, List.of(hostile), 0));
        assertEquals(List.of(hostile), payload.get("paths"));
    }

    @Test
    public void aHostileAliasNameSurvivesTheDoubleEncoding() {
        String hostile = "repo\"; alert(1); \\\\ <b>map\n\t";
        Map<String, Object> payload = payloadOf(ChatScripts.setFileCompletions(
                List.of(new ChatSessionController.ReferenceProposal("ref_x", hostile)),
                List.of(), 0));
        assertEquals(List.of(Map.of("id", "ref_x", "name", hostile)), payload.get("aliases"));
    }

    // ---------- question forms (U-014) ----------

    @Test
    public void formCardsPassIdTitleAndRawFieldsAsAJsonStringLiteral() {
        Map<String, Object> field = Map.of("type", "string", "key", "target", "label", "Target");
        String script = ChatScripts.setForms(List.of(new ChatSessionController.FormCard(
                "frm_1", "Pick a target", List.of(field))));
        String json = GSON.fromJson(argumentOf(script), String.class);
        List<Map<String, Object>> cards =
                GSON.fromJson(json, new TypeToken<List<Map<String, Object>>>() { }.getType());
        assertEquals(1, cards.size());
        assertEquals("frm_1", cards.get(0).get("id"));
        assertEquals("Pick a target", cards.get(0).get("title"));
        assertEquals(List.of(field), cards.get(0).get("fields"));
        // null (no form read yet) must not become the literal null
        assertEquals(List.of(), GSON.fromJson(
                GSON.fromJson(argumentOf(ChatScripts.setForms(null)), String.class),
                new TypeToken<List<Map<String, Object>>>() { }.getType()));
    }

    @Test
    public void parseFormAnswersIsLenientAndNeverThrows() {
        assertEquals(Map.of("target", "app", "jobs", 8.0, "clean", true),
                ChatScripts.parseFormAnswers("{\"target\":\"app\",\"jobs\":8,\"clean\":true}"));
        assertEquals(Map.of(), ChatScripts.parseFormAnswers(null));
        assertEquals(Map.of(), ChatScripts.parseFormAnswers(""));
        assertEquals(Map.of(), ChatScripts.parseFormAnswers("   "));
        assertEquals(Map.of(), ChatScripts.parseFormAnswers("not json at all"));
        // whatever the page sends travels as-is - the service validates the schema
        assertEquals(Map.of("answer", Map.of()), ChatScripts.parseFormAnswers("{\"answer\":{}}"));
    }

    @Test
    public void nullsBecomeEmptyStringsInsteadOfTheLiteralNull() {
        assertEquals("", payloadOf(ChatScripts.appendUser(null)).get("text"));
        Map<String, Object> payload = payloadOf(ChatScripts.setAssistantText(null, null, null, null, null));
        assertEquals("", payload.get("mid"));
        assertEquals("", payload.get("text"));
        assertEquals("", payload.get("reasoning"));
        assertEquals("", payload.get("meta"));
        assertEquals(List.of(), payload.get("tools"));
    }
}
