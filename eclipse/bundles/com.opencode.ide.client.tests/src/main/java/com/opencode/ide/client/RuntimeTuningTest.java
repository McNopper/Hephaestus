package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link RuntimeTuning}: defaults mirror the tuning
 * constants, setters clamp instead of storing nonsense, and the knobs are
 * readable live (running workers poll them per use).
 */
public class RuntimeTuningTest {

    @Before
    public void reset() {
        RuntimeTuning.restoreDefaults();
    }

    @Test
    public void defaultsMirrorTheTuningConstants() {
        assertEquals(1_000, RuntimeTuning.pollMillis());
        assertEquals(Duration.ofMinutes(5), RuntimeTuning.stallTimeout());
        assertEquals(Duration.ofMinutes(30), RuntimeTuning.ticketBudget());
        assertEquals(8, RuntimeTuning.workerThreads());
    }

    @Test
    public void settersClampNonsenseInsteadOfStoringIt() {
        RuntimeTuning.setPollMillis(1);
        assertEquals("poll floor is 100ms", 100, RuntimeTuning.pollMillis());
        RuntimeTuning.setPollMillis(Long.MAX_VALUE);
        assertEquals("poll ceiling is 10min", 600_000, RuntimeTuning.pollMillis());

        RuntimeTuning.setStallTimeout(Duration.ofMillis(1));
        assertEquals(Duration.ofSeconds(5), RuntimeTuning.stallTimeout());
        RuntimeTuning.setStallTimeout(Duration.ofDays(9));
        assertEquals(Duration.ofHours(2), RuntimeTuning.stallTimeout());

        RuntimeTuning.setTicketBudget(Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(30), RuntimeTuning.ticketBudget());
        RuntimeTuning.setTicketBudget(null); // tolerated
        assertEquals(Duration.ofSeconds(30), RuntimeTuning.ticketBudget());

        RuntimeTuning.setWorkerThreads(0);
        assertEquals(1, RuntimeTuning.workerThreads());
        RuntimeTuning.setWorkerThreads(999);
        assertEquals(64, RuntimeTuning.workerThreads());
    }

    @Test
    public void liveChangesAreVisibleToSubsequentReads() {
        RuntimeTuning.setPollMillis(2_500);
        RuntimeTuning.setStallTimeout(Duration.ofSeconds(42));

        assertEquals("running workers see the new sleep", 2_500, RuntimeTuning.pollMillis());
        assertEquals(Duration.ofSeconds(42), RuntimeTuning.stallTimeout());
    }

    @Test
    public void restoreDefaultsPutsEverythingBack() {
        RuntimeTuning.setPollMillis(9_000);
        RuntimeTuning.setStallTimeout(Duration.ofHours(1));
        RuntimeTuning.setTicketBudget(Duration.ofHours(6));

        RuntimeTuning.restoreDefaults();

        assertEquals(1_000, RuntimeTuning.pollMillis());
        assertEquals(Duration.ofMinutes(5), RuntimeTuning.stallTimeout());
        assertEquals(Duration.ofMinutes(30), RuntimeTuning.ticketBudget());
        assertTrue(RuntimeTuning.summary().contains("poll"));
    }

    /**
     * B-015: the engine's env-parsed knob table (FleetTuning) seeds the
     * DEFAULTS — the env knob takes effect on the default path, a later
     * seed never clobbers an explicit runtime override, restore returns to
     * the seeded default, and the test seam drops the seed entirely.
     */
    @Test
    public void seededDefaultsApplyUntilOverriddenAndSurviveRestore() {
        try {
            RuntimeTuning.seedStallTimeout(Duration.ofSeconds(90));
            RuntimeTuning.seedTicketBudget(Duration.ofMinutes(90));
            assertEquals("the seeded default is live immediately", Duration.ofSeconds(90),
                    RuntimeTuning.stallTimeout());
            assertEquals(Duration.ofMinutes(90), RuntimeTuning.ticketBudget());

            RuntimeTuning.setStallTimeout(Duration.ofSeconds(42)); // the dialog override
            RuntimeTuning.seedStallTimeout(Duration.ofSeconds(120)); // a later seed must not clobber it
            assertEquals("a runtime override wins over a later seed", Duration.ofSeconds(42),
                    RuntimeTuning.stallTimeout());

            RuntimeTuning.restoreDefaults();
            assertEquals("restore returns to the SEEDED default, not the baseline", Duration.ofSeconds(120),
                    RuntimeTuning.stallTimeout());
            assertEquals(Duration.ofMinutes(90), RuntimeTuning.ticketBudget());

            RuntimeTuning.restoreDefaults();
            RuntimeTuning.seedStallTimeout(null); // ignored, like a null setter
            RuntimeTuning.seedTicketBudget(Duration.ZERO); // ignored
            assertEquals(Duration.ofSeconds(120), RuntimeTuning.stallTimeout());
            assertEquals(Duration.ofMinutes(90), RuntimeTuning.ticketBudget());
        } finally {
            RuntimeTuning.clearSeededDefaults();
        }
        assertEquals("clear drops the seed back to the baseline", Duration.ofMinutes(5),
                RuntimeTuning.stallTimeout());
        assertEquals(Duration.ofMinutes(30), RuntimeTuning.ticketBudget());
    }
}
