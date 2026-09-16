package com.opencode.ide.ui.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Live "what is this session doing" state for the Server view: the latest
 * streamed text snippet per session, fed from {@code message.part.delta}
 * SSE events ({@code field} {@code text} or {@code reasoning}, carrying a
 * {@code delta} fragment — the same stream the chat view renders). While a
 * session is busy, the view shows the snippet in the session row's Details
 * column so the row does not just say "busy" but what the agent is writing
 * or thinking right now; subagent sessions are ordinary sessions and are
 * covered by the same map.
 *
 * <p>Shape of the state per session: a rolling <em>tail</em> window of at
 * most {@link #SNIPPET_LENGTH} cleaned characters (control characters
 * stripped, so the snippet is always single-line), optionally prefixed
 * with an ellipsis once the front was trimmed and with {@code "thinking: "}
 * while the current phase streams {@code reasoning} deltas. Deltas append
 * immediately, but the <em>published</em> snippet — the one labels read via
 * {@link #snippet(String)} — is refreshed at most once per publish window
 * per session (default ~2/s), coalescing the fast delta stream into a
 * UI-friendly rate on top of the view's already-coalesced refresh. A phase
 * switch ({@code reasoning} &#x2192; {@code text} or back) resets the
 * window and lifts the throttle so the switch shows promptly.</p>
 *
 * <p>SWT-free and JFace-free on purpose so it is unit-testable without a
 * {@code Display} (see {@code SessionActivityTest} in
 * {@code com.opencode.ide.ui.tests}); unlike {@link SessionBusyPoller} it
 * owns no thread. Threading contract: all methods are called from one
 * thread — the Server view does so on the UI thread, after hopping off the
 * SSE thread exactly like its other event handling. Every method tolerates
 * {@code null} arguments and never throws.</p>
 *
 * <p>Lifecycle: snippets are cleared via {@link #clear(String)} when the
 * owning session goes idle (the SSE idle/completion events and the busy
 * poller's reconciliation both do so), via {@link #retainAll(Set)} on a
 * full view reload, and via {@link #clearAll()} on dispose. Instances hold
 * bounded memory only: one tail window of ≤80 chars per tracked
 * session.</p>
 */
public final class SessionActivity {

    /**
     * Default publish window: 500&nbsp;ms — at most ~2 snippet updates per
     * second per session, matching how fast a human can read the row while
     * staying visibly live.
     */
    public static final long DEFAULT_PUBLISH_WINDOW_MILLIS = 500L;

    /**
     * Maximum length of one session's snippet text (the kept tail of the
     * streamed characters; the {@code thinking: } label and the leading
     * ellipsis are extra).
     */
    public static final int SNIPPET_LENGTH = 80;

    /** Prefix marking a snippet that streams the model's reasoning phase. */
    public static final String THINKING_PREFIX = "thinking: ";

    private final long publishWindowMillis;
    private final LongSupplier clock;
    private final Map<String, State> sessions = new HashMap<>();

    /** Single-thread-confined per-session state (see the class contract). */
    private static final class State {
        String field;                                  // "text" | "reasoning" — the current phase
        final StringBuilder tail = new StringBuilder();   // cleaned chars, ≤ SNIPPET_LENGTH
        boolean trimmed;                               // the front was cut -> prefix an ellipsis
        long lastPublish = Long.MIN_VALUE;             // clock value; MIN_VALUE = never published
        String published;                              // the snippet labels currently see (null = none)
    }

    /** Creates the tracker with the default window and the system clock. */
    public SessionActivity() {
        this(DEFAULT_PUBLISH_WINDOW_MILLIS, System::currentTimeMillis);
    }

    /**
     * Test seam: an explicit publish window and clock (millis, monotonic
     * or wall — only differences matter).
     *
     * @param publishWindowMillis minimum distance between two publishes for
     *                            the same session; must be &gt; 0 (tests use
     *                            a few milliseconds)
     * @param clock               supplies "now" in millis
     * @throws IllegalArgumentException when {@code publishWindowMillis} &lt;= 0
     */
    public SessionActivity(long publishWindowMillis, LongSupplier clock) {
        if (publishWindowMillis <= 0) {
            throw new IllegalArgumentException("publishWindowMillis must be > 0: " + publishWindowMillis);
        }
        this.publishWindowMillis = publishWindowMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Feeds one streamed delta into the session's tail window. Deltas for
     * fields other than {@code text}/{@code reasoning}, and blank ids or
     * deltas, are ignored.
     *
     * @return whether the <em>published</em> snippet changed — the caller
     *         schedules a (coalesced) label refresh then and only then
     */
    public boolean onDelta(String sessionId, String field, String delta) {
        if (sessionId == null || sessionId.isEmpty() || delta == null || delta.isEmpty()) {
            return false;
        }
        if (!"text".equals(field) && !"reasoning".equals(field)) {
            return false;
        }
        State state = sessions.computeIfAbsent(sessionId, id -> new State());
        if (!field.equals(state.field)) {
            // phase switch (reasoning <-> text): the old snippet is stale
            // as a whole — reset the tail and lift the throttle so the new
            // phase replaces it immediately, not one window later
            state.field = field;
            state.tail.setLength(0);
            state.trimmed = false;
            state.lastPublish = Long.MIN_VALUE;
        }
        String cleaned = stripControls(delta);
        if (!cleaned.isEmpty()) {
            state.tail.append(cleaned);
            if (state.tail.length() > SNIPPET_LENGTH) {
                state.tail.delete(0, state.tail.length() - SNIPPET_LENGTH);
                state.trimmed = true;
            }
        }
        long now = clock.getAsLong();
        if (state.lastPublish != Long.MIN_VALUE && now - state.lastPublish < publishWindowMillis) {
            return false;   // coalesced into the previous publish; the next one catches up
        }
        state.lastPublish = now;
        String previous = state.published;
        state.published = render(state);
        return !Objects.equals(previous, state.published);
    }

    /**
     * @return the published snippet for the session ({@code null} while it
     *         never streamed or was cleared) — the reasoning label and
     *         ellipsis included, always a single line
     */
    public String snippet(String sessionId) {
        State state = sessions.get(sessionId);
        return state == null ? null : state.published;
    }

    /** Drops the session's snippet (it went idle / was deleted); null tolerated. */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Drops every snippet (view dispose / reconnect). */
    public void clearAll() {
        sessions.clear();
    }

    /**
     * Drops snippets for session ids <em>not</em> in {@code keep} — full-view
     * reload hygiene, so sessions that vanished without an event (server
     * restart) cannot linger. A {@code null} keep set drops everything.
     */
    public void retainAll(Set<String> keep) {
        sessions.keySet().retainAll(keep == null ? Set.of() : keep);
    }

    /** @return how many sessions currently hold state (diagnostics / tests). */
    public int sessionCount() {
        return sessions.size();
    }

    // ---------- pure helpers ----------

    /** The display form: label + ellipsis + tail, or {@code null} when nothing streamed. */
    private static String render(State state) {
        if (state.tail.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if ("reasoning".equals(state.field)) {
            sb.append(THINKING_PREFIX);
        }
        if (state.trimmed) {
            sb.append('\u2026');   // the visible text starts mid-stream
        }
        return sb.append(state.tail).toString();
    }

    /** Removes control characters (everything below 0x20, plus DEL) — keeps the snippet single-line. */
    private static String stripControls(String delta) {
        StringBuilder sb = new StringBuilder(delta.length());
        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
