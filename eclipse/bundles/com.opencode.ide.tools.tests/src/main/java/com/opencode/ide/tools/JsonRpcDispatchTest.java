package com.opencode.ide.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.opencode.ide.tools.cpp.CppToolProvider;
import java.util.List;
import org.junit.Test;

/**
 * Unit-level JSON-RPC dispatch checks: initialize shape, notification semantics, tools/list
 * completeness (union across providers), error codes (-32700/-32601/-32602), tool-level isError
 * results (unknown tool, relative path) and ToolProvider SPI union + routing.
 */
public class JsonRpcDispatchTest {

  private static final List<String> ALL_TOOLS =
      List.of(
          "toolchains_list",
          "cmake_configure",
          "cmake_build",
          "ctest_run",
          "run_binary",
          "debug_batch",
          "lint_run",
          "format_run");

  private final McpDispatcher dispatcher = new McpDispatcher(new CppToolProvider());

  private JsonObject roundTrip(String body) {
    String response = dispatcher.handle(body);
    assertNotNull("a response must be emitted for requests", response);
    return JsonParser.parseString(response).getAsJsonObject();
  }

  @Test
  public void initializeResultShape() {
    JsonObject response =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}");
    assertEquals("2.0", response.get("jsonrpc").getAsString());
    assertEquals(1, response.get("id").getAsInt());
    assertFalse(response.has("error"));
    JsonObject result = response.getAsJsonObject("result");
    assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
    assertTrue(
        "capabilities must advertise tools", result.getAsJsonObject("capabilities").has("tools"));
    JsonObject serverInfo = result.getAsJsonObject("serverInfo");
    assertEquals("eclipse-build", serverInfo.get("name").getAsString());
    assertEquals("0.1.0", serverInfo.get("version").getAsString());
  }

  @Test
  public void initializeEchoesSupportedProtocolVersion() {
    JsonObject response =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
    assertEquals(
        "2024-11-05", response.getAsJsonObject("result").get("protocolVersion").getAsString());
  }

  @Test
  public void initializeFallsBackForUnsupportedProtocolVersion() {
    JsonObject response =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
    assertEquals(
        "2025-03-26", response.getAsJsonObject("result").get("protocolVersion").getAsString());
  }

  @Test
  public void notificationEmitsNoResult() {
    assertNull(
        "notifications must not produce a response body (HTTP 202 path)",
        dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    assertNull(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"cancellation/notification\"}"));
  }

  @Test
  public void toolsListContainsAllToolsWithInputSchemas() {
    JsonObject response = roundTrip("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
    JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(ALL_TOOLS.size(), tools.size());
    for (int i = 0; i < tools.size(); i++) {
      JsonObject tool = tools.get(i).getAsJsonObject();
      assertTrue("tool must have a name", tool.get("name").isJsonPrimitive());
      assertTrue("tool must have a description", tool.get("description").isJsonPrimitive());
      JsonObject schema = tool.getAsJsonObject("inputSchema");
      assertNotNull(schema);
      assertEquals("object", schema.get("type").getAsString());
      assertNotNull(
          "schemas must carry a properties object (may be empty for toolchains_list)",
          schema.getAsJsonObject("properties"));
    }
    List<String> names =
        tools.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList();
    for (String expected : ALL_TOOLS) {
      assertTrue("tools/list must contain " + expected, names.contains(expected));
    }
  }

  @Test
  public void toolsCallUnknownToolIsError() {
    JsonObject response =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"does_not_exist\",\"arguments\":{}}}");
    JsonObject result = response.getAsJsonObject("result");
    assertTrue("unknown tool must set isError", result.get("isError").getAsBoolean());
    String text =
        result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertTrue("error text should name the tool: " + text, text.contains("unknown tool"));
  }

  @Test
  public void malformedJsonIsParseError() {
    JsonObject response = roundTrip("{this is not json");
    assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
    assertTrue(response.get("id").isJsonNull());
  }

  @Test
  public void unknownMethodIsMethodNotFound() {
    JsonObject response = roundTrip("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}");
    assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(6, response.get("id").getAsInt());
  }

  @Test
  public void badParamsIsInvalidParams() {
    JsonObject noName =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"arguments\":{}}}");
    assertEquals(-32602, noName.getAsJsonObject("error").get("code").getAsInt());

    JsonObject missingSource =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"cmake_configure\",\"arguments\":{\"build_dir\":\"C:\\\\tmp\\\\b\"}}}");
    assertEquals(-32602, missingSource.getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  public void relativePathRejectedAsToolError() {
    JsonObject response =
        roundTrip(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"run_binary\",\"arguments\":{\"binary\":\"relative/hello.exe\"}}}");
    JsonObject result = response.getAsJsonObject("result");
    assertTrue(
        "relative paths must be rejected with isError", result.get("isError").getAsBoolean());
    String text =
        result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertTrue(
        "error text should explain the absolute-path requirement: " + text,
        text.contains("absolute"));
  }

  @Test
  public void pingReturnsEmptyResult() {
    JsonObject response = roundTrip("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}");
    assertTrue(response.getAsJsonObject("result").entrySet().isEmpty());
  }

  @Test
  public void secondProviderAddsToolToUnionAndReceivesRouting() {
    McpDispatcher multi = new McpDispatcher(List.of(new CppToolProvider(), new DummyProvider()));
    JsonObject list =
        JsonParser.parseString(
                multi.handle("{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/list\"}"))
            .getAsJsonObject()
            .getAsJsonObject("result");
    JsonArray tools = list.getAsJsonArray("tools");
    assertEquals("union of both providers expected", ALL_TOOLS.size() + 1, tools.size());
    List<String> names =
        tools.asList().stream().map(e -> e.getAsJsonObject().get("name").getAsString()).toList();
    assertTrue("dummy tool expected in the union", names.contains("dummy_echo"));
    assertTrue("cpp tool expected in the union", names.contains("lint_run"));

    JsonObject dummyCall =
        JsonParser.parseString(
                multi.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":21,"
                        + "\"method\":\"tools/call\",\"params\":{\"name\":\"dummy_echo\","
                        + "\"arguments\":{\"value\":\"xyz\"}}}"))
            .getAsJsonObject()
            .getAsJsonObject("result");
    assertFalse("dummy call must succeed", dummyCall.get("isError").getAsBoolean());
    assertEquals(
        "routing must reach the dummy provider",
        "xyz",
        dummyCall.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());

    JsonObject cppCall =
        JsonParser.parseString(
                multi.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":22,"
                        + "\"method\":\"tools/call\",\"params\":{\"name\":\"toolchains_list\",\"arguments\":{}}}"))
            .getAsJsonObject()
            .getAsJsonObject("result");
    assertFalse("cpp call must succeed", cppCall.get("isError").getAsBoolean());
    assertTrue(
        "routing must reach the cpp provider",
        cppCall
            .getAsJsonArray("content")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString()
            .contains("toolchains"));

    JsonObject unknown =
        JsonParser.parseString(
                multi.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":23,"
                        + "\"method\":\"tools/call\",\"params\":{\"name\":\"does_not_exist\",\"arguments\":{}}}"))
            .getAsJsonObject()
            .getAsJsonObject("result");
    assertTrue("unknown tool must set isError", unknown.get("isError").getAsBoolean());
    assertTrue(
        "the available-tools list must span both providers",
        unknown
            .getAsJsonArray("content")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString()
            .contains("dummy_echo"));
  }

  /** Minimal second language pack proving the ToolProvider SPI (union + per-name routing). */
  private static final class DummyProvider implements ToolProvider {

    @Override
    public String language() {
      return "dummy";
    }

    @Override
    public List<McpTool> tools() {
      JsonObject value = new JsonObject();
      value.addProperty("type", "string");
      JsonObject properties = new JsonObject();
      properties.add("value", value);
      JsonObject schema = new JsonObject();
      schema.addProperty("type", "object");
      schema.add("properties", properties);
      schema.add("required", JsonParser.parseString("[\"value\"]").getAsJsonArray());
      return List.of(new McpTool("dummy_echo", "echoes the value argument", schema));
    }

    @Override
    public McpToolResult call(String toolName, JsonObject arguments) {
      return new McpToolResult(arguments.get("value").getAsString(), false);
    }
  }
}
