package com.opencode.ide.client.model;

/**
 * One file's diff of a session ({@code GET /session/:id/diff}): the file path plus the unified
 * patch content. {@code before}/{@code after} are revision identifiers when the server provides
 * them.
 */
public record FileDiff(String path, String before, String after, String content) {}
