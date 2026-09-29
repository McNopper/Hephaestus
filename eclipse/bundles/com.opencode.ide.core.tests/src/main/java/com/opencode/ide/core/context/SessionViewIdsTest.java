package com.opencode.ide.core.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link SessionViewIds} (T-009): the one session-id encoding
 * every per-session view goes through - the round-trip pins it so no caller
 * can fork a second encoding again (the old three encodings opened duplicate
 * views per session).
 */
public class SessionViewIdsTest {

    @Test
    public void sessionIdRoundTripsThroughTheSecondaryId() {
        assertEquals("ses_01H8X", SessionViewIds.sessionId(SessionViewIds.secondaryId("ses_01H8X")));
        assertEquals("ses_50%", SessionViewIds.sessionId(SessionViewIds.secondaryId("ses_50%")));
        assertEquals("s\u00e4_1", SessionViewIds.sessionId(SessionViewIds.secondaryId("s\u00e4_1")));
    }

    @Test
    public void plainSessionIdsAreIdentities() {
        assertEquals("ses_123", SessionViewIds.secondaryId("ses_123"));
    }

    @Test
    public void nullAndBlankDegradeToNullSessionIds() {
        assertNull(SessionViewIds.sessionId(null));
        assertNull(SessionViewIds.sessionId("  "));
        assertEquals("", SessionViewIds.secondaryId(null));
    }

    @Test
    public void legacyUnencodedFormsStillDecode() {
        // tolerant decode: a caller that stored a raw id reads it back
        assertEquals("ses_123", SessionViewIds.sessionId("ses_123"));
    }

    /** T-009: the explicit openDetails(sessionId, autoRefresh) hand-off. */
    @Test
    public void liveSegmentRoundTripsAndFlagsAutoRefresh() {
        SessionViewIds.Parsed parsed = SessionViewIds.parse(SessionViewIds.secondaryId("ses_50%", true));
        assertEquals("ses_50%", parsed.sessionId());
        assertTrue(parsed.autoRefresh());

        SessionViewIds.Parsed plain = SessionViewIds.parse(SessionViewIds.secondaryId("ses_1", false));
        assertEquals("ses_1", plain.sessionId());
        assertFalse(plain.autoRefresh());
    }

    @Test
    public void tildeInASessionIdNeverReadsAsTheLiveSegment() {
        // encoding percent-escapes '~', so the segment cannot occur inside an encoded id
        SessionViewIds.Parsed parsed = SessionViewIds.parse(SessionViewIds.secondaryId("ses~tilde", false));
        assertEquals("ses~tilde", parsed.sessionId());
        assertFalse(parsed.autoRefresh());
    }

    @Test
    public void liveSegmentSurvivesSpecialCharacters() {
        SessionViewIds.Parsed parsed = SessionViewIds.parse(SessionViewIds.secondaryId("s\u00e4_50%~x", true));
        assertEquals("s\u00e4_50%~x", parsed.sessionId());
        assertTrue(parsed.autoRefresh());
    }
}
