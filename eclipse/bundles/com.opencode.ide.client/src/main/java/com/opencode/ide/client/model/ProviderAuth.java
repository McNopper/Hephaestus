package com.opencode.ide.client.model;

/**
 * One auth method from the v2 {@code GET /api/integration} catalog. The client
 * flattens each integration's {@code methods[]} into this display model,
 * keyed by integration id. Types include {@code oauth}, {@code key},
 * {@code env} and {@code command}; labels may be absent.
 */
public record ProviderAuth(
        String provider,
        String type,
        String label) {
}
