package com.opencode.ide.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.SWT;
import org.junit.Test;

/**
 * U-064: the composer's background key is the TUI's Ctrl+B - a plain B must
 * keep typing, and chords with extra modifiers stay other shortcuts
 * (Ctrl+Shift+B, Ctrl+Alt+B are not background).
 */
public class BackgroundKeyTest {

    @Test
    public void ctrlBIsTheBackgroundKey() {
        assertTrue(BackgroundKeys.isBackgroundKey(SWT.MOD1, 'b'));
        assertTrue(BackgroundKeys.isBackgroundKey(SWT.MOD1, 'B'));
    }

    @Test
    public void plainBTypes() {
        assertFalse(BackgroundKeys.isBackgroundKey(0, 'b'));
        assertFalse(BackgroundKeys.isBackgroundKey(0, 'B'));
    }

    @Test
    public void extraModifiersStayOtherShortcuts() {
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1 | SWT.SHIFT, 'B'));
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1 | SWT.ALT, 'b'));
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1 | SWT.MOD2, 'b'));
    }

    @Test
    public void otherCtrlChordsAreUntouched() {
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1, 'a'));
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1, 'c'));
        assertFalse(BackgroundKeys.isBackgroundKey(SWT.MOD1, SWT.CR));
    }
}
