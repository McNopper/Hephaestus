package com.opencode.ide.client.model;

/**
 * A project as listed by {@code GET /project}: the worktree directory and the
 * VCS position (branch + repository remote). Fields are lenient — servers may
 * omit VCS info for non-git directories.
 */
public record ProjectSummary(
        String worktree,
        String branch,
        String repository,
        String id) {

    /** Compatibility construction when the id is not available (lenient parse, tests). */
    public ProjectSummary(String worktree, String branch, String repository) {
        this(worktree, branch, repository, null);
    }
}
