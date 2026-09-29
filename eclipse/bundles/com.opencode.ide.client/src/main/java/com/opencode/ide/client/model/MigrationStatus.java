package com.opencode.ide.client.model;

/**
 * Status of the v1-to-v2 storage migration
 * ({@code GET /experimental/migration/v1}). v2.0.19 live probe:
 * {@code {status: "completed" | "required" | "running" | "error", ...}} - a
 * {@code running} answer may carry progress fields this record deliberately
 * does not model (unknown fields are ignored by the parsing).
 * Nullable-tolerant.
 */
public record MigrationStatus(String status) {
}
