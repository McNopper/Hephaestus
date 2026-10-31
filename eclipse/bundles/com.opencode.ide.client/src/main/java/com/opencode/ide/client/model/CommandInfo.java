package com.opencode.ide.client.model;

/**
 * A custom slash command ({@code GET /command}) loaded from the project's {@code
 * .opencode/command/} directory: its invocation name (without the leading slash) and optional
 * description.
 */
public record CommandInfo(String name, String description) {}
