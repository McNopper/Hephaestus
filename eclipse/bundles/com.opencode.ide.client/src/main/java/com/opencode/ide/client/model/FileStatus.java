package com.opencode.ide.client.model;

/**
 * One changed file of the project ({@code GET /file/status}, opencode v1.18.30
 * wire shape {@code {"path":…,"added":…,"removed":…,"status":…}}): the file
 * path, the git status ({@code added}/{@code deleted}/{@code modified}) and
 * the changed-line counts. All fields may be {@code null} on lenient parse.
 */
public record FileStatus(
        String path,
        String status,
        Integer added,
        Integer removed) {
}
