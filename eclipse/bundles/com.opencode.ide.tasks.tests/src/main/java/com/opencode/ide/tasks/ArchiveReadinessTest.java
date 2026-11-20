package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * U-027 readiness consults the archive: an archived upstream still satisfies its basis-chain
 * downstream (archiving never orphans the V chain), and the wave-close auto-archive moves a done
 * ticket only when no live ticket still needs it. The plain close never archives.
 */
public class ArchiveReadinessTest extends StoreTestHarness {

  private Task ticket(String title, String basis, String stage) {
    Task t =
        store.create(
            "p",
            new TaskStore.CreateSpec(
                title, "d", "task", "developer", "high", 3, List.of("it works"), List.of(), "T"),
            stage);
    if (basis != null) {
      t = store.update("p", t.id, Map.of("basis", basis));
    }
    // U-081: a satisfying upstream must be PROVEN (artifacted)
    store.addArtifact(
        "p", t.id, "doc", "docs/" + stage + "-product.md", "stage product", "fixture");
    return t;
  }

  @Test
  public void anArchivedUpstreamStillSatisfiesItsChildren() {
    Task parent = ticket("chain head", null, "requirements");
    parent = store.update("p", parent.id, Map.of("status", "done"));
    store.archive("p", parent.id, "wave close");
    // the child is touched last: the archived upstream is fresh evidence,
    // not a stale input (the STALE machinery has its own tests)
    Task child = ticket("downstream", parent.id, "system");

    Map<String, StageReadiness.Readiness> withArchive =
        StageReadiness.evaluate(List.of(child), store.archived("p"));
    Map<String, StageReadiness.Readiness> withoutArchive = StageReadiness.evaluate(List.of(child));

    assertEquals(
        "the archived parent still satisfies the chain: " + withArchive.get(child.id),
        StageReadiness.Kind.READY,
        withArchive.get(child.id).kind());
    assertEquals(
        "and the orphan case is exactly what U-027 fixes",
        StageReadiness.Kind.WAIT_UPSTREAM,
        withoutArchive.get(child.id).kind());
  }

  @Test
  public void doneTicketsStayArchivableOnlyThroughTheChainSafeSweep() {
    // the wave-close auto-archiver (U-060) went with the sprint model;
    // task_archive_done is the chain-safe sweep now (pinned in TaskStoreSemanticsTest)
    assertTrue("nothing archives by itself", store.archived("p").isEmpty());
  }
}
