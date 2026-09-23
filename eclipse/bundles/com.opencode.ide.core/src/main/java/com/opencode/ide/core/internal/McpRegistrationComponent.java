package com.opencode.ide.core.internal;

import java.util.concurrent.ExecutorService;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.mcp.McpInfo;

/**
 * OSGi Declarative Services component: once both the {@link OpencodeConnection}
 * service and the {@link McpInfo} service (the local {@code eclipse-build}
 * endpoint) are present, registers the endpoint with the opencode server via
 * {@link McpRegistration}. {@link OpencodeConnection#getClient()} may block
 * (spawn mode waits for server health), so the work runs on a background
 * thread instead of the SCR thread.
 */
public class McpRegistrationComponent {

    private final McpRegistration registration =
            new McpRegistration((level, message, cause) -> ClientLog.BACKEND.get().log(level, message, cause));
    private volatile ExecutorService executor;
    private volatile OpencodeConnection connection;
    private volatile McpInfo mcpInfo;

    protected void activate() {
        executor = com.opencode.ide.client.WorkerPools.serialExecutor("opencode-mcp-registration");
        scheduleRegistration();
    }

    protected void deactivate() {
        ExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
    }

    protected void bindConnection(OpencodeConnection connection) {
        this.connection = connection;
        scheduleRegistration();
    }

    protected void unbindConnection(OpencodeConnection connection) {
        this.connection = null;
    }

    protected void bindMcp(McpInfo info) {
        this.mcpInfo = info;
        scheduleRegistration();
    }

    protected void unbindMcp(McpInfo info) {
        this.mcpInfo = null;
    }

    private void scheduleRegistration() {
        ExecutorService current = executor;
        if (current != null && connection != null && mcpInfo != null) {
            current.execute(this::runRegistration);
        }
    }

    private void runRegistration() {
        OpencodeConnection current = connection;
        McpInfo info = mcpInfo;
        if (current == null || info == null) {
            return;
        }
        try {
            registration.registerIfNeeded(current.getClient(), info.getEndpointUrl());
        } catch (Exception e) {
            CoreActivator.logError("eclipse-build MCP registration failed", e);
        }
    }
}
