package com.opencode.ide.mcp.internal;

import com.opencode.ide.mcp.McpInfo;
import com.opencode.ide.tasks.TaskToolProvider;
import com.opencode.ide.tools.McpDispatcher;
import com.opencode.ide.tools.ToolProvider;
import com.opencode.ide.tools.cpp.CppToolProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OSGi Declarative Services component (immediate) that owns the {@code eclipse-build} MCP server
 * lifecycle: starts the Streamable HTTP endpoint on an ephemeral loopback port on activation, stops
 * it on deactivation, and publishes {@link McpInfo} as an OSGi service while running. The port is
 * backed by {@link McpState}.
 *
 * <p>The dispatcher unions all tool packs: the C/C++ language pack and the task board ({@code
 * task_*} tools over the Markdown task store). The task store root comes from the {@code
 * opencode.tasks.root} system property, which the core bundle's activator bridges from the ONE
 * shared resolver ({@code com.opencode.ide.core.TasksRootResolution}, B-016): the same order the
 * Board view resolves — workspace climb, open-project repo adoption, then the {@code tasksRoot}
 * preference — so both surfaces always see one store. The component is {@code immediate="true"}
 * (T-176): the ONLY consumer of {@link McpInfo} is core's McpRegistrationComponent behind a {@code
 * 0..1} dynamic reference, which never forces a delayed component to activate. Stage two (observed
 * 2026-11-18): the mcp BUNDLE itself was never started — SCR resolves that reference by service
 * <i>name</i>, never loading the class, so nothing in the framework class-loads this bundle; the
 * deterministic hand-off is {@code CoreActivator}, which starts this bundle only after a successful
 * {@code opencode.tasks.root} bridge — the ordering {@link #activate()} depends on (the plain
 * fallback {@code <user.home>/.harness/tasks} is for non-Eclipse embedders only). The counter-case
 * (T-146/CDT) stays delayed because that component class-loads heavy {@code org.eclipse.cdt.*}
 * bundles at activation — this one needs only plain bundles (tools/tasks/client).
 */
public class McpServiceComponent implements McpInfo {

  private static final Logger LOG = Logger.getLogger(McpServiceComponent.class.getName());

  private McpHttpServer server;

  /**
   * DS lifecycle: starts the endpoint. Public so the wiring test can drive it (DS calls
   * reflectively).
   */
  public void activate() {
    try {
      Path tasksRoot =
          Path.of(
              System.getProperty(
                  "opencode.tasks.root",
                  Path.of(System.getProperty("user.home"), ".harness", "tasks").toString()));
      List<ToolProvider> providers =
          List.of(new CppToolProvider(), new TaskToolProvider(tasksRoot));
      server = McpHttpServer.start(new McpDispatcher(providers));
      LOG.info("task store root: " + tasksRoot);
    } catch (IOException e) {
      LOG.log(Level.SEVERE, "failed to start eclipse-build MCP server", e);
      throw new IllegalStateException("eclipse-build MCP server failed to start", e);
    }
    McpState.setPort(server.port());
    McpState.setToken(server.token());
    LOG.info(
        "eclipse-build MCP listening on http://127.0.0.1:"
            + server.port()
            + "/mcp (auth: per-start token)");
  }

  /**
   * DS lifecycle: stops the endpoint. Public so the wiring test can drive it (DS calls
   * reflectively).
   */
  public void deactivate() {
    LOG.info(
        "eclipse-build endpoint stopping (was port " + McpState.port + ") - DS/bundle lifecycle");
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
    return isRunning()
        ? "http://127.0.0.1:" + McpState.port + "/mcp?token=" + McpState.token
        : null;
  }
}
