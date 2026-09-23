package com.opencode.ide.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The fleet lifecycle - ONE state machine behind the prominent
 * Enable/Pause/Stop control (user requirement 2026-09-23: "the fleet is
 * disabled by default; enable, pause and stop must be prominently visible
 * in the panels").
 *
 * <ul>
 *   <li><b>DISABLED</b> (default): the fleet does not dispatch anything -
 *       this is the deliberate starting state (the token-eater rule).</li>
 *   <li><b>ACTIVE</b> ({@link #enable()}): the dispatch pumps are armed
 *       (auto-dispatch / recurring waves); tickets flow.</li>
 *   <li><b>PAUSED</b> ({@link #pause()}): no NEW dispatches; already
 *       running jobs settle normally (never killed).</li>
 * </ul>
 * {@link #stop()} returns to DISABLED and additionally takes the engine
 * down (its spawned {@code opencode serve} goes with it).
 *
 * <p>The engine side is injected ({@link Actions}) so the SWT panels stay
 * thin and this contract stays unit-testable without a workbench.</p>
 */
public final class FleetLifecycle {

    /** The fleet's pump/engine switches, wired by the board bundle to the real engine. */
    public interface Actions {
        /** Arms the dispatch pumps (auto-dispatch and/or recurring waves). */
        default void startPumping() {
        }

        /** Disarms the dispatch pumps; already running jobs settle normally. */
        default void stopPumping() {
        }

        /** Takes the fleet engine down (pause never calls this; stop does). */
        default void stopEngine() {
        }
    }

    public enum State {
        DISABLED, ACTIVE, PAUSED
    }

    private static final FleetLifecycle INSTANCE = new FleetLifecycle();

    public static FleetLifecycle getDefault() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private State state = State.DISABLED; // the fleet is disabled by default
    private Actions actions = new Actions() {
    };

    /**
     * Visible for tests and for embedders that want an isolated state
     * machine - the workbench panels use {@link #getDefault()} (OSGi note:
     * package-private would not work from the tests bundle anyway; split
     * class loaders make same-package access an IllegalAccessError).
     */
    public FleetLifecycle() {
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Wires the engine switches exactly once (the board bundle does this at startup). */
    public void setActions(Actions newActions) {
        synchronized (lock) {
            actions = newActions == null ? new Actions() {
            } : newActions;
        }
    }

    /** The short status text for the control ("Fleet: disabled" etc.). */
    public String label() {
        switch (state()) {
            case ACTIVE:
                return "Fleet: ACTIVE - dispatching";
            case PAUSED:
                return "Fleet: PAUSED - running jobs settle";
            default:
                return "Fleet: DISABLED - nothing dispatches";
        }
    }

    public boolean canEnable() {
        return state() != State.ACTIVE;
    }

    public boolean canPause() {
        return state() == State.ACTIVE;
    }

    public boolean canStop() {
        return state() != State.DISABLED;
    }

    /** Enable: the fleet may dispatch - arms the pumps. */
    public void enable() {
        Actions current;
        synchronized (lock) {
            if (state == State.ACTIVE) {
                return;
            }
            state = State.ACTIVE;
            current = actions;
        }
        safely(current::startPumping);
        fire();
    }

    /** Pause: no NEW dispatches; already running jobs settle normally (never killed). */
    public void pause() {
        Actions current;
        synchronized (lock) {
            if (state != State.ACTIVE) {
                return;
            }
            state = State.PAUSED;
            current = actions;
        }
        safely(current::stopPumping);
        fire();
    }

    /** Stop: pumps off AND the engine down - back to the disabled default. */
    public void stop() {
        Actions current;
        synchronized (lock) {
            if (state == State.DISABLED) {
                return;
            }
            state = State.DISABLED;
            current = actions;
        }
        safely(current::stopPumping);
        safely(current::stopEngine);
        fire();
    }

    /** Panel refresh hook; the listener runs on the caller's thread. */
    public void addListener(Consumer<State> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<State> listener) {
        listeners.remove(listener);
    }

    /** A broken engine switch must never break the control - and never flip the state back. */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // the state machine already moved; the engine failure surfaces
            // in the panels' own error reporting
        }
    }

    private void fire() {
        State current = state();
        for (Consumer<State> listener : listeners) {
            try {
                listener.accept(current);
            } catch (RuntimeException ignored) {
                // a broken panel listener must not break the others
            }
        }
    }
}
