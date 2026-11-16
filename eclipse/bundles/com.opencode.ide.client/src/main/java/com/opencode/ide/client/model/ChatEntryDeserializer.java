package com.opencode.ide.client.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a v2 message into the client's {@link ChatEntry} shape — the single place that knows the
 * wire shape, shared by the HTTP client and the parsing tests.
 *
 * <p>v2 flattened the v1 {@code {info, parts}} envelope: a message is {@code {id, time, type, ...}}
 * where {@code type} is the role. User messages carry their prompt in a flat {@code text} field;
 * assistant messages carry {@code content[]} parts plus {@code agent}, {@code model}, {@code cost},
 * {@code tokens}, {@code finish} and {@code time.completed}. Non-conversational kinds (skill,
 * shell, idle, compaction, switches) are reduced to a text part so the transcript stays readable.
 */
public final class ChatEntryDeserializer implements JsonDeserializer<ChatEntry> {

  @Override
  public ChatEntry deserialize(JsonElement element, Type type, JsonDeserializationContext ctx) {
    if (element == null || !element.isJsonObject()) {
      return null;
    }
    JsonObject msg = element.getAsJsonObject();
    JsonObject time =
        msg.has("time") && msg.get("time").isJsonObject()
            ? msg.getAsJsonObject("time")
            : new JsonObject();
    long created = number(time, "created");
    long completed = number(time, "completed");

    String providerId = null;
    String modelId = null;
    String variant = null;
    if (msg.has("model") && msg.get("model").isJsonObject()) {
      JsonObject model = msg.getAsJsonObject("model");
      providerId = string(model, "providerID");
      modelId = string(model, "id");
      variant = string(model, "variant");
    }

    String role = string(msg, "type");
    ChatMessageInfo info =
        new ChatMessageInfo(
            string(msg, "id"),
            string(msg, "sessionID"),
            role,
            new Session.Time(created, completed > 0 ? completed : created, 0),
            string(msg, "agent"),
            string(msg, "mode"),
            // terminal markers carry their result as "outcome", not "finish";
            // shell rows carry their lifecycle in "status" (running/exited/...)
            string(msg, "finish") != null
                ? string(msg, "finish")
                : string(msg, "outcome") != null
                    ? string(msg, "outcome")
                    : ("shell".equals(role) ? string(msg, "status") : null),
            msg.has("cost") && msg.get("cost").isJsonPrimitive()
                ? msg.get("cost").getAsDouble()
                : null,
            msg.has("tokens") ? ctx.deserialize(msg.get("tokens"), Session.Tokens.class) : null,
            providerId,
            modelId,
            variant,
            null,
            completed);

    // shell runs carry their command and captured output tail on the message
    // (chat parity CP-02) - the transcript renders the run as its own strip
    boolean shell = "shell".equals(role);
    String shellCommand = shell ? string(msg, "command") : null;
    String shellOutput = null;
    if (shell && msg.has("output") && msg.get("output").isJsonObject()) {
      shellOutput = string(msg.getAsJsonObject("output"), "output");
    }

    return new ChatEntry(info, parts(msg, ctx), shellCommand, shellOutput);
  }

  /** v2 parts: {@code content[]} for assistants, a flat {@code text} for everyone else. */
  private static List<ChatPart> parts(JsonObject msg, JsonDeserializationContext ctx) {
    List<ChatPart> parts = new ArrayList<>();
    if (msg.has("content") && msg.get("content").isJsonArray()) {
      for (JsonElement part : msg.getAsJsonArray("content")) {
        ChatPart parsed = ctx.deserialize(part, ChatPart.class);
        if (parsed != null) {
          parts.add(parsed);
        }
      }
      return parts;
    }
    if ("compaction".equals(string(msg, "type"))) {
      // T-145: a compaction row carries summary/status/reason - neither content[]
      // nor text - so without this reduction the transcript renders an empty
      // assistant bubble and /compact leaves no visible trace (TUI parity).
      parts.add(new ChatPart("text", compactionMarker(msg), null, null));
      return parts;
    }
    String text = string(msg, "text");
    if (text != null) {
      parts.add(new ChatPart("text", text, null, null));
    }
    return parts;
  }

  /** T-145: readable transcript marker for a compaction row (completed / failed / other). */
  private static String compactionMarker(JsonObject msg) {
    String status = string(msg, "status");
    if ("failed".equals(status)) {
      JsonObject error =
          msg.has("error") && msg.get("error").isJsonObject() ? msg.getAsJsonObject("error") : null;
      String type = error != null ? string(error, "type") : null;
      String message = error != null ? string(error, "message") : null;
      return "Context compaction failed: "
          + (type == null || type.isBlank() ? "unknown error" : type)
          + (message == null || message.isBlank() ? "" : " - " + message);
    }
    if ("completed".equals(status)) {
      String reason = string(msg, "reason");
      return (reason == null || reason.isBlank()
              ? "Context compacted"
              : "Context compacted (" + reason + ")")
          + " - the earlier history now stands as a summary; the next reply runs with a fresh"
          + " window.";
    }
    return "Context compaction " + (status == null || status.isBlank() ? "started" : status) + "…";
  }

  private static String string(JsonObject object, String key) {
    return (object.has(key) && object.get(key).isJsonPrimitive())
        ? object.get(key).getAsString()
        : null;
  }

  private static long number(JsonObject object, String key) {
    return (object.has(key) && object.get(key).isJsonPrimitive())
        ? object.get(key).getAsLong()
        : 0L;
  }
}
