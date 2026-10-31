package com.opencode.ide.tools.cpp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Argument-list-only process execution for the MCP build tools. Prepends toolchain directories to
 * PATH, merges stdout+stderr, caps captured output at 256 KB, enforces a wall-clock timeout
 * (killing the whole process tree on expiry) and reports the outcome as a {@link ToolResult}.
 */
public final class BuildRunner {

  /** Structured outcome of one process run, serialized as JSON text by the tools. */
  public record ToolResult(int exitCode, long durationMs, String output) {}

  private static final int MAX_OUTPUT_BYTES = 256 * 1024;
  private static final String TRUNCATED_MARKER = "\n...[truncated]";

  private BuildRunner() {}

  public static ToolResult run(
      List<String> command, List<Path> pathPrepend, Path workingDir, Duration timeout) {
    long start = System.nanoTime();
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDir != null) {
      builder.directory(workingDir.toFile());
    }
    if (pathPrepend != null && !pathPrepend.isEmpty()) {
      Map<String, String> env = builder.environment();
      String key = env.containsKey("Path") ? "Path" : "PATH";
      String existing = env.getOrDefault(key, "");
      String prepend =
          pathPrepend.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
      env.put(key, prepend + File.pathSeparator + existing);
    }
    builder.redirectErrorStream(true);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      return new ToolResult(-1, 0, "failed to start " + command.get(0) + ": " + e.getMessage());
    }
    OutputPumper pumper = new OutputPumper(process.getInputStream());
    Thread reader = new Thread(pumper, "mcp-process-output");
    reader.setDaemon(true);
    reader.start();
    boolean timedOut = false;
    try {
      timedOut = !process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      timedOut = true;
    }
    if (timedOut) {
      killTree(process);
    }
    try {
      reader.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    int exitCode = timedOut ? -1 : safeExit(process);
    String output = pumper.text(timedOut ? timeout.toMillis() : -1L);
    long durationMs = (System.nanoTime() - start) / 1_000_000L;
    return new ToolResult(exitCode, durationMs, output);
  }

  private static int safeExit(Process process) {
    try {
      return process.exitValue();
    } catch (IllegalThreadStateException e) {
      return -1;
    }
  }

  private static void killTree(Process process) {
    if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
      try {
        Process killer =
            new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                .start();
        killer.waitFor(15, TimeUnit.SECONDS);
      } catch (IOException e) {
        // fall through to destroyForcibly
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * Drains the merged child output into a capped buffer; keeps reading past the cap so the child
   * never blocks.
   */
  private static final class OutputPumper implements Runnable {

    private final InputStream in;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean truncated;

    OutputPumper(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      byte[] chunk = new byte[8192];
      try (InputStream stream = in) {
        int read;
        while ((read = stream.read(chunk)) != -1) {
          int room = MAX_OUTPUT_BYTES - buffer.size();
          if (read > room) {
            truncated = true;
            if (room > 0) {
              buffer.write(chunk, 0, room);
            }
          } else {
            buffer.write(chunk, 0, read);
          }
        }
      } catch (IOException ignored) {
        // child died or stream closed - keep what was captured
      }
    }

    String text(long timeoutMs) {
      StringBuilder sb = new StringBuilder(buffer.toString(StandardCharsets.UTF_8));
      if (truncated) {
        sb.append(TRUNCATED_MARKER);
      }
      if (timeoutMs >= 0) {
        sb.append("\n...[timed out after ").append(timeoutMs).append(" ms]");
      }
      return sb.toString();
    }
  }
}
