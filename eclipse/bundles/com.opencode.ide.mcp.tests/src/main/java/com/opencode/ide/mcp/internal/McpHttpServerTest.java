package com.opencode.ide.mcp.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.tools.McpDispatcher;
import com.opencode.ide.tools.cpp.CppToolProvider;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Component test against the real HTTP endpoint (started on an ephemeral
 * loopback port like inside Eclipse): POST initialize/tools-list round trips,
 * 202 for notifications, 405 for GET, -32700 for malformed bodies, and the
 * G-003 token gate (401 without/wrong token, Bearer accepted).
 */
public class McpHttpServerTest {

    private static McpHttpServer server;

    @BeforeClass
    public static void start() throws IOException {
        server = McpHttpServer.start(new McpDispatcher(new CppToolProvider()));
    }

    @AfterClass
    public static void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void initializeOverHttp() throws IOException {
        Result r = post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        assertEquals(200, r.status());
        assertTrue("content type must be JSON: " + r.contentType(),
                r.contentType().startsWith("application/json"));
        JsonObject response = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(7, response.get("id").getAsInt());
        assertEquals("eclipse-build",
                response.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name")
                        .getAsString());
    }

    @Test
    public void toolsListOverHttp() throws IOException {
        Result r = post("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/list\"}");
        assertEquals(200, r.status());
        JsonObject response = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        List<String> names = tools.asList().stream()
                .map(e -> e.getAsJsonObject().get("name").getAsString()).toList();
        for (String expected : new String[] {"toolchains_list", "cmake_configure", "cmake_build",
                "ctest_run", "run_binary", "debug_batch", "lint_run", "format_run"}) {
            assertTrue("tools/list over HTTP must contain " + expected, names.contains(expected));
        }
    }

    @Test
    public void notificationReturns202WithEmptyBody() throws IOException {
        Result r = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.status());
        assertEquals(0, r.body().length);
    }

    @Test
    public void getIs405() throws IOException {
        HttpURLConnection conn = connection();
        conn.setRequestMethod("GET");
        assertEquals(405, conn.getResponseCode());
        assertEquals("POST", conn.getHeaderField("Allow"));
        conn.disconnect();
    }

    @Test
    public void malformedJsonOverHttpIs32700() throws IOException {
        Result r = post("{not json");
        assertEquals(200, r.status());
        JsonObject response = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void postWithoutTokenIs401() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + server.port() + "/mcp").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void postWithWrongTokenIs401() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(server.endpointUrl() + "deadbeef").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(401, conn.getResponseCode());
        conn.disconnect();
    }

    @Test
    public void bearerHeaderIsAccepted() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + server.port() + "/mcp").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + server.token());
        try (OutputStream out = conn.getOutputStream()) {
            out.write("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();
    }

    private static HttpURLConnection connection() throws IOException {
        // the registered URL (token included) is the legitimate caller's form
        return (HttpURLConnection) URI.create(server.endpointUrl()).toURL().openConnection();
    }

    private static Result post(String body) throws IOException {
        HttpURLConnection conn = connection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        String contentType = conn.getContentType();
        java.io.InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        byte[] response = stream == null ? new byte[0] : stream.readAllBytes();
        conn.disconnect();
        return new Result(status, contentType == null ? "" : contentType, response);
    }

    private record Result(int status, String contentType, byte[] body) {
    }
}
