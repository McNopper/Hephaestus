package com.opencode.ide.client.model;

/**
 * One node of the workspace file tree ({@code GET /file?path=…}): a file or
 * directory with its project-relative path and name.
 */
public record FileNode(
        String name,
        String path,
        String type) {

    /** @return true when this node is a directory (server sends {@code "directory"}). */
    public boolean isDirectory() {
        return "directory".equalsIgnoreCase(type);
    }

    /**
     * The node's display name. v2's {@code FileSystem.Entry} carries only
     * {@code path}/{@code type} (v1 also sent {@code name}), so the name is
     * derived from the path when the server omits it.
     */
    @Override
    public String name() {
        if (name != null) {
            return name;
        }
        if (path == null) {
            return null;
        }
        String p = (path.endsWith("/") || path.endsWith("\\")) ? path.substring(0, path.length() - 1) : path;
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash < 0 ? p : p.substring(slash + 1);
    }
}
