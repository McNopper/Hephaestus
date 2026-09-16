package com.opencode.ide.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Codec for the one-task-per-file Markdown store. A task file is:
 *
 * <pre>
 * ---
 * id: T-001
 * title: Fix the thing
 * type: bug
 * status: in-progress
 * ...                     scalars; lists as one-line JSON arrays
 * ---
 *
 * Free-form description (Markdown).
 *
 * ## Todos
 * - [ ] write spec
 * - [x] interview user
 *
 * ## Artifacts
 * {"kind":"file","ref":"src/x","note":"","by":"dev","ts":"..."}
 *
 * ## Comments
 * {"ts":"...","by":"pm","text":"..."}
 *
 * ## History
 * {"ts":"...","action":"created","by":null}
 * </pre>
 *
 * <p>Format rules (deliberately narrow so a hand-rolled parser stays robust):</p>
 * <ul>
 *   <li>Frontmatter values are YAML-ish scalars: parsed as JSON when the raw
 *       text is valid JSON (numbers, booleans, null, quoted strings, one-line
 *       arrays/objects), otherwise taken as a plain string. Writing mirrors
 *       this: strings that would parse as non-string JSON are JSON-quoted,
 *       everything else is written raw (colons in values are fine - the key
 *       splits on the <em>first</em> colon).</li>
 *   <li>The description is everything between the frontmatter and the first
 *       {@code ## } heading. The four managed sections ({@code ## Todos},
 *       {@code ## Artifacts}, {@code ## Comments}, {@code ## History}) are
 *       tool-owned; empty sections are omitted on write.</li>
 *   <li>Unknown frontmatter keys and unknown body sections are preserved
 *       verbatim on rewrite (whole-file rewrite, round-trip stable).</li>
 *   <li>{@code id} must be a single safe path segment ({@link #isValidId}) -
 *       it doubles as the file name, so a hand-edited {@code ../..} id must
 *       never be able to steer a write out of the project directory.</li>
 *   <li>Output pins LF endings and UTF-8 (no BOM); the parser tolerates CRLF
 *       and a leading BOM so hand-edited and git-CRLF-normalized files load.</li>
 *   <li>Record-section payloads (Artifacts/Comments/History) are strict per
 *       line: a malformed line is quarantined ({@link Task#quarantinedLines},
 *       logged with a warning, dropped on rewrite) instead of failing the
 *       whole file - a corrupt line must never make the ticket unparseable
 *       and thus invisible. Frontmatter and Todos stay strict: a file they
 *       break cannot be mapped to an id or safely rewritten at all.</li>
 * </ul>
 */
public final class TaskFileCodec {

    /** Raised when a file does not conform to the format. */
    public static final class FormatException extends RuntimeException {
        FormatException(String message) {
            super(message);
        }
    }

    private static final Pattern TODO_LINE = Pattern.compile("^- \\[([ xX])] (.*)$");
    private static final Pattern FRONTMATTER_LINE = Pattern.compile("^([A-Za-z0-9_]+): ?(.*)$");
    /**
     * Ticket ids double as file names ({@code <id>.md} resolved against the
     * project directory), so they must stay a single safe path segment: no
     * separators, no drive letters, no {@code ..}. Minted ids
     * ({@code <prefix>-<nnn>}) always match.
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Gson GSON = new Gson();
    private static final Logger LOG = Logger.getLogger(TaskFileCodec.class.getName());

    /** The managed body sections, in canonical write order. */
    static final List<String> SECTIONS = List.of("Todos", "Artifacts", "Comments", "History");

    private TaskFileCodec() {
    }

    /**
     * True when {@code id} is usable as a ticket id: a single safe path
     * segment, because the store persists a ticket to {@code <id>.md} inside
     * the project directory. Rejects {@code null}, blanks, separators,
     * {@code ..} and anything else that could escape the project directory.
     */
    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /** Parses one file's content into a {@link Task}. */
    public static Task read(String content) {
        Task t = new Task();
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String[] lines = text.split("\r\n|\n|\r", -1);

        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].equals("---")) {
            throw new FormatException("file must start with a '---' frontmatter fence");
        }
        i++;
        Map<String, String> raw = new LinkedHashMap<>();
        boolean closed = false;
        for (; i < lines.length; i++) {
            if (lines[i].equals("---")) {
                closed = true;
                i++;
                break;
            }
            if (lines[i].isBlank()) {
                continue;
            }
            Matcher m = FRONTMATTER_LINE.matcher(lines[i]);
            if (!m.matches()) {
                throw new FormatException("malformed frontmatter line: " + lines[i]);
            }
            raw.put(m.group(1), m.group(2));
        }
        if (!closed) {
            throw new FormatException("frontmatter not closed with '---'");
        }

        // Body: description until the first heading, then sections.
        List<String> description = new ArrayList<>();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> extraBlocks = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        String current = null;          // managed section name, or null in description
        String extraHeading = null;     // heading line of the unknown section being collected
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                if (extraHeading != null) {
                    extraBlocks.add(flushExtra(extra, extraHeading));
                    extra = new ArrayList<>();
                    extraHeading = null;
                }
                String name = line.substring(3).trim();
                if (SECTIONS.contains(name)) {
                    current = name;
                    sections.putIfAbsent(current, new ArrayList<>());
                } else {
                    current = null;
                    extraHeading = line;
                }
                continue;
            }
            if (extraHeading != null) {
                extra.add(line);
                continue;
            }
            if (current == null) {
                description.add(line);
            } else {
                sections.get(current).add(line);
            }
        }
        if (extraHeading != null) {
            extraBlocks.add(flushExtra(extra, extraHeading));
        }

        applyFrontmatter(t, raw);
        t.description = String.join("\n", description).strip();
        parseTodos(t, sections.getOrDefault("Todos", List.of()));
        parseRecordSection(t, "Artifacts", sections.getOrDefault("Artifacts", List.of()));
        parseRecordSection(t, "Comments", sections.getOrDefault("Comments", List.of()));
        parseRecordSection(t, "History", sections.getOrDefault("History", List.of()));
        t.extraSections = extraBlocks;
        return t;
    }

    private static String flushExtra(List<String> lines, String heading) {
        StringBuilder b = new StringBuilder(heading);
        for (String l : lines) {
            b.append('\n').append(l);
        }
        return b.toString().stripTrailing();
    }

    /** Serializes a task back to file content (LF, UTF-8, round-trip stable). */
    public static String write(Task t) {
        StringBuilder b = new StringBuilder();
        b.append("---\n");
        fm(b, "id", t.id);
        fm(b, "title", t.title);
        fm(b, "type", t.type);
        fm(b, "status", t.status);
        fm(b, "priority", t.priority);
        fm(b, "role", t.role);
        fm(b, "stage", t.stage);
        fm(b, "story_points", t.storyPoints);
        fm(b, "sprint", t.sprint);
        fm(b, "epic", t.epic);
        fm(b, "assignee", t.assignee);
        fm(b, "blocked", t.blocked);
        fm(b, "blocker", t.blocker);
        fm(b, "labels", t.labels);
        fm(b, "acceptance_criteria", t.acceptanceCriteria);
        fm(b, "created_at", Task.formatTs(t.createdAt));
        fm(b, "updated_at", Task.formatTs(t.updatedAt));
        for (Map.Entry<String, String> e : t.extraFrontmatter.entrySet()) {
            b.append(e.getKey()).append(':').append(e.getValue().isEmpty() ? "" : " " + e.getValue()).append('\n');
        }
        b.append("---\n\n");

        String desc = t.description == null ? "" : t.description.stripTrailing();
        if (!desc.isEmpty()) {
            b.append(desc).append("\n\n");
        }
        if (!t.todos.isEmpty()) {
            b.append("## Todos\n");
            for (Task.Todo todo : t.todos) {
                b.append("- [").append(todo.done() ? 'x' : ' ').append("] ").append(todo.text()).append('\n');
            }
            b.append('\n');
        }
        if (!t.artifacts.isEmpty()) {
            b.append("## Artifacts\n");
            for (Task.Artifact a : t.artifacts) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", a.kind());
                o.addProperty("ref", a.ref());
                o.addProperty("note", a.note());
                o.addProperty("by", a.by());
                o.addProperty("ts", Task.formatTs(a.ts()));
                b.append(GSON.toJson(o)).append('\n');
            }
            b.append('\n');
        }
        if (!t.comments.isEmpty()) {
            b.append("## Comments\n");
            for (Task.Comment c : t.comments) {
                JsonObject o = new JsonObject();
                o.addProperty("ts", Task.formatTs(c.ts()));
                o.addProperty("by", c.by());
                o.addProperty("text", c.text());
                b.append(GSON.toJson(o)).append('\n');
            }
            b.append('\n');
        }
        if (!t.history.isEmpty()) {
            b.append("## History\n");
            for (Task.HistoryEvent e : t.history) {
                JsonObject o = new JsonObject();
                o.addProperty("ts", Task.formatTs(e.ts()));
                o.addProperty("action", e.action());
                o.addProperty("by", e.by());
                b.append(GSON.toJson(o)).append('\n');
            }
            b.append('\n');
        }
        for (String extra : t.extraSections) {
            b.append(extra.stripTrailing()).append("\n\n");
        }
        // collapse a possible trailing run of blank lines to a single final newline
        String out = b.toString();
        while (out.endsWith("\n\n")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    // -- parse helpers ------------------------------------------------------

    private static void applyFrontmatter(Task t, Map<String, String> raw) {
        t.id = scalarString(raw.remove("id"));
        if (t.id == null || t.id.isBlank()) {
            throw new FormatException("frontmatter is missing a non-empty 'id'");
        }
        if (!isValidId(t.id)) {
            throw new FormatException("invalid ticket id '" + t.id
                    + "': ids double as file names and must match " + ID_PATTERN.pattern());
        }
        t.title = orEmpty(scalarString(raw.remove("title")));
        t.description = ""; // body-owned
        t.type = orDefault(scalarString(raw.remove("type")), "task");
        t.status = orDefault(scalarString(raw.remove("status")), "product-backlog");
        t.priority = orDefault(scalarString(raw.remove("priority")), "medium");
        t.role = orDefault(scalarString(raw.remove("role")), "developer");
        t.stage = scalarString(raw.remove("stage"));
        Integer points = scalarInt(raw.remove("story_points"));
        t.storyPoints = points == null ? 0 : points;
        t.sprint = scalarString(raw.remove("sprint"));
        t.epic = scalarString(raw.remove("epic"));
        t.assignee = scalarString(raw.remove("assignee"));
        Boolean blocked = scalarBool(raw.remove("blocked"));
        t.blocked = blocked != null && blocked;
        t.blocker = scalarString(raw.remove("blocker"));
        t.labels = stringListValue(raw.remove("labels"));
        t.acceptanceCriteria = stringListValue(raw.remove("acceptance_criteria"));
        String created = scalarString(raw.remove("created_at"));
        String updated = scalarString(raw.remove("updated_at"));
        t.createdAt = parseInstant(created);
        t.updatedAt = parseInstant(updated != null ? updated : created);
        if (t.createdAt == null) {
            t.createdAt = Instant.now();
        }
        if (t.updatedAt == null) {
            t.updatedAt = t.createdAt;
        }
        t.extraFrontmatter = raw; // unknown keys preserved verbatim
    }

    private static void parseTodos(Task t, List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = TODO_LINE.matcher(line);
            if (!m.matches()) {
                throw new FormatException("malformed Todos line: " + line);
            }
            t.todos.add(new Task.Todo(m.group(2), !m.group(1).isBlank()));
        }
    }

    private static void parseRecordSection(Task t, String name, List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (!parsed.isJsonObject()) {
                    throw new JsonSyntaxException("not an object");
                }
                JsonObject o = parsed.getAsJsonObject();
                switch (name) {
                    case "Artifacts" -> t.artifacts.add(new Task.Artifact(
                            str(o, "kind"), str(o, "ref"), str(o, "note"), str(o, "by"), parseInstant(str(o, "ts"))));
                    case "Comments" -> t.comments.add(new Task.Comment(
                            parseInstant(str(o, "ts")), str(o, "by"), orEmpty(str(o, "text"))));
                    case "History" -> t.history.add(new Task.HistoryEvent(
                            parseInstant(str(o, "ts")), str(o, "action"), str(o, "by")));
                    default -> throw new IllegalStateException(name);
                }
            } catch (JsonSyntaxException | FormatException e) {
                LOG.log(Level.WARNING, "quarantined malformed " + name + " line in ticket " + t.id
                        + " (skipped, dropped on next rewrite): " + line, e);
                t.quarantinedLines.add(name + ": " + line);
            }
        }
    }

    // -- scalar coercion ----------------------------------------------------

    /** Parses a frontmatter raw value into a JSON element (string fallback). */
    static JsonElement value(String raw) {
        String v = raw.strip();
        if (!v.isEmpty()) {
            try {
                return JsonParser.parseString(v);
            } catch (JsonSyntaxException ignored) {
                // fall through: plain string
            }
        }
        return new com.google.gson.JsonPrimitive(raw);
    }

    private static String scalarString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement v = value(raw);
        if (v.isJsonNull()) {
            return null;
        }
        if (v.isJsonPrimitive()) {
            return v.getAsString();
        }
        throw new FormatException("expected a scalar value, got: " + raw);
    }

    private static Integer scalarInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement v = value(raw);
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
            return v.getAsInt();
        }
        throw new FormatException("expected an integer, got: " + raw);
    }

    private static Boolean scalarBool(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement v = value(raw);
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
            return v.getAsBoolean();
        }
        throw new FormatException("expected a boolean, got: " + raw);
    }

    private static List<String> stringListValue(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        JsonElement v = value(raw);
        if (v.isJsonNull()) {
            return out;
        }
        if (!v.isJsonArray()) {
            throw new FormatException("expected a JSON array, got: " + raw);
        }
        JsonArray a = v.getAsJsonArray();
        for (JsonElement e : a) {
            if (!e.isJsonPrimitive()) {
                throw new FormatException("array items must be scalars: " + raw);
            }
            out.add(e.getAsString());
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return Instant.parse(s).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        } catch (java.time.format.DateTimeParseException e) {
            // tolerate the Python store's "+00:00" offsets (hand-edits / imports)
            try {
                return java.time.OffsetDateTime.parse(s).toInstant()
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            } catch (java.time.format.DateTimeParseException e2) {
                throw new FormatException("malformed timestamp '" + s + "'");
            }
        }
    }

    // -- write helpers --------------------------------------------------------

    private static void fm(StringBuilder b, String key, String v) {
        b.append(key).append(": ").append(encode(v)).append('\n');
    }

    private static void fm(StringBuilder b, String key, int v) {
        b.append(key).append(": ").append(v).append('\n');
    }

    private static void fm(StringBuilder b, String key, boolean v) {
        b.append(key).append(": ").append(v).append('\n');
    }

    private static void fm(StringBuilder b, String key, List<String> v) {
        b.append(key).append(": ").append(stringList(v)).append('\n');
    }

    private static String stringList(List<String> v) {
        JsonArray a = new JsonArray();
        v.forEach(a::add);
        return GSON.toJson(a);
    }

    /**
     * Encodes a scalar: raw when it can never be mistaken for non-string JSON
     * and has no leading/trailing whitespace or control characters; otherwise a
     * JSON string literal (which {@link #value} parses back to the same string).
     */
    static String encode(String v) {
        if (v == null) {
            return "null";
        }
        boolean raw = true;
        if (v.isEmpty() || Character.isWhitespace(v.charAt(0))
                || Character.isWhitespace(v.charAt(v.length() - 1))) {
            raw = false;
        }
        if (raw) {
            for (char c : v.toCharArray()) {
                if (c < 0x20 || c == '\u007F') {
                    raw = false;
                    break;
                }
            }
        }
        if (raw) {
            try {
                JsonElement parsed = JsonParser.parseString(v);
                raw = parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString();
            } catch (JsonSyntaxException ignored) {
                raw = true; // not JSON at all -> safe raw
            }
        }
        return raw ? v : GSON.toJson(new com.google.gson.JsonPrimitive(v));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orDefault(String s, String dflt) {
        return s == null ? dflt : s;
    }
}
