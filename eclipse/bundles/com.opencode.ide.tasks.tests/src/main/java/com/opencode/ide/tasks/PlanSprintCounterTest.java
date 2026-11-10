package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-094: planSprint with an EXPLICIT sprint id must advance the sprint counter exactly like the
 * auto-mint does - otherwise mixing explicit and auto ids mints an id that collides with an
 * existing explicit one (the counter never learned about it). Legacy non-{@code S-<n>} ids
 * ("wave-*") leave the counter alone; the counter never regresses; and the learned counter is
 * persisted with the store meta (it survives a reload).
 */
public class PlanSprintCounterTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  private String autoMint() {
    return store.planSprint("p", null, List.of(), null).id();
  }

  private void explicit(String id) {
    store.planSprint("p", id, List.of(), null);
  }

  @Test
  public void explicitIdTeachesTheCounter() {
    explicit("S-05");
    assertEquals("S-06", autoMint());
  }

  @Test
  public void nonMatchingExplicitIdLeavesTheCounterAlone() {
    explicit("wave-2026-11-10");
    assertEquals("S-01", autoMint());
  }

  @Test
  public void explicitLowerThanCounterDoesNotRegress() {
    assertEquals("S-01", autoMint());
    assertEquals("S-02", autoMint());
    explicit("S-01");
    assertEquals("S-03", autoMint());
  }

  @Test
  public void learnedCounterSurvivesAStoreReload() {
    explicit("S-05");
    TaskStore reopened = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    assertEquals(
        "the persisted meta counter learned S-05",
        "S-06",
        reopened.planSprint("p", null, List.of(), null).id());
  }
}
