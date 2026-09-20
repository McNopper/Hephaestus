package com.opencode.ide.client.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Derives live fleet activity from the opencode {@code /event} SSE stream:
 * per-session running/thinking state with tool invocations, and the set of
 * files agents currently work on. Feed every event through {@link #apply};
 * read state via {@link #snapshot()}; observe changes via {@link #addListener}.
 *
 * <p>Thread-safe; listeners run on the calling thread of {@link #apply}.</p>
 */
public final class ActivityTracker {

    private static final class MutableSession {
        volatile boolean running;
        volatile boolean thinking;
        final Map<String, ToolActivity> tools = new ConcurrentHashMap<>();
    }

    private final Map<String, MutableSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FileActivity> files = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Registers a change notification (runs on whichever thread applies events). */
    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Feeds one event; updates derived state and notifies listeners on change. */
    public void apply(OpencodeEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        boolean changed = false;
        switch (event.type()) {
            case "session.status" -> changed = applyStatus(event);
            case "session.idle" -> changed = applyIdle(event);
            case "session.deleted" -> changed = applyDeleted(event);
            // v2 split v1's message.part.updated (one event with a part.type
            // discriminator) into one event per channel:
            case "session.reasoning.started" -> changed = applyThinking(event, true);
            case "session.reasoning.ended", "session.text.started" -> changed = applyThinking(event, false);
            case "session.tool.called", "session.tool.input.started" ->
                changed = applyTool(event, ToolActivity.State.RUNNING);
            case "session.tool.success" -> changed = applyTool(event, ToolActivity.State.COMPLETED);
            case "session.tool.failed" -> changed = applyTool(event, ToolActivity.State.ERROR);
            default -> {
            }
        }
        if (changed) {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
    }

    private boolean applyStatus(OpencodeEvent event) {
        String sessionId = event.string("sessionID");
        if (sessionId == null) {
            return false;
        }
        // v2 nests the status as {"status": {"type": "busy"|"idle"|"retry"}}; the
        // flat-string fallback tolerates older captures
        String status = firstNonNull(event.string("status"), event.at("status.type"));
        boolean running = "busy".equals(status) || "retry".equals(status);
        MutableSession session = sessions.get(sessionId);
        if (session == null) {
            if (!running) {
                return false;
            }
            MutableSession created = new MutableSession();
            created.running = true;
            sessions.put(sessionId, created);
            return true;
        }
        if (session.running != running) {
            session.running = running;
            return true;
        }
        return false;
    }

    private boolean applyIdle(OpencodeEvent event) {
        String sessionId = event.string("sessionID");
        if (sessionId == null) {
            return false;
        }
        MutableSession session = sessions.get(sessionId);
        if (session != null && session.running) {
            session.running = false;
            return true;
        }
        return false;
    }

    private boolean applyDeleted(OpencodeEvent event) {
        String sessionId = event.string("sessionID");
        if (sessionId == null || sessions.remove(sessionId) == null) {
            return false;
        }
        files.values().removeIf(f -> sessionId.equals(f.sessionId()));
        return true;
    }

    private boolean applyThinking(OpencodeEvent event, boolean thinking) {
        String sessionId = event.string("sessionID");
        if (sessionId == null) {
            return false;
        }
        MutableSession session = sessions.get(sessionId);
        if (session == null) {
            if (!thinking) {
                return false;
            }
            session = sessions.computeIfAbsent(sessionId, id -> new MutableSession());
        }
        if (session.thinking != thinking) {
            session.thinking = thinking;
            return true;
        }
        return false;
    }

    private boolean applyTool(OpencodeEvent event, ToolActivity.State state) {
        String sessionId = event.string("sessionID");
        if (sessionId == null) {
            return false;
        }
        // v2 fields (flat, not nested under "part"): the per-invocation id on
        // every tool event, the tool name on tool.input.started / tool.called
        String invocation = event.string("id");
        String tool = event.string("name");
        String file = firstNonNull(event.at("input.filePath"), event.at("input.path"),
                event.at("input.file"), event.at("input.absolutePath"));
        if (invocation == null && tool == null && file == null) {
            return false;
        }
        String key = invocation != null ? invocation : (tool + "|" + file);
        MutableSession session = sessions.computeIfAbsent(sessionId, id -> new MutableSession());
        // v2 completion events (tool.success / tool.failed) repeat neither the
        // name nor the input - inherit them from the tracked invocation, or
        // completed tools would never release their file
        ToolActivity previous = session.tools.get(key);
        if (tool == null && previous != null) {
            tool = previous.tool();
        }
        if (file == null && previous != null) {
            file = previous.file();
        }
        ToolActivity activity = new ToolActivity(tool, file, state);
        boolean changed = false;
        ToolActivity replaced = session.tools.put(key, activity);
        if (replaced == null || !replaced.equals(activity)) {
            changed = true;
        }
        if (file != null) {
            if (state == ToolActivity.State.RUNNING) {
                FileActivity entry = new FileActivity(sessionId, tool, file);
                FileActivity previousEntry = files.put(file, entry);
                if (previousEntry == null || !previousEntry.equals(entry)) {
                    changed = true;
                }
            } else if (files.remove(file) != null) {
                changed = true;
            }
        }
        if (session.thinking) {
            session.thinking = false;
            changed = true;
        }
        return changed;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Drops all derived state for a session (e.g. after it ended). */
    public void sessionEnded(String sessionId) {
        if (sessionId == null) {
            return;
        }
        if (sessions.remove(sessionId) != null) {
            files.values().removeIf(f -> sessionId.equals(f.sessionId()));
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
    }

    /** @return an immutable copy of the current derived activity. */
    public ActivitySnapshot snapshot() {
        Map<String, SessionActivity> copy = new java.util.LinkedHashMap<>();
        sessions.forEach((id, session) -> copy.put(id,
                new SessionActivity(id, session.running, session.thinking,
                        new ArrayList<>(session.tools.values()))));
        return new ActivitySnapshot(copy, new java.util.LinkedHashMap<>(files));
    }
}
