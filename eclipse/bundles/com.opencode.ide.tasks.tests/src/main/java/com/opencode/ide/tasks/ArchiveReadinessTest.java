package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * U-027 readiness consults the archive: an archived upstream still satisfies
 * its epic-chain downstream (archiving never orphans the V chain), and the
 * wave-close auto-archive moves a done ticket only when no live ticket still
 * needs it. The plain close never archives.
 */
public class ArchiveReadinessTest extends StoreTestHarness {

    private Task ticket(String title, String epic, String stage) {
        Task t = store.create("p", new TaskStore.CreateSpec(
                title, "d", "task", "developer", "high", 3,
                List.of("it works"), List.of(), null, "T"), stage);
        if (epic != null) {
            t = store.update("p", t.id, Map.of("epic", epic));
        }
        return t;
    }

    @Test
    public void anArchivedUpstreamStillSatisfiesItsChildren() {
        Task parent = ticket("chain head", "E-1", "requirements");
        parent = store.update("p", parent.id, Map.of("status", "done"));
        store.archive("p", parent.id, "wave close");
        // the child is touched last: the archived upstream is fresh evidence,
        // not a stale input (the STALE machinery has its own tests)
        Task child = ticket("downstream", "E-1", "system");

        Map<String, StageReadiness.Readiness> withArchive =
                StageReadiness.evaluate(List.of(child), store.archived("p"));
        Map<String, StageReadiness.Readiness> withoutArchive =
                StageReadiness.evaluate(List.of(child));

        assertEquals("the archived parent still satisfies the chain: "
                + withArchive.get(child.id), StageReadiness.Kind.READY,
                withArchive.get(child.id).kind());
        assertEquals("and the orphan case is exactly what U-027 fixes",
                StageReadiness.Kind.WAIT_UPSTREAM, withoutArchive.get(child.id).kind());
    }

    @Test
    public void autoArchiveKeepsUpstreamsNeededByLiveTickets() {
        Task parent = ticket("needed upstream", "E-2", "requirements");
        Task child = ticket("live downstream", "E-2", "system");
        Task lone = ticket("unneeded done", null, "requirements");
        store.planSprint("p", "S-01", List.of(parent.id, child.id, lone.id), "goal");
        store.update("p", parent.id, Map.of("status", "done"));
        store.update("p", lone.id, Map.of("status", "done"));

        Map<String, Object> closed = store.closeSprint("p", "S-01", true);

        assertTrue("the unneeded done ticket is archived",
                ((List<?>) closed.get("auto_archived")).contains(lone.id));
        assertFalse("the upstream a live ticket still needs is kept",
                ((List<?>) closed.get("auto_archived")).contains(parent.id));
        assertTrue("the live downstream stays live",
                store.list("p", null, null, null, null).stream()
                        .anyMatch(t -> t.id.equals(child.id)));
    }

    @Test
    public void thePlainCloseNeverArchives() {
        Task lone = ticket("done work", null, "requirements");
        store.planSprint("p", "S-02", List.of(lone.id), "goal");
        store.update("p", lone.id, Map.of("status", "done"));

        store.closeSprint("p", "S-02");

        assertTrue("no auto-archive without the flag", store.archived("p").isEmpty());
    }
}
