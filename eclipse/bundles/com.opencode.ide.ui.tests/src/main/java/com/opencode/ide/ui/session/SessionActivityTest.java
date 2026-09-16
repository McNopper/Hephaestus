package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.function.LongSupplier;

import org.junit.Test;

/**
 * Unit tests for {@link SessionActivity}: the SWT-free per-session snippet
 * state behind the Server view's live "what is it doing" column. No SWT, no
 * Display — the publish throttle is driven by an injected mutable clock, so
 * every timing case is deterministic.
 */
public class SessionActivityTest {

    /** Fast enough to exercise the throttle without slowing the suite. */
    private static final long WINDOW_MILLIS = 500L;

    /** Deterministic clock the tests advance by hand. */
    private static final class MutableClock implements LongSupplier {
        long now = 1_000L;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private SessionActivity tracker() {
        return new SessionActivity(WINDOW_MILLIS, clock);
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }

    // ---------- appending / publishing ----------

    @Test
    public void textDeltasBuildAndPublishTheSnippet() {
        SessionActivity activity = tracker();

        assertTrue(activity.onDelta("s1", "text", "Fixing"));
        assertEquals("Fixing", activity.snippet("s1"));

        clock.now += WINDOW_MILLIS;   // next window: coalesced deltas catch up
        assertTrue(activity.onDelta("s1", "text", " the build"));
        assertEquals("Fixing the build", activity.snippet("s1"));
    }

    @Test
    public void reasoningDeltasAreLabeledThinking() {
        SessionActivity activity = tracker();

        assertTrue(activity.onDelta("s1", "reasoning", "check the errors first"));

        assertEquals("thinking: check the errors first", activity.snippet("s1"));
    }

    @Test
    public void controlCharactersAreStrippedToASingleLine() {
        SessionActivity activity = tracker();

        assertTrue(activity.onDelta("s1", "text", "line one\n\tline two\r\nend\u007F"));

        assertEquals("line oneline twoend", activity.snippet("s1"));
    }

    @Test
    public void snippetIsATailWindowCappedAtEightyChars() {
        SessionActivity activity = tracker();
        String stream = repeat('a', 30) + repeat('b', 30) + repeat('c', 30) + repeat('d', 30);

        assertTrue(activity.onDelta("s1", "text", stream));

        String snippet = activity.snippet("s1");
        assertEquals('…', snippet.charAt(0));   // the front was trimmed
        assertEquals(SessionActivity.SNIPPET_LENGTH + 1, snippet.length());
        // 120 chars streamed, the first 40 cut: 20 b + 30 c + 30 d remain
        assertEquals(repeat('b', 20) + repeat('c', 30) + repeat('d', 30), snippet.substring(1));
    }

    @Test
    public void exactlyEightyCharsCarriesNoEllipsis() {
        SessionActivity activity = tracker();
        String stream = repeat('x', 80);

        assertTrue(activity.onDelta("s1", "text", stream));

        assertEquals(stream, activity.snippet("s1"));
    }

    @Test
    public void controlOnlyDeltaPublishesNothing() {
        SessionActivity activity = tracker();

        assertFalse(activity.onDelta("s1", "text", "\n\n\n"));
        assertNull(activity.snippet("s1"));
    }

    // ---------- throttling ----------

    @Test
    public void publishesWithinAWindowAreCoalescedPerSession() {
        SessionActivity activity = tracker();
        assertTrue(activity.onDelta("s1", "text", "Hello"));

        clock.now += 50L;   // well within the window
        assertFalse(activity.onDelta("s1", "text", " world"));
        assertEquals("Hello", activity.snippet("s1"));   // the published snapshot lags

        clock.now += WINDOW_MILLIS;   // window elapsed: the accumulated tail publishes
        assertTrue(activity.onDelta("s1", "text", "!"));
        assertEquals("Hello world!", activity.snippet("s1"));   // nothing was lost
    }

    @Test
    public void throttleWindowsArePerSession() {
        SessionActivity activity = tracker();
        assertTrue(activity.onDelta("s1", "text", "one"));

        clock.now += 1L;   // inside s1's window, but s2 never published
        assertTrue(activity.onDelta("s2", "text", "two"));

        assertEquals("one", activity.snippet("s1"));
        assertEquals("two", activity.snippet("s2"));
    }

    @Test
    public void phaseSwitchResetsTheTailAndPublishesPromptly() {
        SessionActivity activity = tracker();
        assertTrue(activity.onDelta("s1", "reasoning", "maybe try the tests"));
        assertEquals("thinking: maybe try the tests", activity.snippet("s1"));

        clock.now += 10L;   // within the throttle window — a phase switch still publishes
        assertTrue(activity.onDelta("s1", "text", "Running"));
        assertEquals("Running", activity.snippet("s1"));   // old phase's text is gone
    }

    @Test
    public void samePhaseDeltaAfterClearStartsFresh() {
        SessionActivity activity = tracker();
        assertTrue(activity.onDelta("s1", "text", "before"));

        activity.clear("s1");
        assertNull(activity.snippet("s1"));

        assertTrue(activity.onDelta("s1", "text", "after"));
        assertEquals("after", activity.snippet("s1"));
    }

    // ---------- clearing / lifecycle ----------

    @Test
    public void idleClearRemovesTheSnippetAndTheState() {
        SessionActivity activity = tracker();
        assertTrue(activity.onDelta("s1", "text", "working"));
        assertEquals(1, activity.sessionCount());

        activity.clear("s1");

        assertNull(activity.snippet("s1"));
        assertEquals(0, activity.sessionCount());
        activity.clear("s1");   // idempotent
    }

    @Test
    public void clearAllDropsEverySnippet() {
        SessionActivity activity = tracker();
        activity.onDelta("s1", "text", "one");
        activity.onDelta("s2", "reasoning", "two");
        assertEquals(2, activity.sessionCount());

        activity.clearAll();

        assertNull(activity.snippet("s1"));
        assertNull(activity.snippet("s2"));
        assertEquals(0, activity.sessionCount());
    }

    @Test
    public void retainAllDropsOnlyUnknownSessions() {
        SessionActivity activity = tracker();
        activity.onDelta("keep", "text", "stays");
        activity.onDelta("drop", "text", "goes");

        activity.retainAll(Set.of("keep"));

        assertEquals("stays", activity.snippet("keep"));
        assertNull(activity.snippet("drop"));
        assertEquals(1, activity.sessionCount());
        activity.retainAll(null);   // null keep set: drop everything
        assertEquals(0, activity.sessionCount());
    }

    // ---------- null / garbage tolerance ----------

    @Test
    public void nullAndInvalidArgumentsAreIgnored() {
        SessionActivity activity = tracker();

        assertFalse(activity.onDelta(null, "text", "x"));
        assertFalse(activity.onDelta("", "text", "x"));
        assertFalse(activity.onDelta("s1", null, "x"));
        assertFalse(activity.onDelta("s1", "text", null));
        assertFalse(activity.onDelta("s1", "text", ""));
        assertFalse(activity.onDelta("s1", "tool", "x"));   // only text/reasoning stream

        assertEquals(0, activity.sessionCount());   // none of the above created state
        assertNull(activity.snippet(null));
        assertNull(activity.snippet("unknown"));
        activity.clear(null);   // must not throw
    }

    @Test
    public void zeroWindowIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionActivity(0, () -> 0L));
        assertThrows(NullPointerException.class,
                () -> new SessionActivity(WINDOW_MILLIS, null));
    }
}
