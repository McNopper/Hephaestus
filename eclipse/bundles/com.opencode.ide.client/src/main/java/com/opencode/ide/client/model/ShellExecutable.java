package com.opencode.ide.client.model;

/**
 * One acceptable shell executable ({@code GET /config/shell}). v2.0.19 live
 * probe: the endpoint answers a BARE array of {@code {path, name, acceptable}}
 * objects - no {@code {location, data}} envelope. Nullable-tolerant.
 */
public record ShellExecutable(String path, String name, boolean acceptable) {
}
