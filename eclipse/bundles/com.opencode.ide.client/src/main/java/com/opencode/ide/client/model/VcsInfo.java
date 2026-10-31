package com.opencode.ide.client.model;

/**
 * VCS state of the current project ({@code GET /vcs}): branch name and repository remote URL. Both
 * may be {@code null} outside a git repository.
 */
public record VcsInfo(String branch, String repository) {}
