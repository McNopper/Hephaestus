package com.opencode.ide.client.model;

/**
 * One shell task from {@code GET /api/shell} (v2's Shell tab surface,
 * U-045/v2 parity).
 *
 * <p>Wire shape (v2 {@code Shell.Info}): {@code {"id":"sh…",
 * "status":"running"|"exited"|"timeout"|"killed", "command", "cwd",
 * "shell", "file", "pid", "exit", "metadata", "time":{started,
 * completed?}}}. Only the fields the harness surfaces are mapped.</p>
 */
public record ShellTask(
        String id,
        String status,
        String command,
        String cwd,
        Integer exit,
        Long pid,
        Time time) {

    /** Epoch millis. */
    public record Time(Long started, Long completed) {
    }

    /** @return true while the task is still running (wire status {@code running}). */
    public boolean isRunning() {
        return "running".equals(status);
    }
}
