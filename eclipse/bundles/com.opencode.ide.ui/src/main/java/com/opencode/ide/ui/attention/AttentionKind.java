package com.opencode.ide.ui.attention;

/**
 * The attention kinds U-047 parity covers (the TUI's attention feature):
 * events that mean "the agent needs the human, or the human's wait is over".
 * Each kind carries the popup title it renders with.
 *
 * <p>{@link #QUESTION} is part of the contract but deliberately unmapped in
 * {@link AttentionClassifier}: the v2 wire has no confirmed SSE event for a
 * form ask (forms are pulled via {@code GET .../forms} — see
 * {@code SessionDetailsController#openForms}); wiring it needs a verified
 * event type first.</p>
 */
public enum AttentionKind {

    /** A session raised a permission request ({@code permission.asked}). */
    PERMISSION_ASK("opencode permission request"),

    /** The agent asked a form question (enum reserved; not yet mapped). */
    QUESTION("opencode question"),

    /** A busy session ended with a failure ({@code session.execution.failed}/
     * {@code session.execution.interrupted}). */
    SESSION_ERROR("opencode session error"),

    /** A busy session finished its work ({@code session.idle} and friends). */
    SESSION_COMPLETED("opencode session finished");

    private final String title;

    AttentionKind(String title) {
        this.title = title;
    }

    /** @return the popup title for this kind. */
    public String title() {
        return title;
    }
}
