package com.opencode.ide.ui.attention;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.Gson;
import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * SWT-free unit tests for {@link AttentionClassifier}: the v2 event-type
 * mapping, the busy-set transition memory that keeps idle noise out, and the
 * permission-ask display message. Events are built the same way
 * {@code ActivityTrackerTest} builds them (Gson-deserialized wire JSON), and
 * the permission type name is pinned to the canonical
 * {@link PermissionEvents#ASKED} constant.
 */
public class AttentionClassifierTest {

    private static final Gson GSON = new Gson();

    private final AttentionClassifier classifier = new AttentionClassifier();

    private static OpencodeEvent event(String json) {
        return GSON.fromJson(json, OpencodeEvent.class);
    }

    /** v2 shape: the status is nested as {@code {"status":{"type":...}}}. */
    private static String statusEvent(String sessionId, String statusType) {
        return """
                {"type":"session.status","properties":{"sessionID":"%s","status":{"type":"%s"}}}""".formatted(sessionId,
                statusType);
    }

    /** Older-capture fallback shape: a flat {@code "status"} string. */
    private static String flatStatusEvent(String sessionId, String status) {
        return """
                {"type":"session.status","properties":{"sessionID":"%s","status":"%s"}}""".formatted(sessionId,
                status);
    }

    /** Any {@code session.*} event carrying only a session id. */
    private static String sessionEvent(String type, String sessionId) {
        return """
                {"type":"%s","properties":{"sessionID":"%s"}}""".formatted(type, sessionId);
    }

    // ---------- permission asks ----------

    @Test
    public void permissionAskedRaisesPermissionAskWithDisplayMessage() {
        OpencodeEvent asked = event("""
                {"type":"%s","properties":{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["**/*.go"],"metadata":{"command":"go test ./..."}}}""".formatted(
                PermissionEvents.ASKED));
        AttentionEvent attention = classifier.apply(asked);

        assertNotNull(attention);
        assertEquals(AttentionKind.PERMISSION_ASK, attention.kind());
        assertEquals("ses_1", attention.sessionId());
        assertEquals("bash: go test ./...", attention.message());
    }

    @Test
    public void permissionAskedWithoutSessionIsIgnored() {
        OpencodeEvent asked = event("""
                {"type":"permission.asked","properties":{"id":"per_2","action":"bash"}}""");
        assertNull(classifier.apply(asked));
    }

    @Test
    public void permissionRepliedIsIgnored() {
        OpencodeEvent replied = event("""
                {"type":"permission.replied","properties":{"sessionID":"ses_1","requestID":"per_1","reply":"once"}}""");
        assertNull(classifier.apply(replied));
    }

    // ---------- busy-set memory + completion ----------

    @Test
    public void busyThenIdleRaisesSessionCompletedOnce() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));
        assertTrue(classifier.isBusy("ses_1"));

        AttentionEvent completed = classifier.apply(event(sessionEvent("session.idle", "ses_1")));
        assertNotNull(completed);
        assertEquals(AttentionKind.SESSION_COMPLETED, completed.kind());
        assertEquals("ses_1", completed.sessionId());
        assertTrue(classifier.busySessions().isEmpty());

        // a second idle (e.g. the TUI and Eclipse both see it) is noise
        assertNull(classifier.apply(event(sessionEvent("session.idle", "ses_1"))));
    }

    @Test
    public void idleWithoutBusyIsIgnored() {
        assertNull(classifier.apply(event(sessionEvent("session.idle", "ses_1"))));
        assertNull(classifier.apply(event(sessionEvent("session.execution.succeeded", "ses_1"))));
    }

    @Test
    public void retryCountsAsBusy() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "retry"))));
        assertEquals(java.util.Set.of("ses_1"), classifier.busySessions());
        assertNotNull(classifier.apply(event(sessionEvent("session.idle", "ses_1"))));
    }

    @Test
    public void flatStatusStringShapeIsHonored() {
        assertNull(classifier.apply(event(flatStatusEvent("ses_1", "busy"))));
        assertTrue(classifier.isBusy("ses_1"));
    }

    @Test
    public void nonBusyStatusCompletesBusySession() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));

        AttentionEvent completed = classifier.apply(event(statusEvent("ses_1", "idle")));
        assertNotNull(completed);
        assertEquals(AttentionKind.SESSION_COMPLETED, completed.kind());
    }

    @Test
    public void statusWithoutTypeDetailChangesNothing() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));

        OpencodeEvent detailless = event("""
                {"type":"session.status","properties":{"sessionID":"ses_1"}}""");
        assertNull(classifier.apply(detailless));
        assertTrue(classifier.isBusy("ses_1"));
    }

    // ---------- execution outcomes ----------

    @Test
    public void executionSucceededAfterBusyCompletesSession() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));
        AttentionEvent completed = classifier.apply(event(sessionEvent("session.execution.succeeded", "ses_1")));
        assertEquals(AttentionKind.SESSION_COMPLETED, completed.kind());
    }

    @Test
    public void executionFailedAfterBusyRaisesSessionError() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));
        AttentionEvent error = classifier.apply(event(sessionEvent("session.execution.failed", "ses_1")));
        assertNotNull(error);
        assertEquals(AttentionKind.SESSION_ERROR, error.kind());
        assertEquals("ses_1", error.sessionId());
        assertTrue(error.message().contains("failed"));
    }

    @Test
    public void executionInterruptedAfterBusyRaisesSessionError() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));
        AttentionEvent error = classifier.apply(event(sessionEvent("session.execution.interrupted", "ses_1")));
        assertEquals(AttentionKind.SESSION_ERROR, error.kind());
        assertTrue(error.message().contains("interrupted"));
    }

    @Test
    public void sessionDeletedClearsBusySilently() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));

        assertNull(classifier.apply(event(sessionEvent("session.deleted", "ses_1"))));
        assertTrue(classifier.busySessions().isEmpty());
        // the completion the deletion replaced must not also fire
        assertNull(classifier.apply(event(sessionEvent("session.idle", "ses_1"))));
    }

    // ---------- robustness ----------

    @Test
    public void unknownEventTypesAreIgnoredWithoutState() {
        for (String type : new String[] {"session.text.delta", "session.tool.called",
                "session.created", "server.version"}) {
            assertNull(classifier.apply(event(sessionEvent(type, "ses_1"))));
        }
        assertTrue(classifier.busySessions().isEmpty());
    }

    @Test
    public void nullEventAndNullTypeAreSafe() {
        assertNull(classifier.apply(null));
        assertNull(classifier.apply(event("{\"properties\":{\"sessionID\":\"ses_1\"}}")));
    }

    @Test
    public void sessionsTrackedIndependently() {
        assertNull(classifier.apply(event(statusEvent("ses_1", "busy"))));
        assertNull(classifier.apply(event(statusEvent("ses_2", "busy"))));

        AttentionEvent first = classifier.apply(event(sessionEvent("session.idle", "ses_1")));
        assertEquals("ses_1", first.sessionId());
        assertEquals(java.util.Set.of("ses_2"), classifier.busySessions());

        AttentionEvent second = classifier.apply(event(sessionEvent("session.idle", "ses_2")));
        assertEquals("ses_2", second.sessionId());
        assertTrue(classifier.busySessions().isEmpty());
    }
}
