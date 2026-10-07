package com.opencode.ide.chat;

import org.eclipse.swt.SWT;

/**
 * The chat composer's background key (U-064): the TUI's Ctrl+B. Pure
 * predicate kept out of the SWT view so it stays testable headless (the view
 * classes are not exported API).
 */
public final class BackgroundKeys {

    private BackgroundKeys() {
    }

    /**
     * @return true when {@code stateMask} is exactly MOD1 (Ctrl/Cmd) and the
     *         key is B - plain B types, and chords carrying additional
     *         modifiers stay other shortcuts
     */
    public static boolean isBackgroundKey(int stateMask, int keyCode) {
        boolean mod1Only = (stateMask & (SWT.MOD1 | SWT.MOD2 | SWT.ALT | SWT.SHIFT)) == SWT.MOD1;
        return mod1Only && (keyCode == 'b' || keyCode == 'B');
    }
}
