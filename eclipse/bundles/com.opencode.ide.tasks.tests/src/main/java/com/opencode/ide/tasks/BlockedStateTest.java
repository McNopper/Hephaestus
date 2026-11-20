package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-067 contracts: blocked is a STATE, not a flag. Entering remembers where clearing returns
 * (resume_to), the board's status+blocked drop contract applies order-free as one move, legacy
 * files migrate on read, done tickets can never stay blocked (commit-time invariant), blocked
 * tickets cannot be planned or advanced, and wave close keeps the state instead of silently
 * unblocking.
 */
public class BlockedStateTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;

  @Before
  public void setUp() {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
  }

  private Task staged(String title) {
    Task t =
        store.create(
            "p", new TaskStore.CreateSpec(title, "", "task", "dev", "low", 1, null, null, "T"));
    return store.update("p", t.id, Map.of("stage", "design"));
  }

  private Task writeTicketFile(String id, String frontmatter) throws Exception {
    Path dir = tmp.getRoot().toPath().resolve("tasks").resolve("p");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(id + ".md"), "---\n" + frontmatter + "---\n");
    return store.get("p", id);
  }

  @Test
  public void blockedIsAStatusInTheEnum() {
    assertTrue(Task.VALID_STATUSES.contains("blocked"));
    assertEquals(7, Task.VALID_STATUSES.size());
  }

  @Test
  public void setBlockedRemembersTheStatusAndClearingReturnsToIt() {
    Task t = staged("remember");
    store.update("p", t.id, Map.of("status", "in-review"));
    Task blocked = store.setBlocked("p", t.id, "waiting for the PO", "pm");
    assertEquals("blocked", blocked.status);
    assertEquals("in-review", blocked.resumeTo);
    assertTrue(blocked.isBlocked());
    // persisted through the file (status + resume_to round-trip)
    Task reloaded = new TaskStore(tmp.getRoot().toPath().resolve("tasks")).get("p", t.id);
    assertEquals("blocked", reloaded.status);
    assertEquals("in-review", reloaded.resumeTo);
    Task cleared = store.clearBlocked("p", t.id, "pm");
    assertEquals("in-review", cleared.status);
    assertFalse(cleared.isBlocked());
    assertEquals(null, cleared.resumeTo);
    assertEquals(null, cleared.blocker);
  }

  @Test
  public void blockingEntersTheStateAndClearingLandsBackWhereItWas() {
    // U-110: tickets are stationary - escalation blocks the ticket IN its stage (no movement)
    Task t = staged("handback");
    Task back =
        store.update(
            "p",
            t.id,
            Map.of("blocked", true, "blocker", "sent back from architecture: the design lies"));
    assertEquals("blocked", back.status);
    assertEquals("ready", back.resumeTo);
    assertEquals("design", back.stage);
    assertTrue(back.blocker.contains("the design lies"));
    Task cleared = store.clearBlocked("p", back.id, "dev");
    assertEquals("ready", cleared.status);
    assertEquals(null, cleared.blocker);
  }

  @Test
  public void updateAppliesStatusAndBlockedAsOneOrderFreeMove() {
    // the board's backward drop contract: any iteration order of the
    // change map must yield resume_to=ready, status=blocked
    for (int attempt = 0; attempt < 4; attempt++) {
      Task t = staged("drop" + attempt);
      Map<String, Object> changes = new HashMap<>();
      changes.put("stage", "architecture");
      changes.put("status", "ready");
      changes.put("assignee", null);
      changes.put("blocked", true);
      changes.put("blocker", "sent back from design: reason");
      Task moved = store.update("p", t.id, changes);
      assertEquals("blocked", moved.status);
      assertEquals("ready", moved.resumeTo);
      assertEquals("architecture", moved.stage);
    }
  }

  @Test
  public void clearWithoutResumeFallsBackToReady() throws Exception {
    Task t =
        writeTicketFile(
            "B-9", "id: B-9\ntitle: stuck\nstatus: blocked\nresume_to: no-such-status\n");
    assertTrue(t.isBlocked());
    Task cleared = store.clearBlocked("p", "B-9", "pm");
    assertEquals("ready", cleared.status);
    assertEquals(null, cleared.resumeTo);
  }

  @Test
  public void legacyFlagFileMigratesOnRead() throws Exception {
    Task t =
        writeTicketFile(
            "B-8", "id: B-8\ntitle: legacy\nstatus: in-review\nblocked: true\nblocker: old flag\n");
    assertEquals("blocked", t.status);
    assertEquals("in-review", t.resumeTo);
    assertTrue(
        "the migration is recorded once",
        t.history.stream().anyMatch(h -> h.action().contains("migrated")));
  }

  @Test
  public void doneNeverKeepsTheStateOrItsReason() {
    Task t = staged("finish");
    store.setBlocked("p", t.id, "hold", "pm");
    // U-110: a definition-stage ticket carries its product before it can be done
    store.addArtifact("p", t.id, "doc", "docs/design-product.md", "stage product", null);
    Task done = store.update("p", t.id, Map.of("status", "done"));
    assertFalse(done.isBlocked());
    assertEquals(null, done.resumeTo);
    assertEquals(null, done.blocker);
  }

  @Test
  public void blockedTicketsCannotBeClaimed() {
    Task t = staged("frozen");
    store.update("p", t.id, Map.of("status", "in-review"));
    store.setBlocked("p", t.id, "hold", "pm");
    assertTrue(
        "a blocked ticket never surfaces in a claim",
        store.claim("p", "developer", null, null) == null);
    assertEquals("blocked", store.get("p", t.id).status);
  }

  @Test
  public void listFilterSpeaksTheState() {
    Task t = staged("filter");
    store.setBlocked("p", t.id, "hold", "pm");
    List<Task> onlyBlocked = store.list("p", null, null, true);
    assertTrue(onlyBlocked.stream().anyMatch(x -> x.id.equals(t.id)));
    List<Task> onlyUnblocked = store.list("p", null, null, false);
    assertTrue(onlyUnblocked.stream().noneMatch(x -> x.id.equals(t.id)));
  }
}
