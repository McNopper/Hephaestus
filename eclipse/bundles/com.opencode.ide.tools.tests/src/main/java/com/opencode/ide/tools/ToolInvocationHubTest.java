package com.opencode.ide.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.opencode.ide.tools.cpp.CppToolProvider;

import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for the invocation-capture seam: {@link McpDispatcher} reports
 * every routed tools/call (name, argument summary, output tail, outcome) to
 * the {@link ToolInvocationHub}'s registered {@link ToolInvocationListener}s,
 * listener exceptions never break dispatch, and long outputs are tailed to
 * {@link ToolInvocation#MAX_OUTPUT_CHARS}. Uses the same plain-JUnit,
 * SWT-free style as {@link JsonRpcDispatchTest}; listeners are always
 * removed again so other tests in this JVM stay unobserved.
 */
public class ToolInvocationHubTest {

    /** Records every invocation it is told about. */
    private static final class RecordingListener implements ToolInvocationListener {
        final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();

        @Override
        public void onToolInvocation(ToolInvocation invocation) {
            invocations.add(invocation);
        }

        ToolInvocation last() {
            return invocations.get(invocations.size() - 1);
        }
    }

    /** One-shot provider whose call behaviour the test wires up. */
    private static final class StubProvider implements ToolProvider {
        final java.util.function.Function<JsonObject, McpToolResult> behaviour;

        StubProvider(java.util.function.Function<JsonObject, McpToolResult> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String language() {
            return "stub";
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
            return List.of(new McpTool("stub_echo", "echoes the value argument", schema));
        }

        @Override
        public McpToolResult call(String toolName, JsonObject arguments) {
            return behaviour.apply(arguments);
        }
    }

    private final RecordingListener recorder = new RecordingListener();

    @After
    public void removeListeners() {
        for (ToolInvocationListener listener : ToolInvocationHub.listeners()) {
            ToolInvocationHub.removeListener(listener);
        }
    }

    private String callStub(McpToolResult result, String argumentsJson) {
        McpDispatcher dispatcher = new McpDispatcher(new StubProvider(args -> result));
        return dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"stub_echo\",\"arguments\":"
                + (argumentsJson == null ? "{}" : argumentsJson) + "}}");
    }

    @Test
    public void listenerSeesNameArgumentSummaryAndOutput() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        McpToolResult result = new McpToolResult("built 3 targets", false);
        String response = callStub(result,
                "{\"value\":\"hello\",\"count\":3,\"on\":true}");

        assertNotNull("the response must still be a normal result", response);
        assertFalse(JsonParser.parseString(response).getAsJsonObject().has("error"));

        assertEquals(1, recorder.invocations.size());
        ToolInvocation invocation = recorder.last();
        assertEquals("stub_echo", invocation.tool());
        assertEquals("value=hello, count=3, on=true", invocation.argumentsSummary());
        assertEquals("built 3 targets", invocation.output());
        assertFalse("a successful call is not an error result", invocation.errorResult());
        assertNull("no dispatch-level failure", invocation.failure());
        assertFalse(invocation.dispatchFailure());
        assertTrue("sequence ids are positive", invocation.sequence() > 0);
        assertTrue("startedAt is a sane epoch", invocation.startedAtMillis() > 0);
        assertTrue("duration is non-negative", invocation.durationMillis() >= 0);
    }

    @Test
    public void longOutputIsTailedToTheCapWithAMarker() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        String filler = "x".repeat(10_000);
        callStub(new McpToolResult(filler, false), null);

        ToolInvocation invocation = recorder.last();
        assertTrue("output must be capped around the tail size",
                invocation.output().length() <= ToolInvocation.MAX_OUTPUT_CHARS + 80);
        assertTrue("the truncation marker must name the dropped characters",
                invocation.output().contains("earlier characters truncated]"));
        assertTrue("the kept part is the TAIL",
                invocation.output().endsWith(filler.substring(filler.length()
                        - ToolInvocation.MAX_OUTPUT_CHARS)));
    }

    @Test
    public void throwingListenerNeverBreaksDispatchAndOthersStillRun() {
        AtomicInteger brokenCalls = new AtomicInteger();
        ToolInvocationListener broken = invocation -> {
            brokenCalls.incrementAndGet();
            throw new IllegalStateException("observer blew up");
        };
        assertTrue(ToolInvocationHub.addListener(broken));
        assertTrue(ToolInvocationHub.addListener(recorder));

        String response = callStub(new McpToolResult("still fine", false), null);

        assertNotNull("dispatch must answer despite the throwing listener", response);
        JsonObject result = JsonParser.parseString(response).getAsJsonObject()
                .getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("the agent must still receive the tool text", "still fine",
                result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("the broken listener ran (and was swallowed)", 1, brokenCalls.get());
        assertEquals("the healthy listener still ran", 1, recorder.invocations.size());
    }

    @Test
    public void mcpErrorResultsKeepTheirToolText() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        callStub(McpToolResult.error("cmake_configure: no toolchain found"), null);

        ToolInvocation invocation = recorder.last();
        assertTrue(invocation.errorResult());
        assertNull(invocation.failure());
        assertTrue(invocation.output().contains("no toolchain found"));
    }

    @Test
    public void paramErrorIsReportedAsDispatchFailureAndStillMapsToInvalidParams() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        McpDispatcher dispatcher = new McpDispatcher(new CppToolProvider());
        String response = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"cmake_configure\","
                + "\"arguments\":{\"build_dir\":\"C:\\\\tmp\\\\b\"}}}");

        assertEquals(-32602, JsonParser.parseString(response).getAsJsonObject()
                .getAsJsonObject("error").get("code").getAsInt());
        ToolInvocation invocation = recorder.last();
        assertTrue("a thrown ParamError is a dispatch-level failure", invocation.dispatchFailure());
        assertNotNull(invocation.failure());
        assertTrue("the failure names the exception: " + invocation.failure(),
                invocation.failure().startsWith("ParamError"));
        assertEquals("failures carry no tool output", "", invocation.output());
        assertEquals("cmake_configure", invocation.tool());
    }

    @Test
    public void unknownToolIsReportedAsAnErrorResult() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        McpDispatcher dispatcher = new McpDispatcher(new StubProvider(args -> new McpToolResult("x", false)));
        dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"does_not_exist\",\"arguments\":{}}}");

        ToolInvocation invocation = recorder.last();
        assertEquals("does_not_exist", invocation.tool());
        assertTrue(invocation.errorResult());
        assertNull("an unknown tool is an MCP error result, not a dispatch failure",
                invocation.failure());
        assertTrue(invocation.output().contains("unknown tool"));
        assertEquals("(no arguments)", invocation.argumentsSummary());
    }

    @Test
    public void sequenceNumbersIncreaseMonotonically() {
        assertTrue(ToolInvocationHub.addListener(recorder));
        callStub(new McpToolResult("one", false), null);
        callStub(new McpToolResult("two", false), null);
        callStub(new McpToolResult("three", false), null);

        assertEquals(3, recorder.invocations.size());
        for (int i = 1; i < recorder.invocations.size(); i++) {
            assertTrue("sequences must increase",
                    recorder.invocations.get(i).sequence() > recorder.invocations.get(i - 1).sequence());
        }
    }

    @Test
    public void addRemoveAndDuplicateListenerSemantics() {
        ToolInvocationListener listener = invocation -> {
        };
        assertFalse("null is rejected", ToolInvocationHub.addListener(null));
        assertTrue(ToolInvocationHub.addListener(listener));
        assertFalse("registering the same instance twice is a no-op",
                ToolInvocationHub.addListener(listener));
        assertEquals(1, ToolInvocationHub.listeners().size());
        assertTrue(ToolInvocationHub.removeListener(listener));
        assertFalse("removing twice reports false", ToolInvocationHub.removeListener(listener));
        assertTrue(ToolInvocationHub.listeners().isEmpty());

        callStub(new McpToolResult("unobserved", false), null);
        assertTrue("a removed listener hears nothing", recorder.invocations.isEmpty());
    }

    @Test
    public void summarizeArgumentsCoversValueShapes() {
        assertEquals("(no arguments)", ToolInvocationHub.summarizeArguments(null));
        assertEquals("(no arguments)", ToolInvocationHub.summarizeArguments(new JsonObject()));

        JsonObject args = new JsonObject();
        args.addProperty("build_dir", "C:\\tmp\\b");
        args.addProperty("jobs", 8);
        args.addProperty("clean", true);
        args.addProperty("empty", "");
        args.add("targets", JsonParser.parseString("[\"app\",\"tests\"]").getAsJsonArray());
        args.add("env", JsonParser.parseString("{\"PATH\":\"long\"}").getAsJsonObject());
        args.addProperty("missing", (String) null);
        assertEquals("build_dir=C:\\tmp\\b, jobs=8, clean=true, empty=\"\", "
                + "targets=[2 items], env={1 field}, missing=null",
                ToolInvocationHub.summarizeArguments(args));
    }

    @Test
    public void summarizeArgumentsCapsTheLine() {
        JsonObject args = new JsonObject();
        args.addProperty("blob", "y".repeat(2_000));
        String summary = ToolInvocationHub.summarizeArguments(args);
        assertTrue("summary is capped near the limit: " + summary.length(),
                summary.length() <= ToolInvocationHub.MAX_SUMMARY_CHARS + 3);
        assertTrue("capping keeps an ellipsis", summary.endsWith("..."));
    }

    @Test
    public void summarizeArgumentsCapsAcrossManySmallEntries() {
        JsonObject args = new JsonObject();
        for (int i = 0; i < 50; i++) {
            args.addProperty("key" + i, "value-" + i);
        }
        String summary = ToolInvocationHub.summarizeArguments(args);
        assertTrue(summary.length() <= ToolInvocationHub.MAX_SUMMARY_CHARS + 3);
        assertTrue(summary.endsWith("..."));
        assertTrue("early entries survive the cap", summary.startsWith("key0=value-0, key1=value-1"));
    }

    @Test
    public void tailHelperHandlesEdges() {
        assertEquals("", ToolInvocation.tail(null, 100));
        assertEquals("short", ToolInvocation.tail("short", 100));
        String tailed = ToolInvocation.tail("abcdef", 3);
        assertEquals("...[3 earlier characters truncated]\ndef", tailed);
    }
}
