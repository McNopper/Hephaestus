package com.opencode.ide.tasks;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * One task (historically: ticket) of the {@link TaskStore}. A mutable POJO whose
 * serialized JSON shape mirrors the retired Python {@code pm} MCP server
 * field-for-field (snake_case, same key order) so agent-side skills and prose
 * that read tool results keep working unchanged.
 *
 * <p>Instances are only mutated inside {@link TaskStore} transactions; every
 * mutation bumps {@code updated_at} and (where the pm server did so) appends a
 * history event.</p>
 */
public final class Task {

    /** Task types accepted by create/update (mirrors the pm server's VALID_TYPES). */
    public static final List<String> VALID_TYPES = List.of("story", "task", "bug", "spike");

    /** The status machine. Update is deliberately lax (any of these, no transition graph), as in the pm server. */
    public static final List<String> VALID_STATUSES = List.of(
            "product-backlog", "sprint-backlog", "in-progress", "in-review", "done");

    /** Priority weight for claim/backlog ordering (higher = first). */
    public static final Map<String, Integer> PRIORITY_ORDER = Map.of(
            "low", 0, "medium", 1, "high", 2, "critical", 3);

    /** Definition-side roles (traceability pairs these with VERIFICATION_ROLES). */
    public static final Set<String> DEFINITION_ROLES = Set.of("architect", "developer");

    /** Verification-side roles. */
    public static final Set<String> VERIFICATION_ROLES = Set.of("tester");

    /**
     * Pinned timestamp format: UTC, millisecond precision, always {@code Z}.
     * Lexicographic order equals chronological order, which the claim/backlog
     * sorts rely on when files are merged from mixed writers.
     */
    public static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** A checklist item. */
    public record Todo(String text, boolean done) {
    }

    /** The hand-off locator: where a produced artifact lives. */
    public record Artifact(String kind, String ref, String note, String by, Instant ts) {
    }

    /** A human/agent note on a task. */
    public record Comment(Instant ts, String by, String text) {
    }

    /** One append-only history event. */
    public record HistoryEvent(Instant ts, String action, String by) {
    }

    /** A sprint (metadata sidecar; the board itself is derived from the tasks). */
    public record Sprint(String id, String goal, String status, Instant createdAt, Instant closedAt) {

        /** Serializes with the pm server's sprint field names and order. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("goal", goal);
            o.addProperty("status", status);
            o.addProperty("created_at", formatTs(createdAt));
            o.addProperty("closed_at", closedAt == null ? null : formatTs(closedAt));
            return o;
        }
    }

    public String id;
    public String title = "";
    public String description = "";
    public String type = "task";
    public String status = "product-backlog";
    public boolean blocked;
    public String blocker;
    public String sprint;
    public int storyPoints;
    public String priority = "medium";
    public String role = "developer";
    /** V-model pipeline stage (see {@link VStages}); {@code null} = legacy/untracked ticket. */
    public String stage;
    public String assignee;
    public String epic;
    public List<String> labels = new ArrayList<>();
    public List<String> acceptanceCriteria = new ArrayList<>();
    public List<Todo> todos = new ArrayList<>();
    public List<Artifact> artifacts = new ArrayList<>();
    public List<Comment> comments = new ArrayList<>();
    public List<HistoryEvent> history = new ArrayList<>();
    public Instant createdAt;
    public Instant updatedAt;

    /** Unknown frontmatter keys, preserved verbatim across rewrite round-trips. */
    public Map<String, String> extraFrontmatter = new LinkedHashMap<>();

    /** Unknown {@code ## X} body sections, preserved verbatim (raw lines incl. heading). */
    public List<String> extraSections = new ArrayList<>();

    /**
     * Malformed record-section lines (Artifacts/Comments/History) quarantined
     * at parse time, as {@code "<Section>: <raw line>"}. Never written back:
     * the next store rewrite of the ticket drops them (the repair). Surfaced
     * in {@link #toJson} only while non-empty so a corrupt line never hides
     * the ticket and remains visible for hand repair.
     */
    public List<String> quarantinedLines = new ArrayList<>();

    /** Formats an instant with the pinned store format; null-safe. */
    public static String formatTs(Instant t) {
        return t == null ? null : TS_FORMAT.format(t);
    }

    /** Appends a history event with the current time (millisecond precision, like the store). */
    public void history(String action, String by) {
        history.add(new HistoryEvent(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), action, by));
    }

    /** Serializes with the pm server's ticket field names and order (nulls included). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("description", description);
        o.addProperty("type", type);
        o.addProperty("status", status);
        o.addProperty("blocked", blocked);
        o.addProperty("blocker", blocker);
        o.addProperty("sprint", sprint);
        o.addProperty("story_points", storyPoints);
        o.addProperty("priority", priority);
        o.addProperty("role", role);
        o.addProperty("stage", stage);
        o.addProperty("assignee", assignee);
        JsonArray ac = new JsonArray();
        acceptanceCriteria.forEach(ac::add);
        o.add("acceptance_criteria", ac);
        JsonArray lb = new JsonArray();
        labels.forEach(lb::add);
        o.add("labels", lb);
        o.addProperty("epic", epic);
        o.add("artifacts", artifactsJson());
        o.add("todos", todosJson());
        o.addProperty("created_at", formatTs(createdAt));
        o.addProperty("updated_at", formatTs(updatedAt));
        o.add("history", historyJson());
        o.add("comments", commentsJson());
        if (!quarantinedLines.isEmpty()) {
            JsonArray q = new JsonArray();
            quarantinedLines.forEach(q::add);
            o.add("quarantined", q);
        }
        return o;
    }

    private JsonArray artifactsJson() {
        JsonArray a = new JsonArray();
        for (Artifact art : artifacts) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", art.kind());
            o.addProperty("ref", art.ref());
            o.addProperty("note", art.note());
            o.addProperty("by", art.by());
            o.addProperty("ts", formatTs(art.ts()));
            a.add(o);
        }
        return a;
    }

    private JsonArray todosJson() {
        JsonArray a = new JsonArray();
        for (Todo todo : todos) {
            JsonObject o = new JsonObject();
            o.addProperty("text", todo.text());
            o.addProperty("done", todo.done());
            a.add(o);
        }
        return a;
    }

    private JsonArray historyJson() {
        JsonArray a = new JsonArray();
        for (HistoryEvent e : history) {
            JsonObject o = new JsonObject();
            o.addProperty("ts", formatTs(e.ts()));
            o.addProperty("action", e.action());
            o.add("by", e.by() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(e.by()));
            a.add(o);
        }
        return a;
    }

    private JsonArray commentsJson() {
        JsonArray a = new JsonArray();
        for (Comment c : comments) {
            JsonObject o = new JsonObject();
            o.addProperty("ts", formatTs(c.ts()));
            o.addProperty("by", c.by());
            o.addProperty("text", c.text());
            a.add(o);
        }
        return a;
    }
}
