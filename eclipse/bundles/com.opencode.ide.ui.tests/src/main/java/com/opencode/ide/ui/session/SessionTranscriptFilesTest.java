package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Unit tests for the Batch-C "open in editor" temp-file helper: name
 * sanitization (session ids and message ordinals must never become invalid
 * path fragments) and the UTF-8 snapshot write itself.
 */
public class SessionTranscriptFilesTest {

    @Test
    public void safeNameKeepsAsciiWordsAndCollapsesTheRestToDashes() {
        assertEquals("ses_aBcD-message-3", SessionTranscriptFiles.safeName("ses_aBcD/message 3"));
        assertEquals("T-001-diff", SessionTranscriptFiles.safeName("T-001 diff"));
        assertEquals("T-001.diff", SessionTranscriptFiles.safeName("T-001.diff"));
        assertEquals("untitled", SessionTranscriptFiles.safeName(null));
        assertEquals("untitled", SessionTranscriptFiles.safeName("   "));
        assertEquals("untitled", SessionTranscriptFiles.safeName("///"));
        assertEquals("transcript", SessionTranscriptFiles.safeName("transcript"));
    }

    @Test
    public void writeProducesAUtf8SnapshotFileWithSanitizedSuffix() throws Exception {
        Path file = SessionTranscriptFiles.write("ses_1/message 2", "hallö\nnext line");

        assertTrue("file must exist: " + file, Files.isRegularFile(file));
        assertTrue("file name is sanitized: " + file.getFileName(),
                file.getFileName().toString().startsWith("ses_1-message-2-"));
        assertTrue("file name keeps the .txt suffix: " + file.getFileName(),
                file.getFileName().toString().endsWith(".txt"));
        assertEquals("hallö\nnext line", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    public void nullContentWritesAnEmptyFileAndEveryCallIsAFreshSnapshot() throws Exception {
        Path first = SessionTranscriptFiles.write("a", null);
        Path second = SessionTranscriptFiles.write("a", null);

        assertEquals("", Files.readString(first, StandardCharsets.UTF_8));
        assertTrue("every open writes its own snapshot", !first.equals(second));
    }
}
