package com.opencode.ide.ui.model;

import com.opencode.ide.client.model.MigrationStatus;

/**
 * The v1-to-v2 storage-migration banner line for the Server view (U-046 slice
 * 2): maps a {@link MigrationStatus} to the persistent info line shown while
 * the server migrates v1 session history, or the warning line when that
 * migration failed. {@code completed} and {@code required} show nothing, and
 * so does an absent endpoint (older builds answer 404 - unknown/hidden).
 * SWT-free so the mapping is unit-testable; the view only renders.
 */
public final class MigrationBanner {

    /** Info line while the migration is running. */
    public static final String RUNNING_TEXT = "migrating v1 session history\u2026";

    /** Warning line when the migration ended in {@code error}. */
    public static final String ERROR_TEXT = "v1 session history migration failed";

    private MigrationBanner() {
    }

    /**
     * One banner line: text plus whether it is a warning (rendered
     * prominently); {@code null} when nothing should show.
     *
     * @param status the wire status ({@code completed | required | running |
     *               error}), null-tolerant and case-insensitive
     */
    public static Line of(String status) {
        if ("running".equalsIgnoreCase(status)) {
            return new Line(RUNNING_TEXT, false);
        }
        if ("error".equalsIgnoreCase(status)) {
            return new Line(ERROR_TEXT, true);
        }
        return null; // completed / required / unknown -> hidden
    }

    /**
     * Aggregates the per-server lines of one refresh into the one line the
     * view shows: an {@code error} anywhere wins over {@code running}; null
     * inputs never contribute.
     */
    public static Line merge(Line first, Line second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.warning() ? second : first;
    }

    /** One banner line; {@code warning} marks the error case. */
    public record Line(String text, boolean warning) {
    }
}
