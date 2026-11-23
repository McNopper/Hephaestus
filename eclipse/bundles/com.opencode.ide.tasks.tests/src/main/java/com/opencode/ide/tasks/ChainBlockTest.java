package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

/**
 * AC4 (owner rule 2026-11-20, the chain): blocking a leaf walks UP the basis chain transitively to
 * the requirement - every hop records the origin ({@code blocked by chain from <id>}) and carries
 * the {@code [chain: <id>] } marker on its blocker while its own pre-block state is kept in {@code
 * resume_to}; clearing the origin walks DOWN the same chain, restoring each hop's exact pre-block
 * state from {@code resume_to} (fallback ready when it is unset or invalid). Both directions plus
 * the round trip.
 */
public class ChainBlockTest extends StoreTestHarness {

  private Task create(String title, String stage, String basis) {
    return store.create(
        "p",
        new TaskStore.CreateSpec(
            title, null, "task", "developer", "medium", null, null, null, null, null, null, basis),
        stage);
  }

  private static boolean historyHas(Task t, String action) {
    return t.history != null
        && t.history.stream().anyMatch(h -> h != null && action.equals(h.action()));
  }

  @Test
  public void blockingTheLeafWalksUpToTheRequirementAndClearingWalksBackDown() {
    Task req = create("the requirement", "requirements", null);
    Task sys = create("the system", "system", req.id);
    Task impl = create("the implementation", "implementation", sys.id);
    // birth rule cross-pin: ready without a basis, paused while the basis chain is open
    assertEquals("ready", req.status);
    assertEquals("paused", sys.status);
    assertEquals("paused", impl.status);
    // a leaf can only be blocked out of a claimable state (paused is never blocked directly)
    impl = store.update("p", impl.id, Map.of("status", "ready"));

    Task blocked = store.setBlocked("p", impl.id, "missing information", "tester");

    // UP-WALK: every ancestor is blocked, each remembering where clearing returns it
    String marker = "[chain: " + impl.id + "] ";
    assertEquals("blocked", blocked.status);
    assertEquals("ready", blocked.resumeTo);
    assertEquals("missing information", blocked.blocker);
    Task sysBlocked = store.get("p", sys.id);
    assertEquals("blocked", sysBlocked.status);
    assertEquals("paused", sysBlocked.resumeTo);
    assertTrue(sysBlocked.blocker, sysBlocked.blocker.startsWith(marker));
    assertTrue(
        "the origin is recorded on the hop",
        historyHas(sysBlocked, "blocked by chain from " + impl.id));
    Task reqBlocked = store.get("p", req.id);
    assertEquals("blocked", reqBlocked.status);
    assertEquals("ready", reqBlocked.resumeTo);
    assertTrue(reqBlocked.blocker, reqBlocked.blocker.startsWith(marker));
    assertTrue(
        "the origin is recorded on the requirement",
        historyHas(reqBlocked, "blocked by chain from " + impl.id));

    // DOWN-WALK: clearing the origin restores every hop's exact pre-block state
    Task cleared = store.clearBlocked("p", impl.id, "tester");
    assertEquals("ready", cleared.status);
    assertEquals(null, cleared.resumeTo);
    assertEquals(null, cleared.blocker);
    assertTrue(historyHas(cleared, "unblocked"));
    Task sysRestored = store.get("p", sys.id);
    assertEquals("paused (via resume_to, not the ready fallback)", "paused", sysRestored.status);
    assertEquals(null, sysRestored.resumeTo);
    assertEquals(null, sysRestored.blocker);
    assertFalse(sysRestored.isBlocked());
    assertTrue(historyHas(sysRestored, "unblocked (chain from " + impl.id + ")"));
    Task reqRestored = store.get("p", req.id);
    assertEquals("ready", reqRestored.status);
    assertEquals(null, reqRestored.resumeTo);
    assertEquals(null, reqRestored.blocker);
    assertFalse(reqRestored.isBlocked());
    assertTrue(historyHas(reqRestored, "unblocked (chain from " + impl.id + ")"));

    // the full round trip leaves no residue: nothing in the chain reads blocked anymore
    assertTrue(
        "every hop of the chain is clear after the round trip",
        store.list("p", null, null, true).isEmpty());
  }

  @Test
  public void aChainHopWithAnInvalidResumeToFallsBackToReady() throws Exception {
    Task req = create("req", "requirements", null);
    Task sys = create("sys", "system", req.id);
    Task impl = create("impl", "implementation", sys.id);
    store.update("p", impl.id, Map.of("status", "ready"));
    store.setBlocked("p", impl.id, "missing information", "tester");

    // corrupt the hop's remembered pre-block state (legacy/hand-edit shape): the clear must
    // fall back to ready instead of restoring garbage
    Path file = tmp.getRoot().toPath().resolve("tasks").resolve("p").resolve(sys.id + ".md");
    String text = Files.readString(file);
    assertTrue("resume_to is persisted on the blocked hop", text.contains("resume_to: paused"));
    Files.writeString(file, text.replace("resume_to: paused", "resume_to: no-such-status"));

    TaskStore reloaded = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    reloaded.clearBlocked("p", impl.id, "tester");

    Task hop = reloaded.get("p", sys.id);
    assertFalse(hop.isBlocked());
    assertEquals("ready (the fallback)", "ready", hop.status);
    assertEquals(null, hop.resumeTo);
    assertEquals(null, hop.blocker);
  }
}
