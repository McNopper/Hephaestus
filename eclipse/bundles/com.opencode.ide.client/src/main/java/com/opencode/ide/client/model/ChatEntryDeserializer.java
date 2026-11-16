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
 * shell, idle, switches) are reduced to a text part so the transcript stays readable; {@code
 * compaction} rows keep their own {@link ChatEntry.Compaction} payload (T-147) - the TUI renders
 * them as a divider row over the full summary, never as plain text.
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

    // T-147: compaction rows keep their payload (status/summary/error/native) -
    // neither content[] nor text - rendered as the TUI's CompactionMessage row.
    ChatEntry.Compaction compaction = "compaction".equals(role) ? compaction(msg) : null;

    // T-149: synthetic rows keep their notice payload (source/goalID/description) - the injected
    // objective prompt (msg.text) is NEVER a part: the TUI renders only the description as an
    // inline notice row (routes/session/index.tsx SessionNoticeMessage), never an assistant bubble.
    ChatEntry.Notice notice = "synthetic".equals(role) ? notice(msg) : null;
    List<ChatPart> parts = "synthetic".equals(role) ? List.of() : parts(msg, ctx);

    return new ChatEntry(info, parts, shellCommand, shellOutput, compaction, notice);
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
    String text = string(msg, "text");
    if (text != null) {
      parts.add(new ChatPart("text", text, null, null));
    }
    return parts;
  }

  /**
   * The T-147 compaction payload: {@code status/reason/summary} on completed+running rows, {@code
   * error{type,message}} on failed rows, {@code native.messages} marking a provider compaction (the
   * TUI's "Provider compaction" label). No text part is fabricated - the row renders from this.
   */
  private static ChatEntry.Compaction compaction(JsonObject msg) {
    JsonObject error =
        msg.has("error") && msg.get("error").isJsonObject() ? msg.getAsJsonObject("error") : null;
    boolean providerNative =
        msg.has("native")
            && msg.get("native").isJsonObject()
            && msg.getAsJsonObject("native").has("messages")
            && !msg.getAsJsonObject("native").get("messages").isJsonNull();
    return new ChatEntry.Compaction(
        string(msg, "status"),
        string(msg, "summary"),
        error == null ? null : string(error, "type"),
        error == null ? null : string(error, "message"),
        providerNative);
  }

  /**
   * The T-149 notice payload: {@code metadata.source} (the {@code goal} source picks the TUI's
   * &#9678; glyph), {@code metadata.goalID} (live-append dedupe key) and {@code description} (the
   * row's display text, e.g. "Goal: Fix the build"). Blank/absent description keeps the row
   * displayable-but-empty - historyRows drops it exactly like the TUI does ({@code rows.ts:392}).
   */
  private static ChatEntry.Notice notice(JsonObject msg) {
    JsonObject metadata =
        msg.has("metadata") && msg.get("metadata").isJsonObject()
            ? msg.getAsJsonObject("metadata")
            : null;
    return new ChatEntry.Notice(
        metadata == null ? null : string(metadata, "source"),
        metadata == null ? null : string(metadata, "goalID"),
        string(msg, "description"));
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
