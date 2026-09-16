package com.opencode.ide.chat.internal;

/**
 * Makes selector-combo changes deliberate (user report 2026-09-16: moving the
 * mouse over the selector row above the chat input — especially scrolling
 * the transcript with the pointer passing over a combo — silently flipped
 * agent/model/variant; Windows read-only combos fire {@code SWT.Selection}
 * on mouse-wheel). A change commits only when <b>armed</b>: the dropdown was
 * explicitly opened (mouse down on the combo) and an item picked, or Enter
 * ({@code SWT.DefaultSelection}) was pressed. Un-armed drift (wheel, stray
 * arrow keys) is reported to the caller as {@code null} so it can revert the
 * combo to {@link #committed()}.
 *
 * <p>SWT-free and unit-tested; the view owns the SWT wiring
 * (mouse-down/focus-out/selection events), this class owns the semantics.
 * Public for the separate-bundle tests (OSGi class-loader split —
 * package-private access fails at runtime).</p>
 */
public final class SelectorGuard {

    private boolean armed;
    private String committed = "";

    public SelectorGuard(String initial) {
        committed = initial == null ? "" : initial;
    }

    /** Arms the next selection: the user explicitly opened the dropdown. */
    public void arm() {
        armed = true;
    }

    /**
     * Disarms without committing (focus left the combo — an opened-then
     * abandoned dropdown must not turn the next wheel drift into a pick).
     */
    public void disarm() {
        armed = false;
    }

    /**
     * Consumes one selection change.
     *
     * @param picked the combo's current text
     * @return the committed text when the change was armed; {@code null}
     *         when it was drift — the caller reverts the combo to
     *         {@link #committed()}
     */
    public String attempt(String picked) {
        if (!armed) {
            return null;
        }
        armed = false;
        committed = picked == null ? "" : picked;
        return committed;
    }

    /**
     * Tracks a programmatic update (async selector load, preference
     * preselect): nothing commits, but the revert baseline moves so the
     * next drift reverts to the freshly loaded value.
     */
    public void reset(String text) {
        armed = false;
        committed = text == null ? "" : text;
    }

    /** The value drifts revert to. */
    public String committed() {
        return committed;
    }
}
