package com.opencode.ide.mojo.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opencode.ide.tasks.Task;
import com.opencode.ide.tasks.TaskFileCodec;
import com.opencode.ide.tasks.VStages;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The engine behind {@code opencode-tasks:sync}: lints every project of a
 * store root and, in fix mode, applies the safe normalizations. Checks per
 * project: frontmatter schema (parse, id/filename agreement, valid
 * status/type/priority, valid V-model stage or absent, non-blank role,
 * parseable timestamps), codec round-trip stability and canonical form, LF
 * line endings, {@code _meta.json}
 * counter consistency (per-prefix seq and sprint counter), duplicate ids, and
 * sprint references (warning only).
 *
 * <p>Fixes never delete or rename ticket files and never write outside the
 * project directory: CRLF/drift files are re-encoded through
 * {@link TaskFileCodec} (only when the ticket is otherwise clean), and
 * {@code _meta.json} counters are bumped through a {@link JsonObject} so
 * unknown keys and their order survive. Unfixable findings are reported and
 * left untouched. Read-only when {@code fix} is false.</p>
 */
public final class StoreSync {

    /** ERROR findings are real problems; WARNING findings never fail a build. */
    public enum Severity { ERROR, WARNING }

    /**
     * One finding. Fixable ERROR findings are applied in fix mode and fail the
     * build only in strict mode without fix; unfixable ERROR findings always
     * fail; WARNING findings (sprint references) are informational.
     */
    public record Finding(Severity severity, boolean fixable, String file, String message) {

        static Finding error(String file, String message) {
            return new Finding(Severity.ERROR, false, file, message);
        }

        static Finding fixable(String file, String message) {
            return new Finding(Severity.ERROR, true, file, message);
        }

        static Finding warning(String file, String message) {
            return new Finding(Severity.WARNING, false, file, message);
        }
    }

    /** Per-project outcome: files checked, findings, fixes applied. */
    public record ProjectReport(String project, int filesChecked, List<Finding> findings,
            List<String> appliedFixes) {
    }

    /** The whole run's outcome. */
    public record Result(List<ProjectReport> projects) {

        /** True when at least one finding cannot be fixed automatically. */
        public boolean hasUnfixableErrors() {
            return projects.stream().flatMap(p -> p.findings().stream())
                    .anyMatch(f -> f.severity() == Severity.ERROR && !f.fixable());
        }

        /** True when at least one fixable finding is present (relevant in strict mode). */
        public boolean hasFixableFindings() {
            return projects.stream().flatMap(p -> p.findings().stream())
                    .anyMatch(f -> f.severity() == Severity.ERROR && f.fixable());
        }
    }

    private static final Gson GSON = new Gson();
    private static final Pattern ID_PATTERN = Pattern.compile("^(.+)-(\\d+)$");
    private static final Pattern SPRINT_PATTERN = Pattern.compile("^S-(\\d+)$");

    private final Path root;

    /** @param root the store root (the directory holding the project subdirectories). */
    public StoreSync(Path root) {
        this.root = root;
    }

    /** Validates (and with {@code fix} normalizes) every project directory. */
    public Result run(boolean fix) throws IOException {
        List<ProjectReport> reports = new ArrayList<>();
        for (Path dir : StoreIo.projectDirs(root)) {
            reports.add(syncProject(dir, fix));
        }
        return new Result(reports);
    }

