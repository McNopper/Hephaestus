package com.opencode.ide.chat;

/**
 * The three answers the U-014 in-chat permission surfaces offer (the ask
 * banner row and the permission dialog): the wire response for
 * {@code respondToPermission(sessionId, permissionId, response, remember,
 * feedback)} plus its remember flag. SWT-free on purpose so the mapping is
 * unit-testable — both the banner's and the dialog's buttons route through
 * this one enum, so the two surfaces can never drift apart.
 */
public enum ChatPermissionDecision {

    /** Allow this one request (nothing is remembered). */
    ONCE("once", false),

    /** Allow and remember the approval for later asks of the same kind. */
    ALWAYS("always", true),

    /** Deny, optionally with feedback for the agent (the caller's extra). */
    REJECT("reject", false);

    private final String response;
    private final boolean remember;

    ChatPermissionDecision(String response, boolean remember) {
        this.response = response;
        this.remember = remember;
    }

    /** @return the wire response verb ({@code "once"}/{@code "always"}/{@code "reject"}). */
    public String response() {
        return response;
    }

    /** @return whether the answer is sent as a remembered approval. */
    public boolean remember() {
        return remember;
    }
}
