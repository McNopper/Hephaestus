package com.opencode.ide.mcp.internal;

/**
 * State shared between the DS component and the public
 * {@link com.opencode.ide.mcp.McpInfo} accessor. Lives in the internal
 * package (visible only to the host and the tests bundle via x-friends).
 */
public final class McpState {

    /** The loopback port the MCP endpoint listens on, or -1 while not running. */
    public static volatile int port = -1;

    /** The per-start auth token the endpoint requires (see McpHttpServer), or null while not running. */
    public static volatile String token;

    private McpState() {
    }

    static void setPort(int newPort) {
        port = newPort;
    }

    static void setToken(String newToken) {
        token = newToken;
    }
}