    private ProjectReport syncProject(Path dir, boolean fix) throws IOException {
        List<Path> files = StoreIo.taskFiles(dir);
        List<Finding> findings = new ArrayList<>();
        List<String> applied = new ArrayList<>();

        Path metaFile = dir.resolve("_meta.json");
        JsonObject meta = null;
        boolean metaBroken = false;
        if (Files.isRegularFile(metaFile)) {
            try {
                meta = StoreIo.readMeta(dir);
            } catch (RuntimeException e) {
                metaBroken = true;
                findings.add(Finding.error("_meta.json", "unparseable: " + e.getMessage()));
            }
        } else if (!files.isEmpty()) {
            findings.add(Finding.fixable("_meta.json",
                    "missing _meta.json (id/sprint counters cannot be advanced safely)"));
        }

        Map<String, Integer> maxSeq = new HashMap<>();
        Map<String, String> idToFile = new HashMap<>();
        int maxSprint = 0;
        if (meta != null && meta.get("sprints") != null && meta.get("sprints").isJsonObject()) {
            for (String key : meta.getAsJsonObject("sprints").keySet()) {
                maxSprint = Math.max(maxSprint, sprintNumber(key));
            }
        }

        for (Path file : files) {
            String name = file.getFileName().toString();
            String stem = name.substring(0, name.length() - ".md".length());
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Task task;
            try {
                task = TaskFileCodec.read(raw);
            } catch (RuntimeException e) {
                findings.add(Finding.error(name, "does not parse: " + e.getMessage()));
                bumpMax(maxSeq, stem);
                continue;
            }
            boolean hardError = false;
            bumpMax(maxSeq, task.id);
            if (!stem.equals(task.id)) {
                hardError = true;
                findings.add(Finding.error(name,
                        "frontmatter id '" + task.id + "' does not match file name '" + stem + "'"));
            }
            if (!Task.VALID_STATUSES.contains(task.status)) {
                hardError = true;
                findings.add(Finding.error(name,
                        "invalid status '" + task.status + "' (valid: " + Task.VALID_STATUSES + ")"));
            }
            if (!Task.VALID_TYPES.contains(task.type)) {
                hardError = true;
                findings.add(Finding.error(name,
                        "invalid type '" + task.type + "' (valid: " + Task.VALID_TYPES + ")"));
            }
            if (!Task.PRIORITY_ORDER.containsKey(task.priority)) {
                hardError = true;
                findings.add(Finding.error(name, "invalid priority '" + task.priority
                        + "' (valid: " + Task.PRIORITY_ORDER.keySet() + ")"));
            }
            if (task.stage != null && !VStages.isValid(task.stage)) {
                // unfixable: which stage a hand-edited ticket belongs to is a
                // human decision; only report it (valid set: VStages.STAGES)
                hardError = true;
                findings.add(Finding.error(name, "invalid stage '" + task.stage
                        + "' (valid: " + VStages.STAGES + " or absent)"));
            }
            if (task.role == null || task.role.isBlank()) {
                hardError = true;
                findings.add(Finding.error(name, "role must be a non-empty string"));
            }
            String firstWithId = idToFile.putIfAbsent(task.id, name);
            if (firstWithId != null) {
                hardError = true;
                findings.add(Finding.error(name,
                        "duplicate id '" + task.id + "' (also used by " + firstWithId + ")"));
            }
            String normalized = null;
            try {
                normalized = TaskFileCodec.write(task);
                if (!task.id.equals(TaskFileCodec.read(normalized).id)) {
                    findings.add(Finding.error(name, "codec round-trip changed the id"));
                    normalized = null;
                }
            } catch (RuntimeException e) {
                findings.add(Finding.error(name, "codec round-trip failed: " + e.getMessage()));
            }
            if (!hardError) {
                if (!hasFrontmatterKey(raw, "created_at")) {
                    findings.add(Finding.fixable(name,
                            "missing created_at (re-encode stamps the codec default)"));
                }
                if (!hasFrontmatterKey(raw, "updated_at")) {
                    findings.add(Finding.fixable(name,
                            "missing updated_at (re-encode stamps the codec default)"));
                }
                if (raw.indexOf('\r') >= 0) {
                    findings.add(Finding.fixable(name, "CRLF line endings (store files must be LF)"));
                }
                if (normalized != null && !stripCr(raw).equals(normalized)) {
                    findings.add(Finding.fixable(name, "not in canonical codec form (re-encode)"));
                }
            }
            if (fix && !hardError && normalized != null && !raw.equals(normalized)) {
                StoreIo.writeAtomic(file, normalized);
                applied.add(name + ": re-encoded to canonical LF form");
            }
            if (task.sprint != null) {
                maxSprint = Math.max(maxSprint, sprintNumber(task.sprint));
                if (meta != null && meta.get("sprints") != null && meta.get("sprints").isJsonObject()
                        && !meta.getAsJsonObject("sprints").has(task.sprint)) {
                    findings.add(Finding.warning(name,
                            "sprint '" + task.sprint + "' has no entry in _meta.json sprints"));
                }
            }
        }

        if (meta != null) {
            JsonObject seq = metaObject(meta, "seq");
            for (Map.Entry<String, Integer> e : maxSeq.entrySet()) {
                int current = intOrZero(seq, e.getKey());
                if (current < e.getValue()) {
                    findings.add(Finding.fixable("_meta.json", "seq['" + e.getKey() + "']=" + current
                            + " but the highest existing ticket suffix is " + e.getValue()));
                }
            }
            int counter = intOrZero(meta, "counter");
            if (counter < maxSprint) {
                findings.add(Finding.fixable("_meta.json", "counter=" + counter
                        + " but the highest sprint id is S-" + String.format("%02d", maxSprint)));
            }
        }

        if (fix && !metaBroken) {
            boolean dirty = false;
            JsonObject toWrite = meta;
            if (toWrite == null && !files.isEmpty()) {
                toWrite = new JsonObject();
                toWrite.add("seq", new JsonObject());
                toWrite.addProperty("counter", 0);
                toWrite.add("sprints", new JsonObject());
                dirty = true;
            }
            if (toWrite != null) {
                JsonObject seq = metaObject(toWrite, "seq");
                if (toWrite.get("seq") != seq) {
                    toWrite.add("seq", seq);
                    dirty = true;
                }
                for (Map.Entry<String, Integer> e : maxSeq.entrySet()) {
                    if (intOrZero(seq, e.getKey()) < e.getValue()) {
                        seq.addProperty(e.getKey(), e.getValue());
                        dirty = true;
                    }
                }
                if (intOrZero(toWrite, "counter") < maxSprint) {
                    toWrite.addProperty("counter", maxSprint);
                    dirty = true;
                }
                if (dirty) {
                    StoreIo.writeAtomic(metaFile, GSON.toJson(toWrite));
                    applied.add("_meta.json: counters made consistent");
                }
            }
        }

        return new ProjectReport(dir.getFileName().toString(), files.size(), findings, applied);
    }

