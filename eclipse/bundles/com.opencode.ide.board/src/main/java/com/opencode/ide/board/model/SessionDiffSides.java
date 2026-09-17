package com.opencode.ide.board.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.opencode.ide.board.internal.GitCli;
import com.opencode.ide.client.model.FileDiff;

/**
 * Resolves the two compare sides of a session diff (SWT-free, testable):
 * for every {@link FileDiff} that carries git revisions, the BEFORE text is
 * {@code git show <before>:<path>} and the AFTER text is
 * {@code git show <after>:<path>} — both run in the repo that owns the
 * session's objects (worktrees share the object store, so the merged main
 * checkout resolves fleet-session revisions too). Files whose revisions
 * cannot be resolved (missing revs, binary, git error) are returned as
 * {@code null} sides so the caller can fall back to the plain-text patch
 * view for them.
 *
 * <p>Used by the Fleet view's diff action to open Eclipse's built-in
 * compare editor (side-by-side, hunk navigation) instead of a plain text
 * dialog (user direction 2026-09-17: "is there no official diff tool
 * included in Eclipse?").</p>
 */
public final class SessionDiffSides {

    /** One file's compare sides; a side may be {@code null} when unresolvable. */
    public record Side(String path, String before, String after) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private SessionDiffSides() {
    }

    /**
     * Resolves the sides for the given diffs (never null; entries keep the
     * input order). A file without both revisions, or whose {@code git show}
     * fails, yields a {@link Side} with null texts — the caller decides how
     * to present those.
     */
    public static List<Side> resolve(Path repoRoot, List<FileDiff> diffs) {
        List<Side> sides = new ArrayList<>();
        if (diffs == null) {
            return sides;
        }
        for (FileDiff file : diffs) {
            if (file == null || file.path() == null || file.path().isBlank()) {
                continue;
            }
            String path = file.path().replace('\\', '/');
            if (!repoUsable(repoRoot)) {
                sides.add(new Side(path, null, null));
                continue;
            }
            String before = revisionText(repoRoot, file.before(), path);
            String after = revisionText(repoRoot, file.after(), path);
            if (before == null && after == null) {
                sides.add(new Side(path, null, null));
            } else {
                sides.add(new Side(path, orEmpty(before), orEmpty(after)));
            }
        }
        return sides;
    }

    /** @return how many sides carry resolvable text (the compare editor is worth opening when > 0). */
    public static int resolved(List<Side> sides) {
        if (sides == null) {
            return 0;
        }
        return (int) sides.stream().filter(s -> s.before() != null || s.after() != null).count();
    }

    private static boolean repoUsable(Path repoRoot) {
        return repoRoot != null
                && (java.nio.file.Files.isDirectory(repoRoot.resolve(".git"))
                        || java.nio.file.Files.isRegularFile(repoRoot.resolve(".git")));
    }

    /**
     * {@code git show <rev>:<path>} as UTF-8 text; null when rev is blank,
     * does not resolve, or the command fails. GitCli hides exit codes, so
     * existence is proven first via {@code git rev-parse <rev>} (empty
     * stdout means the rev is unknown) — otherwise a failed {@code show}
     * would be indistinguishable from an empty file.
     */
    private static String revisionText(Path repoRoot, String revision, String path) {
        if (revision == null || revision.isBlank()) {
            return null;
        }
        try {
            String sha = GitCli.run(List.of("git", "-C", repoRoot.toString(),
                    "rev-parse", revision), TIMEOUT);
            if (sha == null || sha.isBlank()) {
                return null;
            }
            String out = GitCli.run(List.of("git", "-C", repoRoot.toString(),
                    "show", revision + ":" + path), TIMEOUT);
            return out == null ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
