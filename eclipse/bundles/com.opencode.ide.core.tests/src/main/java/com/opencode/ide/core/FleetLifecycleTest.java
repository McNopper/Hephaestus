package com.opencode.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link FleetLifecycle}: the fleet starts DISABLED (the
 * deliberate token-eater default) and the enable/pause/stop contract drives
 * exactly the right engine switches - pause never kills running jobs
 * (stopPumping only), stop alone takes the engine down.
 */
public class FleetLifecycleTest {

    /** Records which engine switches fired, in order. */
    private static final class RecordingActions implements FleetLifecycle.Actions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void startPumping() {
            calls.add("startPumping");
        }

        @Override
        public void stopPumping() {
            calls.add("stopPumping");
        }

        @Override
        public void stopEngine() {
            calls.add("stopEngine");
        }
    }

    @Test
    public void theFleetIsDisabledByDefault() {
        FleetLifecycle lifecycle = new FleetLifecycle();

        assertEquals(FleetLifecycle.State.DISABLED, lifecycle.state());
        assertTrue(lifecycle.label(), lifecycle.label().contains("DISABLED"));
        assertTrue("a disabled fleet can be enabled", lifecycle.canEnable());
        assertFalse(lifecycle.canPause());
        assertFalse(lifecycle.canStop());
    }

    @Test
    public void enableArmsThePumpsAndPauseOnlyDisarmsThem() {
        FleetLifecycle lifecycle = new FleetLifecycle();
        RecordingActions actions = new RecordingActions();
        lifecycle.setActions(actions);

        lifecycle.enable();
        assertEquals(FleetLifecycle.State.ACTIVE, lifecycle.state());
        assertTrue(lifecycle.canPause());

        lifecycle.pause();
        assertEquals(FleetLifecycle.State.PAUSED, lifecycle.state());
        assertEquals("pause must never touch the engine - running jobs settle",
                List.of("startPumping", "stopPumping"), actions.calls);
        assertTrue(lifecycle.canEnable());
    }

    @Test
    public void stopTakesTheEngineDownAndReturnsToDisabled() {
        FleetLifecycle lifecycle = new FleetLifecycle();
        RecordingActions actions = new RecordingActions();
        lifecycle.setActions(actions);

        lifecycle.enable();
        lifecycle.stop();
        assertEquals(FleetLifecycle.State.DISABLED, lifecycle.state());
        assertEquals(List.of("startPumping", "stopPumping", "stopEngine"), actions.calls);

        lifecycle.stop(); // idempotent
        assertEquals(List.of("startPumping", "stopPumping", "stopEngine"), actions.calls);
    }

    @Test
    public void transitionsFireThePanelListenerAndSurviveThrowingParts() {
        FleetLifecycle lifecycle = new FleetLifecycle();
        lifecycle.setActions(new FleetLifecycle.Actions() {
            @Override
            public void startPumping() {
                throw new IllegalStateException("engine refused to start");
            }
        });
        List<FleetLifecycle.State> seen = new ArrayList<>();
        lifecycle.addListener(seen::add);
        lifecycle.addListener(state -> {
            throw new IllegalStateException("a broken panel");
        });

        lifecycle.enable(); // the broken switch AND the broken listener are contained

        assertEquals(FleetLifecycle.State.ACTIVE, lifecycle.state());
        assertEquals(List.of(FleetLifecycle.State.ACTIVE), seen);
    }
}
