package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-025 archive semantics (user direction 2026-09-19): archived tickets move to {@code _archive/},
 * leave the live listing (board, readiness, dispatch), stay readable via {@code archived()}, and a
 * closing wave archives its done tickets automatically.
 */
public class TaskStoreArchiveTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private TaskStore store;
  private Path projectDir;

  @Before
  public void setUp() throws Exception {
    store = new TaskStore(tmp.getRoot().toPath().resolve("tasks"));
    projectDir = store.root().resolve(TaskStore.sanitizeProject("p"));
    Files.createDirectories(projectDir);
  }

  private String create() {
    return store.create("p", TaskStore.CreateSpec.of("some work")).id;
  }

  @Test
  public void archivedTicketLeavesTheLiveListingButStaysReadable() {
    String id = create();
    store.update("p", id, java.util.Map.of("status", (Object) "done"));

    store.archive("p", id, "test");

    assertTrue("live file moved", Files.notExists(projectDir.resolve(id + ".md")));
    assertTrue(
        "archive file written",
        Files.isRegularFile(projectDir.resolve("_archive").resolve(id + ".md")));
    assertTrue("live listing is empty", store.list("p", null, null, null).isEmpty());
    List<Task> archived = store.archived("p");
    assertEquals(1, archived.size());
    assertEquals(id, archived.get(0).id);
    assertEquals("done", archived.get(0).status);
    assertTrue(
        "history records the archive",
        archived.get(0).history.stream()
            .anyMatch(h -> h.action() != null && h.action().contains("archived")));
  }

  @Test
  public void archiveOfUnknownTicketFails() {
    try {
      store.archive("p", "T-999", "test");
      org.junit.Assert.fail("expected NotFound");
    } catch (RuntimeException expected) {
      // NotFound from require()
    }
  }

  @Test
  public void doneTicketsStayVisibleForTheReadinessChain() {
    // U-025 lesson (live 2026-09-19): archiving orphaned WAIT_UPSTREAM
    // children - the done upstream must stay visible to the readiness
    // basis chain; archiving stays manual until readiness consults the
    // archive (task_archive_done is the chain-safe sweep).
    String done = create();
    String wip = create();
    store.update("p", done, java.util.Map.of("status", (Object) "done"));
    store.update("p", wip, java.util.Map.of("status", (Object) "in-progress"));

    assertTrue(
        "done ticket stays live for the chain",
        store.list("p", null, null, null).stream().anyMatch(t -> t.id.equals(done)));
    assertTrue(store.archived("p").isEmpty());
  }

  @Test
  public void absentArchiveDirectoryReadsAsEmpty() {
    assertTrue(store.archived("p").isEmpty());
  }

  /**
   * T-240 (AC1/AC3): an archived id and a genuinely absent id stay DISTINGUISHABLE through the same
   * {@link TaskStore.NotFound} class - the archived verdict names the archive file, the absent id
   * keeps the exact historical message (that message is pinned for the absent case by {@code
   * TaskStoreSemanticsTest#getMissingFailsWithPmMessage}). The write verbs answer the same way
   * (they resolve through the same verdict), and neither id comes back into the live listing.
   */
  @Test
  public void archivedAndMissingIdsStayDistinguishableOnGetAndOnTheWriteVerbs() {
    String id = create();
    store.update("p", id, Map.of("status", "done"));
    store.archive("p", id, "test");

    String archivedVerdict = notFoundMessage(() -> store.get("p", id));
    assertTrue(
        "the verdict names the archive: " + archivedVerdict,
        archivedVerdict.contains("ticket " + id + " is archived - see "));
    assertTrue(
        "the verdict points at the archive file: " + archivedVerdict,
        archivedVerdict.contains("_archive/" + id + ".md"));

    String missingVerdict = notFoundMessage(() -> store.get("p", "T-999"));
    assertEquals(
        "a genuinely absent id keeps the pinned historical message",
        "ticket T-999 not found in project p",
        missingVerdict);

    String writeVerdict = notFoundMessage(() -> store.addComment("p", id, "mutate?", "test"));
    assertTrue(
        "a write on an archived id names the archive too: " + writeVerdict,
        writeVerdict.contains("is archived - see "));

    assertTrue(
        "the live listing never gains the archived id back",
        store.list("p", null, null, null).isEmpty());
  }

  /**
   * T-240: {@code task_links} is the SCOPE view - an archived chain hop resolves and is marked
   * {@code archived:true} (it used to read {@code missing:true}, the same false negative as the old
   * task_get verdict), the archived ticket itself opens its own graph, and an id resolving NOWHERE
   * keeps {@code missing:true} so the two verdicts stay distinguishable.
   */
  @Test
  public void linksMarksAnArchivedChainHopAndKeepsMissingForAnIdResolvingNowhere()
      throws Exception {
    String parent = create();
    String child =
        store.create(
                "p",
                new TaskStore.CreateSpec(
                    "child",
                    "",
                    "task",
                    "developer",
                    "medium",
                    0,
                    List.of(),
                    List.of(),
                    "T",
                    null,
                    null,
                    parent))
            .id;
    store.update("p", parent, Map.of("status", "done"));
    store.archive("p", parent, "test");

    Map<?, ?> archivedHop = firstUpHop(child);
    assertEquals("the archived hop is marked", Boolean.TRUE, archivedHop.get("archived"));
    assertNull("the archived hop is not reported missing", archivedHop.get("missing"));

    Map<String, Object> archivedSelf = store.links("p", parent);
    assertEquals(
        "an archived ticket opens its own graph", Boolean.TRUE, archivedSelf.get("archived"));

    // drop the archive record: the hop now resolves NOWHERE - missing, not archived
    Files.delete(projectDir.resolve("_archive").resolve(parent + ".md"));
    Map<?, ?> missingHop = firstUpHop(child);
    assertEquals("the gone hop is reported missing", Boolean.TRUE, missingHop.get("missing"));
    assertNull("the gone hop carries no archive mark", missingHop.get("archived"));
    assertEquals(
        "the archived ticket itself is not found any more",
        "ticket " + parent + " not found",
        notFoundMessage(() -> store.links("p", parent)));
  }

  /** The child's single {@code up} row of {@link TaskStore#links}. */
  private Map<?, ?> firstUpHop(String childId) {
    Map<String, Object> graph = store.links("p", childId);
    List<?> up = (List<?>) graph.get("up");
    assertEquals("the chain has exactly one hop", 1, up.size());
    return (Map<?, ?>) up.get(0);
  }

  private String notFoundMessage(Runnable call) {
    try {
      call.run();
      org.junit.Assert.fail("expected NotFound");
      return null;
    } catch (TaskStore.NotFound expected) {
      return expected.getMessage();
    }
  }
}
