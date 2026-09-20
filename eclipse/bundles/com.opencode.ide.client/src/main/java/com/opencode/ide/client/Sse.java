package com.opencode.ide.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Pure helpers for the opencode server-sent events wire format (no I/O, no
 * threading) - extracted so they can be unit-tested directly.
 *
 * <p>SSE frames are lines beginning with {@code data:}; one JSON event per
 * blank-line-separated block (multi-line {@code data:} blocks are joined with
 * newlines, per the SSE spec).</p>
 */
public final class Sse {

    private Sse() {
    }

    /**
     * Accumulate {@code data:} lines, emitting one JSON string per event to {@code jsonSink}.
     * A blank line terminates an event; end-of-input also terminates any pending
     * buffered event (per the SSE spec, EOF dispatches the event one final time).
     */
    public static void parseFrames(Iterator<String> lines, Consumer<String> jsonSink) {
        if (lines == null || jsonSink == null) {
            return;
        }
        StringBuilder buffer = new StringBuilder();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.startsWith("data:")) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(line.substring(5).stripLeading());
            } else if (line.isBlank()) {
                if (buffer.length() > 0) {
                    jsonSink.accept(buffer.toString());
                    buffer.setLength(0);
                }
            }
        }
        if (buffer.length() > 0) {
            jsonSink.accept(buffer.toString());
        }
    }

    /** Convenience: collect all JSON frames from a line iterator. */
    public static List<String> frames(Iterator<String> lines) {
        List<String> out = new ArrayList<>();
        parseFrames(lines, out::add);
        return out;
    }

    /**
     * Parse one SSE JSON frame into an {@link OpencodeEvent}. Returns {@code null}
     * for malformed JSON (pure - does not log).
     *
     * <p>v2 frames are {@code {id, created, type, location, data, durable}}: the
     * event payload is {@code data} and {@code location.directory} scopes the
     * event, because v2 serves one stream for every directory.</p>
     */
    public static OpencodeEvent parseEvent(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : null;
            JsonObject properties = (object.has("data") && object.get("data").isJsonObject())
                    ? object.getAsJsonObject("data")
                    : new JsonObject();
            return new OpencodeEvent(type, properties, directoryOf(object));
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code location.directory} of a v2 frame, or {@code null} when absent. */
    private static String directoryOf(JsonObject frame) {
        if (!frame.has("location") || !frame.get("location").isJsonObject()) {
            return null;
        }
        JsonObject location = frame.getAsJsonObject("location");
        return (location.has("directory") && location.get("directory").isJsonPrimitive())
                ? location.get("directory").getAsString()
                : null;
    }

    /** Parse a raw SSE text blob into events (skipping malformed frames). */
    public static List<OpencodeEvent> events(String sseText) {
        List<OpencodeEvent> events = new ArrayList<>();
        if (sseText == null) {
            return events;
        }
        Iterator<String> lines = java.util.Arrays.asList(sseText.split("\n", -1)).iterator();
        for (String json : frames(lines)) {
            OpencodeEvent event = parseEvent(json);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }
}
