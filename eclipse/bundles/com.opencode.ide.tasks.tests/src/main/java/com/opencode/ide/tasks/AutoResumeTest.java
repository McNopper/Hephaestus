package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/**
 * AC5 (owner rule 2026-11-20, the pump): a ticket born {@code paused} on an open basis resumes
 * paused -&gt; ready automatically the moment its basis completes - through BOTH terminal verdicts
 * ({@code done} and {@code not-implemented}) - and records the resume in its history as {@code
 * resumed: basis <id> completed}.
 */
public class AutoResumeTest extends StoreTestHarness {

  private Task basis() {
    return store.create("p", TaskStore.CreateSpec.of("upstream work"));
  }

  private Task pausedChildOf(Task basis) {
    Task child =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                "dependent work",
                null,
                "task",
                "developer",
                "medium",
                null,
                null,
                null,
                null,
                null,
                null,
                basis.id),
            "implementation");
    assertEquals("born paused while the basis is open", "paused", child.status);
    return child;
  }

  private static boolean resumedOn(Task child, String basisId) {
    String expected = "resumed: basis " + basisId + " completed";
    return child.history != null
        && child.history.stream().anyMatch(h -> h != null && expected.equals(h.action()));
  }

  @Test
  public void completingTheBasisAsDoneResumesThePausedChild() {
    Task basis = basis();
    Task child = pausedChildOf(basis);

    store.update("p", basis.id, Map.of("status", "done"));

    Task resumed = store.get("p", child.id);
    assertEquals("ready", resumed.status);
    assertEquals(null, resumed.resumeTo);
    assertTrue(
        "the pump records 'resumed: basis " + basis.id + " completed'",
        resumedOn(resumed, basis.id));
  }

  @Test
  public void completingTheBasisAsNotImplementedResumesThePausedChild() {
    Task basis = basis();
    Task child = pausedChildOf(basis);

    store.update("p", basis.id, Map.of("status", "not-implemented"));

    Task resumed = store.get("p", child.id);
    assertEquals("ready", resumed.status);
    assertEquals(null, resumed.resumeTo);
    assertTrue(
        "the pump fires on not-implemented too (a DONE state)", resumedOn(resumed, basis.id));
  }
}
