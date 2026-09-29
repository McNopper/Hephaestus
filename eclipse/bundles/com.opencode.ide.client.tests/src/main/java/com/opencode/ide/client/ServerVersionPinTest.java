package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Test;

import com.opencode.ide.client.ServerVersionPin.Verdict;
import com.opencode.ide.client.model.HealthStatus;

/**
 * Unit tests for {@link ServerVersionPin}: the pinned-version verdict over
 * one {@link HealthStatus} — matching (whitespace tolerated), mismatch
 * (healthy flag irrelevant, warning naming both versions and the remedy),
 * and the unknown cases (null snapshot, null or blank version) — plus the
 * never-throws contract.
 */
public class ServerVersionPinTest {

    /**
     * The live pin, read reflectively on purpose: {@code PINNED_VERSION} is
     * a compile-time constant, so a direct reference would be INLINED into
     * this class at compile time - after a pin bump, an incrementally
     * compiled stale test class compares the OLD inlined pin against the NEW
     * runtime pin and fails spuriously (seen 2026-09-29, bumping 2.0.10 ->
     * 2.0.19). Reflection always reads the live value.
     */
    private static final String PINNED = livePin();

    private static String livePin() {
        try {
            Field pin = ServerVersionPin.class.getField("PINNED_VERSION");
            return (String) pin.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PINNED_VERSION must stay a public static field", e);
        }
    }

    @Test
    public void pinnedVersionIsMatching() {
        Verdict verdict = ServerVersionPin.evaluate(new HealthStatus(true, PINNED));
        assertEquals(Verdict.Kind.MATCHING, verdict.kind());
        assertEquals(PINNED, verdict.serverVersion());
        assertNull(verdict.warning());
    }

    @Test
    public void surroundingWhitespaceStillMatchesAndIsNormalized() {
        Verdict verdict = ServerVersionPin.evaluate(new HealthStatus(true, "  " + PINNED + " "));
        assertEquals(Verdict.Kind.MATCHING, verdict.kind());
        assertEquals(PINNED, verdict.serverVersion());
    }

    @Test
    public void otherVersionIsMismatchCarryingTheSeenVersion() {
        Verdict verdict = ServerVersionPin.evaluate(new HealthStatus(true, "1.19.0"));
        assertTrue(verdict.isMismatch());
        assertEquals(Verdict.Kind.MISMATCH, verdict.kind());
        assertEquals("1.19.0", verdict.serverVersion());
    }

    @Test
    public void theHealthyFlagIsNotPartOfTheVerdict() {
        assertEquals(Verdict.Kind.MISMATCH,
                ServerVersionPin.evaluate(new HealthStatus(false, "1.19.0")).kind());
        assertEquals(Verdict.Kind.MATCHING,
                ServerVersionPin.evaluate(new HealthStatus(false, PINNED)).kind());
    }

    @Test
    public void mismatchWarningNamesBothVersionsAndTheRemedy() {
        String warning = ServerVersionPin.evaluate(new HealthStatus(true, "1.19.0")).warning();
        assertEquals("opencode server 1.19.0 != pinned " + PINNED
                + " - the endpoint cross-check may have rotted; rerun the endpoint smoke", warning);
    }

    @Test
    public void nullVersionIsUnknown() {
        Verdict verdict = ServerVersionPin.evaluate(new HealthStatus(true, null));
        assertEquals(Verdict.Kind.UNKNOWN, verdict.kind());
        assertNull(verdict.serverVersion());
        assertNull(verdict.warning());
    }

    @Test
    public void blankVersionIsUnknown() {
        for (String blank : new String[] { "", "   ", "\t" }) {
            Verdict verdict = ServerVersionPin.evaluate(new HealthStatus(true, blank));
            assertEquals("blank reads as UNKNOWN: '" + blank + "'", Verdict.Kind.UNKNOWN, verdict.kind());
            assertNull("no warning without a version", verdict.warning());
        }
    }

    @Test
    public void nullSnapshotIsUnknownAndNeverThrows() {
        Verdict verdict = ServerVersionPin.evaluate(null);
        assertEquals(Verdict.Kind.UNKNOWN, verdict.kind());
        assertNull(verdict.serverVersion());
        assertNull(verdict.warning());
    }
}
