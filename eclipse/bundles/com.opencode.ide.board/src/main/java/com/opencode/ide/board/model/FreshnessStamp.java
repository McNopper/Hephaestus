package com.opencode.ide.board.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * U-035 freshness stamp: the grey "updated HH:mm:ss" text in the board's
 * store row, rewritten on every SUCCESSFUL snapshot apply so a stale board
 * is visible at a glance (a failed refresh keeps the last good stamp — the
 * age itself is the signal, and the error renders through the existing
 * content-description path). Pure formatting: instant and zone in, text
 * out; the view supplies the clock.
 */
public final class FreshnessStamp {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The stamp's fixed prefix ("updated "), tooltip-friendly and sortable. */
    public static final String PREFIX = "updated ";

    private FreshnessStamp() {
    }

    /**
     * @param epochMillis when the last successful apply happened
     * @param zone        the zone to render the wall-clock time in (the
     *                    view passes the system default)
     * @return {@code "updated HH:mm:ss"}
     */
    public static String text(long epochMillis, ZoneId zone) {
        return PREFIX + TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }
}
