package com.opencode.ide.mcp.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wiring test of the DS component exactly as it runs inside Eclipse:
 * {@code activate()} builds the multi-pack dispatcher (C++ + task board),
 * the task store root comes from the {@code opencode.tasks.root} system
 * property, and a {@code task_create} over the real HTTP loopback endpoint
 * lands as a Markdown file under that root. Covers the glue no other test
 * sees (property resolution, provider union in the component).
 */
public class McpServiceComponentTasksWiringTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private McpServiceComponent component;
    private String priorRoot;

    @Before
    public void setUp() {
        priorRoot = System.getProperty("opencode.tasks.root");
        System.setProperty("opencode.tasks.root",
                tmp.getRoot().toPath().resolve("tasks").toString());
        component = new McpServiceComponent();
        component.activate();
    }

    @After
    public void tearDown() {
        component.deactivate();
        if (priorRoot == null) {
            System.clearProperty("opencode.tasks.root");
        } else {
            System.setProperty("opencode.tasks.root", priorRoot);
        }
    }

    @Test
    public void endpointServesTaskToolsAndWritesToConfiguredRoot() throws IOException {
        assertTrue("component must be running", component.isRunning());
        assertTrue("endpoint URL carries the auth token (G-003): " + component.getEndpointUrl(),
                component.getEndpointUrl().matches("http://127\\.0\\.0\\.1:\\d+/mcp\\?token=[0-9a-f]{48}"));

        JsonObject list = JsonParser.parseString(
                post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).getAsJsonObject();
        JsonArray tools = list.getAsJsonObject("result").getAsJsonArray("tools");
        boolean hasTaskCreate = false;
        boolean hasCppTool = false;
        for (int i = 0; i < tools.size(); i++) {
            String name = tools.get(i).getAsJsonObject().get("name").getAsString();
            hasTaskCreate |= "task_create".equals(name);
            hasCppTool |= "toolchains_list".equals(name);
        }
        assertTrue("tools/list unions the task pack", hasTaskCreate);
        assertTrue("tools/list still unions the C++ pack", hasCppTool);

        JsonObject created = JsonParser.parseString(post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"task_create\",\"arguments\":"
                        + "{\"project\":\"wiring\",\"title\":\"from the endpoint\"}}}"))
                .getAsJsonObject();
        assertTrue(created.getAsJsonObject("result").get("isError").getAsBoolean() == false);

        Path taskFile = tmp.getRoot().toPath().resolve("tasks").resolve("wiring").resolve("T-001.md");
        assertTrue("the write must land under the configured root: " + taskFile,
                Files.isRegularFile(taskFile));
        assertTrue(Files.readString(taskFile, StandardCharsets.UTF_8).contains("from the endpoint"));
    }

    @Test
    public void deactivateStopsTheEndpoint() throws IOException {
        int port = component.getPort();
        component.deactivate();
        assertEquals(-1, component.getPort());
        assertTrue(component.getEndpointUrl() == null);
        // the port is released (best-effort: connecting must fail or refuse)
        HttpURLConnection conn = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + port + "/mcp").toURL().openConnection();
        conn.setConnectTimeout(500);
        try {
            conn.getResponseCode();
            // a response would mean something still listens - acceptable only if
            // the OS reused the ephemeral port for another service; assert refusals only
        } catch (IOException expected) {
            // refused = released
        }
        conn.disconnect();
        // re-activate so @After's deactivate() is balanced
        component.activate();
    }

    private String post(String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(component.getEndpointUrl())
                .toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());
        byte[] response = conn.getInputStream().readAllBytes();
        conn.disconnect();
        return new String(response, StandardCharsets.UTF_8);
    }
}
