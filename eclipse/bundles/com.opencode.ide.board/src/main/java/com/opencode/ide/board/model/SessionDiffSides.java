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
     * input order). Side semantics, tolerant of what the server actually
     * sends:
     * <ul>
     *   <li>{@code "WORKING"} (any case) — the current worktree file at
     *       {@code <repoRoot>/<path>} (missing file reads as empty).</li>
     *   <li>a git revision — validated via {@code git rev-parse}, then
     *       {@code git show <rev>:<path>}.</li>
     *   <li>blank BEFORE defaults to {@code HEAD}; blank AFTER defaults to
     *       the worktree file.</li>
     * </ul>
     * Files where nothing resolves yield null sides — the caller decides
     * how to present those.
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
            String before = resolveSide(repoRoot, file.before(), path, "HEAD");
            String after = resolveSide(repoRoot, file.after(), path, null);
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
     * Resolves one side: {@code WORKING} (any case) reads the worktree
     * file; a blank value falls back to {@code fallbackRev} ("HEAD" for the
     * before side, none for after); anything else must resolve as a git
     * revision. GitCli hides exit codes, so existence is proven first via
     * {@code git rev-parse} — otherwise a failed {@code show} would be
     * indistinguishable from an empty file.
     */
    private static String resolveSide(Path repoRoot, String value, String path, String fallbackRev) {
        if (value != null && "working".equalsIgnoreCase(value.trim())) {
            return worktreeText(repoRoot, path);
        }
        String revision = value == null || value.isBlank() ? fallbackRev : value.trim();
        if (revision == null) {
            return null;
        }
        if ("working".equalsIgnoreCase(revision)) {
            return worktreeText(repoRoot, path);
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

    /** The worktree file's current content; a missing file reads as empty (deleted side). */
    private static String worktreeText(Path repoRoot, String path) {
        try {
            return java.nio.file.Files.readString(repoRoot.resolve(path), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
