package com.opencode.ide.fleet;

/**
 * B-009: classify a silent hang from the last visible activity (pure). A
 * worker that states tool-discovery intent and then goes silent hit a
 * TOOLS-LOAD failure - the {@code task_*} MCP tools never arrived - and the
 * blocker says so instead of a generic stall. Never a silent stall.
 */
public final class HangKind {

    private HangKind() {
    }

    /**
     * @param lastAssistant the last assistant text seen before the silence
     * @param lastTool      the last tool call seen before the silence
     * @return the cause to put in the blocker reason
     */
    public static String of(String lastAssistant, String lastTool) {
        String text = lastAssistant == null ? "" : lastAssistant.toLowerCase(java.util.Locale.ROOT);
        String tool = lastTool == null ? "" : lastTool.toLowerCase(java.util.Locale.ROOT);
        if (tool.startsWith("task_") || text.contains("task tools")) {
            return "tools-load failure: the task_* tools never arrived - check the tasks MCP server";
        }
        return "hang";
    }
}
