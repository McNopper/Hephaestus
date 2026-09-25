package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * B-009 hang classification (pure): a worker that goes silent right after
 * stating tool-discovery intent hit a TOOLS-LOAD failure - the blocker must
 * say so instead of a generic stall. Never a silent stall.
 */
public class HangKindTest {

    @Test
    public void toolDiscoveryIntentNamesTheToolsLoadFailure() {
        assertTrue(HangKind.of("The convention is clear... Let me discover the task tools",
                null).contains("tools-load"));
        assertTrue(HangKind.of("anything", "task_claim").contains("tools-load"));
    }

    @Test
    public void aPlainHangStaysAPlainHang() {
        assertEquals("hang", HangKind.of("let me think about the design", "bash"));
        assertEquals("hang", HangKind.of(null, null));
    }

    @Test
    public void theReasonIsActionable() {
        assertTrue("it names where to look: " + HangKind.of("discovering the task tools now", null),
                HangKind.of("discovering the task tools now", null).contains("tasks MCP"));
    }
}
