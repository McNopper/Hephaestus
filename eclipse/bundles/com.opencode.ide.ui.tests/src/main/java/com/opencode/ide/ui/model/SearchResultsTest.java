package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the SWT-free {@link SearchResults} behind the Repo view:
 * query trimming plus the dedup/cap/format rules of the file rows (v2 has no
 * text/symbol search - file names only). No SWT, no JFace, no Display.
 */
public class SearchResultsTest {

    // ---------- parse ----------

    @Test
    public void parseTrimsTheQuery() {
        assertEquals("readme", SearchResults.parse("readme"));
        assertEquals("readme", SearchResults.parse("  readme  "));
    }

    @Test
    public void fileQueriesPreserveLiteralAtAndSlashPrefixes() {
        assertEquals("@scope/package", SearchResults.parse("  @scope/package  "));
        assertEquals("/src/main.cpp", SearchResults.parse("/src/main.cpp"));
    }

    @Test
    public void nullAndBlankQueriesAreBlank() {
        assertEquals("", SearchResults.parse(null));
        assertTrue(SearchResults.parse(null).isBlank());
        assertTrue(SearchResults.parse("").isBlank());
        assertTrue(SearchResults.parse("   ").isBlank());
    }

    // ---------- fromFiles ----------

    @Test
    public void fromFilesLabelsByLastSegmentAndDedups() {
        // Arrays.asList (not List.of): the fixture intentionally contains nulls
        List<SearchResults.Row> rows = SearchResults.fromFiles(
                java.util.Arrays.asList("docs/readme.md", "readme.md", "docs/readme.md", null, " "));

        assertEquals(2, rows.size());
        assertEquals("readme.md", rows.get(0).label());
        assertEquals("docs/readme.md", rows.get(0).location());
        assertEquals("docs/readme.md", rows.get(0).path());
        assertEquals("file", rows.get(0).kind());
        assertEquals("readme.md", rows.get(1).location());
    }

    @Test
    public void fromFilesCapsAtDefaultCapAfterDedup() {
        // 60 unique paths but the first path repeats later: still 50 rows
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            paths.add("f" + i + ".txt");
        }
        paths.add("f0.txt"); // duplicate of the first

        List<SearchResults.Row> rows = SearchResults.fromFiles(paths);

        assertEquals(SearchResults.DEFAULT_CAP, rows.size());
        assertEquals("f49.txt", rows.get(49).label()); // cap keeps the first 50
    }

    @Test
    public void fromFilesToleratesNullList() {
        assertEquals(List.of(), SearchResults.fromFiles(null));
        assertEquals(List.of(), SearchResults.fromFiles(List.of()));
    }
}