    private static JsonObject metaObject(JsonObject meta, String key) {
        return meta.get(key) != null && meta.get(key).isJsonObject()
                ? meta.getAsJsonObject(key)
                : new JsonObject();
    }

    private static int intOrZero(JsonObject o, String key) {
        if (o == null) {
            return 0;
        }
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return 0;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException notAnInt) {
            return 0;
        }
    }

    private static void bumpMax(Map<String, Integer> maxSeq, String id) {
        if (id == null) {
            return;
        }
        Matcher m = ID_PATTERN.matcher(id);
        if (!m.matches()) {
            return;
        }
        maxSeq.merge(m.group(1), Integer.parseInt(m.group(2)), Math::max);
    }

    private static int sprintNumber(String sprintId) {
        if (sprintId == null) {
            return 0;
        }
        Matcher m = SPRINT_PATTERN.matcher(sprintId);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean hasFrontmatterKey(String raw, String key) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        String[] lines = text.split("\r\n|\n|\r", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !lines[i].equals("---")) {
            return false;
        }
        for (i++; i < lines.length; i++) {
            if (lines[i].equals("---")) {
                break;
            }
            if (lines[i].startsWith(key + ":")) {
                return true;
            }
        }
        return false;
    }

    private static String stripCr(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }
}
