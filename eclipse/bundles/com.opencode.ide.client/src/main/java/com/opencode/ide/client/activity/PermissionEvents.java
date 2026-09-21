package com.opencode.ide.client.activity;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Parses permission-related {@code /api/event} SSE events into
 * {@link PermissionRequest}s. Unknown types and missing, blank or non-string
 * identifiers yield {@code null}. Missing display fields are tolerated.
 *
 * <p><b>Event contract (verified against a live opencode 2.0.11 server):</b></p>
 * <ul>
 *   <li>{@code permission.asked} — data
 *   {@code { id, sessionID, action, resources: string[], save?: string[],
 *   metadata?, source? }}: a new request is pending. The top-level {@code id}
 *   identifies the permission request used in the reply endpoint. Optional
 *   {@code source: { type, messageID, id }} identifies the originating tool
 *   invocation; its id is a different identity. {@code action} is the category
 *   (e.g. {@code "shell"}); {@code resources} are the patterns. Metadata
 *   string values carry display hints like the command.</li>
 *   <li>{@code permission.replied} — data
 *   {@code { sessionID, requestID, reply: once|always|reject }}: the request
 *   was answered (possibly by another client — e.g. an attached TUI).</li>
 * </ul>
 *
 * <p>The fleet queue is keyed by permission request id. Both asks and replies
 * must resolve to that same identity, including requests without tool metadata.</p>
 */
public final class PermissionEvents {

    /** A request was raised (see class javadoc for the payload shape). */
    public static final String ASKED = "permission.asked";

    /** A request was answered — by us or any other client (see class javadoc). */
    public static final String REPLIED = "permission.replied";

    /** Metadata keys probed (in order) for a display title; string values only. */
    private static final List<String> TITLE_KEYS = List.of("title", "command", "path", "pattern", "description");

    private PermissionEvents() {
    }

    /**
     * Parses one event.
     *
     * @return the {@link PermissionRequest}, or {@code null} for non-permission
     *         events, {@code null}/{@code unknown} types, and permission
     *         events whose session or permission id is missing (nothing
     *         actionable)
     */
    public static PermissionRequest parse(OpencodeEvent event) {
        if (event == null || event.type() == null) {
            return null;
        }
        return switch (event.type()) {
            case ASKED -> parseAsked(event);
            case REPLIED -> parseReplied(event);
            default -> null;
        };
    }

    private static PermissionRequest parseAsked(OpencodeEvent event) {
        String sessionId = nonBlankString(event, "sessionID");
        String permissionId = nonBlankString(event, "id");
        if (sessionId == null || permissionId == null) {
            return null;
        }
        return new PermissionRequest(sessionId, permissionId, nonBlankString(event, "action"),
                strings(event, "resources"), metadataTitle(event), PermissionRequest.Status.PENDING);
    }

    private static PermissionRequest parseReplied(OpencodeEvent event) {
        String sessionId = nonBlankString(event, "sessionID");
        String permissionId = nonBlankString(event, "requestID");
        if (sessionId == null || permissionId == null) {
            return null;
        }
        return new PermissionRequest(sessionId, permissionId, null, List.of(), null,
                PermissionRequest.Status.ANSWERED);
    }

    private static String nonBlankString(OpencodeEvent event, String key) {
        JsonObject properties = event.properties();
        JsonElement value = properties == null ? null : properties.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    /** The named property as a string list; non-string entries are skipped. */
    private static List<String> strings(OpencodeEvent event, String key) {
        JsonObject properties = event.properties();
        if (properties == null || !properties.has(key)) {
            return List.of();
        }
        JsonElement element = properties.get(key);
        if (!element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                values.add(item.getAsString());
            }
        }
        return List.copyOf(values);
    }

    /** First string metadata value among {@link #TITLE_KEYS}; {@code null} when absent. */
    private static String metadataTitle(OpencodeEvent event) {
        JsonObject properties = event.properties();
        if (properties == null || !properties.has("metadata")) {
            return null;
        }
        JsonElement metadata = properties.get("metadata");
        if (!metadata.isJsonObject()) {
            return null;
        }
        JsonObject object = metadata.getAsJsonObject();
        for (String key : TITLE_KEYS) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
