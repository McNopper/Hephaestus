package com.opencode.ide.client.model;

/**
 * One remembered allow/deny permission rule ({@code GET /permission/saved}),
 * i.e. the persisted outcome of an {@code always} permission decision.
 * v2.0.19 live probe: a {@code {data: [...]}} envelope whose items carry an
 * {@code id} plus further fields (action, pattern, ...) whose exact set is not
 * pinned yet - this record commits to the {@code id} only and parsing ignores
 * the rest. Nullable-tolerant.
 */
public record SavedPermission(String id) {
}
