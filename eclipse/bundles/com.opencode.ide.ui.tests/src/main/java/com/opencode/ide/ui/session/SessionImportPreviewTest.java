package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonParser;
import com.opencode.ide.ui.session.SessionImportPreview.Preview;

/**
 * Unit tests for {@link SessionImportPreview}: the lenient parse behind the
 * import flow's pick -&gt; preview -&gt; import steps (U-048). The document is
 * what "Export transcript…" writes — the RAW {@code {data:{info, messages}}}
 * export body — and the payloads must survive verbatim (numbers stay
 * numbers) into the {@code importSession} maps. SWT-free.
 */
public class SessionImportPreviewTest {

    /** The export route's answer shape: the {data:{info, messages}} envelope. */
    private static final String EXPORT_BODY = """
            {
              "data": {
                "info": {
                  "id": "ses_source",
                  "projectID": "p1",
                  "title": "Fix the widget",
                  "agent": "build",
                  "cost": 0.12,
                  "tokens": { "input": 100, "output": 50, "reasoning": 5,
                              "cache": { "read": 10, "write": 2 } },
                  "time": { "created": 1786511177910, "updated": 1786511199000 },
                  "location": { "directory": "C:\\\\repo" }
                },
                "messages": [
                  { "id": "msg_1", "type": "user", "text": "Fix it." },
                  { "id": "msg_2", "type": "assistant", "text": "Done." }
                ]
              }
            }
            """;

    @Test
    public void exportEnvelopeUnwrapsIntoPreviewAndPayloads() {
        Preview preview = SessionImportPreview.parse(EXPORT_BODY);

        assertEquals("Fix the widget", preview.title());
        assertEquals(2, preview.messageCount());
        // the payloads keep JsonElement values (numbers stay numbers on
        // re-serialization - the server validates Session.Info types)
        assertEquals("ses_source",
                ((com.google.gson.JsonElement) preview.info().get("id")).getAsString());
        assertEquals(2, preview.messages().size());
        assertEquals("user",
                ((com.google.gson.JsonElement) preview.messages().get(0).get("type")).getAsString());
    }

    /** Numbers must stay numbers: the server validates Session.Info (cost is finite). */
    @Test
    public void payloadValuesKeepTheirJsonTypesForReSerialization() {
        Preview preview = SessionImportPreview.parse(EXPORT_BODY);

        Object cost = preview.info().get("cost");
        assertEquals("the cost stays a JsonElement the client re-serializes verbatim",
                "0.12", String.valueOf(cost));
        assertTrue("still a JSON value, not a stringified copy",
                cost instanceof com.google.gson.JsonElement);
        String reSerialized = new com.google.gson.Gson().toJson(preview.info());
        assertTrue("the re-serialized body carries the number, not a string: " + reSerialized,
                reSerialized.contains("\"cost\":0.12"));
    }

    @Test
    public void bareInfoMessagesShapeIsAcceptedToo() {
        Preview preview = SessionImportPreview.parse(
                "{\"info\":{\"title\":\"Bare\"},\"messages\":[{\"type\":\"user\",\"text\":\"hi\"}]}");

        assertEquals("Bare", preview.title());
        assertEquals(1, preview.messageCount());
    }

    @Test
    public void missingTitleFallsBackToTheSessionId() {
        Preview preview = SessionImportPreview.parse(
                "{\"info\":{\"id\":\"ses_x\"},\"messages\":[]}");

        assertEquals("ses_x", preview.title());
    }

    @Test
    public void junkMessagesAreNotForwarded() {
        Preview preview = SessionImportPreview.parse(
                "{\"info\":{},\"messages\":[{\"type\":\"user\"}, 42, null]}");

        assertEquals("only object messages can be imported", 1, preview.messages().size());
    }

    @Test
    public void nonDocumentsAreRejectedWithAClearReason() {
        assertThrows("empty", IllegalArgumentException.class,
                () -> SessionImportPreview.parse(""));
        assertThrows("blank", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("   "));
        assertThrows("not JSON", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("this is not json"));
        assertThrows("not an object", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("[1,2,3]"));
        assertThrows("missing messages", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("{\"info\":{}}"));
        assertThrows("missing info", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("{\"messages\":[]}"));
        assertThrows("empty data envelope", IllegalArgumentException.class,
                () -> SessionImportPreview.parse("{\"data\":{}}"));
    }

    /** The parsed payloads feed importSession(info, messages, directory) verbatim. */
    @Test
    public void payloadsAreTheImportSessionArguments() {
        Preview preview = SessionImportPreview.parse(EXPORT_BODY);
        Map<String, Object> info = preview.info();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) (List<?>) preview.messages();

        assertEquals("the wire id round-trips as a JsonElement string",
                "ses_source", ((com.google.gson.JsonElement) info.get("id")).getAsString());
        assertEquals("msg_1", ((com.google.gson.JsonElement) messages.get(0).get("id")).getAsString());
        // the fixture's cache.read is 10 - the point is it stays a NUMBER
        assertEquals(10, JsonParser.parseString(new com.google.gson.Gson().toJson(info))
                .getAsJsonObject().getAsJsonObject("tokens").getAsJsonObject("cache")
                .get("read").getAsInt());
    }
}
