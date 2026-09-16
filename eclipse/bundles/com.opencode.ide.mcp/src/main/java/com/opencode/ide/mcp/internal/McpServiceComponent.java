package com.opencode.ide.mcp.internal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.opencode.ide.mcp.McpInfo;
import com.opencode.ide.tools.McpDispatcher;
import com.opencode.ide.tools.ToolProvider;
import com.opencode.ide.tools.cpp.CppToolProvider;
import com.opencode.ide.tasks.TaskToolProvider;

/**
 * OSGi Declarative Services component (immediate) that owns the
 * {@code eclipse-build} MCP server lifecycle: starts the Streamable HTTP
 * endpoint on an ephemeral loopback port on activation, stops it on
 * deactivation, and publishes {@link McpInfo} as an OSGi service while
 * running. The port is backed by {@link McpState}.
 *
 * <p>The dispatcher unions all tool packs: the C/C++ language pack and the
 * task board ({@code task_*} tools over the Markdown task store). The task
 * store root is configurable with the {@code opencode.tasks.root} system
 * property (absolute path); the core bundle's activator bridges the
 * {@code tasksRoot} preference into that property, and the component is
 * {@code immediate="false"} so it activates only when the first consumer
 * binds {@link McpInfo} — guaranteeing the bridged property is already set
 * (the plain fallback is {@code <user.home>/.opencode/tasks} for non-Eclipse
 * embedders).</p>
 */
public class McpServiceComponent implements McpInfo {

    private static final Logger LOG = Logger.getLogger(McpServiceComponent.class.getName());

    private McpHttpServer server;

    /** DS lifecycle: starts the endpoint. Public so the wiring test can drive it (DS calls reflectively). */
    public void activate() {
        try {
            Path tasksRoot = Path.of(
                    System.getProperty("opencode.tasks.root",
                            Path.of(System.getProperty("user.home"), ".opencode", "tasks").toString()));
            List<ToolProvider> providers = List.of(new CppToolProvider(), new TaskToolProvider(tasksRoot));
            server = McpHttpServer.start(new McpDispatcher(providers));
            LOG.info("task store root: " + tasksRoot);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "failed to start eclipse-build MCP server", e);
            throw new IllegalStateException("eclipse-build MCP server failed to start", e);
        }
        McpState.setPort(server.port());
        McpState.setToken(server.token());
        LOG.info("eclipse-build MCP listening on http://127.0.0.1:" + server.port()
                + "/mcp (auth: per-start token)");
    }

    /** DS lifecycle: stops the endpoint. Public so the wiring test can drive it (DS calls reflectively). */
    public void deactivate() {
        McpState.setPort(-1);
        McpState.setToken(null);
        McpHttpServer current = server;
        server = null;
        if (current != null) {
            current.stop();
        }
    }

    @Override
    public int getPort() {
        return McpState.port;
    }

    @Override
    public boolean isRunning() {
        return McpState.port > 0;
    }

    @Override
    public String getEndpointUrl() {
        // includes the ?token= secret: this URL is the registration hand-out
        return isRunning() ? "http://127.0.0.1:" + McpState.port + "/mcp?token=" + McpState.token
                : null;
    }
}
