package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.opencode.ide.client.model.ShellTask;

/**
 * Rows for the shell tasks of ONE session (U-041): the transcript is the
 * association — every {@code type:"shell"} message in the session's RAW
 * message list carries the {@code shellID} ({@code ^sh_}) of the task that
 * wrote it, plus command, lifecycle, exit and an output tail (the same
 * derivation {@code SessionObserver} uses for fleet observability;
 * {@code SessionObserverTest.shellRunsCarryCommandExitAndOutputTail} pins the
 * wire shape). The live {@code GET /api/shell} list (running tasks only at
 * the 2.0.19 pin) overlays those rows by id so a running task's status is
 * authoritative while it runs.
 *
 * <p>Association honesty: a live task with no shell message in THIS
 * session's transcript is not shown — its {@code Shell.Info.metadata} would
 * carry the originating session id, but the client's {@link ShellTask} record
 * does not map metadata, so we cannot claim it. Unassociated tasks remain
 * the Background view's service-wide surface; this per-session list never
 * guesses.</p>
 *
 * <p>SWT-free so the join is unit-testable; the view only renders.</p>
 */
public final class SessionShells {

    /** Kept tail of a task's output (characters, like SessionObserver's TAIL). */
    public static final int OUTPUT_TAIL = 200;

    /**
     * @param messages  the session's RAW message list ({@code getMessagesJson};
     *                  newest-first on the wire, may be {@code null})
     * @param liveTasks {@code GET /api/shell} (running tasks only; may be
     *                  {@code null})
     * @return rows in transcript order (newest first) with running tasks
     *         floated to the top — live activity is what the section is for
     */
    public static List<Row> rows(JsonArray messages, List<ShellTask> liveTasks) {
        List<Row> rows = new ArrayList<>();
        Map<String, ShellTask> live = byId(liveTasks);
        if (messages != null) {
            for (JsonElement element : messages) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                if (!"shell".equals(string(message, "type"))) {
                    continue;
                }
                String shellId = string(message, "shellID");
                String command = string(message, "command");
                if (shellId == null || command == null) {
                    continue; // not associable: no id to act on, nothing to run
                }
                String status = string(message, "status");
                Integer exit = number(message, "exit");
                if (exit == null) {
                    exit = number(object(message, "metadata"), "exit");
                }
                String output = tail(outputOf(message));
                ShellTask overlay = live.remove(shellId);
                boolean running = overlay != null && overlay.isRunning();
                rows.add(new Row(shellId, command,
                        running ? overlay.status() : status == null ? "unknown" : status,
                        exit, output, overlay != null));
            }
        }
        // live tasks without a transcript row are NOT this session's (see class
        // doc) - leftovers in `live` are deliberately dropped
        rows.sort((a, b) -> Boolean.compare(b.isRunning(), a.isRunning()));
        return List.copyOf(rows);
    }

    private static Map<String, ShellTask> byId(List<ShellTask> liveTasks) {
        Map<String, ShellTask> byId = new HashMap<>();
        if (liveTasks != null) {
            for (ShellTask task : liveTasks) {
                if (task != null && task.id() != null) {
                    byId.put(task.id(), task);
                }
            }
        }
        return byId;
    }

    private static String outputOf(JsonObject message) {
        JsonObject output = object(message, "output");
        return output == null ? null : string(output, "output");
    }

    /** The kept tail: last {@link #OUTPUT_TAIL} characters, stripped. */
    private static String tail(String output) {
        if (output == null) {
            return null;
        }
        String stripped = output.strip();
        return stripped.length() <= OUTPUT_TAIL ? stripped : stripped.substring(stripped.length() - OUTPUT_TAIL);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static Integer number(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return object.get(key).getAsInt();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    /**
     * One shell task of the session: the {@code sh_} id (the handle for
     * output/read and remove), the command line, lifecycle, exit code, the
     * transcript's output tail and whether the task is still in the server's
     * live list.
     */
    public record Row(String shellId, String command, String status, Integer exit,
            String outputTail, boolean live) {

        /** True while the wire status is {@code running}. */
        public boolean isRunning() {
            return "running".equals(status);
        }

        /** {@code status  •  exit N} with the exit only when known (the Details column). */
        public String detailLabel() {
            StringBuilder sb = new StringBuilder(status == null ? "unknown" : status);
            if (exit != null) {
                sb.append("  \u2022  exit ").append(exit.intValue());
            }
            if (live) {
                sb.append("  \u2022  live");
            }
            return sb.toString();
        }
    }
}
