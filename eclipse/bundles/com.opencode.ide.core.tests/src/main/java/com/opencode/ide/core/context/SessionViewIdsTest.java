package com.opencode.ide.core.context;

import static org.junit.Assert.assertEquals;
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
}
