package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.FileStatus;

/**
 * Unit tests for the SWT-free {@link WorkingSet} model behind the Server
 * view's Working set category: load sorting (status group, natural string
 * order, then path), the lenient degradation to {@link WorkingSet#EMPTY},
 * the unmodifiable entries, the summary formats (empty / single status /
 * mixed; unknown statuses count as modified — a documented choice) and the
 * per-entry label. No SWT, no JFace, no Display.
 */
public class WorkingSetTest {

    // ---------- fixtures ----------

    private static FileStatus file(String path, String status, Integer added, Integer removed) {
        return new FileStatus(path, status, added, removed);
    }

    /** Only the file-status endpoint works; everything else is never touched. */
    private static final class FakeClient extends com.opencode.ide.ui.testsupport.StubClient {
        List<FileStatus> status = List.of();
        boolean throwOnStatus;

        @Override
        public List<FileStatus> getFileStatus() throws OpencodeException {
            if (throwOnStatus) {
                throw new OpencodeException("status endpoint gone");
            }
            return status;
        }

    }

    // ---------- load ----------

    @Test
    public void loadSortsStatusGroupsThenPath() {
        FakeClient client = new FakeClient();
        client.status = List.of(
                file("b.txt", "modified", 1, 2),
                file("z.txt", "added", 10, 0),
                file("a.txt", "modified", 3, 1),
                file("c.txt", "deleted", 0, 5),
                file("a.txt", "added", 4, 0));

        WorkingSet set = WorkingSet.load(client);

        // pinned order: status in natural string order (added < deleted < modified), then path
        assertEquals(List.of("a.txt", "z.txt", "c.txt", "a.txt", "b.txt"),
                set.entries().stream().map(FileStatus::path).toList());
        assertEquals(List.of("added", "added", "deleted", "modified", "modified"),
                set.entries().stream().map(FileStatus::status).toList());
        assertEquals(5, set.size());
        assertFalse(set.isEmpty());
    }

    @Test
    public void loadDropsNullElementsButKeepsNullFields() {
        // Arrays.asList (not List.of): the fixture intentionally contains null
        FakeClient client = new FakeClient();
        client.status = Arrays.asList(
                file(null, "modified", null, null),
                null,
                file("a.txt", "renamed", 1, 1));

        WorkingSet set = WorkingSet.load(client);

        assertEquals(2, set.size());
        assertEquals("(unnamed) (modified)", WorkingSet.entryLabel(set.entries().get(0)));
        assertEquals("a.txt (renamed, +1/-1)", WorkingSet.entryLabel(set.entries().get(1)));
    }

    @Test
    public void endpointFailureDegradesToEmpty() {
        FakeClient client = new FakeClient();
        client.throwOnStatus = true;

        WorkingSet set = WorkingSet.load(client);

        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
        assertEquals(List.of(), set.entries());
        assertEquals("", set.summary());
    }

    @Test
    public void nullClientNullListAndEmptyListDegradeToEmpty() {
        assertTrue(WorkingSet.load(null).isEmpty());
        FakeClient client = new FakeClient();
        client.status = null;
        assertTrue(WorkingSet.load(client).isEmpty());
        client.status = List.of();
        WorkingSet set = WorkingSet.load(client);
        assertTrue(set.isEmpty());
        assertEquals("", set.summary());
    }

    // ---------- entries ----------

    @Test
    public void entriesAreUnmodifiable() {
        FakeClient client = new FakeClient();
        client.status = List.of(file("a.txt", "modified", 1, 1));

        assertThrows(UnsupportedOperationException.class,
                () -> WorkingSet.load(client).entries().add(file("b.txt", "added", 1, 0)));
    }

    // ---------- summary ----------

    @Test
    public void summaryOfASingleStatusIsTheTotalOnly() {
        FakeClient client = new FakeClient();
        client.status = List.of(
                file("a.txt", "modified", 1, 1),
                file("b.txt", "modified", 2, 0),
                file("c.txt", "modified", 0, 3));

        assertEquals("3 changed", WorkingSet.load(client).summary());
    }

    @Test
    public void summaryOfMixedStatusesListsPerStatusCounts() {
        FakeClient client = new FakeClient();
        client.status = List.of(
                file("a.txt", "modified", 1, 1),
                file("b.txt", "modified", 2, 0),
                file("c.txt", "added", 5, 0));
        assertEquals("3 changed · 2 modified · 1 added", WorkingSet.load(client).summary());

        client.status = List.of(
                file("a.txt", "added", 1, 0),
                file("b.txt", "deleted", 0, 2));
        assertEquals("2 changed · 1 added · 1 deleted", WorkingSet.load(client).summary());
    }

    @Test
    public void summaryCountsUnknownStatusesAsModified() {
        FakeClient client = new FakeClient();
        client.status = List.of(
                file("a.txt", "modified", 1, 1),
                file("b.txt", "renamed", 0, 0));
        // one group only (renamed folds into modified): no per-status breakdown
        assertEquals("2 changed", WorkingSet.load(client).summary());

        client.status = List.of(
                file("a.txt", "renamed", 0, 0),
                file("b.txt", "added", 1, 0));
        assertEquals("2 changed · 1 modified · 1 added", WorkingSet.load(client).summary());
    }

    // ---------- entryLabel ----------

    @Test
    public void entryLabelFormatsStatusAndCountsAndDegrades() {
        assertEquals("src/A.java (modified, +3/-1)",
                WorkingSet.entryLabel(file("src/A.java", "modified", 3, 1)));
        assertEquals("src/B.java (modified, +2)", WorkingSet.entryLabel(file("src/B.java", "modified", 2, null)));
        assertEquals("src/C.java (modified, -4)", WorkingSet.entryLabel(file("src/C.java", "modified", null, 4)));
        assertEquals("src/D.java (added)", WorkingSet.entryLabel(file("src/D.java", "added", null, null)));
        assertEquals("src/E.java", WorkingSet.entryLabel(file("src/E.java", null, 1, 1)));
        assertEquals("(unnamed) (modified)", WorkingSet.entryLabel(file(null, "modified", null, null)));
        assertEquals("(unnamed)", WorkingSet.entryLabel(file(null, null, null, null)));
        assertEquals("(unnamed)", WorkingSet.entryLabel(null));
    }
}
