package com.opencode.ide.board.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * U-035: the board's activation refresh passes a pure rate-limit gate
 * ({@link RefreshGate}) - the first activation refreshes, further ones only
 * after the minimum interval, a denied activation never extends the window,
 * and gates are independent per view. Pure clock-in arithmetic: no sleeps.
 */
public class RefreshGateTest {

    /** The very first activation may refresh (a fresh view is never stale-blocked). */
    @Test
    public void firstActivationIsAllowed() {
        RefreshGate gate = new RefreshGate(5000);

        assertTrue(gate.allow(1_000_000L));
    }

    /** Tabbing back inside the window must not churn the refresh pipeline. */
    @Test
    public void activationsInsideTheWindowAreSuppressed() {
        RefreshGate gate = new RefreshGate(5000);
        assertTrue(gate.allow(1_000_000L));

        assertFalse("2s later is still inside the window", gate.allow(1_002_000L));
        assertFalse("4.9s after that too", gate.allow(1_004_999L));
    }

    /** The window is measured from the last ALLOWED activation, never extended by denials. */
    @Test
    public void deniedActivationsDoNotExtendTheWindow() {
        RefreshGate gate = new RefreshGate(5000);
        assertTrue(gate.allow(1_000_000L));
        assertFalse(gate.allow(1_004_999L));

        assertTrue("opens 5s after the allowed one, not after the denied one",
                gate.allow(1_005_000L));
    }

    /** Exactly at the interval boundary the next activation passes again. */
    @Test
    public void theWindowReopensAtExactlyTheInterval() {
        RefreshGate gate = new RefreshGate(5000);
        assertTrue(gate.allow(1_000_000L));
        assertTrue(gate.allow(1_005_000L));

        assertFalse(gate.allow(1_005_001L));
    }

    /** A zero interval degenerates to always-allow (no rate limit). */
    @Test
    public void zeroIntervalAlwaysAllows() {
        RefreshGate gate = new RefreshGate(0);

        assertTrue(gate.allow(1L));
        assertTrue(gate.allow(1L));
        assertTrue(gate.allow(2L));
    }

    /** Each view owns its gate: one view's refresh never blocks another's. */
    @Test
    public void gatesAreIndependent() {
        RefreshGate first = new RefreshGate(5000);
        RefreshGate second = new RefreshGate(5000);
        assertTrue(first.allow(1_000_000L));

        assertFalse(first.allow(1_000_001L));
        assertTrue("a second view's gate keeps its own window", second.allow(1_000_001L));
    }
}
