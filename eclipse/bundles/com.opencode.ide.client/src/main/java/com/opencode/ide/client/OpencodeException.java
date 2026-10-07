package com.opencode.ide.client;

/**
 * Root exception for all opencode client failures (HTTP errors, malformed
 * responses, connection problems).
 */
public class OpencodeException extends Exception {
    private static final long serialVersionUID = 1L;

    public OpencodeException(String message) {
        super(message);
    }

    public OpencodeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The reply-wait budget expired (no-progress window or the absolute cap)
     * while the server may still be working (B-024). Callers that can recover
     * by watching instead of failing (the chat's late-reply watcher) recognize
     * this subtype.
     */
    public static final class ReplyTimeout extends OpencodeException {
        private static final long serialVersionUID = 1L;

        public ReplyTimeout(String message) {
            super(message);
        }
    }
}
