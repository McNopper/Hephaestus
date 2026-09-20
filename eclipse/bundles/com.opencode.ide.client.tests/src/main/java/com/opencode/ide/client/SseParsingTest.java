package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Unit tests for the pure SSE wire-format parser {@link Sse} - no server, no I/O.
 * Fixtures use the v2 frame shape {@code {id, created, type, location, data}}.
 */
public class SseParsingTest {

    @Test
    public void parsesSingleEvent() {
        String sse = "data: {\"id\":\"evt_1\",\"created\":1,\"type\":\"session.created\",\"data\":{\"sessionID\":\"ses_1\"}}\n\n";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("session.created", events.get(0).type());
        assertEquals("ses_1", events.get(0).string("sessionID"));
    }

    @Test
    public void parsesMultipleEvents() {
        String sse = """
                data: {"id":"evt_1","created":1,"type":"session.status","data":{"sessionID":"ses_a"}}

                data: {"id":"evt_2","created":2,"type":"session.idle","data":{"sessionID":"ses_b"}}

                """;
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(2, events.size());
        assertEquals("session.status", events.get(0).type());
        assertEquals("ses_a", events.get(0).string("sessionID"));
        assertEquals("session.idle", events.get(1).type());
    }

    @Test
    public void joinsMultiLineDataFrames() {
        String sse = "data: {\"type\":\"x\",\ndata: \"data\":{\"k\":\"v\"}}\n\n";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("x", events.get(0).type());
        // the event payload is the `data` object, so "v" lives at key "k"
        assertEquals("v", events.get(0).at("k"));
    }

    @Test
    public void skipsMalformedFrames() {
        String sse = "data: {\"type\":\"ok\",\"data\":{}}\n\ndata: this-is-not-json\n\ndata: {broken\n\n";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("ok", events.get(0).type());
    }

    @Test
    public void ignoresNonDataLines() {
        String sse = """
                : comment line
                event: session.status
                data: {"type":"session.status","data":{"sessionID":"ses_x"}}

                """;
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("ses_x", events.get(0).string("sessionID"));
    }

    @Test
    public void parseEventNullForBlank() {
        assertNull(Sse.parseEvent(null));
        assertNull(Sse.parseEvent(""));
        assertNull(Sse.parseEvent("   "));
    }

    @Test
    public void emptyInputYieldsNoEvents() {
        assertTrue(Sse.events("").isEmpty());
        assertTrue(Sse.events(null).isEmpty());
    }

    @Test
    public void unterminatedFrameAtEndOfInputIsEmitted() {
        String sse = "data: {\"type\":\"session.idle\",\"data\":{\"sessionID\":\"ses_z\"}}";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("session.idle", events.get(0).type());
        assertEquals("ses_z", events.get(0).string("sessionID"));
    }

    @Test
    public void unterminatedMultiLineFrameAtEndOfInputIsEmitted() {
        String sse = "data: {\"type\":\"x\",\ndata: \"data\":{\"k\":\"v\"}}";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("x", events.get(0).type());
        assertEquals("v", events.get(0).at("k"));
    }

    @Test
    public void terminatedThenUnterminatedFramesBothEmitted() {
        String sse = "data: {\"type\":\"a\",\"data\":{}}\n\ndata: {\"type\":\"b\",\"data\":{}}";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).type());
        assertEquals("b", events.get(1).type());
    }

    @Test
    public void partialDataAtEndOfInputIsFlushedButSkippedWhenMalformed() {
        String partial = "data: {\"type\":";
        List<String> frames = Sse.frames(java.util.Arrays.asList(partial).iterator());
        assertEquals("EOF must flush pending data as a frame", 1, frames.size());
        assertEquals("{\"type\":", frames.get(0));
        assertTrue("malformed flushed frame is skipped downstream",
                Sse.events(partial).isEmpty());
    }

    @Test
    public void noDataLinesAtEndOfInputEmitNothing() {
        List<String> frames = Sse.frames(java.util.Arrays.asList(": comment", "event: x").iterator());
        assertTrue(frames.isEmpty());
    }

    @Test
    public void locationDirectoryScopesTheEvent() {
        // v2 serves ONE stream for every directory; the frame's location is
        // what ties an event to a project or worktree
        String sse = "data: {\"id\":\"evt_1\",\"created\":1,\"type\":\"session.idle\","
                + "\"location\":{\"directory\":\"C:\\\\Development\\\\GitHub\\\\Hephaestus\"},"
                + "\"data\":{\"sessionID\":\"ses_g1\"}}\n\n";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("session.idle", events.get(0).type());
        assertEquals("ses_g1", events.get(0).string("sessionID"));
        assertEquals("C:\\Development\\GitHub\\Hephaestus", events.get(0).directory());
    }

    @Test
    public void framesWithoutLocationHaveNoDirectory() {
        String sse = "data: {\"type\":\"server.connected\",\"data\":{}}\n\n";
        List<OpencodeEvent> events = Sse.events(sse);
        assertEquals(1, events.size());
        assertEquals("server.connected", events.get(0).type());
        assertNull(events.get(0).directory());
    }
}
