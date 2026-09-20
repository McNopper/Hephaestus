package com.opencode.ide.client.model;

/**
 * Response of {@code GET /api/info} — the v2 replacement for v1's
 * {@code GET /global/health}. v2 reports no explicit health flag: reaching the
 * endpoint at all is the health signal, and {@code version} comes straight from
 * the server info object.
 */
public record HealthStatus(boolean healthy, String version) {
}
