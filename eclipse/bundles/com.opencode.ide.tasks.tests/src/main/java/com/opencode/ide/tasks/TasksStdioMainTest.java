package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * In-process drive of the stdio loop (fallback leg of the transport coverage): feed JSON-RPC lines
 * on a swapped stdin, capture stdout, and verify the loop honours {@code --root}, answers
 * initialize/tools/call and stays silent for notifications and blank lines. (stdin/stdout swapping
 * is not parallel-safe; this class must stay sequential.)
 */
public class TasksStdioMainTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private String run(String inputJsonLines, Path root) throws Exception {
    InputStream origIn = System.in;
    PrintStream origOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setIn(new ByteArrayInputStream(inputJsonLines.getBytes(StandardCharsets.UTF_8)));
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      TasksStdioMain.main(new String[] {"--root", root.toString()});
    } finally {
      System.setIn(origIn);
      System.setOut(origOut);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  @Test
  public void loopAnswersRequestsAndIgnoresNoise() throws Exception {
    Path root = tmp.getRoot().toPath().resolve("tasks");
    String input =
        String.join(
            "\n",
            "",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"task_create\",\"arguments\":"
                + "{\"project\":\"p\",\"title\":\"via stdio\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"task_get\",\"arguments\":"
                + "{\"project\":\"p\",\"ticket_id\":\"T-001\"}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"task_claim\",\"arguments\":"
                + "{\"project\":\"p\",\"role\":\"developer\"}}}");
    String out = run(input, root);
    List<String> lines =
        java.util.Arrays.stream(out.split("\r?\n")).filter(l -> !l.isBlank()).toList();
    assertEquals(
        "one response per request, none for the notification/blank lines", 4, lines.size());
    assertTrue(lines.get(0).contains("\"protocolVersion\":\"2025-03-26\""));
    assertTrue(lines.get(1).contains("via stdio"));
    assertTrue(lines.get(2).contains("T-001"));
    assertEquals(
        "claim with nothing claimable returns the JSON literal null",
        "null",
        com.google.gson.JsonParser.parseString(lines.get(3))
            .getAsJsonObject()
            .getAsJsonObject("result")
            .getAsJsonArray("content")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString());

    // the write landed in the requested root
    TaskStore store = new TaskStore(root);
    assertEquals("via stdio", store.get("p", "T-001").title);
    assertNull(store.get("p", "T-001").assignee);
  }
}
