package com.opencode.ide.ui.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.opencode.ide.client.Sse;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Unit tests for {@link SessionEventFilter}: the SWT-free predicate that
 * decides whether one SSE event should refresh the Session Details view of a
 * given session. No SWT, no Display — events are parsed from the real wire
 * format via {@link Sse#parseEvent(String)}, including malformed ones.
 *
 * <p>The frames are v2-shaped: the payload lives under {@code data} (v1 called
 * it {@code properties}) and every session-scoped event carries a flat
 * top-level {@code sessionID}.</p>
 */
public class SessionEventFilterTest {

    private static final String OWN = "ses_own";
    private static final String OTHER = "ses_other";

    /** One SSE JSON frame, exactly as the /event stream delivers it. */
    private static OpencodeEvent event(String json) {
        return Sse.parseEvent(json);
    }

    /** A v2 session-scoped frame of the given type for the given session. */
    private static OpencodeEvent sessionEvent(String type, String sessionId) {
        return event("{\"type\":\"" + type + "\",\"data\":{\"sessionID\":\"" + sessionId + "\"}}");
    }

    // ---------- own-session events trigger ----------

    @Test
    public void sessionIdleForOwnSessionTriggers() {
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.idle", OWN)));
    }

    @Test
    public void executionOutcomesForOwnSessionTrigger() {
        // v1 read "the turn is over" off session.idle / message.updated; v2
        // names the outcome explicitly, and any of the three ends the turn
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.execution.succeeded", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.execution.failed", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.execution.interrupted", OWN)));
    }

    @Test
    public void textEndedForOwnSessionTriggers() {
        // v2's "a message settled" signal, the closest heir of message.updated
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.text.ended", OWN)));
    }

    @Test
    public void renamedForOwnSessionTriggers() {
        // v1's session.updated is gone; a title change now arrives as session.renamed
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.renamed", OWN)));
    }

    @Test
    public void streamingDeltasForOwnSessionTrigger() {
        // v1's message.part.updated split into per-kind delta events; these
        // arrive in bursts, which is what the view's debounce is for
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.text.delta", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.reasoning.delta", OWN)));
    }

    @Test
    public void toolLifecycleForOwnSessionTriggers() {
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.tool.called", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.tool.input.started", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.tool.progress", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.tool.success", OWN)));
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.tool.failed", OWN)));
    }

    @Test
    public void realWorldDeltaFrameCarriesItsIdsFlat() {
        // the verified v2 payload: {sessionID, assistantMessageID, ordinal, delta}
        assertTrue(SessionEventFilter.shouldRefreshFor(OWN,
                event("{\"type\":\"session.text.delta\",\"data\":{\"sessionID\":\"" + OWN + "\","
                        + "\"assistantMessageID\":\"msg_1\",\"ordinal\":3,\"delta\":\"hi\"}}")));
    }

    // ---------- other sessions / irrelevant types are ignored ----------

    @Test
    public void streamingDeltaForOtherSessionIsIgnored() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN,
                sessionEvent("session.text.delta", OTHER)));
    }

    @Test
    public void sessionIdleForOtherSessionIsIgnored() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.idle", OTHER)));
    }

    @Test
    public void irrelevantTypeForOwnSessionIsIgnored() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.created", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.deleted", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.status", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.usage.updated", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("permission.asked", OWN)));
    }

    @Test
    public void retiredV1TypesNoLongerTrigger() {
        // these names do not exist on a v2 server; matching them would only
        // resurrect dead branches if an old capture is ever replayed
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("message.updated", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("message.part.updated", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("message.part.delta", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("session.updated", OWN)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, sessionEvent("todo.updated", OWN)));
    }

    // ---------- malformed input is tolerated ----------

    @Test
    public void nullEventYieldsFalse() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, null));
    }

    @Test
    public void missingTypeYieldsFalse() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN,
                event("{\"data\":{\"sessionID\":\"" + OWN + "\"}}")));
    }

    @Test
    public void nullPropertiesYieldFalse() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN, new OpencodeEvent("session.idle", null)));
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN,
                new OpencodeEvent("session.text.delta", null)));
    }

    @Test
    public void emptyPropertiesYieldFalse() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN,
                event("{\"type\":\"session.idle\",\"data\":{}}")));
    }

    @Test
    public void nonStringSessionIdIsIgnored() {
        assertFalse(SessionEventFilter.shouldRefreshFor(OWN,
                event("{\"type\":\"session.idle\",\"data\":{\"sessionID\":{\"id\":\"" + OWN + "\"}}}")));
    }

    // ---------- the queried session id itself is validated ----------

    @Test
    public void blankSessionIdYieldsFalse() {
        String json = "{\"type\":\"session.idle\",\"data\":{\"sessionID\":\"" + OWN + "\"}}";
        assertFalse(SessionEventFilter.shouldRefreshFor(null, event(json)));
        assertFalse(SessionEventFilter.shouldRefreshFor("", event(json)));
        assertFalse(SessionEventFilter.shouldRefreshFor("  ", event(json)));
    }
}
