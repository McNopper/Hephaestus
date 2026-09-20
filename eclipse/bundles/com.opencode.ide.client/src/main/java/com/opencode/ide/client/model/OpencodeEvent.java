package com.opencode.ide.client.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One event from the opencode server's {@code /api/event} SSE stream. The
 * frame shape is {@code {"id", "created", "type", "location", "data"}} and the
 * event payload lives under {@code data}. Relevant types include
 * {@code session.created}, {@code session.deleted}, {@code session.status},
 * {@code session.idle}, {@code session.text.delta},
 * {@code session.reasoning.delta}, {@code session.tool.called},
 * {@code permission.asked} and {@code permission.replied}.
 *
 * <p>v2 serves a single stream for every directory, so {@link #directory} (from
 * the frame's {@code location.directory}) is what scopes an event to a project
 * or worktree — the v1 per-project {@code /event} endpoint is gone.</p>
 *
 * <p>JSON navigation helpers ({@link #string}, {@link #at}, {@link #as}) keep
 * Gson use inside core so UI consumers never depend on Gson directly.</p>
 */
public record OpencodeEvent(String type, JsonObject properties, String directory) {

    private static final Gson GSON = new Gson();

    /** An event with no location (test fixtures and v1-shaped callers). */
    public OpencodeEvent(String type, JsonObject properties) {
        this(type, properties, null);
    }

    /** @return the named top-level property as a string, or {@code null} if absent/not a string. */
    public String string(String key) {
        if (properties == null || !properties.has(key)) {
            return null;
        }
        JsonElement el = properties.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    /** @return the string at a dot path (e.g. {@code "part.state.status"}), or {@code null}. */
    public String at(String path) {
        if (properties == null || path == null) {
            return null;
        }
        JsonElement current = properties;
        for (String key : path.split("\\.")) {
            if (current == null || !current.isJsonObject() || !current.getAsJsonObject().has(key)) {
                return null;
            }
            current = current.getAsJsonObject().get(key);
        }
        return current.isJsonPrimitive() ? current.getAsString() : null;
    }

    /** Deserialize a top-level property into the given type (e.g. {@code info -> Session}). */
    public <T> T as(String key, Class<T> type) {
        if (properties == null || !properties.has(key)) {
            return null;
        }
        try {
            return GSON.fromJson(properties.get(key), type);
        } catch (Exception e) {
            return null;
        }
    }
}
