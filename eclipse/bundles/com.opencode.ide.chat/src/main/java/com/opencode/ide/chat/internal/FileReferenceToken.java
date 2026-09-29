package com.opencode.ide.chat.internal;

/**
 * The {@code @}-token detection of the {@code @}-file autocomplete (U-012):
 * which token the caret is in, and whether that token is an {@code @}-file
 * reference. SWT-free so it is unit-testable; the view feeds it the composer
 * text plus caret offset on every modification.
 */
public final class FileReferenceToken {

    private FileReferenceToken() {
    }

    /**
     * @param text  the composer text (may be {@code null})
     * @param caret the caret offset into {@code text} (0-based; clamped)
     * @return the token the caret is in when it is an {@code @}-file
     *         reference - the {@code @} plus everything typed after it, e.g.
     *         {@code "@char"} - or {@code null} when the caret is outside any
     *         {@code @} token (no token, an already-completed one, or a word
     *         that merely contains an {@code @} like an e-mail address)
     */
    public static String tokenAt(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int offset = Math.max(0, Math.min(caret, text.length()));
        int start = offset;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        if (start >= text.length() || text.charAt(start) != '@') {
            return null; // the caret's word is not an @ reference
        }
        String token = text.substring(start, offset);
        // "@" alone (nothing typed after it yet) is still a query - the
        // empty one; the controller answers it with an empty list
        return token;
    }

    /**
     * @return the search query of {@code token} - the text after the
     *         {@code @} ({@code ""} for a bare {@code "@"}); {@code null}
     *         when {@code token} is not an {@code @} token
     */
    public static String queryOf(String token) {
        if (token == null || token.isEmpty() || token.charAt(0) != '@') {
            return null;
        }
        return token.substring(1);
    }

    /**
     * @return {@code text} with the {@code @} token that spans
     *         {@code [tokenStart, tokenEnd)} replaced by
     *         {@code "@" + pickedPath + " "} (an existing single separator
     *         space right after the token is consumed by the inserted one, so
     *         a mid-text pick does not double-space); a defensive copy that
     *         never throws on bad bounds (returns {@code text} unchanged then)
     */
    public static String replaceToken(String text, int tokenStart, int tokenEnd, String pickedPath) {
        if (text == null || pickedPath == null || tokenStart < 0 || tokenEnd < tokenStart
                || tokenStart > text.length() || tokenEnd > text.length()) {
            return text;
        }
        String rest = text.substring(tokenEnd);
        if (rest.startsWith(" ")) {
            rest = rest.substring(1); // the inserted space replaces the separator
        }
        return text.substring(0, tokenStart) + "@" + pickedPath + " " + rest;
    }
}
