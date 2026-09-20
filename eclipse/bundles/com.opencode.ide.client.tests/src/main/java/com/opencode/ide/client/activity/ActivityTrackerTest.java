package com.opencode.ide.client.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.Gson;
import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Unit tests for {@link ActivityTracker}'s event-to-activity derivation rules
 * over the v2 event names: {@code session.status}/{@code session.idle}/
 * {@code session.deleted}, {@code session.reasoning.started/.ended},
 * {@code session.text.started} and the {@code session.tool.*} lifecycle —
 * v1's single {@code message.part.updated} with a {@code part} discriminator
 * is gone.
 */
public class ActivityTrackerTest {

    private static final Gson GSON = new Gson();

    private static OpencodeEvent event(String json) {
        return GSON.fromJson(json, OpencodeEvent.class);
    }

    /** v2 shape: the status is an object, not a plain string. */
    private static String statusEvent(String sessionId, String status) {
        return """
                {"type":"session.status","properties":{"sessionID":"%s","status":{"type":"%s"}}}""".formatted(sessionId,
                status);
    }

    private static String idleEvent(String sessionId) {
        return """
                {"type":"session.idle","properties":{"sessionID":"%s"}}""".formatted(sessionId);
    }

    private static String deletedEvent(String sessionId) {
        return """
                {"type":"session.deleted","properties":{"sessionID":"%s"}}""".formatted(sessionId);
    }

    private static String reasoningStarted(String sessionId) {
        return """
                {"type":"session.reasoning.started","properties":{"sessionID":"%s","assistantMessageID":"msg_1","ordinal":0}}"""
                .formatted(sessionId);
    }

    private static String textStarted(String sessionId) {
        return """
                {"type":"session.text.started","properties":{"sessionID":"%s","assistantMessageID":"msg_1","ordinal":0}}"""
                .formatted(sessionId);
    }

    /** A tool invocation starting: name + input present (session.tool.called / tool.input.started). */
    private static String toolStarted(String sessionId, String invocation, String tool, String inputJson) {
        return """
                {"type":"session.tool.called","properties":{"sessionID":"%s","assistantMessageID":"msg_1","id":"%s","name":"%s","input":%s}}"""
                .formatted(sessionId, invocation, tool, inputJson);
    }

    /** A tool invocation ending: v2 repeats neither name nor input, only the invocation id. */
    private static String toolEnded(String sessionId, String invocation, String type) {
        return """
                {"type":"%s","properties":{"sessionID":"%s","assistantMessageID":"msg_1","id":"%s"}}"""
                .formatted(type, sessionId, invocation);
    }

