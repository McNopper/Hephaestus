package com.opencode.ide.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * B-012 regression coverage for {@link StdioServer}: a clean stdin EOF exits 0, a dead pipe (read
 * failure or swallowed write failure) fails FAST with a nonzero code, and an orphaned launcher ends
 * the loop instead of lingering as a zombie JVM. (Stream/queue based - no parallel-sensitive
 * System.in swapping.)
 */
public class StdioServerTest {

  private static BufferedReader reader(String... lines) {
    return new BufferedReader(new InputStreamReaderOf(String.join("\n", lines)));
  }

  /** Minimal Reader-over-String bridge (keeps the test free of extra deps). */
  private static final class InputStreamReaderOf extends java.io.Reader {
    private final java.io.Reader delegate;

    InputStreamReaderOf(String text) {
      this.delegate = new java.io.StringReader(text);
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      return delegate.read(cbuf, off, len);
    }

    @Override
    public void close() {}
  }

  private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

  /** A tool provider whose invocation throws an {@link Error} - the B-0xx crash class. */
  private static final class ThrowingProvider implements ToolProvider {
    @Override
    public String language() {
      return "poison";
    }

    @Override
    public java.util.List<McpTool> tools() {
      return java.util.List.of(
          new McpTool("poison", "a tool whose invocation throws an Error", schemaForPoison()));
    }

    @Override
    public McpToolResult call(String toolName, JsonObject arguments) {
      throw new AssertionError("simulated Error escaping a handler (store contention class)");
    }

    private JsonObject schemaForPoison() {
      JsonObject obj = new JsonObject();
      obj.addProperty("type", "object");
      return obj;
    }
  }

  @Test
  public void aThrowingHandlerDoesNotKillTheLoop() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    int code =
        StdioServer.serve(
            reader(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"poison\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"),
            new PrintStream(captured, true, StandardCharsets.UTF_8),
            new McpDispatcher(new ThrowingProvider()),
            () -> true);

    assertEquals("the loop survives the Error from the handler", StdioServer.EXIT_OK, code);
    String out = captured.toString(StandardCharsets.UTF_8);
    assertTrue(
        "the poison request is answered with an internal error: " + out,
        out.contains("\"id\":1") && out.contains("-32603"));
    assertTrue(
        "the loop keeps serving: the next request is answered: " + out, out.contains("\"id\":2"));
  }

  @Test
  public void cleanEofExitsOkAndAnswersRequests() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    int code =
        StdioServer.serve(
            reader("", PING, "   "),
            new PrintStream(captured, true, StandardCharsets.UTF_8),
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            () -> true);

    assertEquals(StdioServer.EXIT_OK, code);
    String out = captured.toString(StandardCharsets.UTF_8);
    assertTrue("the ping is answered: " + out, out.contains("\"id\":1"));
  }

  @Test
  public void notificationAndBlankLinesProduceNoOutput() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    int code =
        StdioServer.serve(
            reader("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", ""),
            new PrintStream(captured, true, StandardCharsets.UTF_8),
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            () -> true);

    assertEquals(StdioServer.EXIT_OK, code);
    assertEquals("", captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void brokenStdoutFailsFastWithNonzeroCode() {
    AtomicInteger writes = new AtomicInteger();
    PrintStream broken =
        new PrintStream(
            new OutputStream() {
              @Override
              public void write(int b) throws IOException {
                writes.incrementAndGet();
                throw new IOException("broken pipe");
              }
            },
            true,
            StandardCharsets.UTF_8);
    // more requests than the loop may consume: a dead host must STOP the loop
    String many = String.join("\n", PING, PING, PING, PING, PING);

    int code =
        StdioServer.serve(
            reader(many),
            broken,
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            () -> true);

    assertEquals(StdioServer.EXIT_PIPE_DIED, code);
    assertTrue("the loop failed fast (writes: " + writes.get() + ")", writes.get() <= 2);
  }

  @Test
  public void readFailureFailsFastWithNonzeroCode() {
    AtomicInteger reads = new AtomicInteger();
    InputStream flaky =
        new InputStream() {
          @Override
          public int read() throws IOException {
            if (reads.incrementAndGet() <= 4) {
              return PING.charAt(0); // degenerate but consumed via the reader below
            }
            throw new IOException("host's pipe died");
          }
        };
    BufferedReader in =
        new BufferedReader(new java.io.InputStreamReader(flaky, StandardCharsets.UTF_8));

    int code =
        StdioServer.serve(
            in,
            new PrintStream(new ByteArrayOutputStream(), true),
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            () -> true);

    assertEquals(StdioServer.EXIT_PIPE_DIED, code);
  }

  @Test
  public void orphanedParentExitsNonzeroWithoutReading() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    int code =
        StdioServer.serve(
            reader(PING, PING),
            new PrintStream(captured, true, StandardCharsets.UTF_8),
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            () -> false);

    assertEquals(StdioServer.EXIT_ORPHANED, code);
    assertEquals(
        "nothing is answered once the launcher is gone",
        "",
        captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void readFailureAfterOrphaningReportsOrphanedNotPipeDied() {
    AtomicBoolean parentAlive = new AtomicBoolean(true);
    InputStream dies =
        new InputStream() {
          @Override
          public int read() throws IOException {
            parentAlive.set(false); // the launcher dies while the read is in flight
            throw new IOException("pipe closed with the parent");
          }
        };
    BufferedReader in =
        new BufferedReader(new java.io.InputStreamReader(dies, StandardCharsets.UTF_8));
    BooleanSupplier parent = parentAlive::get;

    int code =
        StdioServer.serve(
            in,
            new PrintStream(new ByteArrayOutputStream(), true),
            new McpDispatcher(new com.opencode.ide.tools.cpp.CppToolProvider()),
            parent);

    assertEquals(StdioServer.EXIT_ORPHANED, code);
  }
}
