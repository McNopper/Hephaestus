package com.opencode.ide.client.model;

/**
 * Status of a session from {@code GET /session/status}: a map of {@code sessionId ->
 * SessionStatus}. The {@code type} is {@code "idle"} / {@code "busy"} / {@code "retry"}. Since
 * opencode 1.18.23 the map lists BUSY sessions only — an absent session is idle (do not require an
 * explicit idle entry).
 */
public record SessionStatus(String type) {}
