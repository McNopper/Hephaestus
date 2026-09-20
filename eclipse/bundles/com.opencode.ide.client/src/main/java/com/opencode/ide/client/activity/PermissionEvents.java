package com.opencode.ide.client.activity;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Parses permission-related {@code /event} SSE events into
 * {@link PermissionRequest}s. Shape-tolerant: unknown types yield
 * {@code null}, missing or non-string fields are read leniently, and parsing
 * never throws — a malformed event simply produces {@code null}.
 *
 * <p><b>Event contract (v2, verified against a live opencode 2.0.10 server):</b></p>
 * <ul>
 *   <li>{@code permission.asked} — data
 *   {@code { sessionID, action, resources: string[], save?, metadata,
 *   source: { type, messageID, id } }}: a new request is pending. The
 *   permission id used in the answer endpoint lives at {@code source.id}
 *   (v1 had a top-level {@code id}); {@code action} is the category (e.g.
 *   {@code "bash"}, v1 called it {@code permission}); {@code resources} are
 *   the patterns (v1 called them {@code patterns}). {@code metadata} is an
 *   open record whose string values carry display hints like the command.</li>
 *   <li>{@code permission.replied} — data
 *   {@code { sessionID, requestID, reply: once|always|reject }}: the request
 *   was answered (possibly by another client — e.g. an attached TUI).
 *   Unchanged from v1.</li>
 * </ul>
 *
 * <p>Older/other builds reportedly emit a {@code permission.updated}-style
 * event instead; since that shape could not be verified against the server
 * this harness targets, it is intentionally not parsed here. Adding it later
 * only means extending {@link #parse} — the queue
 * ({@code com.opencode.ide.fleet.PermissionQueue}) is keyed by permission id
 * and tolerant of any field gaps.</p>
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
        String sessionId = first(event.string("sessionID"), event.string("sessionId"));
        // v2 keeps the permission id at source.id; v1 had a top-level id
        String permissionId = first(event.at("source.id"), event.string("id"),
                event.string("permissionID"), event.string("permissionId"));
        if (sessionId == null || permissionId == null) {
            return null;
        }
        // v2 renamed the fields: action (was permission), resources (was patterns)
        String category = first(event.string("action"), event.string("permission"));
        List<String> patterns = strings(event, "resources");
        if (patterns.isEmpty()) {
            patterns = strings(event, "patterns");
        }
        return new PermissionRequest(sessionId, permissionId, category, patterns,
                metadataTitle(event), PermissionRequest.Status.PENDING);
    }

    private static PermissionRequest parseReplied(OpencodeEvent event) {
        String sessionId = first(event.string("sessionID"), event.string("sessionId"));
        String permissionId = first(event.string("requestID"), event.string("permissionID"),
                event.string("id"));
        if (sessionId == null || permissionId == null) {
            return null;
        }
        return new PermissionRequest(sessionId, permissionId, null, List.of(), null,
                PermissionRequest.Status.ANSWERED);
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

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
