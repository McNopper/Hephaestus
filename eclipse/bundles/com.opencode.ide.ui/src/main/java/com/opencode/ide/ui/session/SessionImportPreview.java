package com.opencode.ide.ui.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lenient preview of a session-export document for the import flow (U-048):
 * the file the view's "Export transcript…" writes is the RAW body of
 * {@code GET /api/experimental/session/{id}/export} — {@code {data:
 * {info, messages}}} — and the import route ({@code POST
 * /api/experimental/session/import}) wants exactly that {@code info} and
 * {@code messages}. This class unwraps both shapes (data envelope or bare),
 * validates the minimum the server requires, and extracts what the preview
 * shows: the title and the message count. SWT-free and unit-testable; the
 * dialog only renders.
 */
public final class SessionImportPreview {

    private SessionImportPreview() {
    }

    /**
     * @param document the export document as text (a picked file's content)
     * @return the preview with the verbatim {@code info}/{@code messages}
     *         payloads for {@code importSession}
     * @throws IllegalArgumentException when the document is not JSON or
     *                                  lacks {@code info}/{@code messages} —
     *                                  the message names what is missing
     */
    public static Preview parse(String document) {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("the file is empty");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(document);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the file is not valid JSON");
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the file is not a session export (not a JSON object)");
        }
        JsonObject body = root.getAsJsonObject();
        JsonObject unwrapped = body.has("data") && body.get("data").isJsonObject()
                ? body.getAsJsonObject("data") : body;
        JsonObject info = object(unwrapped, "info");
        JsonArray messages = array(unwrapped, "messages");
        if (info == null || messages == null) {
            throw new IllegalArgumentException(
                    "the export is missing its " + (info == null ? "info" : "messages") + " member");
        }
        return new Preview(mapOf(info), toMaps(messages), title(info), messages.size());
    }

    private static String title(JsonObject info) {
        String title = string(info, "title");
        String id = string(info, "id");
        if (title != null && !title.isBlank()) {
            return title;
        }
        return id == null ? "(untitled)" : id;
    }

    /**
     * The map form {@code importSession} takes. Values stay {@link JsonElement}s
     * on purpose: the client re-serializes the map with Gson, which renders a
     * JsonElement verbatim — numbers stay numbers, so the server's
     * {@code Session.Info} validation sees the export's own types, never
     * stringified copies.
     */
    private static List<Map<String, Object>> toMaps(JsonArray messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonElement element : messages) {
            if (element == null || !element.isJsonObject()) {
                continue; // the server rejects junk; we never forward it
            }
            out.add(mapOf(element.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    /** A JsonObject as an insertion-ordered map of its JsonElement values. */
    private static Map<String, Object> mapOf(JsonObject object) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
        }
        return values;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    /**
     * One parsed export: the payloads for {@code importSession(info, messages,
     * directory)} plus what the preview dialog shows (title, message count).
     */
    public record Preview(Map<String, Object> info, List<Map<String, Object>> messages,
            String title, int messageCount) {
    }
}
