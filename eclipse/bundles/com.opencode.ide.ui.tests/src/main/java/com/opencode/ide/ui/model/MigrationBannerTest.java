package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.opencode.ide.ui.model.MigrationBanner.Line;

/**
 * Unit tests for {@link MigrationBanner} (the v1-migration status line of
 * the Server view, U-046 slice 2): no SWT, no HTTP.
 */
public class MigrationBannerTest {

    @Test
    public void runningShowsTheInfoLine() {
        Line line = MigrationBanner.of("running");

        assertEquals("migrating v1 session history\u2026", line.text());
        assertFalse(line.warning());
    }

    @Test
    public void errorShowsTheWarningLine() {
        Line line = MigrationBanner.of("error");

        assertEquals("v1 session history migration failed", line.text());
        assertTrue(line.warning());
    }

    /** completed/required show nothing - and so does an unknown/absent endpoint (404 on older builds). */
    @Test
    public void completedRequiredAndUnknownStayHidden() {
        assertNull(MigrationBanner.of("completed"));
        assertNull(MigrationBanner.of("required"));
        assertNull(MigrationBanner.of(null));
        assertNull(MigrationBanner.of(""));
        assertNull(MigrationBanner.of("something-new"));
    }

    @Test
    public void statusIsCaseInsensitive() {
        assertFalse(MigrationBanner.of("Running").warning());
        assertTrue(MigrationBanner.of("ERROR").warning());
    }

    @Test
    public void mergePrefersTheWarningAndToleratesNullSides() {
        Line running = MigrationBanner.of("running");
        Line error = MigrationBanner.of("error");

        assertTrue("a running banner upgrades to the warning", MigrationBanner.merge(running, error).warning());
        assertTrue("a warning stays when the other side only runs",
                MigrationBanner.merge(error, running).warning());
        assertEquals(running, MigrationBanner.merge(running, null));
        assertEquals(error, MigrationBanner.merge(null, error));
        assertNull(MigrationBanner.merge(null, null));
        assertEquals(running, MigrationBanner.merge(running, running));
    }
}
