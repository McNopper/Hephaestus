package com.opencode.ide.ui.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.Test;

import com.opencode.ide.tools.ToolInvocation;

/**
 * Unit tests for {@link AgentToolsFormat} — the SWT-free renderer behind
 * the "Agent Tools" console (U-009). The console glue
 * ({@link AgentToolsConsole}) is UI-bound and covered by the live
 * verification criterion on the ticket instead.
 */
public class AgentToolsFormatTest {

    private static final long STARTED = 1_700_000_000_000L;

    private static ToolInvocation completed(String tool, String args, String output, boolean error) {
        return ToolInvocation.completed(1L, STARTED, 250L, tool, args, output, error);
    }

    @Test
    public void nullInvocationRendersEmptyBlockAndHeader() {
        assertEquals("", AgentToolsFormat.block(null));
        assertEquals("", AgentToolsFormat.header(null));
    }

    @Test
    public void headerCarriesTimestampToolAndDuration() {
        String expectedTime = DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(STARTED));
        String header = AgentToolsFormat.header(completed("cmake_build", "x=y", "ok", false));
        assertTrue(header, header.startsWith("[" + expectedTime + "] "));
        assertTrue(header, header.contains("cmake_build"));
        assertTrue(header, header.endsWith("(250ms)"));
    }

    @Test
    public void durationRendersMillisBelowASecondAndSecondsAbove() {
        assertEquals("0ms", AgentToolsFormat.duration(0));
        assertEquals("250ms", AgentToolsFormat.duration(250));
        assertEquals("999ms", AgentToolsFormat.duration(999));
        assertEquals("1.0s", AgentToolsFormat.duration(1000));
        assertEquals("3.4s", AgentToolsFormat.duration(3400));
        assertEquals("61.7s", AgentToolsFormat.duration(61700));
        assertEquals("0ms", AgentToolsFormat.duration(-5));
    }

    @Test
    public void blockNestsOutputUnderHeaderAndArgsAndEndsWithNewline() {
        String block = AgentToolsFormat.block(completed(
                "cmake_build", "build_dir=C:\\b, target=app",
                "[ 50%] Building CXX object main.cpp.o\n[100%] Linking\n", false));
        String[] lines = block.split("\n");
        assertEquals("  args: build_dir=C:\\b, target=app", lines[1]);
        assertEquals("  [ 50%] Building CXX object main.cpp.o", lines[2]);
        assertEquals("  [100%] Linking", lines[3]);
        assertTrue(block.endsWith("\n"));
    }

    @Test
    public void outputWithoutTrailingNewlineStillEndsTheBlock() {
        String block = AgentToolsFormat.block(completed("ctest_run", "(no arguments)", "2/2 passed", false));
        assertTrue(block.endsWith("  2/2 passed\n"));
    }

    @Test
    public void emptyOutputLeavesHeaderAndArgsOnly() {
        String block = AgentToolsFormat.block(completed("cmake_configure", "dir=C:\\b", "", false));
        assertEquals(2, block.split("\n").length);
    }

    @Test
    public void mcpErrorResultIsMarkedErrorAndKeepsOutput() {
        String block = AgentToolsFormat.block(completed("run_binary", "path=app", "segmentation fault", true));
        assertTrue(block, block.contains("! ERROR run_binary"));
        assertTrue(block, block.contains("  segmentation fault"));
        assertFalse(block, block.contains("failed:"));
    }

    @Test
    public void dispatchFailureIsMarkedFailedWithTheMessageAndNoOutput() {
        ToolInvocation failed = ToolInvocation.failed(7L, STARTED, 12L,
                "debug_batch", "binary=app", "gdb not found on PATH");
        String block = AgentToolsFormat.block(failed);
        assertTrue(block, block.contains("! FAILED debug_batch"));
        assertTrue(block, block.contains("  failed: gdb not found on PATH"));
        assertEquals(3, block.split("\n").length);
    }

    @Test
    public void sequenceIsNotRenderedButAccepted() {
        ToolInvocation inv = ToolInvocation.completed(99L, STARTED, 1L, "t", "a=b", "", false);
        assertEquals(99L, inv.sequence());
        assertFalse(AgentToolsFormat.block(inv).contains("99"));
    }

    @Test
    public void indentSkipsBlankLinesButKeepsContent() {
        assertEquals("", AgentToolsFormat.indent(""));
        assertEquals("", AgentToolsFormat.indent(null));
        assertEquals("  a\n  b\n", AgentToolsFormat.indent("a\nb\n"));
        assertEquals("  a\n\n  b", AgentToolsFormat.indent("a\n\nb"));
    }
}
