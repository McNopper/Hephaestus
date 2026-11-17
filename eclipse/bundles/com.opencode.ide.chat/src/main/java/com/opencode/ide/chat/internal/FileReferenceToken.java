package com.opencode.ide.chat.internal;

/**
 * The {@code @}-token detection of the {@code @}-file autocomplete (U-012): which token the caret
 * is in, and whether that token is an {@code @}-file reference. SWT-free so it is unit-testable;
 * the view feeds it the composer text plus caret offset on every modification.
 */
public final class FileReferenceToken {

  private FileReferenceToken() {}

  /**
   * @param text the composer text (may be {@code null})
   * @param caret the caret offset into {@code text} (0-based; clamped)
   * @return the token the caret is in when it is an {@code @}-file reference - the {@code @} plus
   *     everything typed after it, e.g. {@code "@char"} - or {@code null} when the caret is outside
   *     any {@code @} token (no token, an already-completed one, or a word that merely contains an
   *     {@code @} like an e-mail address)
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
   * @return the search query of {@code token} - the text after the {@code @} ({@code ""} for a bare
   *     {@code "@"}) <b>without</b> its {@code #line}/{@code #start-end} suffix, so a reference the
   *     user already annotated still matches files (CP-10 / FR-CP-10); {@code null} when {@code
   *     token} is not an {@code @} token
   */
  public static String queryOf(String token) {
    if (token == null || token.isEmpty() || token.charAt(0) != '@') {
      return null;
    }
    String after = token.substring(1);
    int hash = after.indexOf('#');
    return hash < 0 ? after : after.substring(0, hash);
  }

  /**
   * @param token an {@code @}-token (may be {@code null})
   * @return the line/range suffix the SERVER resolves - everything from the first {@code #} after
   *     the {@code @} ({@code "#20-45"}, {@code "#L10"}); {@code ""} when there is none. This is
   *     the part FR-CP-10 requires to survive a completion pick verbatim.
   */
  public static String suffixOf(String token) {
    if (token == null || token.length() < 2) {
      return "";
    }
    int hash = token.indexOf('#', 1);
    return hash < 0 ? "" : token.substring(hash);
  }

  /**
   * @return {@code text} with the {@code @} token that spans {@code [tokenStart, tokenEnd)}
   *     replaced by {@code "@" + pickedPath + suffix + " "} where {@code suffix} is the word's own
   *     {@code #line}/{@code #start-end} part <b>kept verbatim</b> (CP-10 / FR-CP-10 - the server
   *     resolves ranges, as in the TUI); the word is re-scanned forward from {@code tokenEnd} to
   *     its whitespace boundary, so a caret mid-token still replaces the whole reference. An
   *     existing single separator space right after the word is consumed by the inserted one; a
   *     defensive copy that never throws on bad bounds (returns {@code text} unchanged then)
   */
  public static String replaceToken(String text, int tokenStart, int tokenEnd, String pickedPath) {
    if (text == null
        || pickedPath == null
        || tokenStart < 0
        || tokenEnd < tokenStart
        || tokenStart > text.length()
        || tokenEnd > text.length()) {
      return text;
    }
    int fullEnd = tokenEnd;
    while (fullEnd < text.length() && !Character.isWhitespace(text.charAt(fullEnd))) {
      fullEnd++; // the word may continue past the caller's end (caret mid-token)
    }
    String word = text.substring(tokenStart, fullEnd);
    String suffix = suffixOf(word);
    String rest = text.substring(fullEnd);
    if (rest.startsWith(" ")) {
      rest = rest.substring(1); // the inserted space replaces the separator
    }
    return text.substring(0, tokenStart) + "@" + pickedPath + suffix + " " + rest;
  }
}
