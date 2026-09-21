package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure (SWT-free) formatting of the Repo view's file-search results: the
 * client's {@code findFiles} path list mapped to uniform display {@link Row}s
 * (kind, label, {@code path} location), with deduplication and a result cap.
 *
 * <p>v2-only: the server has no text or symbol search endpoints — {@code
 * /fs/find} matches file and directory names only — so the view offers plain
 * file search and nothing else.</p>
 */
public final class SearchResults {

    /** Results are capped after dedup so a huge workspace cannot flood the view. */
    public static final int DEFAULT_CAP = 50;

    /**
     * One display row: {@code kind} ("file"), a human label (the file name),
     * the location ({@code path}) plus the raw path/line for actions.
     */
    public record Row(String kind, String label, String location, String path, int lineNumber) {
    }

    private SearchResults() {
    }

    /** The search box content, trimmed; blank means "no search" (restore the tree). */
    public static String parse(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** Maps fuzzy file-search paths to rows (dedup by path, capped). */
    public static List<Row> fromFiles(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Row> rows = new ArrayList<>();
        for (String path : paths) {
            if (path == null || path.isBlank() || !seen.add(path)) {
                continue;
            }
            rows.add(new Row("file", fileNameOf(path), path, path, 0));
            if (rows.size() >= DEFAULT_CAP) {
                break;
            }
        }
        return rows;
    }

    // ---------- helpers ----------

    /** @return the last segment of the path (the file name), or the whole path when flat. */
    static String fileNameOf(String path) {
        String p = RepoTree.normalize(path);
        if (p.isEmpty()) {
            return "(root)";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }
}
