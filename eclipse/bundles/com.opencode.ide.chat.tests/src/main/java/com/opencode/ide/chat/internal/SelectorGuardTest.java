package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the deliberate-pick semantics of {@link SelectorGuard} (the mouse-over/model-flip
 * fix): armed picks commit, wheel/keyboard drift reverts, programmatic loads move the baseline,
 * abandoned dropdowns disarm.
 */
public class SelectorGuardTest {

  @Test
  public void unArmedSelectionIsDriftAndReportsNull() {
    SelectorGuard guard = new SelectorGuard("llm-1");
    assertNull("wheel drift must not commit", guard.attempt("llm-3"));
    assertEquals("revert target stays the committed value", "llm-1", guard.committed());
  }

  @Test
  public void armedPickCommitsAndConsumesTheArm() {
    SelectorGuard guard = new SelectorGuard("llm-1");
    guard.arm();
    assertEquals("llm-3", guard.attempt("llm-3"));
    assertEquals("llm-3", guard.committed());
    assertNull("the arm is one-shot: the next drift still reverts", guard.attempt("llm-4"));
    assertEquals("llm-3", guard.committed());
  }

  @Test
  public void enterPathArmsAndCommitsImmediately() {
    SelectorGuard guard = new SelectorGuard("build");
    guard.arm(); // DefaultSelection = the same deliberate intent
    assertEquals("review", guard.attempt("review"));
  }

  @Test
  public void focusOutDisarmsAnAbandonedDropdown() {
    SelectorGuard guard = new SelectorGuard("llm-1");
    guard.arm(); // dropdown opened...
    guard.disarm(); // ...and abandoned (focus left without a pick)
    assertNull("a later drift must not ride the stale arm", guard.attempt("llm-3"));
  }

  @Test
  public void resetMovesTheBaselineForProgrammaticLoads() {
    SelectorGuard guard = new SelectorGuard("llm-1");
    guard.arm();
    guard.reset("llm-2"); // async load / preference preselect
    assertNull("arm cleared by the load", guard.attempt("llm-3"));
    assertEquals("drift now reverts to the loaded value", "llm-2", guard.committed());
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
