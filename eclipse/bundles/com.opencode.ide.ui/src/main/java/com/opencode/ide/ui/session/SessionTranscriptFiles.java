package com.opencode.ide.ui.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Temp-file backing for the "open in editor" actions (Batch C of U-002).
 * The modern workbench removed {@code IStorageEditorInput}, so an in-memory
 * editor input is no longer possible — the supported read-only surface is
 * the default text editor over an EFS file store. This helper writes one
 * UTF-8 snapshot {@code .txt} per open (registered deleteOnExit, so nothing
 * accumulates beyond the JVM) and keeps the name file-system-safe: session
 * ids and message ordinals collapse to dashes instead of becoming invalid
 * path fragments.
 */
public final class SessionTranscriptFiles {

    private SessionTranscriptFiles() {
    }

    /**
     * Writes {@code content} to a new temp file named after {@code name}.
     *
     * @param name    the editor tab name (null/blank falls back to
     *                {@code untitled}; unsafe characters collapse to dashes)
     * @param content the file content (null reads as empty)
     * @return the written file (never null; lives in java.io.tmpdir)
     * @throws IllegalStateException on IO failure (tmpdir missing, disk full)
     */
    public static Path write(String name, String content) {
        String safe = safeName(name);
        String prefix = safe.length() < 3 ? safe + "-transcript" : safe;
        try {
            Path file = Files.createTempFile(prefix + "-", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("failed to write transcript file: " + e.getMessage(), e);
        }
    }

    /**
     * File-name-safe form of the tab name: every character outside
     * {@code [A-Za-z0-9._-]} collapses to dashes, blank input (or a name
     * that cleans down to nothing but dashes/dots) reads as
     * {@code untitled}. Public for the separate-bundle tests (OSGi gives
     * host and test bundle distinct class loaders — package-private access
     * fails at runtime).
     */
    public static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "untitled";
        }
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isBlank() || !cleaned.matches(".*[A-Za-z0-9].*")) {
            return "untitled";
        }
        return cleaned;
    }
}
