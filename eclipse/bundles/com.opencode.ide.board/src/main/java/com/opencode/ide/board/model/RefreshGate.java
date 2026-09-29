package com.opencode.ide.board.model;

/**
 * U-035 rate-limit gate for the board's activation refresh: part
 * activation ({@code setFocus}) is a refresh cadence — mirroring the Fleet
 * view's peer re-read — but tabbing through views must never churn the
 * single-flight refresh pipeline. The first activation passes; further
 * ones pass only after the minimum interval elapsed since the last
 * ALLOWED activation (a denied activation never extends the window).
 *
 * <p>Pure time arithmetic: the caller supplies the clock, so the gate is
 * unit-testable without SWT, waits or wall-clock sleeps.</p>
 */
public final class RefreshGate {

    /** Minimum time between two allowed activations, in milliseconds. */
    private final long minIntervalMillis;
    /** Clock value of the last allowed activation; valid once {@link #used}. */
    private long lastAllowedMillis;
    private boolean used;

    /**
     * @param minIntervalMillis how long to wait between allowed activations
     *                          (values below zero clamp to zero = always allow)
     */
    public RefreshGate(long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /**
     * @param nowMillis the caller's clock (e.g. {@code System.currentTimeMillis()})
     * @return true when this activation may refresh; false while inside the
     *         window of the last allowed one
     */
    public synchronized boolean allow(long nowMillis) {
        if (used && nowMillis - lastAllowedMillis < minIntervalMillis) {
            return false;
        }
        used = true;
        lastAllowedMillis = nowMillis;
        return true;
    }
}
