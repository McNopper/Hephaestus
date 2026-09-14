package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.junit.Test;

/**
 * Unit tests for the SWT-free {@link CwdCheck} spawn-cwd cross-check of the
 * Server view's project header: OK for equal directories (trailing
 * separators and case ignored, per Windows conventions), MISMATCH carrying
 * both paths, UNKNOWN whenever a side is absent (null-safety), and the
 * warning-marker line for the header tooltip. No SWT, no Eclipse types.
 */
public class CwdCheckTest {

    // ---------- OK ----------

    @Test
    public void sameDirectoryIsOk() {
        assertEquals(CwdCheck.Kind.OK, CwdCheck.check("/w/foo", Path.of("/w/foo")).kind());
    }

    @Test
    public void trailingSeparatorsAreIgnored() {
        assertEquals(CwdCheck.Kind.OK, CwdCheck.check("/w/foo/", Path.of("/w/foo")).kind());
        assertTrue(CwdCheck.sameDirectory("C:\\w\\foo\\", "C:\\w\\foo", true));
    }

    @Test
    public void separatorStylesAreIgnored() {
        assertTrue(CwdCheck.sameDirectory("/w/foo", "\\w\\foo", true));
    }

    @Test
    public void caseDifferencesAreIgnored() {
        assertTrue(CwdCheck.sameDirectory("C:\\W\\Foo", "c:\\w\\foo", true));
        assertTrue(CwdCheck.sameDirectory("/w/foo", "/W/FOO", true));
    }

    @Test
    public void unixPreservesCaseBackslashesAndFilenameWhitespace() {
        assertFalse(CwdCheck.sameDirectory("/w/Foo", "/w/foo", false));
        assertFalse(CwdCheck.sameDirectory("/w/a\\b", "/w/a/b", false));
        assertFalse(CwdCheck.sameDirectory("/w/foo\\", "/w/foo", false));
        assertFalse(CwdCheck.sameDirectory("/w/foo ", "/w/foo", false));
        assertTrue(CwdCheck.sameDirectory("/w/a\\b/", "/w/a\\b", false));
        assertTrue(CwdCheck.sameDirectory("/w/foo///", "/w/foo", false));
    }

    @Test
    public void rootsAndUnknownPathsAreNotConflated() {
        assertTrue(CwdCheck.sameDirectory("/", "///", false));
        assertTrue(CwdCheck.sameDirectory("C:\\", "c:/", true));
        assertFalse(CwdCheck.sameDirectory("C:", "C:/", true));
        assertFalse(CwdCheck.sameDirectory(null, null, false));
        assertFalse(CwdCheck.sameDirectory("", "/", false));
    }

    // ---------- MISMATCH ----------

    @Test
    public void differentDirectoriesMismatchWithBothPaths() {
        CwdCheck check = CwdCheck.check("/w/foo", Path.of("/w/bar"));

        assertEquals(CwdCheck.Kind.MISMATCH, check.kind());
        assertEquals("/w/foo", check.serverPath());
        assertEquals(Path.of("/w/bar"), check.workspacePath());
    }

    // ---------- UNKNOWN ----------

    @Test
    public void nullServerPathIsUnknown() {
        CwdCheck check = CwdCheck.check(null, Path.of("/w/foo"));

        assertEquals(CwdCheck.Kind.UNKNOWN, check.kind());
        assertEquals(Path.of("/w/foo"), check.workspacePath());
    }

    @Test
    public void blankServerPathIsUnknown() {
        assertEquals(CwdCheck.Kind.UNKNOWN, CwdCheck.check("   ", Path.of("/w/foo")).kind());
    }

    @Test
    public void nullWorkspaceDirIsUnknown() {
        CwdCheck check = CwdCheck.check("/w/foo", null);

        assertEquals(CwdCheck.Kind.UNKNOWN, check.kind());
        assertEquals("/w/foo", check.serverPath());
    }

    @Test
    public void bothSidesNullIsUnknownAndNeverThrows() {
        CwdCheck check = CwdCheck.check(null, null);

        assertEquals(CwdCheck.Kind.UNKNOWN, check.kind());
        assertEquals("", check.warningLine());
    }

    // ---------- warningLine ----------

    @Test
    public void warningLineRendersMismatchWithBothPaths() {
        CwdCheck check = CwdCheck.check("/w/foo", Path.of("/w/bar"));

        assertEquals("⚠ cwd mismatch: /w/foo vs " + Path.of("/w/bar"), check.warningLine());
    }

    @Test
    public void warningLineEmptyOnOkAndUnknown() {
        assertEquals("", CwdCheck.check("/w/foo", Path.of("/w/foo")).warningLine());
        assertEquals("", CwdCheck.check("/w/foo", null).warningLine());
        assertEquals("", CwdCheck.check(null, Path.of("/w/foo")).warningLine());
    }
}
