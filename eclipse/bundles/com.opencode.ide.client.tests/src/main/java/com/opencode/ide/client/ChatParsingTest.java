package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatEntryDeserializer;
import com.opencode.ide.client.model.ChatMessageInfo;

/**
 * Unit tests for the v2 {@code GET /api/session/:id/message} mapping, parsed
 * through the same {@link ChatEntryDeserializer} the HTTP client uses — never
 * plain Gson, so the tests exercise the production wire path.
 *
 * <p>The fixture below mirrors a <b>verbatim capture from a live opencode
 * 2.0.10 server</b>: v2 flattened the v1 {@code {info, parts}} envelope — the
 * role is the message {@code type}, the user prompt is a flat {@code text},
 * assistant parts live in {@code content[]}, the model is a nested
 * {@code {id, providerID, variant}} object for every role, and completion is
 * {@code time.completed}. It must stay a real capture: an earlier hand-written
 * fixture once invented a shape the server never sent and the tests passed
 * while the UI broke.</p>
 */
public class ChatParsingTest {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
            .registerTypeAdapter(ChatEntry.class, new ChatEntryDeserializer())
            .create();

    /** Mirrored from GET /api/session/:id/message (opencode 2.0.10), newest first. */
    private static final String HISTORY_JSON = """
            [
              {
                "id": "msg_0036e08bc001ps0jHM7NO3caut",
                "time": { "created": 1786763937980, "completed": 1786763947855 },
                "type": "assistant",
                "agent": "build",
                "model": { "id": "kimi-k2.7-code", "providerID": "opencode-go", "variant": "high" },
                "content": [
                  { "type": "reasoning", "text": "The user wants a simple \\"ack\\" reply." },
                  { "type": "text", "text": "ack" }
                ],
                "cost": 0.0064992,
                "tokens": { "input": 6736, "output": 3, "reasoning": 22,
                            "cache": { "read": 0, "write": 0 } },
                "finish": "stop"
              },
              {
                "id": "msg_0036e089d001Fqti0Zp8VVfjbY",
                "time": { "created": 1786763937949 },
                "type": "user",
                "text": "Reply with exactly: ack"
              }
            ]
            """;

    private static List<ChatEntry> history() {
        return GSON.fromJson(HISTORY_JSON,
                TypeToken.getParameterized(List.class, ChatEntry.class).getType());
    }

    @Test
    public void historyMapsRolesAndParts() {
        List<ChatEntry> entries = history();
        assertEquals(2, entries.size());

        ChatEntry assistant = entries.get(0);
        assertFalse(assistant.isUser());
        assertEquals("assistant", assistant.info().role());
        assertEquals("ack", assistant.text());
        assertEquals("The user wants a simple \"ack\" reply.", assistant.reasoning());

        ChatEntry user = entries.get(1);
        assertTrue(user.isUser());
        assertEquals("msg_0036e089d001Fqti0Zp8VVfjbY", user.info().id());
        assertEquals("user", user.info().role());
        assertEquals("Reply with exactly: ack", user.text());
        assertEquals("", user.reasoning());
    }

    @Test
    public void assistantModelIsReadFromTheNestedObject() {
        ChatMessageInfo info = history().get(0).info();
        assertEquals("opencode-go", info.providerId());
        assertEquals("kimi-k2.7-code", info.modelId());
        assertEquals("high", info.variantName());
        assertEquals("opencode-go/kimi-k2.7-code (high)", info.modelLabel());
        assertEquals("stop", info.finish());
        assertEquals("build", info.agent());
    }

    @Test
    public void completionComesFromTimeCompleted() {
        assertTrue("completed assistant", history().get(0).info().isComplete());
        assertFalse("user prompts carry no completion stamp", history().get(1).info().isComplete());
    }

    @Test
    public void costAndTokensSurvive() {
        ChatMessageInfo info = history().get(0).info();
        assertEquals(0.0064992, info.cost(), 0.0000001);
        assertEquals(6736L, info.tokens().input());
        assertEquals(22L, info.tokens().reasoning());
    }

    @Test
    public void modelLabelIsEmptyWhenTheServerOmitsTheModel() {
        ChatEntry entry = GSON.fromJson("{\"id\":\"m\",\"type\":\"assistant\",\"content\":[]}", ChatEntry.class);
        assertEquals("", entry.info().modelLabel());
        assertNull(entry.info().providerId());
        assertFalse(entry.info().isComplete());
    }

    @Test
    public void idleMarkersParseAsTheirOwnRole() {
        ChatEntry entry = GSON.fromJson(
                "{\"id\":\"msg_x\",\"time\":{\"created\":1},\"type\":\"idle\",\"outcome\":\"succeeded\"}",
                ChatEntry.class);
        assertEquals("idle", entry.info().role());
        assertEquals("", entry.text());
    }

    @Test
    public void nullContentIsTolerated() {
        ChatEntry entry = GSON.fromJson("{\"id\":\"m\",\"type\":\"assistant\",\"content\":null}", ChatEntry.class);
        assertEquals("", entry.text());
        assertEquals(0, entry.parts().size());
    }

    @Test
    public void malformedEntriesYieldNull() {
        assertNull(GSON.fromJson("\"not an object\"", ChatEntry.class));
    }
}
