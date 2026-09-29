package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.Test;

/**
 * U-035: the store row's freshness stamp is pure formatting - instant and
 * zone in, {@code "updated HH:mm:ss"} out - so the last successful snapshot
 * apply is visible at a glance (fixed zone in the tests: never the machine
 * default).
 */
public class FreshnessStampTest {

    /** The stamp carries the "updated" prefix and the wall-clock time. */
    @Test
    public void stampsTheTimeOfTheLastUpdate() {
        long millis = Instant.parse("2026-09-29T10:05:07.123Z").toEpochMilli();

        assertEquals("updated 10:05:07", FreshnessStamp.text(millis, ZoneOffset.UTC));
    }

    /** The zone is a parameter: the same instant renders per zone, not per machine. */
    @Test
    public void theZoneIsTheCallersChoice() {
        long millis = Instant.parse("2026-09-29T23:59:01.000Z").toEpochMilli();

        assertEquals("updated 23:59:01", FreshnessStamp.text(millis, ZoneOffset.UTC));
        assertEquals("updated 01:59:01", FreshnessStamp.text(millis, ZoneOffset.ofHours(2)));
    }

    /** Midnight and single-digit components keep the zero-padded shape. */
    @Test
    public void componentsStayZeroPadded() {
        long midnight = Instant.parse("2026-01-01T00:00:00.500Z").toEpochMilli();

        assertEquals("updated 00:00:00", FreshnessStamp.text(midnight, ZoneOffset.UTC));
    }

    /** The prefix is a constant so tooltips and tests agree on the wording. */
    @Test
    public void thePrefixIsStable() {
        assertEquals("updated ", FreshnessStamp.PREFIX);
    }
}
