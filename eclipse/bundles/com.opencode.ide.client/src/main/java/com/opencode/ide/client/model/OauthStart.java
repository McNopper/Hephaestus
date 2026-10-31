package com.opencode.ide.client.model;

/**
 * The answer of {@code POST /provider/:id/oauth/authorize} (opencode v1.18): {@code {url, method,
 * instructions}} - where to send the user, how the flow continues and human-facing instructions. A
 * {@code null} {@code url} means nothing was started (lenient parse; all fields nullable).
 */
public record OauthStart(String url, String method, String instructions) {}
