package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.opencode.ide.fleet.dispatch.AutoDispatch;
import com.opencode.ide.fleet.dispatch.RecurringWaves;
import com.opencode.ide.tasks.TaskStore;

/**
 * U-022: engine tests for the recurring-waves loop over a real {@link TaskStore}
 * with fake running/launch seams and manually driven cycles (deterministic —
 * the loop's own executor is never started). Pins the acceptance contract:
 * wave-to-wave planning with no human click, parking + NEEDS-HUMAN summary on
 * blocked tickets with automatic resume on unblock, the budget hard stop,
 * the clean nothing-plannable stop, and the drain semantics of an adopted
 * wave.
 */
public class RecurringWavesTest {

    private static final String PROJECT = "p";

    private Path base;
    private TaskStore store;
    private final AtomicReference<Set<String>> running = new AtomicReference<>(Set.of());
    private final List<String> launched = new CopyOnWriteArrayList<>();
    private final List<String> notices = new CopyOnWriteArrayList<>();
    private MutableClock clock;

    /** A hand-advanceable clock for the summary-cadence assertions. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Before
    public void setUp() throws Exception {
        base = Files.createTempDirectory("opencode-recurring-waves").toAbsolutePath().normalize();
        store = new TaskStore(base.resolve("tasks"));
        launched.clear();
        notices.clear();
        running.set(Set.of());
        clock = new MutableClock(Instant.parse("2026-09-18T10:00:00Z"));
    }

    @After
    public void tearDown() {
        if (base != null && Files.isDirectory(base)) {
            try (var walk = Files.walk(base)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().setWritable(true));
                try (var again = Files.walk(base)) {
                    again.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // best effort on Windows
                        }
                    });
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /** A requirements-stage ticket in the product backlog with the given priority. */
    private String backlogTicket(String title, String priority) {
        var t = store.create(PROJECT, new TaskStore.CreateSpec(
                title, "", "task", "developer", priority, 0, List.of(), List.of(), null, "T"));
        store.update(PROJECT, t.id, java.util.Map.of("stage", "requirements"));
        return t.id;
    }

    private RecurringWaves loop(int maxConcurrent, double budget) {
        return new RecurringWaves(store, PROJECT, AutoDispatch.of(maxConcurrent, budget, false),
                null, running::get, (id, attempt) -> {
                    launched.add(id);
                    // simulate the fleet claiming the ticket: in-progress + live job
                    store.update(PROJECT, id, java.util.Map.of("status", "in-progress"));
                    Set<String> next = new HashSet<>(running.get());
                    next.add(id);
                    running.set(Set.copyOf(next));
                }, notices::add, clock);
    }

    private void settleDone(String id) {
        // model time passing: the drain's launch-echo hold (2 min) must have
        // expired so a settled launch no longer consumes capacity
        clock.advance(Duration.ofMinutes(3));
        running.set(Set.of());
        store.update(PROJECT, id, java.util.Map.of("status", "done"));
    }

    @Test
    public void plansWavesFromTheBacklogAndMovesOnWithoutAHumanClick() {
        String high = backlogTicket("high", "high");
        String medium = backlogTicket("medium", "medium");
        String low = backlogTicket("low", "low");
        RecurringWaves waves = loop(2, 0);

        RecurringWaves.Status first = waves.tick();

        // wave 1: the top two priorities, launched by the same cycle
        assertEquals(1, first.wavesPlanned());
        assertNotNull(first.wave());
        assertEquals(List.of(high, medium), launched);
        assertEquals("product-backlog", store.get(PROJECT, low).status);

        // wave 1 drains (both done) → the NEXT wave plans itself from the backlog
        settleDone(high);
        settleDone(medium);
        RecurringWaves.Status second = waves.tick();

        assertEquals(2, second.wavesPlanned());
        assertEquals(List.of(high, medium, low), launched);
        assertEquals(second.wave(), store.get(PROJECT, low).sprint);
        assertTrue("each wave is a fresh sprint", !first.wave().equals(second.wave()));

        // wave 2 drains → nothing plannable, nothing blocked → clean stop
        settleDone(low);
        RecurringWaves.Status third = waves.tick();

        assertFalse(third.running());
        assertEquals(RecurringWaves.StopReason.NOTHING_PLANNABLE, third.stopReason());
        waves.stop();
    }

    @Test
    public void blockedTicketsParkTheLoopAsNeedsHumanAndUnblockingResumesIt() {
        String blocked = backlogTicket("needs the owner", "high");
        store.setBlocked(PROJECT, blocked, "waiting on the product owner", "test");
        RecurringWaves waves = loop(2, 0);

        RecurringWaves.Status parked = waves.tick();

        // no wave is planned from a blocked ticket; the loop parks (no stop reason) and surfaces the escalation
        assertEquals(0, parked.wavesPlanned());
        assertNull("parking is not stopping — the loop stays enabled", parked.stopReason());
        assertEquals(1, parked.needsHuman().size());
        assertEquals(blocked, parked.needsHuman().get(0).id());
        assertTrue(notices.toString(), notices.stream().anyMatch(n -> n.contains("NEEDS-HUMAN")
                && n.contains(blocked)));

        // the periodic summary repeats only after the cadence period
        clock.advance(Duration.ofMinutes(1));
        waves.tick();
        assertEquals(1, notices.stream().filter(n -> n.contains("NEEDS-HUMAN")).count());
        clock.advance(Duration.ofMinutes(5));
        waves.tick();
        assertEquals(2, notices.stream().filter(n -> n.contains("NEEDS-HUMAN")).count());

        // clearing the blocker resumes the loop automatically — no click, no restart
        store.clearBlocked(PROJECT, blocked, "owner");
        RecurringWaves.Status resumed = waves.tick();

        assertEquals(1, resumed.wavesPlanned());
        assertEquals(List.of(blocked), launched);
        assertTrue(resumed.needsHuman().isEmpty());
        waves.stop();
    }

