package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the deliberate-pick semantics of {@link SelectorGuard}
 * (the mouse-over/model-flip fix): armed picks commit, wheel/keyboard drift
 * reverts, programmatic loads move the baseline, abandoned dropdowns disarm.
 */
public class SelectorGuardTest {

    @Test
    public void unArmedSelectionIsDriftAndReportsNull() {
        SelectorGuard guard = new SelectorGuard("glm");
        assertNull("wheel drift must not commit", guard.attempt("glm-4.7"));
        assertEquals("revert target stays the committed value", "glm", guard.committed());
    }

    @Test
    public void armedPickCommitsAndConsumesTheArm() {
        SelectorGuard guard = new SelectorGuard("glm");
        guard.arm();
        assertEquals("glm-4.7", guard.attempt("glm-4.7"));
        assertEquals("glm-4.7", guard.committed());
        assertNull("the arm is one-shot: the next drift still reverts", guard.attempt("glm-4.5"));
        assertEquals("glm-4.7", guard.committed());
    }

    @Test
    public void enterPathArmsAndCommitsImmediately() {
        SelectorGuard guard = new SelectorGuard("build");
        guard.arm(); // DefaultSelection = the same deliberate intent
        assertEquals("review", guard.attempt("review"));
    }

    @Test
    public void focusOutDisarmsAnAbandonedDropdown() {
        SelectorGuard guard = new SelectorGuard("glm");
        guard.arm(); // dropdown opened...
        guard.disarm(); // ...and abandoned (focus left without a pick)
        assertNull("a later drift must not ride the stale arm", guard.attempt("glm-4.7"));
    }

    @Test
    public void resetMovesTheBaselineForProgrammaticLoads() {
        SelectorGuard guard = new SelectorGuard("glm");
        guard.arm();
        guard.reset("glm-5.3"); // async load / preference preselect
        assertNull("arm cleared by the load", guard.attempt("glm-4.7"));
        assertEquals("drift now reverts to the loaded value", "glm-5.3", guard.committed());
    }

    @Test
    public void nullTolerantTexts() {
        SelectorGuard guard = new SelectorGuard(null);
        assertEquals("", guard.committed());
        guard.arm();
        assertEquals("", guard.attempt(null));
        guard.reset(null);
        assertEquals("", guard.committed());
    }
}
