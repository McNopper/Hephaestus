package com.opencode.ide.ui.model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.FileStatus;

/**
 * Pure (SWT-free) working-set model for the Server view's Working set
 * category: the project's changed files ({@code GET /file/status}), sorted by
 * status group then path, with the one-line {@link #summary()} and the
 * per-entry label {@code "path (status, +added/-removed)"}.
 *
 * <p>{@link #load(OpencodeClient)} is lenient by design: an absent endpoint,
 * empty or {@code null} list, {@code null} entries or any transport failure
 * degrades to {@link #EMPTY} — never {@code null}, never an exception — so
 * the category always renders. The summary derives counts from the status
 * string ({@code modified}/{@code added}/{@code deleted}); any unknown status
 * counts as modified rather than being dropped or getting its own group
 * (choice pinned in {@code WorkingSetTest} in {@code com.opencode.ide.ui.tests}).</p>
 */
public final class WorkingSet {

    /** The graceful-degradation instance: no changed files, empty summary. */
    public static final WorkingSet EMPTY = new WorkingSet(List.of());

    /** Deterministic entry order: status (natural string order, {@code null} first), then path. */
    private static final Comparator<FileStatus> ORDER = Comparator
            .comparing((FileStatus f) -> f.status() == null ? "" : f.status())
            .thenComparing(f -> f.path() == null ? "" : f.path());

    private final List<FileStatus> entries;

    private WorkingSet(List<FileStatus> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * The client's changed files (see {@code GET /vcs/status}), sorted by
     * status then path; {@code null} list elements are dropped. Any failure
     * degrades to {@link #EMPTY}.
     */
    public static WorkingSet load(OpencodeClient client) {
        return load(client, null);
    }

    /**
     * Scoped variant: v2 resolves {@code /vcs/status} per location - pass the
     * connection's working directory, or the shared service answers for the
     * user's home directory.
     */
    public static WorkingSet load(OpencodeClient client, String directory) {
        if (client == null) {
            return EMPTY;
        }
        List<FileStatus> status;
        try {
            status = client.getFileStatus(directory);
        } catch (Exception e) {
            return EMPTY;
        }
        return of(status);
    }

    /**
     * The pure factory behind every non-empty instance (tests and future
     * callers): sorts a copy (status then path) and drops {@code null}
     * entries; a {@code null}/empty input yields {@link #EMPTY}.
     */
    public static WorkingSet of(List<FileStatus> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        List<FileStatus> sorted = entries.stream()
                .filter(f -> f != null)
                .sorted(ORDER)
                .collect(Collectors.toList());
        return sorted.isEmpty() ? EMPTY : new WorkingSet(sorted);
    }

    /** Whether the project has no changed files. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The number of changed files. */
    public int size() {
        return entries.size();
    }

    /** The sorted, unmodifiable changed-file entries. */
    public List<FileStatus> entries() {
        return entries;
    }

    /**
     * The one-line summary, e.g. {@code "3 changed"} or
     * {@code "3 changed · 2 modified · 1 added"}: the total, plus the
     * per-status counts (in the order modified, added, deleted) once more
     * than one status is present. Unknown statuses count as modified. Empty
     * when there are no changed files.
     */
    public String summary() {
        if (entries.isEmpty()) {
            return "";
        }
        int modified = 0;
        int added = 0;
        int deleted = 0;
        for (FileStatus entry : entries) {
            String status = entry.status();
            if ("added".equals(status)) {
                added++;
            } else if ("deleted".equals(status)) {
                deleted++;
            } else {
                modified++;   // "modified" and any unknown status
            }
        }
        StringBuilder sb = new StringBuilder(entries.size() + " changed");
        if ((modified > 0 ? 1 : 0) + (added > 0 ? 1 : 0) + (deleted > 0 ? 1 : 0) > 1) {
            if (modified > 0) {
                sb.append(" · ").append(modified).append(" modified");
            }
            if (added > 0) {
                sb.append(" · ").append(added).append(" added");
            }
            if (deleted > 0) {
                sb.append(" · ").append(deleted).append(" deleted");
            }
        }
        return sb.toString();
    }

    /**
     * Label of one entry: {@code "path (status, +added/-removed)"}; unknown
     * parts degrade ({@code "path (status, +added)"}, {@code "path (status)"},
     * {@code "path"}, {@code "(unnamed)"} for a missing path).
     */
    public static String entryLabel(FileStatus entry) {
        if (entry == null) {
            return "(unnamed)";
        }
        String path = entry.path() == null || entry.path().isEmpty() ? "(unnamed)" : entry.path();
        String status = entry.status();
        if (status == null || status.isEmpty()) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path).append(" (").append(status);
        if (entry.added() != null || entry.removed() != null) {
            sb.append(", ");
            if (entry.added() != null) {
                sb.append('+').append(entry.added());
            }
            if (entry.added() != null && entry.removed() != null) {
                sb.append('/');
            }
            if (entry.removed() != null) {
                sb.append('-').append(entry.removed());
            }
        }
        return sb.append(')').toString();
    }
}