    @Test
    public void aBlockedTicketBeingRetriedIsNotYetAtTheHuman() {
        String retrying = backlogTicket("retry in flight", "high");
        store.setBlocked(PROJECT, retrying, "transient failure", "test");
        running.set(Set.of(retrying)); // a live fleet job owns the retry
        RecurringWaves waves = loop(2, 0);

        RecurringWaves.Status status = waves.tick();

        assertTrue(status.needsHuman().isEmpty());
        // the retry settles still blocked → the ticket lights up NEEDS-HUMAN
        running.set(Set.of());
        RecurringWaves.Status after = waves.tick();
        assertEquals(1, after.needsHuman().size());
        waves.stop();
    }

    @Test
    public void budgetExhaustionIsACleanHardStop() {
        String one = backlogTicket("one", "high");
        RecurringWaves waves = loop(2, 0.10);

        waves.tick(); // wave 1 planned and launched (estimate 0.05 each, budget 0.10)
        assertEquals(1, waves.status().wavesPlanned());

        // wave 1 settles with recorded spend that leaves no room for even one launch
        settleDone(one);
        store.addComment(PROJECT, one,
                "fleet actuals: cost 0.09 USD, tokens 100 (in 50 / out 25 / reasoning 25), agent a, model m",
                "fleet");
        backlogTicket("two", "medium"); // still plannable backlog — the budget must refuse it

        RecurringWaves.Status stopped = waves.tick();

        assertFalse(stopped.running());
        assertEquals(RecurringWaves.StopReason.BUDGET_EXHAUSTED, stopped.stopReason());
        assertEquals(1, stopped.wavesPlanned());
        assertEquals(0, stopped.needsHuman().size());
        assertTrue(notices.stream().anyMatch(n -> n.contains("hard stop")));
        // a stopped loop stays frozen — further ticks do nothing
        RecurringWaves.Status still = waves.tick();
        assertEquals(RecurringWaves.StopReason.BUDGET_EXHAUSTED, still.stopReason());
        assertEquals(1, still.wavesPlanned());
    }

    @Test
    public void inFlightWaveWorkHoldsTheWaveOpen() {
        String one = backlogTicket("one", "high");
        RecurringWaves waves = loop(2, 0);
        waves.tick();
        String wave = waves.activeWave();
        assertEquals(List.of(one), launched);

        // one ticket is claimed (in-progress) — no re-planning while work is in flight
        String late = backlogTicket("late arrival", "low");
        RecurringWaves.Status status = waves.tick();

        assertEquals(1, status.wavesPlanned());
        assertEquals(wave, waves.activeWave());
        assertEquals("product-backlog", store.get(PROJECT, late).status);
        waves.stop();
    }

    @Test
    public void adoptsTheGivenSprintAsTheFirstWave() {
        String existing = backlogTicket("existing wave work", "high");
        store.planSprint(PROJECT, "S-01", List.of(existing), "hand-planned");
        String fresh = backlogTicket("backlog work", "low");
        RecurringWaves waves = new RecurringWaves(store, PROJECT, AutoDispatch.of(2, 0, false),
                "S-01", running::get, (id, attempt) -> launched.add(id), notices::add, clock);

        RecurringWaves.Status status = waves.tick();

        assertEquals("S-01", status.wave());
        assertEquals(0, status.wavesPlanned()); // adopted, not planned
        assertEquals(List.of(existing), launched);
        assertEquals("product-backlog", store.get(PROJECT, fresh).status);
        waves.stop();
    }

    @Test
    public void offByDefaultAndStopIsIdempotent() {
        String id = backlogTicket("idle", "high");
        RecurringWaves waves = loop(2, 0);

        assertFalse("nothing ticks until start() is called", waves.isRunning());

        waves.tick(); // a manual cycle plans and launches, but the loop is not "on"
        assertFalse(waves.isRunning());
        assertEquals(1, waves.status().wavesPlanned());

        waves.stop();
        waves.stop();
        assertEquals(RecurringWaves.StopReason.STOPPED, waves.status().stopReason());
        // frozen: further ticks change nothing
        launched.clear();
        waves.tick();
        assertTrue(launched.isEmpty());
    }

    @Test
    public void waitingUpstreamTicketsReturnToTheBacklogAndReEnterWithTheirUpstream() {
        // an epic chain in CONSECUTIVE stages: requirements parent (READY) +
        // system child (WAIT_UPSTREAM until the parent lands). Both carry
        // their stage/epic from creation (no post-create touches), so the
        // child reads READY — not STALE — once its parent finishes.
        var parent = store.create(PROJECT, new TaskStore.CreateSpec(
                "requirements first", "", "task", "pm", "high", 1, List.of(), List.of(), null, "T"),
                "requirements");
        var child = store.create(PROJECT, new TaskStore.CreateSpec(
                "system after", "", "task", "architect", "critical", 2, List.of(), List.of(), parent.id, "T"),
                "system");
        RecurringWaves waves = loop(1, 0);

        // wave 1: only the READY parent fits (child waits upstream; cap 1 anyway)
        waves.tick();
        assertEquals(List.of(parent.id), launched);

        // parent finishes → child becomes READY → the next wave takes it
        settleDone(parent.id);
        RecurringWaves.Status next = waves.tick();

        assertEquals(2, next.wavesPlanned());
        assertEquals(List.of(parent.id, child.id), launched);
        waves.stop();
    }
}
