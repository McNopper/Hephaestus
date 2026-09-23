package com.opencode.ide.tasks;

import static org.junit.Assert.assertFalse;

import java.nio.file.Path;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tools.McpDispatcher;

/**
 * Shared JSON-RPC tools fixture (2026-09-23 CPD findings: StageToolsEdgeTest
 * and TaskReadinessToolTest copy-pasted it): a store-backed
 * {@link TaskToolProvider} behind an {@link McpDispatcher} and the one
 * {@code tools/call} round trip both use.
 */
public abstract class ToolRpcHarness {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    protected Path root;
    protected McpDispatcher dispatcher;

    @Before
    public void setUp() {
        root = tmp.getRoot().toPath().resolve("tasks");
        dispatcher = new McpDispatcher(new TaskToolProvider(root));
    }

    /** One {@code tools/call} round trip: the RESULT object (domain errors arrive as isError results). */
    protected JsonObject call(String tool, String argsJson) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + argsJson + "}}";
        JsonObject response = JsonParser.parseString(dispatcher.handle(body)).getAsJsonObject();
        assertFalse("domain errors must be isError results, not protocol errors", response.has("error"));
        return response.getAsJsonObject("result");
    }
}