    @Test
    public void statusBusyAndRetryMarkSessionRunning() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(statusEvent("ses_1", "busy")));
        tracker.apply(event(statusEvent("ses_2", "retry")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertEquals(2, snapshot.sessions().size());
        assertTrue(snapshot.sessions().get("ses_1").running());
        assertTrue(snapshot.sessions().get("ses_2").running());
        assertFalse(snapshot.sessions().get("ses_1").thinking());
    }

    @Test
    public void flatStringStatusIsStillTolerated() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event("{\"type\":\"session.status\",\"properties\":{\"sessionID\":\"ses_1\",\"status\":\"busy\"}}"));
        assertTrue(tracker.snapshot().sessions().get("ses_1").running());
    }

    @Test
    public void statusIdleStopsRunningSession() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(statusEvent("ses_1", "busy")));
        tracker.apply(event(statusEvent("ses_1", "idle")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertEquals(1, snapshot.sessions().size());
        assertFalse(snapshot.sessions().get("ses_1").running());
    }

    @Test
    public void statusIdleForUnknownSessionCreatesNothing() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(statusEvent("ses_1", "idle")));
        tracker.apply(event(statusEvent("ses_2", "waiting")));
        assertTrue(tracker.snapshot().sessions().isEmpty());
    }

    @Test
    public void idleEventStopsRunningSession() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(statusEvent("ses_1", "busy")));
        tracker.apply(event(idleEvent("ses_1")));
        assertFalse(tracker.snapshot().sessions().get("ses_1").running());
    }

    @Test
    public void toolRunningWithFileAppearsInSnapshot() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(statusEvent("ses_1", "busy")));
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/src/A.java\"}")));
        ActivitySnapshot snapshot = tracker.snapshot();
        FileActivity file = snapshot.files().get("/src/A.java");
        assertNotNull(file);
        assertEquals("ses_1", file.sessionId());
        assertEquals("edit", file.tool());
        assertEquals("/src/A.java", file.file());
        SessionActivity session = snapshot.sessions().get("ses_1");
        assertEquals(1, session.activity().size());
        ToolActivity tool = session.activity().get(0);
        assertEquals("edit", tool.tool());
        assertEquals("/src/A.java", tool.file());
        assertEquals(ToolActivity.State.RUNNING, tool.state());
    }

    @Test
    public void toolCompletedRemovesFileAndShowsCompleted() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/src/A.java\"}")));
        tracker.apply(event(toolEnded("ses_1", "toolu_1", "session.tool.success")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertNull(snapshot.files().get("/src/A.java"));
        assertEquals(1, snapshot.sessions().get("ses_1").activity().size());
        ToolActivity done = snapshot.sessions().get("ses_1").activity().get(0);
        assertEquals(ToolActivity.State.COMPLETED, done.state());
        assertEquals("the completion inherits the tool name", "edit", done.tool());
        assertEquals("the completion inherits the file", "/src/A.java", done.file());
    }

    @Test
    public void toolErrorWithoutFileRecordsErrorState() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "bash", "{\"command\":\"ls\"}")));
        tracker.apply(event(toolEnded("ses_1", "toolu_1", "session.tool.failed")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertTrue(snapshot.files().isEmpty());
        SessionActivity session = snapshot.sessions().get("ses_1");
        assertEquals(1, session.activity().size());
        ToolActivity tool = session.activity().get(0);
        assertEquals("bash", tool.tool());
        assertNull(tool.file());
        assertEquals(ToolActivity.State.ERROR, tool.state());
    }

    @Test
    public void filePathVariantKeysResolve() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"path\":\"/p\"}")));
        tracker.apply(event(toolStarted("ses_1", "toolu_2", "read", "{\"file\":\"/f\"}")));
        tracker.apply(event(toolStarted("ses_1", "toolu_3", "glob", "{\"absolutePath\":\"/ap\"}")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertEquals(3, snapshot.files().size());
        assertNotNull(snapshot.files().get("/p"));
        assertNotNull(snapshot.files().get("/f"));
        assertNotNull(snapshot.files().get("/ap"));
        tracker.apply(event(toolStarted("ses_1", "toolu_4", "edit", "{\"filePath\":\"/first\",\"path\":\"/second\"}")));
        assertEquals("edit", tracker.snapshot().files().get("/first").tool());
        assertEquals(4, tracker.snapshot().files().size());
    }

    @Test
    public void reasoningThenTextTogglesThinking() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(reasoningStarted("ses_1")));
        assertTrue(tracker.snapshot().sessions().get("ses_1").thinking());
        tracker.apply(event(textStarted("ses_1")));
        assertFalse(tracker.snapshot().sessions().get("ses_1").thinking());
    }

    @Test
    public void sessionDeletedDropsSessionAndFiles() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/a\"}")));
        tracker.apply(event(toolStarted("ses_2", "toolu_2", "edit", "{\"filePath\":\"/b\"}")));
        tracker.apply(event(deletedEvent("ses_1")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertNull(snapshot.sessions().get("ses_1"));
        assertNotNull(snapshot.sessions().get("ses_2"));
        assertNull(snapshot.files().get("/a"));
        assertNotNull(snapshot.files().get("/b"));
    }

    @Test
    public void sessionEndedDropsSessionAndFiles() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/a\"}")));
        tracker.sessionEnded("ses_1");
        ActivitySnapshot snapshot = tracker.snapshot();
        assertTrue(snapshot.sessions().isEmpty());
        assertTrue(snapshot.files().isEmpty());
        tracker.sessionEnded("unknown");
        assertTrue(tracker.snapshot().sessions().isEmpty());
        assertTrue(tracker.snapshot().files().isEmpty());
    }

    @Test
    public void listenerFiresOnlyOnActualChange() {
        ActivityTracker tracker = new ActivityTracker();
        int[] calls = { 0 };
        tracker.addListener(() -> calls[0]++);
        tracker.apply(event(statusEvent("ses_1", "busy")));
        assertEquals(1, calls[0]);
        tracker.apply(event(statusEvent("ses_1", "busy")));
        tracker.apply(event(statusEvent("ses_1", "retry")));
        assertEquals(1, calls[0]);
        tracker.apply(event(statusEvent("ses_1", "idle")));
        assertEquals(2, calls[0]);
        tracker.apply(event(statusEvent("ses_1", "idle")));
        tracker.apply(event(idleEvent("ses_1")));
        tracker.apply(event("{\"type\":\"mcp.tools.changed\",\"properties\":{}}"));
        tracker.apply(null);
        assertEquals(2, calls[0]);
    }

    @Test
    public void duplicateToolUpdateDoesNotRefire() {
        ActivityTracker tracker = new ActivityTracker();
        int[] calls = { 0 };
        tracker.addListener(() -> calls[0]++);
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/a\"}")));
        assertEquals(1, calls[0]);
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/a\"}")));
        assertEquals(1, calls[0]);
        tracker.apply(event(toolEnded("ses_1", "toolu_1", "session.tool.success")));
        assertEquals(2, calls[0]);
        tracker.apply(event(toolEnded("ses_1", "toolu_1", "session.tool.success")));
        assertEquals(2, calls[0]);
    }

    @Test
    public void snapshotIsImmutable() {
        ActivityTracker tracker = new ActivityTracker();
        tracker.apply(event(toolStarted("ses_1", "toolu_1", "edit", "{\"filePath\":\"/a\"}")));
        ActivitySnapshot snapshot = tracker.snapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.sessions().put("ses_2", snapshot.sessions().get("ses_1")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.files().remove("/a"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.sessions().get("ses_1").activity().add(new ToolActivity("read", null, null)));
        assertNotNull(tracker.snapshot().files().get("/a"));
        assertEquals(1, tracker.snapshot().sessions().get("ses_1").activity().size());
    }

    @Test
    public void nullAndUnknownEventsAreIgnored() {
        ActivityTracker tracker = new ActivityTracker();
        int[] calls = { 0 };
        tracker.addListener(() -> calls[0]++);
        tracker.apply(null);
        tracker.apply(event("{\"type\":\"mcp.tools.changed\",\"properties\":{}}"));
        tracker.apply(event("{\"type\":\"session.status\",\"properties\":{}}"));
        tracker.apply(event(textStarted("ses_9")));
        tracker.apply(event("{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_9\"}}"));
        assertTrue(tracker.snapshot().sessions().isEmpty());
        assertTrue(tracker.snapshot().files().isEmpty());
        assertEquals(0, calls[0]);
    }
}
