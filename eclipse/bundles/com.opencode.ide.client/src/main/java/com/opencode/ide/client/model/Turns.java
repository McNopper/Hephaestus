package com.opencode.ide.client.model;

import java.util.List;

/**
 * Turn-completion evidence over a session's message list (B-008 completion
 * contract, rubberduck F-1/F-2/F-3): the ONE place that decides "did this
 * turn end", shared by the client's reply polling ({@code awaitReply}) and
 * the fleet's completion probe — two judges that previously disagreed.
 *
 * <p><b>List order.</b> {@code GET /session/:id/message} returns newest
 * first on the wire (the mirrored 2.0.10 capture in {@code ChatParsingTest}
 * is the contract fixture), but every consumer in this codebase works
 * chronologically. {@code HttpOpencodeClient.getMessages} therefore
 * NORMALIZES to oldest-first once, and everything here assumes that
 * contract: "later in the list" = newer.</p>
 *
 * <p><b>Turn shape (v2).</b> One turn produces MULTIPLE assistant messages
 * (one per agentic step), each stamped {@code time.completed} when ITS step
 * finishes — so a finished-looking assistant message is NOT turn end (an
 * inter-step boundary looks exactly like that). The authoritative turn-end
 * signal is the v2 {@code idle} marker row the server appends when the turn
 * closes (the same signal {@code HttpOpencodeClient.awaitReply} anchors
 * on). Non-conversational rows ({@code shell}, {@code idle}, …) keep their
 * wire {@code type} as {@link ChatMessageInfo#role()} and must not mask the
 * reply: they trail the final assistant message and made naive
 * "last entry" completion checks never fire (B-008 gap (a)).</p>
 */
public final class Turns {

    /** Roles that carry conversation turns. */
    private static final java.util.Set<String> CONVERSATIONAL = java.util.Set.of("user", "assistant");

    /**
     * Turn-end marker roles appended by the server when a turn closes.
     * Only {@code idle} is fixture-backed wire vocabulary; the set is an
     * explicit allowlist so unknown marker types cannot silently mask a
     * finished turn (they fall back to the evidence path).
     */
    private static final java.util.Set<String> TURN_MARKERS = java.util.Set.of("idle");

    /** Terminal tool-call statuses; anything else (running, streaming, …) is in flight. */
    private static final java.util.Set<String> TERMINAL_TOOL =
            java.util.Set.of("completed", "error", "cancelled", "canceled");

    /** Terminal shell statuses (wire enum: running/exited/timeout/killed). */
    private static final java.util.Set<String> TERMINAL_SHELL =
            java.util.Set.of("exited", "completed", "error", "timeout", "killed", "cancelled", "canceled");

    private Turns() {
    }

    /**
     * The authoritative turn end: a {@code TURN_MARKERS} row exists at/after
     * the trailing conversational entry (the server stamped the turn closed).
     */
    public static boolean turnEnded(List<ChatEntry> messages) {
        int trailing = trailingConversational(messages);
        for (int i = Math.max(trailing, 0) + 1; i < messages.size(); i++) {
            ChatEntry m = messages.get(i);
            if (m != null && m.info() != null && TURN_MARKERS.contains(m.info().role())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The finished-reply evidence, marker-less servers only: the trailing
     * conversational entry is an assistant message with non-blank text whose
     * step is stamped complete ({@code time.completed}), and no tool call or
     * shell run of the transcript is still in flight. Callers must
     * additionally require their own "turn may be over" condition (the
     * prompt call resolved, or a quiet window elapsed) — evidence alone is
     * true at every inter-step boundary (F-005).
     *
     * @return the evidence message, or {@code null}
     */
    public static ChatEntry replyEvidence(List<ChatEntry> messages) {
        int trailing = trailingConversational(messages);
        if (trailing < 0) {
            return null;
        }
        ChatEntry last = messages.get(trailing);
        if (last == null || last.info() == null || !"assistant".equals(last.info().role())
                || last.text() == null || last.text().isBlank()
                || !last.info().isComplete() // v2 time.completed stamp: 0 = the step is still streaming
                || inFlightWork(messages)) {
            return null;
        }
        return last;
    }

    /** True while any tool call or shell run of the transcript is non-terminal. */
    public static boolean inFlightWork(List<ChatEntry> messages) {
        for (ChatEntry m : messages) {
            if (m == null) {
                continue;
            }
            if (m.info() != null && "shell".equals(m.info().role())
                    && m.info().finish() != null && !TERMINAL_SHELL.contains(m.info().finish())) {
                return true;
            }
            for (ChatPart part : m.parts()) {
                if (part != null && part.isTool()
                        && inFlight(part.stateName(), TERMINAL_TOOL)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * B-013 semantics at the model level: a call is in flight unless its
     * status is terminal; an absent or blank status degrades to "not in
     * flight" (missing fields are absent values, never phantom activity).
     */
    public static boolean inFlight(String status, java.util.Set<String> terminal) {
        return status != null && !status.isBlank() && !terminal.contains(status);
    }

    /** A tool call in flight (v2 tool states: running, streaming, …). */
    public static boolean toolInFlight(String status) {
        return inFlight(status, TERMINAL_TOOL);
    }

    /** A shell run in flight (v2 shell states: running/exited/timeout/killed). */
    public static boolean shellInFlight(String status) {
        return inFlight(status, TERMINAL_SHELL);
    }

    /**
     * The index of the trailing CONVERSATIONAL entry (roles
     * {@code user|assistant}); non-conversational rows (shell runs, idle
     * markers, …) never mask the reply. {@code -1} when the transcript has
     * none. Later list position = newer (the getMessages contract).
     */
    public static int trailingConversational(List<ChatEntry> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatEntry m = messages.get(i);
            if (m != null && m.info() != null && CONVERSATIONAL.contains(m.info().role())) {
                return i;
            }
        }
        return -1;
    }
}
