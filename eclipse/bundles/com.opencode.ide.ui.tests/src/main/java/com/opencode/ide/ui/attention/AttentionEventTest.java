package com.opencode.ide.ui.attention;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** The {@link AttentionEvent} value contract: kind required, message defaulted. */
public class AttentionEventTest {

    @Test
    public void blankMessageFallsBackToKindTitle() {
        assertEquals(AttentionKind.SESSION_COMPLETED.title(),
                new AttentionEvent(AttentionKind.SESSION_COMPLETED, null, "ses_1").message());
        assertEquals(AttentionKind.SESSION_ERROR.title(),
                new AttentionEvent(AttentionKind.SESSION_ERROR, "  ", "ses_1").message());
    }

    @Test
    public void explicitMessageIsKept() {
        assertEquals("bash: go test ./...", new AttentionEvent(AttentionKind.PERMISSION_ASK,
                "bash: go test ./...", "ses_1").message());
    }

    @Test
    public void nullKindIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new AttentionEvent(null, "message", "ses_1"));
    }
}
